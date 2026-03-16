// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Borg GPU driver implementation.

#include "driver.h"
#include "borg_math.h"
#include "spirb.h"

// --- Hardware addresses ---
#define UART_TX (*(volatile uint32_t *)0x08000080)
#define UART_STATUS (*(volatile uint32_t *)0x08000084)
#define UART_BAUD (*(volatile uint32_t *)0x08000088)
#define STARTUP_DELAY() do { \
    for (volatile int i = 0; i < 10000; i++) ; \
  } while (0)

#define BORG_BASE 0x080000C0
#define BORG_REG(n) (*(volatile uint32_t *)(BORG_BASE + (n) * 4))
#define BORG_IMEM(n) (*(volatile uint32_t *)(BORG_BASE + 32 + (n) * 4))
#define BORG_CONTROL (*(volatile uint32_t *)(BORG_BASE + 60))
#define BORG_STATUS (*(volatile uint32_t *)(BORG_BASE + 60))

#define PSRAM_OUT(n) (*(volatile uint32_t *)(0x01001000 + 128 + (n) * 4))

#define FB_OFFSET 32
#define FP16_SIXTEEN 0x4C00

// FP16 half-width of framebuffer (for NDC → screen-space conversion)
#if BORG_FB_WIDTH == 16
  #define FP16_HALF_WIDTH 0x4800   // 8.0
#elif BORG_FB_WIDTH == 32
  #define FP16_HALF_WIDTH 0x4C00   // 16.0
#elif BORG_FB_WIDTH == 64
  #define FP16_HALF_WIDTH 0x5000   // 32.0
#else
  #error "Unsupported BORG_FB_WIDTH — add FP16_HALF_WIDTH entry"
#endif

#define NUM_VERTICES 3

// --- UART ---
void putc_uart(int c) {
  while (UART_STATUS & 1)
    ;
  UART_TX = c;
}
static void puts_uart(const char *s) {
  while (*s)
    putc_uart(*s++);
}



// --- Borg FPU helpers ---
static void borg_run(void) {
  BORG_CONTROL = 2;
  (void)BORG_STATUS;
  BORG_CONTROL = 1;
  int timeout = 100000;
  while (!(BORG_STATUS & 2) && timeout > 0)
    timeout--;
}

static uint16_t borg_fp16_add(uint16_t a, uint16_t b) {
  BORG_IMEM(0) = 0x0210;
  BORG_IMEM(1) = 0x0000;
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

#define BORG_FP16_SUB(a, b) borg_fp16_add((a), (b) ^ 0x8000)
#define BORG_FP16_NEG(x) ((x) ^ 0x8000)
#define FP16_TWO  0x4000

static uint16_t borg_fp16_mul(uint16_t a, uint16_t b) {
  // fmul r0, r1, r2: [15:14]=01, [11:8]=r2, [7:4]=r1, [3:0]=r0
  BORG_IMEM(0) = 0x4210;
  BORG_IMEM(1) = 0x0000; // halt
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

static uint16_t borg_fp16_fmadd(uint16_t a, uint16_t b, uint16_t c) {
  // fmadd r0, r1, r2, r3: [15:14]=10, [13:12]=r3(low2), [11:8]=r2, [7:4]=r1, [3:0]=r0
  // r0 = r1 * r2 + r3
  BORG_IMEM(0) = 0xB210;  // fmadd r0 = r1 * r2 + r3 (rs3=3 → bits[13:12]=11=3)
  BORG_IMEM(1) = 0x0000;
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  BORG_REG(3) = c;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

// FP16 reciprocal using Newton-Raphson: y = 1/x
// Initial estimate via exponent flip, refined with 2 NR iterations.
static uint16_t borg_fp16_rcp(uint16_t x) {
  uint16_t sign = x & 0x8000;
  uint16_t exp = (x >> 10) & 0x1F;
  if (exp == 0 || exp == 31) return 0;
  // Initial estimate: flip exponent around bias, zero mantissa
  uint16_t est_exp = 30 - exp;
  if (est_exp >= 31) return sign | 0x7C00;
  uint16_t y = sign | (est_exp << 10);
  // Newton-Raphson: y = y * (2 - x * y), 2 iterations
  for (int i = 0; i < 2; i++) {
    uint16_t xy = borg_fp16_mul(x, y);
    uint16_t correction = BORG_FP16_SUB(FP16_TWO, xy);
    y = borg_fp16_mul(y, correction);
  }
  return y;
}

// --- Shader globals ---
static spirb_shader_t vert_shader;
static spirb_shader_t rast_shader;
static spirb_shader_t frag_shader;

static void borg_load_spirb_shader(const spirb_shader_t *s) {
  for (int i = 0; i < s->num_instrs; i++)
    BORG_IMEM(i) = s->instrs[i];
  BORG_IMEM(s->num_instrs) = 0x0000;
}

static void borg_load_add_shader(void) {
  BORG_IMEM(0) = 0x0210;
  BORG_IMEM(1) = 0x0000;
  BORG_IMEM(2) = 0x0000;
  BORG_IMEM(3) = 0x0000;
}

static uint16_t borg_fp16_sub_raw(uint16_t a, uint16_t b) {
  BORG_REG(1) = a;
  BORG_REG(2) = b ^ 0x8000;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}




// --- Vertex shader ---
static void run_vertex_shader(const uint16_t *uniforms,
                               const uint16_t *attrs,
                               uint16_t *outputs) {
  const spirb_shader_t *s = &vert_shader;
  borg_load_spirb_shader(s);

  for (int i = 0; i < s->num_uniforms; i++)
    BORG_REG(s->uniform_regs[i]) = uniforms[i];

  for (int v = 0; v < NUM_VERTICES; v++) {
    BORG_CONTROL = 2;
    for (int i = 0; i < s->num_uniforms; i++)
      BORG_REG(s->uniform_regs[i]) = uniforms[i];
    for (int i = 0; i < s->num_attributes; i++)
      BORG_REG(s->attribute_regs[i]) = attrs[v * s->num_attributes + i];
    for (int i = 0; i < s->num_consts; i++)
      BORG_REG(s->const_regs[i]) = s->const_vals[i];
    borg_run();
    for (int i = 0; i < s->num_outputs; i++)
      outputs[v * s->num_outputs + i] = BORG_REG(s->output_regs[i]) & 0xFFFF;
  }
  puts_uart("D\r\n");
}

static void screen_space_translate(const uint16_t *vout,
                                    uint16_t *sx, uint16_t *sy) {
  int stride = vert_shader.num_outputs;
  for (int v = 0; v < NUM_VERTICES; v++) {
    sx[v] = borg_fp16_fmadd(vout[v * stride + 0], FP16_HALF_WIDTH, FP16_HALF_WIDTH);
    sy[v] = borg_fp16_fmadd(vout[v * stride + 1], FP16_HALF_WIDTH, FP16_HALF_WIDTH);
  }
}

static void compute_edge_vectors(const uint16_t *sx, const uint16_t *sy,
                                  uint16_t *dx, uint16_t *neg_dy) {
  dx[0] = BORG_FP16_SUB(sx[1], sx[0]);
  neg_dy[0] = BORG_FP16_NEG(BORG_FP16_SUB(sy[1], sy[0]));
  dx[1] = BORG_FP16_SUB(sx[2], sx[1]);
  neg_dy[1] = BORG_FP16_NEG(BORG_FP16_SUB(sy[2], sy[1]));
  dx[2] = BORG_FP16_SUB(sx[0], sx[2]);
  neg_dy[2] = BORG_FP16_NEG(BORG_FP16_SUB(sy[0], sy[2]));
  puts_uart("F\r\n");
}

static void compute_pixel_deltas(uint16_t pcx, uint16_t pcy,
                                  const uint16_t *sx, const uint16_t *sy,
                                  uint16_t *dpx, uint16_t *dpy) {
  borg_load_add_shader();
  for (int e = 0; e < 3; e++) {
    dpx[e] = borg_fp16_sub_raw(pcx, sx[e]);
    dpy[e] = borg_fp16_sub_raw(pcy, sy[e]);
  }
}

static inline int fp16_ge_zero(uint16_t v) { return (v & 0x8000) == 0; }

// Precomputed FP16 pixel center coordinates: 0.5, 1.5, ..., 31.5
static const uint16_t pc_lut[32] = {
    0x3800, 0x3E00, 0x4100, 0x4300, // 0.5, 1.5, 2.5, 3.5
    0x4480, 0x4580, 0x4680, 0x4780, // 4.5, 5.5, 6.5, 7.5
    0x4840, 0x48C0, 0x4940, 0x49C0, // 8.5, 9.5, 10.5, 11.5
    0x4A40, 0x4AC0, 0x4B40, 0x4BC0, // 12.5, 13.5, 14.5, 15.5
    0x4C20, 0x4C60, 0x4CA0, 0x4CE0, // 16.5, 17.5, 18.5, 19.5
    0x4D20, 0x4D60, 0x4DA0, 0x4DE0, // 20.5, 21.5, 22.5, 23.5
    0x4E20, 0x4E60, 0x4EA0, 0x4EE0, // 24.5, 25.5, 26.5, 27.5
    0x4F20, 0x4F60, 0x4FA0, 0x4FE0  // 28.5, 29.5, 30.5, 31.5
};

// --- Rasterization ---
static uint16_t borg_rasterize_edge(uint16_t dx_e, uint16_t neg_dy_e,
                                     uint16_t dpx_e, uint16_t dpy_e) {
  const spirb_shader_t *s = &rast_shader;
  BORG_REG(s->attribute_regs[0]) = dx_e;
  BORG_REG(s->attribute_regs[1]) = neg_dy_e;
  BORG_REG(s->attribute_regs[2]) = dpx_e;
  BORG_REG(s->attribute_regs[3]) = dpy_e;
  borg_run();
  return BORG_REG(s->output_regs[0]) & 0xFFFF;
}

static uint16_t __attribute__((noinline)) borg_frag_channel(
    uint16_t e0, uint16_t e1, uint16_t e2,
    uint16_t inv_area, uint16_t c0, uint16_t c1, uint16_t c2) {
  BORG_REG(frag_shader.attribute_regs[0]) = e0;
  BORG_REG(frag_shader.attribute_regs[1]) = e1;
  BORG_REG(frag_shader.attribute_regs[2]) = e2;
  BORG_REG(frag_shader.uniform_regs[0]) = inv_area;
  BORG_REG(frag_shader.uniform_regs[1]) = c0;
  BORG_REG(frag_shader.uniform_regs[2]) = c1;
  BORG_REG(frag_shader.uniform_regs[3]) = c2;
  borg_run();
  return BORG_REG(frag_shader.output_regs[0]) & 0xFFFF;
}

static int __attribute__((noinline)) borg_bary_rgb(
    uint16_t *dx, uint16_t *neg_dy,
    uint16_t *dpx, uint16_t *dpy,
    uint16_t inv_area, uint16_t colors[3][3],
    uint16_t *r_out, uint16_t *g_out, uint16_t *b_out) {
  borg_load_spirb_shader(&rast_shader);
  uint16_t e0 = borg_rasterize_edge(dx[0], neg_dy[0], dpx[0], dpy[0]);
  uint16_t e1 = borg_rasterize_edge(dx[1], neg_dy[1], dpx[1], dpy[1]);
  uint16_t e2 = borg_rasterize_edge(dx[2], neg_dy[2], dpx[2], dpy[2]);
  if ((fp16_ge_zero(e0) && e0 != 0) ||
      (fp16_ge_zero(e1) && e1 != 0) ||
      (fp16_ge_zero(e2) && e2 != 0))
    return 0;
  borg_load_spirb_shader(&frag_shader);
  *r_out = borg_frag_channel(e0, e1, e2, inv_area,
                              colors[0][0], colors[1][0], colors[2][0]);
  *g_out = borg_frag_channel(e0, e1, e2, inv_area,
                              colors[0][1], colors[1][1], colors[2][1]);
  *b_out = borg_frag_channel(e0, e1, e2, inv_area,
                              colors[0][2], colors[1][2], colors[2][2]);
  return 1;
}

// --- Public API ---

void borg_init(const uint8_t *vert_blob, unsigned int vert_len,
               const uint8_t *rast_blob, unsigned int rast_len,
               const uint8_t *frag_blob, unsigned int frag_len) {
  STARTUP_DELAY();
  UART_BAUD = 34;
  puts_uart("Borg pipeline\r\n");
  spirb_parse(vert_blob, &vert_shader);
  spirb_parse(rast_blob, &rast_shader);
  spirb_parse(frag_blob, &frag_shader);
}

void borg_set_angle(borg_draw_data_t *d, uint16_t angle_fp16) {
  // Compute vertex shader uniforms from angle
  // The vertex shader expects: uniform[0]=sin, uniform[1]=cos, uniform[2]=-sin
  d->uniforms[0] = fp16_sin(angle_fp16);
  d->uniforms[1] = fp16_cos(angle_fp16);
  d->uniforms[2] = fp16_neg(d->uniforms[0]);
}

#define FRAME_FB_SIZE (BORG_FB_WIDTH * BORG_FB_HEIGHT * 3)  // 768 words
#define FRAME_STRIDE  (FRAME_FB_SIZE + 1)                    // 769 words (FB + DONE marker)

void borg_cmd_draw(const borg_draw_data_t *d, const borg_vertex_t vertices[3], int frame) {
  // Clear stale DONE marker for this frame
  PSRAM_OUT(frame * FRAME_STRIDE + FRAME_FB_SIZE) = 0;
  // Build colors array from vertex data
  uint16_t colors[3][3];
  for (int v = 0; v < 3; v++)
    for (int c = 0; c < 3; c++)
      colors[v][c] = vertices[v].color[c];
  // Build vertex attribute array from vertex positions
  uint16_t attrs[NUM_VERTICES * 2];
  for (int v = 0; v < NUM_VERTICES; v++) {
    attrs[v * 2 + 0] = vertices[v].pos[0];
    attrs[v * 2 + 1] = vertices[v].pos[1];
  }

  // Vertex shader
  uint16_t vout[NUM_VERTICES * SPIRB_MAX_REGS];
  run_vertex_shader(d->uniforms, attrs, vout);

  // Screen-space translation
  uint16_t sx[3], sy[3];
  screen_space_translate(vout, sx, sy);

  // Triangle setup: compute inv_area from screen-space positions
  // area = (sx1-sx0)*(sy2-sy0) - (sx2-sx0)*(sy1-sy0) (2D cross product)
  uint16_t dx10 = BORG_FP16_SUB(sx[1], sx[0]);
  uint16_t dy20 = BORG_FP16_SUB(sy[2], sy[0]);
  uint16_t dx20 = BORG_FP16_SUB(sx[2], sx[0]);
  uint16_t dy10 = BORG_FP16_SUB(sy[1], sy[0]);
  uint16_t area = BORG_FP16_SUB(borg_fp16_mul(dx10, dy20),
                                 borg_fp16_mul(dx20, dy10));
  uint16_t inv_area = borg_fp16_rcp(area);

  // Edge vectors
  uint16_t dx[3], neg_dy[3];
  compute_edge_vectors(sx, sy, dx, neg_dy);

  // Rasterize + fragment shade
  for (int py = 0; py < BORG_FB_HEIGHT; py++) {
    uint16_t pcy = pc_lut[py];
    for (int px = 0; px < BORG_FB_WIDTH; px++) {
      uint16_t pcx = pc_lut[px];
      uint16_t dpx_arr[3], dpy_arr[3];
      compute_pixel_deltas(pcx, pcy, sx, sy, dpx_arr, dpy_arr);
      uint16_t r = 0, g = 0, b = 0;
      borg_bary_rgb(dx, neg_dy, dpx_arr, dpy_arr,
                     inv_area, colors, &r, &g, &b);
      int base = frame * FRAME_STRIDE + (py * BORG_FB_WIDTH + px) * 3;
      PSRAM_OUT(base + 0) = r;
      PSRAM_OUT(base + 1) = g;
      PSRAM_OUT(base + 2) = b;
    }
  }
}

void borg_present(int frame) {
  PSRAM_OUT(frame * FRAME_STRIDE + FRAME_FB_SIZE) = 0xDEAD;
  puts_uart("DONE\r\n");
}
