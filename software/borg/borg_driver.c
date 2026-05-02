// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg GPU driver — pipeline orchestration, hardware init, draw commands.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_math.h"
#include "borg_raster.h"
#include "borg_spirb.h"
#include <stdbool.h>
#include <stdint.h>

// @doc:mmio-map
#include "borg_isa.h" // IWYU pragma: keep — used by @doc extractor + ISA macros
#include "borg_sys.h"
// @doc:end

#define FP16_SIXTEEN 0x4C00

// --- PSRAM frame layout (tiled: 2 words per pixel) ---
// Each pixel: lo word = {B[15:0], Z[15:0]} packed, hi word = {unused, R[15:0],
// G[15:0]} Actually: lo = R|Z packed by flusher, hi = G|B packed by flusher.
// FRAME_FB_SIZE = W*H*2 words (tiled layout, no separate Z buffer).
#define FRAME_FB_SIZE (BORG_FB_WIDTH * BORG_FB_HEIGHT * 2)
#define FRAME_ZB_SIZE 0
#define FRAME_STRIDE (FRAME_FB_SIZE + 1) // FB + DONE marker

// Maximum tiles for the largest supported framebuffer (128×128 / 4×4 = 32×32
// tiles).
#define BORG_MAX_TILES 1024      // (128/4)*(128/4) = 1024
#define BORG_MAX_DRAWS 16        // max draw calls per frame
#define BORG_MAX_TRIS_PER_TILE 8 // max triangles that can touch one tile

// Step 29.5: Sequencer auto-detection flag (set during borgCreateGraphicsPipeline).
static int has_sequencer = 0;

// Uniform ping-pong page (toggles each draw call; shared by CPU and sequencer paths).
static int current_uniform_page = 0;

// Step 30.1b: Sequencer shader ROM constants.
//
// Identity vertex shader (3 instructions):
//   The sequencer's pipeWrite snoop captures r0=x, r1=y during sWaitVert.
//   u0=screen_x, u1=screen_y per vertex (loaded by DMA from descriptor).
//   Output r0=x, r1=y so sWaitVert snoops them into clipRegs[v][0:1].
static const uint32_t seq_vert_shader[] = {
  BORG_INSTR_FADD(0, 0, 31, 1),  // r0 = u0 + 0 = screen x
  BORG_INSTR_FADD(1, 1, 31, 1),  // r1 = u1 + 0 = screen y
  BORG_INSTR_HALT,
};
#define SEQ_VERT_SHADER_LEN (sizeof(seq_vert_shader) / sizeof(seq_vert_shader[0]))

// Triangle setup shader (31 instructions, with edge normalization for Step 30.1c):
//   u0-u5 = screen coords of all 3 vertices (written by sWriteSetupInputs).
//   u6    = inv_width = 1/fb_width (written by sWriteSetupInputs, Step 30.1c).
//   Outputs r0-r5 = normalized edge components (divided by fb_width).
//   Output  r7    = inv_area = W/area (matching CPU path convention).
//   r8-r21 are working registers (intermediate positions).
static const uint32_t seq_setup_shader[] = {
  // Copy screen coords from uniforms u0-u5 into working regs r8-r13
  BORG_INSTR_FADD( 8, 0, 31, 1),  // r8  = v0.x
  BORG_INSTR_FADD( 9, 1, 31, 1),  // r9  = v0.y
  BORG_INSTR_FADD(10, 2, 31, 1),  // r10 = v1.x
  BORG_INSTR_FADD(11, 3, 31, 1),  // r11 = v1.y
  BORG_INSTR_FADD(12, 4, 31, 1),  // r12 = v2.x
  BORG_INSTR_FADD(13, 5, 31, 1),  // r13 = v2.y
  // Edge vectors
  BORG_INSTR_FNEG(14, 10, 0),     // r14 = -v1.x
  BORG_INSTR_FADD( 0,  8, 14, 0), // r0  = v0.x - v1.x  (e0.dx)
  BORG_INSTR_FNEG(15,  9, 0),     // r15 = -v0.y
  BORG_INSTR_FADD( 1, 11, 15, 0), // r1  = v1.y - v0.y  (e0.dy)
  BORG_INSTR_FNEG(16, 12, 0),     // r16 = -v2.x
  BORG_INSTR_FADD( 2, 10, 16, 0), // r2  = v1.x - v2.x  (e1.dx)
  BORG_INSTR_FNEG(17, 11, 0),     // r17 = -v1.y
  BORG_INSTR_FADD( 3, 13, 17, 0), // r3  = v2.y - v1.y  (e1.dy)
  BORG_INSTR_FNEG(18,  8, 0),     // r18 = -v0.x
  BORG_INSTR_FADD( 4, 12, 18, 0), // r4  = v2.x - v0.x  (e2.dx)
  BORG_INSTR_FNEG(19, 13, 0),     // r19 = -v2.y
  BORG_INSTR_FADD( 5,  9, 19, 0), // r5  = v0.y - v2.y  (e2.dy)
  // Area = e0.dx * e2.dy + e2.dx * (-e0.dy)
  BORG_INSTR_FMUL(20,  0,  5, 0), // r20 = e0.dx * e2.dy
  BORG_INSTR_FNEG(21,  1, 0),     // r21 = -e0.dy
  BORG_INSTR_FMADD(6, 4, 21, 20, 0), // r6 = e2.dx * r21 + r20 = area
  // Negate area to match CPU convention (triangle_setup: area ^= 0x8000).
  BORG_INSTR_FNEG(6,  6, 0),      // r6 = -area
  // Step 30.1c: Edge normalization.
  // Multiply raw edges by inv_width (u6) to match CPU path's borg_load_edge_constants().
  // Multiply negated area by inv_width: area/W → rcp gives W/area = inv_area.
  // Order: normalize AFTER raw area computation, matching CPU's triangle_setup().
  BORG_INSTR_FMUL( 0,  0,  6, 2), // r0 = e0.dx * u6(inv_width)  (funct3=2 → rs2 from uniform)
  BORG_INSTR_FMUL( 1,  1,  6, 2), // r1 = e0.dy * inv_width
  BORG_INSTR_FMUL( 2,  2,  6, 2), // r2 = e1.dx * inv_width
  BORG_INSTR_FMUL( 3,  3,  6, 2), // r3 = e1.dy * inv_width
  BORG_INSTR_FMUL( 4,  4,  6, 2), // r4 = e2.dx * inv_width
  BORG_INSTR_FMUL( 5,  5,  6, 2), // r5 = e2.dy * inv_width
  BORG_INSTR_FMUL( 6,  6,  6, 2), // r6 = (-area) * inv_width = -area/W
  BORG_INSTR_FRCP( 7,  6, 0),     // r7 = rcp(-area/W) = W/area = inv_area
  BORG_INSTR_HALT,
};
#define SEQ_SETUP_SHADER_LEN (sizeof(seq_setup_shader) / sizeof(seq_setup_shader[0]))

// Per-tile triangle list (the "bin").
// bin_count[t] = number of draw calls touching tile t
// bin_list[t][i] = index into draw_calls[] for the i-th triangle on tile t
static uint8_t bin_count[BORG_MAX_TILES];
static uint8_t bin_list[BORG_MAX_TILES][BORG_MAX_TRIS_PER_TILE];

typedef struct {
  int w, h;
} dim2_t;

typedef struct {
  int psram_offset;        // -1 = no texture
  dim2_t size;             // integer dimensions
  uint16_t w_fp16, h_fp16; // FP16 dimensions for Borg FPU
} texture_t;

// Runtime framebuffer dimensions and derived values
int borg_fb_width;
int borg_fb_height;
static fp16_t fp16_half_width;
static fp16_t pc_lut[BORG_MAX_FB_DIM];

// Integer bounding box (clamped to framebuffer).
typedef struct {
  int x0, y0, x1, y1;
} bbox_t;

// Snapshot of one submitted draw call (post-clip, post-setup).
typedef struct {
  triangle_t tri; // fully set-up triangle (screen_pos, edges, uniforms)
  texture_t tex;  // copy of texture state at draw time
  bbox_t bb;      // screen-space AABB (used for binning)
  int frame;      // target frame index
} draw_call_t;

static draw_call_t draw_calls[BORG_MAX_DRAWS];
static int draw_call_count = 0;
static rgb16_t
    last_clear_color; // saved by borgBinReset, used for empty-tile fill

#define NUM_VERTICES 3
#define MAX_CLIP_VERTS 4 // Clipping one triangle can produce at most a quad

// Clipped vertex: carries all interpolable attributes through clipping.
typedef struct {
  fp16_t x, y, z, w; // clip-space position
  fp16_t r, g, b;    // vertex color
  fp16_t u, v;       // texture UV
} clip_vertex_t;

// Global timing vars
unsigned int t_init_cycles = 0;
unsigned int t_clear_cycles = 0;
unsigned int t_draw_cycles = 0;

// --- Texture state ---
static texture_t tex = {.psram_offset = -1};

// --- UART ---
void putc_uart(int c) {
  while (UART_STATUS & 1)
    ;
  UART_TX = c;
}

void puts_uart(const char *s) {
  while (*s)
    putc_uart(*s++);
}

// --- Timing and debug printing ---
static inline unsigned int get_cycles(void) {
  unsigned int cycles;
  __asm__ volatile("csrr %0, cycle" : "=r"(cycles));
  return cycles;
}

// --- Shader globals ---
static spirb_shader_t vert_shader;
static spirb_shader_t rast_shader;
static spirb_shader_t frag_shader;

// --- Vertex shader ---
static void run_vertex_shader(const fp16_t *uniforms, const fp16_t *attrs,
                              fp16_t *outputs) {
  const spirb_shader_t *s = &vert_shader;
  borg_load_spirb_shader(s);

  for (int i = 0; i < s->num_uniforms; i++)
    BORG_GPU->uniform[s->uniform_regs[i]] = uniforms[i];

  for (int v = 0; v < NUM_VERTICES; v++) {
    BORG_GPU->control = CONTROL_REG_T__RESET_PIPELINE_bm;
    for (int i = 0; i < s->num_uniforms; i++)
      BORG_GPU->uniform[s->uniform_regs[i]] = uniforms[i];
    for (int i = 0; i < s->num_attributes; i++)
      BORG_GPU->gpr[s->attribute_regs[i]] = attrs[v * s->num_attributes + i];
    for (int i = 0; i < s->num_consts; i++)
      BORG_GPU->gpr[s->const_regs[i]] = s->const_vals[i];
    borg_run(BORG_IMEM_VERT_OFFSET);
    for (int i = 0; i < s->num_outputs; i++)
      outputs[v * s->num_outputs + i] =
          BORG_GPU->gpr[s->output_regs[i]] & 0xFFFF;
  }
}

// --- Public API ---

void borgCreateDevice(void) {
  STARTUP_DELAY();
  UART_BAUD = UART_BAUD_DEFAULT;
  puts_uart("Borg pipeline\r\n");
  unsigned int t_init = get_cycles();

  // Read framebuffer dimensions from PSRAM (written by host)
  borg_fb_width = PSRAM_IN(0);
  borg_fb_height = PSRAM_IN(1);
  if (borg_fb_width == 0)
    borg_fb_width = 32;
  if (borg_fb_height == 0)
    borg_fb_height = 32;

  // Compute FP16 half-width for NDC→screen transform.
  // Must encode both exponent AND mantissa to handle non-power-of-2 widths.
  // E.g. width=60 → hw=30 → FP16 0x4F80 (30.0), not 0x4C00 (16.0).
  int hw = borg_fb_width / 2;
  int exp = 0;
  int tmp = hw;
  while (tmp > 1) {
    tmp >>= 1;
    exp++;
  }
  int mantissa = ((hw - (1 << exp)) << (10 - exp)) & 0x3FF;
  fp16_half_width = ((exp + 15) << 10) | mantissa;

  // Compute pixel center LUT: 0.5, 1.5, ..., (width-0.5)
  fp16_t val = FP16_HALF;
  for (int i = 0; i < borg_fb_width; i++) {
    pc_lut[i] = val;
    val = borg_fp16_add(val, FP16_ONE);
  }

  // Step 25.4.1: Configure hardware tile flusher base address.
  // Actual per-tile base is set dynamically in borgBinRender.
  BORG_GPU->flush_fb_base = PSRAM_OUT_SPI(0 * FRAME_STRIDE);
  // log2(fbWidth) — fbWidth is always a power of 2
  unsigned int log2_w = 0;
  unsigned int w = (unsigned int)borg_fb_width;
  while (w > 1) {
    w >>= 1;
    log2_w++;
  }
  BORG_GPU->flush_width = log2_w;

  t_init_cycles = get_cycles() - t_init;
}

void borgCreateGraphicsPipeline(const BorgShaderModule *vert,
                                const BorgShaderModule *rast,
                                const BorgShaderModule *frag) {
  spirb_parse(vert->code, &vert_shader);
  spirb_parse(rast->code, &rast_shader);
  spirb_parse(frag->code, &frag_shader);

  // Step 30.1b: Stage sequencer shaders to PSRAM and auto-detect hardware.
  for (int i = 0; i < (int)SEQ_VERT_SHADER_LEN; i++)
    PSRAM_OUT_RAW(SEQ_VERT_SHADER_ADDR + (uint32_t)i * 4) = seq_vert_shader[i];
  for (int i = 0; i < (int)SEQ_SETUP_SHADER_LEN; i++)
    PSRAM_OUT_RAW(SEQ_SETUP_SHADER_ADDR + (uint32_t)i * 4) = seq_setup_shader[i];

  BORG_GPU->seq_vert_addr  = SEQ_VERT_SHADER_ADDR;
  BORG_GPU->seq_vert_len   = SEQ_VERT_SHADER_LEN;
  BORG_GPU->seq_setup_addr = SEQ_SETUP_SHADER_ADDR;
  BORG_GPU->seq_setup_len  = SEQ_SETUP_SHADER_LEN;
  // Step 30.1c: Precompute 1/fb_width as exact FP16 (fb_width is a power of 2).
  // fp16(2^n) has exp = n+15, so fp16(2^-n) has exp = 15-n = 30 - exp_of_width.
  {
    fp16_t fb_fp16   = uint_to_fp16(borg_fb_width);
    fp16_t inv_width = (fp16_t)((30u - ((fb_fp16 >> 10) & 0x1Fu)) << 10);
    BORG_GPU->seq_inv_width = inv_width;
  }

  // Detection: trigger with desc=0, check if seq_busy goes high within 1 cycle.
  // If the hardware has no BorgSequencer, seq_trigger is unmapped and seq_busy stays 0.
  BORG_GPU->seq_desc_base = 0;
  BORG_GPU->seq_trigger = 1;
  volatile uint32_t st = BORG_GPU->status;
  has_sequencer = (st & STATUS_REG_T__SEQ_BUSY_bm) ? 1 : 0;
  if (has_sequencer) {
    while (BORG_GPU->status & STATUS_REG_T__SEQ_BUSY_bm)
      ;
    current_uniform_page = 0; // sequencer always uses page 0 (no ping-pong)
  }
}

void borg_set_angle(borg_draw_data_t *d, fp16_t angle_fp16) {
  // Compute vertex shader uniforms from angle for Phase 2 4x4 Projection
  fp16_t s = fp16_sin(angle_fp16);
  fp16_t c = fp16_cos(angle_fp16);
  fp16_t ns = fp16_neg(s);

  // Col 0 (X)
  d->uniforms[0] = c;
  d->uniforms[1] = s;
  d->uniforms[2] = FP16_ZERO;
  d->uniforms[3] = FP16_ZERO;

  // Col 1 (Y)
  d->uniforms[4] = ns;
  d->uniforms[5] = c;
  d->uniforms[6] = FP16_ZERO;
  d->uniforms[7] = FP16_ZERO;

  // Col 2 (Z) - passed through seamlessly
  d->uniforms[8] = FP16_ZERO;
  d->uniforms[9] = FP16_ZERO;
  d->uniforms[10] = FP16_ONE;
  d->uniforms[11] = FP16_ZERO;

  // Col 3 (Translations/W)
  d->uniforms[12] = FP16_ZERO;
  d->uniforms[13] = FP16_ZERO;
  d->uniforms[14] = FP16_ZERO;
  d->uniforms[15] = FP16_ONE;
}

// TBDR: Reset binning state. No PSRAM clearing needed —
// clear color is written to empty tiles during borgBinRender.
static void borgBinReset(rgb16_t cc) {
  for (int i = 0; i < BORG_MAX_TILES; i++)
    bin_count[i] = 0;
  draw_call_count = 0;
  last_clear_color = cc;
}

void borg_clear_zbuffer(int frame, rgb16_t clear_color) {
  unsigned int t_start = get_cycles();
  borgBinReset(clear_color);
  t_clear_cycles = get_cycles() - t_start;
}

void borg_set_texture(int tex_width, int tex_height) {
  tex = (texture_t){.psram_offset = 0, // unused, texture at fixed PSRAM addr
                    .size = {tex_width, tex_height},
                    .w_fp16 = uint_to_fp16(tex_width),
                    .h_fp16 = uint_to_fp16(tex_height)};
  // Step 21.2: Enable hardware sTexFetch via TEX_CONFIG MMIO register.
  // Texture lives at TEX_PSRAM_BYTE_ADDR_FIXED (defined in borg_layout.h),
  // BEFORE the framebuffer, so it always fits in the 16-bit base_addr field.
  BORG_GPU->tex_config =
      (TEX_PSRAM_BYTE_ADDR_FIXED & TEX_CONFIG_REG_T__BASE_ADDR_bm) |
      TEX_CONFIG_REG_T__EN_bm;
}

void borg_clear_texture(void) {
  tex.psram_offset = -1;
  // Step 21.2: Disable hardware sTexFetch.
  BORG_GPU->tex_config = 0;
}

// --- Triangle Clipping (Step 6) ---

// Linearly interpolate between two clip vertices: result = a + t * (b - a)
static clip_vertex_t clip_lerp(const clip_vertex_t *a, const clip_vertex_t *b,
                               fp16_t t) {
  clip_vertex_t out;
  // For each component: out = a + t * (b - a) = fmadd(t, b-a, a)
  out.x = borg_fp16_fmadd(t, BORG_FP16_SUB(b->x, a->x), a->x);
  out.y = borg_fp16_fmadd(t, BORG_FP16_SUB(b->y, a->y), a->y);
  out.z = borg_fp16_fmadd(t, BORG_FP16_SUB(b->z, a->z), a->z);
  out.w = borg_fp16_fmadd(t, BORG_FP16_SUB(b->w, a->w), a->w);
  out.r = borg_fp16_fmadd(t, BORG_FP16_SUB(b->r, a->r), a->r);
  out.g = borg_fp16_fmadd(t, BORG_FP16_SUB(b->g, a->g), a->g);
  out.b = borg_fp16_fmadd(t, BORG_FP16_SUB(b->b, a->b), a->b);
  out.u = borg_fp16_fmadd(t, BORG_FP16_SUB(b->u, a->u), a->u);
  out.v = borg_fp16_fmadd(t, BORG_FP16_SUB(b->v, a->v), a->v);
  return out;
}

// Clip polygon against near plane (z >= 0 in clip space).
// Sutherland-Hodgman: vertices with z < 0 are outside.
// Returns number of output vertices (0..MAX_CLIP_VERTS).
static int clip_near(const clip_vertex_t *in, int n_in, clip_vertex_t *out) {
  int n_out = 0;
  for (int i = 0; i < n_in; i++) {
    int prev_idx = (i == 0) ? n_in - 1 : i - 1;
    const clip_vertex_t *cur = &in[i];
    const clip_vertex_t *prev = &in[prev_idx];
    int cur_inside = fp16_ge_zero(cur->z);
    int prev_inside = fp16_ge_zero(prev->z);
    if (cur_inside != prev_inside) {
      // Edge crosses the near plane — compute intersection.
      // t = prev.z / (prev.z - cur.z)
      fp16_t denom = BORG_FP16_SUB(prev->z, cur->z);
      fp16_t t = borg_fp16_mul(prev->z, borg_fp16_rcp(denom));
      out[n_out++] = clip_lerp(prev, cur, t);
    }
    if (cur_inside)
      out[n_out++] = *cur;
  }
  return n_out;
}

// Clip polygon against far plane (z <= w in clip space).
// Sutherland-Hodgman: vertices with z > w are outside.
// Returns number of output vertices (0..MAX_CLIP_VERTS+1, capped at 7 for a
// triangle).
static int clip_far(const clip_vertex_t *in, int n_in, clip_vertex_t *out) {
  int n_out = 0;
  for (int i = 0; i < n_in; i++) {
    int prev_idx = (i == 0) ? n_in - 1 : i - 1;
    const clip_vertex_t *cur = &in[i];
    const clip_vertex_t *prev = &in[prev_idx];
    // signed distance to far plane: d = w - z (inside when d >= 0)
    fp16_t d_cur = BORG_FP16_SUB(cur->w, cur->z);
    fp16_t d_prev = BORG_FP16_SUB(prev->w, prev->z);
    int cur_inside = fp16_ge_zero(d_cur);
    int prev_inside = fp16_ge_zero(d_prev);
    if (cur_inside != prev_inside) {
      // t = d_prev / (d_prev - d_cur)
      fp16_t denom = BORG_FP16_SUB(d_prev, d_cur);
      fp16_t t = borg_fp16_mul(d_prev, borg_fp16_rcp(denom));
      out[n_out++] = clip_lerp(prev, cur, t);
    }
    if (cur_inside)
      out[n_out++] = *cur;
  }
  return n_out;
}

// Compute screen-space AABB of 3 vertices, clamped to framebuffer.
static bbox_t compute_bbox(const xy16x3_t *pos) {
  fp16_t min_x = pos->v[0].x, max_x = pos->v[0].x;
  fp16_t min_y = pos->v[0].y, max_y = pos->v[0].y;
  for (int i = 1; i < 3; i++) {
    if (fp16_lt(pos->v[i].x, min_x))
      min_x = pos->v[i].x;
    if (fp16_lt(max_x, pos->v[i].x))
      max_x = pos->v[i].x;
    if (fp16_lt(pos->v[i].y, min_y))
      min_y = pos->v[i].y;
    if (fp16_lt(max_y, pos->v[i].y))
      max_y = pos->v[i].y;
  }
  int x0 = fp16_ge_zero(min_x) ? fp16_to_uint(min_x) : 0;
  int y0 = fp16_ge_zero(min_y) ? fp16_to_uint(min_y) : 0;
  int x1 = fp16_ge_zero(max_x) ? fp16_to_uint(max_x) + 1 : 0;
  int y1 = fp16_ge_zero(max_y) ? fp16_to_uint(max_y) + 1 : 0;
  if (x1 > BORG_FB_WIDTH)
    x1 = BORG_FB_WIDTH;
  if (y1 > BORG_FB_HEIGHT)
    y1 = BORG_FB_HEIGHT;
  return (bbox_t){x0, y0, x1, y1};
}



// Load all uniforms for a draw call into the hardware pipeline.
static void setup_tile_uniforms(const draw_call_t *dc) {
  const triangle_t *tri = &dc->tri;
  const texture_t *t = &dc->tex;

  // Ping-pong the uniform pages (Step 13.4)
  current_uniform_page ^= 1;
  BORG_GPU->control =
      (current_uniform_page << CONTROL_REG_T__UNIFORM_WRITE_PAGE_bp);

  // Rasterizer uniforms (u0-u11): edge constants + negated vertex positions
  borg_load_edge_constants(&rast_shader, tri->edges.v);
  for (int i = 0; i < 3; i++) {
    BORG_GPU->uniform[rast_shader.uniform_regs[6 + i * 2 + 0]] =
        BORG_FP16_NEG(tri->screen_pos.v[i].x);
    BORG_GPU->uniform[rast_shader.uniform_regs[6 + i * 2 + 1]] =
        BORG_FP16_NEG(tri->screen_pos.v[i].y);
  }

  // Fragment uniforms: inv_area, vertex colors/UV, z
  // Barycentric weight mapping: w0→v2, w1→v0, w2→v1
  const rgb16_t *colors = tri->colors.v;
  BORG_GPU->uniform[frag_shader.uniform_regs[0]] = tri->inv_area;

  if (tri->has_uvs) {
    const uv16_t *uvs = tri->uvs.v;
    load_uniform_triple(&frag_shader, 1, borg_fp16_mul(uvs[1].u, t->w_fp16),
                        borg_fp16_mul(uvs[0].u, t->w_fp16),
                        borg_fp16_mul(uvs[2].u, t->w_fp16));
    load_uniform_triple(&frag_shader, 4, borg_fp16_mul(uvs[1].v, t->h_fp16),
                        borg_fp16_mul(uvs[0].v, t->h_fp16),
                        borg_fp16_mul(uvs[2].v, t->h_fp16));
    load_uniform_triple(&frag_shader, 7, 0, 0, 0);
    for (int i = 13; i <= 18; i++)
      BORG_GPU->uniform[frag_shader.uniform_regs[i]] = 0;
  } else {
    load_uniform_triple(&frag_shader, 1, colors[1].r, colors[0].r, colors[2].r);
    load_uniform_triple(&frag_shader, 4, colors[1].g, colors[0].g, colors[2].g);
    load_uniform_triple(&frag_shader, 7, colors[1].b, colors[0].b, colors[2].b);
    for (int i = 13; i <= 18; i++)
      BORG_GPU->uniform[frag_shader.uniform_regs[i]] = 0;
  }

  load_uniform_triple(&frag_shader, 10, tri->z_vals.v[1], tri->z_vals.v[0],
                      tri->z_vals.v[2]);

  BORG_GPU->frag_pc = BORG_IMEM_FRAG_OFFSET;

  // Enable/disable texture for this draw call
  if (tri->has_uvs) {
    BORG_GPU->tex_config =
        (TEX_PSRAM_BYTE_ADDR_FIXED & TEX_CONFIG_REG_T__BASE_ADDR_bm) |
        TEX_CONFIG_REG_T__EN_bm;
  } else {
    BORG_GPU->tex_config = 0;
  }
}

// Bin a single draw call into the per-tile lists.
static void borgBin(void) {
  int tiles_per_row = borg_fb_width >> 2;
  for (int dc = 0; dc < draw_call_count; dc++) {
    const bbox_t *bb = &draw_calls[dc].bb;
    for (int ty = bb->y0 & ~3; ty < bb->y1; ty += 4) {
      for (int tx = bb->x0 & ~3; tx < bb->x1; tx += 4) {
        int tile_index = (ty >> 2) * tiles_per_row + (tx >> 2);
        int cnt = bin_count[tile_index];
        if (cnt < BORG_MAX_TRIS_PER_TILE) {
          bin_list[tile_index][cnt] = (uint8_t)dc;
          bin_count[tile_index] = cnt + 1;
        }
      }
    }
  }
}

// Record a draw call for later TBDR rendering.
static void record_draw_call(const triangle_t *tri, const texture_t *t,
                             int frame) {
  if (draw_call_count >= BORG_MAX_DRAWS)
    return;
  int idx = draw_call_count;
  draw_calls[idx] = (draw_call_t){
      .tri = *tri,
      .tex = *t,
      .bb = compute_bbox(&tri->screen_pos),
      .frame = frame,
  };

  // Step 29.5: Write vertex descriptor to PSRAM for sequencer path.
  // Descriptor layout: 3 vertices × 8 FP16 words (x,y,z,r,g,b,u,v) × 4 bytes.
  // Each word occupies a 32-bit PSRAM slot with the FP16 value in the low 16 bits.
  if (has_sequencer) {
    uint32_t desc_base = SEQ_DESC_BASE_ADDR + (uint32_t)idx * SEQ_DESC_STRIDE;
    for (int v = 0; v < 3; v++) {
      uint32_t vbase = desc_base + (uint32_t)v * 32;
      // Screen-space position (x, y from screen_pos, z from z_vals)
      PSRAM_OUT_RAW(vbase + 0)  = tri->screen_pos.v[v].x;
      PSRAM_OUT_RAW(vbase + 4)  = tri->screen_pos.v[v].y;
      PSRAM_OUT_RAW(vbase + 8)  = tri->z_vals.v[v];
      // Color
      PSRAM_OUT_RAW(vbase + 12) = tri->colors.v[v].r;
      PSRAM_OUT_RAW(vbase + 16) = tri->colors.v[v].g;
      PSRAM_OUT_RAW(vbase + 20) = tri->colors.v[v].b;
      // UV
      PSRAM_OUT_RAW(vbase + 24) = tri->uvs.v[v].u;
      PSRAM_OUT_RAW(vbase + 28) = tri->uvs.v[v].v;
    }
  }

  draw_call_count++;
}

// TBDR tile-ordered render loop.
static void borgBinRender(int frame) {
  int tiles_per_row = borg_fb_width >> 2;
  int tile_rows = borg_fb_height >> 2;
  int fb_offset = frame * FRAME_STRIDE;

  // Precompute packed clear color in tiled format:
  //   lo word = {b[31:16], z_max[15:0]}
  //   hi word = {r[31:16], g[15:0]}
  uint32_t cc_lo = ((uint32_t)last_clear_color.b << 16) | FP16_MAX_DEPTH;
  uint32_t cc_hi = ((uint32_t)last_clear_color.r << 16) | last_clear_color.g;

  borg_load_spirb_shader_at(&rast_shader, BORG_IMEM_RAST_OFFSET);
  borg_load_spirb_shader_at(&frag_shader, BORG_IMEM_FRAG_OFFSET);
  borg_load_add_shader();

  // Set the clear-color shadow registers once per frame.
  // These same values are reused in the per-pixel MMIO pre-fill loop below.
  BORG_GPU->tile_bz = cc_lo;   // {B[31:16], Z_max[15:0]}


  for (int tile_row = 0; tile_row < tile_rows; tile_row++) {
    for (int tile_col = 0; tile_col < tiles_per_row; tile_col++) {
      int tile_index = tile_row * tiles_per_row + tile_col;
      int count = (int)bin_count[tile_index];

      int tx = tile_col << 2;
      int ty = tile_row << 2;

      if (count == 0) {
        // Empty tile: write clear color directly to PSRAM (32 words = 16 pixels
        // × 2).
        int base = fb_offset + tile_index * 32;
        for (int tile_idx = 0; tile_idx < 16; tile_idx++) {
          PSRAM_OUT(base + tile_idx * 2 + 0) = cc_lo;
          PSRAM_OUT(base + tile_idx * 2 + 1) = cc_hi;
        }
        continue;
      }

      // tileBase: written once per tile (same for all triangles on this tile).
      uint32_t tile_base_spi =
          PSRAM_OUT_SPI(0 * FRAME_STRIDE) + (uint32_t)tile_index * 128;
      BORG_GPU->flush_fb_base = tile_base_spi;

      // Pre-fill all 16 tile SRAM pixels with {clear_color.RGB, Z=max}.
      // Uses the TILE_BZ shadow (set once above) + per-pixel TILE_CTRL/TILE_RG.
      for (int tile_idx = 0; tile_idx < 16; tile_idx++) {
        BORG_GPU->tile_ctrl = (uint32_t)tile_idx;
        BORG_GPU->tile_rg   = cc_hi;
      }

      for (int slot = 0; slot < count; slot++) {
        const draw_call_t *dc = &draw_calls[(int)bin_list[tile_index][slot]];

        // Set up hardware uniforms for this draw call.
        // Step 30.1c: sequencer handles edge normalization autonomously.
        if (has_sequencer) {
          uint32_t desc_addr = SEQ_DESC_BASE_ADDR
              + (uint32_t)(int)bin_list[tile_index][slot] * SEQ_DESC_STRIDE;
          BORG_GPU->seq_desc_base = desc_addr;
          BORG_GPU->seq_trigger = 1;
          while (BORG_GPU->status & STATUS_REG_T__SEQ_BUSY_bm)
            ;
          current_uniform_page = 0; // sequencer always uses page 0 (no ping-pong)
          borg_load_spirb_shader_at(&rast_shader, BORG_IMEM_RAST_OFFSET);
          borg_load_spirb_shader_at(&frag_shader, BORG_IMEM_FRAG_OFFSET);
          borg_load_add_shader();
          BORG_GPU->control =
              ((uint32_t)current_uniform_page << CONTROL_REG_T__UNIFORM_WRITE_PAGE_bp);
          BORG_GPU->frag_pc = BORG_IMEM_FRAG_OFFSET;
          // Step 30.1e: The sequencer's sStageUniforms always writes vertex
          // colors to u13-u21. For textured triangles, the fragment shader
          // expects UV data in u13-u18 and 0s in u19-u21 instead.
          if (dc->tri.has_uvs) {
            const uv16_t *uvs = dc->tri.uvs.v;
            const texture_t *t = &dc->tex;
            // u13-u15: scaled U coords (bary order: v1, v0, v2)
            BORG_GPU->uniform[13] = borg_fp16_mul(uvs[1].u, t->w_fp16);
            BORG_GPU->uniform[14] = borg_fp16_mul(uvs[0].u, t->w_fp16);
            BORG_GPU->uniform[15] = borg_fp16_mul(uvs[2].u, t->w_fp16);
            // u16-u18: scaled V coords (bary order: v1, v0, v2)
            BORG_GPU->uniform[16] = borg_fp16_mul(uvs[1].v, t->h_fp16);
            BORG_GPU->uniform[17] = borg_fp16_mul(uvs[0].v, t->h_fp16);
            BORG_GPU->uniform[18] = borg_fp16_mul(uvs[2].v, t->h_fp16);
            // u19-u21: 0 (blue channel unused in texture mode)
            BORG_GPU->uniform[19] = 0;
            BORG_GPU->uniform[20] = 0;
            BORG_GPU->uniform[21] = 0;
            // u25-u30: 0 (unused UV slots)
            for (int i = 25; i <= 30; i++)
              BORG_GPU->uniform[i] = 0;
            BORG_GPU->tex_config =
                (TEX_PSRAM_BYTE_ADDR_FIXED & TEX_CONFIG_REG_T__BASE_ADDR_bm) |
                TEX_CONFIG_REG_T__EN_bm;
          } else {
            BORG_GPU->tex_config = 0;
          }
        } else {
          setup_tile_uniforms(dc);
        }

        // Enqueue tile command.
        while (BORG_GPU->status & STATUS_REG_T__FIFO_FULL_bm)
          ;
        uint32_t cmd = ((uint32_t)tx << CMD_ENQUEUE_REG_T__TILE_X_bp) |
                       ((uint32_t)ty << CMD_ENQUEUE_REG_T__TILE_Y_bp);
        BORG_GPU->cmd_enqueue = cmd;

        // Allow FIFO pop and dispatcher to start.
        for (volatile int k = 0; k < 16; k++) {
          __asm__ volatile("nop");
        }

        // Drain iterator (advances hardware through all 16 tile pixels).
        do {
          uint32_t iter = BORG_GPU->iter;
          if (!(iter & ITER_REG_T__VALID_bm))
            break;
          BORG_GPU->iter = 1;
        } while (1);

        // --- Tile flush ---
        // Auto-detect HW flusher: FLUSH_BUSY goes high right after tileComplete
        // when hasFlusher=true (Sim / ULX3S).  On pico-ice (hasFlusher=false) it
        // is hardwired 0 → always takes the CPU flush path below.
        if (BORG_GPU->status & STATUS_REG_T__FLUSH_BUSY_bm) {
          // HW flusher active — wait for it to finish writing to PSRAM.
          while (BORG_GPU->status & STATUS_REG_T__FLUSH_BUSY_bm);
        } else {
          // CPU tile flush (pico-ice fallback, hasFlusher=false):
          // read 16 tile-buffer pixels from BRAM and write to PSRAM.
          int base = fb_offset + tile_index * 32;
          for (int tile_idx = 0; tile_idx < 16; tile_idx++) {
            BORG_GPU->tile_ctrl = (uint32_t)tile_idx;  // trigger BRAM read
            // 2-cycle BRAM latency (SyncReadMem → readDataHeld)
            __asm__ volatile("nop"); __asm__ volatile("nop");
            __asm__ volatile("nop"); __asm__ volatile("nop");
            uint32_t bz = BORG_GPU->tile_bz;   // {B[31:16], Z[15:0]}
            uint32_t rg = BORG_GPU->tile_rg;   // {R[31:16], G[15:0]}
            PSRAM_OUT(base + tile_idx * 2 + 0) = bz;
            PSRAM_OUT(base + tile_idx * 2 + 1) = rg;
          }
        }

      }   // for (int slot...)
    }
  }
  (void)frame;
}

// Clip-space → NDC → screen-space for 3 vertices.
static void perspective_divide(const clip_vertex_t *cv, fp16_t ndc[3][3],
                               xy16x3_t *pos) {
  for (int v = 0; v < 3; v++) {
    fp16_t inv_w = borg_fp16_rcp(cv[v].w);
    ndc[v][0] = borg_fp16_mul(cv[v].x, inv_w);
    ndc[v][1] = borg_fp16_mul(cv[v].y, inv_w);
    ndc[v][2] = borg_fp16_mul(cv[v].z, inv_w);
    pos->v[v] =
        (xy16_t){borg_fp16_fmadd(ndc[v][0], fp16_half_width, fp16_half_width),
                 borg_fp16_fmadd(ndc[v][1], fp16_half_width, fp16_half_width)};
  }
}

// Triangle setup: compute signed area, back-face cull, return inv_area.
// Returns 0 if the triangle is degenerate or back-facing.
static int triangle_setup(const xy16x3_t *pos, fp16_t *inv_area) {
  fp16_t dx10 = BORG_FP16_SUB(pos->v[1].x, pos->v[0].x);
  fp16_t dy20 = BORG_FP16_SUB(pos->v[2].y, pos->v[0].y);
  fp16_t dx20 = BORG_FP16_SUB(pos->v[2].x, pos->v[0].x);
  fp16_t dy10 = BORG_FP16_SUB(pos->v[1].y, pos->v[0].y);
  fp16_t area =
      BORG_FP16_SUB(borg_fp16_mul(dx10, dy20), borg_fp16_mul(dx20, dy10));
  if (fp16_ge_zero(area))
    return 0; // degenerate or back-facing

  // Hardware fstep.s expects positive edge values.
  // Since we allow negative area, we negate it here and in the edge constants.
  area ^= 0x8000;

  // Edge-vector normalization compensation (see borg_load_edge_constants):
  // borg_load_edge_constants divides each edge vector by fb_width, so
  // inv_area must be multiplied by fb_width to keep barycentric weights
  // correct:
  //   w = (E/W) * (W/area) = E/area  ✓
  //
  // Implementation: construct inv_width via direct FP16 exponent flip (avoids
  // the Fp16Rcp factor-of-2 bug for power-of-2 inputs), multiply area by it to
  // get area/W (always a normal FP16 at any practical resolution), then rcp
  // that.
  //   inv_area_scaled = rcp(area * inv_width) = rcp(area/W) = W/area
  //
  // At 128×128: area/128 ≈ 184 (normal), rcp(184) ≈ 0.00543 >> 6e-5 min-normal.
  fp16_t fb_fp16 = uint_to_fp16(borg_fb_width);
  fp16_t inv_width = (fp16_t)((30u - ((fb_fp16 >> 10) & 0x1Fu)) << 10);
  fp16_t area_norm = borg_fp16_mul(area, inv_width); // area / fb_width
  *inv_area = borg_fp16_rcp(area_norm);              // W / area
  return 1;
}

// Rasterize a single triangle from 3 clip vertices (post-clipping).
static void rasterize_clipped_triangle(const clip_vertex_t *cv,
                                       const texture_t *t, int frame) {
  fp16_t ndc[3][3];
  triangle_t tri;
  perspective_divide(cv, ndc, &tri.screen_pos);
  fp16_t inv_area;
  int setup_res = triangle_setup(&tri.screen_pos, &inv_area);

  if (!setup_res)
    return;
  tri.inv_area = inv_area;

  compute_edge_vectors(tri.screen_pos.v, tri.edges.v);

  for (int v = 0; v < 3; v++) {
    tri.colors.v[v] = (rgb16_t){cv[v].r, cv[v].g, cv[v].b};
    tri.uvs.v[v] = (uv16_t){cv[v].u, cv[v].v};
  }
  tri.z_vals = (fp16x3_t){{ndc[0][2], ndc[1][2], ndc[2][2]}};
  tri.has_uvs = (t->psram_offset >= 0);

  // TBDR: record for deferred tile-ordered rendering instead of immediate
  // shade.
  record_draw_call(&tri, t, frame);
}

// Build clip-space vertices from vertex shader output and original attributes.
static void build_clip_vertices(const fp16_t *vout, int stride,
                                const borg_vertex_t vertices[3],
                                clip_vertex_t clip_in[3]) {
  for (int v = 0; v < 3; v++) {
    clip_in[v].x = vout[v * stride + 0];
    clip_in[v].y = vout[v * stride + 1];
    clip_in[v].z = (stride >= 3) ? vout[v * stride + 2] : FP16_ZERO;
    clip_in[v].w = (stride >= 4) ? vout[v * stride + 3] : FP16_ONE;
    clip_in[v].r = vertices[v].color[0];
    clip_in[v].g = vertices[v].color[1];
    clip_in[v].b = vertices[v].color[2];
    clip_in[v].u = vertices[v].uv[0];
    clip_in[v].v = vertices[v].uv[1];
  }
}

static void clip_and_rasterize(const clip_vertex_t clip_in[3],
                               const texture_t *t, int frame) {
  clip_vertex_t clip_a[MAX_CLIP_VERTS + 1];
  clip_vertex_t clip_b[MAX_CLIP_VERTS + 2];

  int n_near = clip_near(clip_in, 3, clip_a);
  if (n_near < 3)
    return;

  int n_far = clip_far(clip_a, n_near, clip_b);
  if (n_far < 3)
    return;

  for (int i = 1; i < n_far - 1; i++) {
    clip_vertex_t tri[3] = {clip_b[0], clip_b[i], clip_b[i + 1]};
    rasterize_clipped_triangle(tri, t, frame);
  }
}

void borgCmdDraw(const borg_draw_data_t *d, const borg_vertex_t vertices[3],
                 int frame) {
  unsigned int t_start = get_cycles();

  fp16_t attrs[NUM_VERTICES * 3];
  for (int v = 0; v < NUM_VERTICES; v++) {
    attrs[v * 3 + 0] = vertices[v].pos[0];
    attrs[v * 3 + 1] = vertices[v].pos[1];
    attrs[v * 3 + 2] = vertices[v].pos[2];
  }

  fp16_t vout[NUM_VERTICES * SPIRB_MAX_REGS];
  run_vertex_shader(d->uniforms, attrs, vout);

  clip_vertex_t clip_in[3];
  build_clip_vertices(vout, vert_shader.num_outputs, vertices, clip_in);

  // TBDR: records draw call for deferred rendering (no immediate render)
  clip_and_rasterize(clip_in, &tex, frame);
  t_draw_cycles += get_cycles() - t_start;
}

void borg_present(int frame) {
  unsigned int t_wait = get_cycles();

  // TBDR: bin all recorded draw calls, then render tile-by-tile.
  borgBin();
  borgBinRender(frame);

  // Wait for the GPU to finish the last tile flush.
  while (!(BORG_GPU->status & STATUS_REG_T__IDLE_bm))
    ;
  t_draw_cycles += get_cycles() - t_wait;

  int base = frame * FRAME_STRIDE + FRAME_FB_SIZE;
  PSRAM_OUT(base) = DONE_MARKER;
  PSRAM_OUT(base + 1) = t_init_cycles & 0xFFFF;
  PSRAM_OUT(base + 2) = (t_init_cycles >> 16) & 0xFFFF;
  PSRAM_OUT(base + 3) = t_clear_cycles & 0xFFFF;
  PSRAM_OUT(base + 4) = (t_clear_cycles >> 16) & 0xFFFF;
  PSRAM_OUT(base + 5) = t_draw_cycles & 0xFFFF;
  PSRAM_OUT(base + 6) = (t_draw_cycles >> 16) & 0xFFFF;
}
