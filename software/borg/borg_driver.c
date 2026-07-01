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

// --- DRAM frame layout (tiled: 2 words per pixel) ---
// RGB565 tiled framebuffer: the flusher writes one 16-bit RGB565 halfword per
// pixel (R[15:11] G[10:5] B[4:0]); depth lives only in the on-chip tile buffer.
// FRAME_FB_SIZE = W*H/2 32-bit words (2 bytes/pixel, 2 pixels per 32-bit word).
#define FRAME_FB_SIZE (BORG_FB_WIDTH * BORG_FB_HEIGHT / 2)
#define FRAME_ZB_SIZE 0
#define FRAME_STRIDE (FRAME_FB_SIZE + 1) // FB + DONE marker

// Maximum tiles for the largest supported framebuffer (128×128 / 4×4 = 32×32
// tiles).
#define BORG_MAX_TILES 1024      // (128/4)*(128/4) = 1024
// In-RAM draw-call buffer — mirrors SEQ_MAX_DRAWS from borg_layout.h which
// also sizes the DRAM descriptor window (SEQ_MAX_DRAWS × SEQ_DESC_STRIDE).
#define BORG_MAX_DRAWS SEQ_MAX_DRAWS
#define BORG_MAX_TRIS_PER_TILE 8    // max triangles that can touch one tile

// Sequencer auto-detection (set in borgCreateGraphicsPipeline).
// Sim/ULX3S: hasSequencer=true → autonomous uniform staging.
// PicoIce:   hasSequencer=false → CPU setup_tile_uniforms() fallback.
static int has_sequencer = 0;

// Double-buffering: back_buf is the buffer the GPU renders to next.
// Starts at 1 so the first render goes to buffer 1 while the scanout
// shows buffer 0 (black), giving a clean first frame.
static int back_buf = 1;

// DRAM_OUT() word offset of the DONE_MARKER written by the most recent
// borg_present() call — see borg_last_present_marker_offset().
static int last_present_marker_offset = 0;

// Step 30.1b: Sequencer shader ROM constants.
//
// GPU MVP vertex shader ABI (borgc-compiled, uploaded at runtime by borgvk):
//   u0..u2   = raw model pos x,y,z  (loaded by vertex DMA from descriptor)
//   u8..u23  = viewport-baked MVP, 16 values column-major (from cache_ts_mvp):
//              x' row = hw*(M0row + M3row)   — folds the +1 viewport translate
//              y' row = hw*(M1row + M3row)
//              z  row = M2row (raw)          — clip-space depth
//              w  row = M3row (raw)          — clip-space w
//              u8 =x'00 u9 =y'00 u10=z00 u11=w00   (col 0)
//              u12=x'01 u13=y'01 u14=z01 u15=w01   (col 1)
//              u16=x'02 u17=y'02 u18=z02 u19=w02   (col 2)
//              u20=x'03 u21=y'03 u22=z03 u23=w03   (col 3)
//   The shader computes clip'_x, clip'_y, clip_z, clip_w (4 dot products), then
//   inv_w = 1/clip_w and screen = clip' * inv_w.  Because x'/y' already carry
//   hw*(Mrow+M3row), screen_x = clip'_x/w = hw*(clip_x/w + 1) = hw*ndc_x + hw —
//   the full viewport transform, with no extra hw uniform.  AFFINE MVPs (M3 row
//   = [0,0,0,1]) give clip_w = 1, so the divide is a no-op and this reduces
//   exactly to the previous orthographic behaviour (0xAC / 0xAD demos unchanged).
//   r0=screen_x, r1=screen_y, r2=ndc_z (snooped by the sequencer into clipRegs).
//   r30,r31 = 0 when seqBusy=true (special BorgCore registers).
// lightDir = (0.424, 0.566, 0.707) as FP16, pinned to reserved GPRs r17-19.
#define BORGC_LIGHTDIR_R17 0x36c8  // fp16(0.424)
#define BORGC_LIGHTDIR_R18 0x3887  // fp16(0.566)
#define BORGC_LIGHTDIR_R19 0x39a7  // fp16(0.707)
// 32.0 for the DDX rescale FMULs, pinned to uniform u31 (both pages).
#define BORGC_DDXSCALE_U31 0x5000  // fp16(32.0)

// Hand-written GPU MVP vertex shader with HARDWARE PERSPECTIVE DIVIDE.  Used by
// the self-contained firmware (triangle/vkcube, headless sims) when no host
// uploads a borgc vertex shader at runtime.  ABI:
//   u0..u2   = raw model pos x,y,z (loaded by vertex DMA from the descriptor)
//   u8..u23  = viewport-baked MVP (16 values, column-major)
//   r30/r31  = 0 when seqBusy=true (special BorgCore registers)
//   r0=screen_x, r1=screen_y, r2=ndc_z (snooped by the sequencer into clipRegs).
static const uint32_t seq_vert_shader[] = {
  BORG_INSTR_FADD(24, 0, 30, 1),         // r24 = u0 (model x)
  BORG_INSTR_FADD(25, 1, 30, 1),         // r25 = u1 (model y)
  BORG_INSTR_FADD(26, 2, 30, 1),         // r26 = u2 (model z)
  BORG_INSTR_FADD( 0, 20, 30, 1),
  BORG_INSTR_FMADD( 0, 24,  8,  0, 2),
  BORG_INSTR_FMADD( 0, 25, 12,  0, 2),
  BORG_INSTR_FMADD( 0, 26, 16,  0, 2),
  BORG_INSTR_FADD( 1, 21, 30, 1),
  BORG_INSTR_FMADD( 1, 24,  9,  1, 2),
  BORG_INSTR_FMADD( 1, 25, 13,  1, 2),
  BORG_INSTR_FMADD( 1, 26, 17,  1, 2),
  BORG_INSTR_FADD( 2, 22, 30, 1),
  BORG_INSTR_FMADD( 2, 24, 10,  2, 2),
  BORG_INSTR_FMADD( 2, 25, 14,  2, 2),
  BORG_INSTR_FMADD( 2, 26, 18,  2, 2),
  BORG_INSTR_FADD( 3, 23, 30, 1),
  BORG_INSTR_FMADD( 3, 24, 11,  3, 2),
  BORG_INSTR_FMADD( 3, 25, 15,  3, 2),
  BORG_INSTR_FMADD( 3, 26, 19,  3, 2),
  BORG_INSTR_FRCP( 4, 3, 0),             // r4 = 1 / clip_w
  BORG_INSTR_FMUL( 0, 0, 4, 0),          // screen_x = clip'_x * inv_w
  BORG_INSTR_FMUL( 1, 1, 4, 0),          // screen_y = clip'_y * inv_w
  BORG_INSTR_FMUL( 2, 2, 4, 0),          // ndc_z    = clip_z  * inv_w
  BORG_INSTR_HALT,
};
#define SEQ_VERT_SHADER_LEN (sizeof(seq_vert_shader) / sizeof(seq_vert_shader[0]))

// Triangle setup shader (31 instructions, with edge normalization for Step 30.1c):
//   u0-u5 = screen coords of all 3 vertices (written by sWriteSetupInputs).
//   u6    = inv_width = 1/fb_width (written by sWriteSetupInputs, Step 30.1c).
//   Outputs r0-r5 = normalized edge components (divided by fb_width).
//   Output  r7    = inv_area = W/area (matching CPU path convention).
//   r8-r16, r20-r24 are working registers (intermediate positions).
//   r17-r19 are RESERVED: they carry the borgc fragment's lightDir from the
//   firmware (written before seq_trigger) through to Pass 2 — do not clobber.
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
  // NOTE: scratch deliberately SKIPS r17-r19 — those hold the borgc fragment's
  // lightDir, written by the firmware ONCE before seq_trigger and read in Pass 2.
  // The original r17/r18/r19 scratch overwrote lightDir with negated screen
  // coords (|v|≈fb_width), so diffuse = dot(±100-ish, normal) saturated → every
  // face shaded pure white or black.  r22-r24 are dead at this point in every
  // stage (FTEX writes r20-22 only during the frag; vertex writes r24-26 before
  // setup runs and setup writes-before-reads).
  BORG_INSTR_FNEG(22, 11, 0),     // r22 = -v1.y
  BORG_INSTR_FADD( 3, 13, 22, 0), // r3  = v2.y - v1.y  (e1.dy)
  BORG_INSTR_FNEG(23,  8, 0),     // r23 = -v0.x
  BORG_INSTR_FADD( 4, 12, 23, 0), // r4  = v2.x - v0.x  (e2.dx)
  BORG_INSTR_FNEG(24, 13, 0),     // r24 = -v2.y
  BORG_INSTR_FADD( 5,  9, 24, 0), // r5  = v0.y - v2.y  (e2.dy)
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

// Per-tile triangle list (the in-RAM "bin").
// bin_count[t] = number of draw calls touching tile t
// bin_list[t][i] = index into draw_calls[] for the i-th triangle on tile t
// uint8_t is sufficient: BORG_MAX_DRAWS = 12, indices are always 0..11.
// The DRAM bin list (Step 32.1 BorgBinner) uses uint16_t entries for
// thousands of triangles — that is a separate, hardware-managed structure.
static uint8_t bin_count[BORG_MAX_TILES];
static uint8_t bin_list[BORG_MAX_TILES][BORG_MAX_TRIS_PER_TILE];

typedef struct {
  int w, h;
} dim2_t;

typedef struct {
  int dram_offset;        // -1 = no texture
  dim2_t size;             // integer dimensions
  uint16_t w_fp16, h_fp16; // FP16 dimensions for Borg FPU
} texture_t;

// Runtime framebuffer dimensions and derived values
int borg_fb_width;
int borg_fb_height;
static fp16_t fp16_half_width;

// log2 of the current texture dimension (set by borg_set_texture; 0 = no clamp).
static uint8_t tex_log2_dim = 0;

// Fragment uniform-staging mode for u19-u27 (see record_draw_call / BorgSequencer):
//   0 (default) = the frag reads model frag_pos there (borgc cube.frag lighting
//                 via dFdx/dFdy) → sequencer loads clipRegs.  FRAG_USES_FRAGPOS=1.
//   1           = the frag reads interpolated per-vertex COLOR there (CTS
//                 out_color=in_color / flat-shaded) → sequencer loads colorRegs.
//                 FRAG_USES_FRAGPOS=0.
// Set via borg_set_frag_vertex_color() before borg_present().
int borg_frag_vertex_color = 0;
void borg_set_frag_vertex_color(int enable) { borg_frag_vertex_color = enable; }
static fp16_t pc_lut[BORG_MAX_FB_DIM];

// Step 32.0: TBR DRAM geometry region base addresses.
// Computed in borgCreateDevice() after framebuffer size is known.
// Layout (SPI byte addresses, sequential after the output framebuffer):
//   tbr_bin_base:   per-tile bin lists  (num_tiles × TBR_BIN_ROW_BYTES)
//   tbr_setup_base: per-triangle store  (SEQ_MAX_TRI × TBR_SETUP_ENTRY_BYTES)
uint32_t tbr_bin_base   = 0;  // set in borgCreateDevice
uint32_t tbr_setup_base = 0;  // set in borgCreateDevice

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

// Command-buffer record-once: static geometry (positions, UVs, metadata) is
// written to DRAM descriptors on the first frame and never again.  Only
// dynamic state (MVP + vertex colors) is rewritten each frame.
// Set to 0 to force a full re-record (e.g. after a pipeline change).
static int g_cmdbuf_valid = 0;

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
static texture_t tex = {.dram_offset = -1};

// --- UART ---
// NOTE: The full SoC's read at UART_STATUS (0x0800001C) does not currently
// ack on the CPU's Decoupled data bus — polling it hangs the CPU forever
// (the write to 0x08000018 worked, but the symmetric read-side decode is
// still broken).  Until that is fixed in Project.scala, use a blind-write
// + fixed cycle delay matching uart_hello.s.  The peripheral has no TX FIFO,
// so a delay shorter than one byte's real shift-out time lets the NEXT
// putc_uart() overwrite UART_TX mid-transmission, silently dropping bytes.
// Derived from CLOCK_MHZ (already known at compile time — see Makefile)
// rather than a fixed constant: a value tuned for one clock (e.g. the 4 MHz
// ASIC target) silently under-delays at another (25 MHz sim/ULX3S needs
// ~2170 cycles/byte vs ASIC's ~347) — this was the actual bug.
#define UART_BAUD_RATE       115200  // distinct from UART_BAUD (the MMIO register)
#define UART_CYCLES_PER_BIT  ((CLOCK_MHZ * 1000000) / UART_BAUD_RATE)
#define UART_CYCLES_PER_BYTE (UART_CYCLES_PER_BIT * 10)  // start + 8 data + stop
// The busy-wait loop costs ~2 cycles/iteration (addi+bnez) on the
// multi-cycle Hutt core (empirical, see uart_hello.s); scale with margin.
#define UART_TX_DELAY_ITERS  (UART_CYCLES_PER_BYTE * 3 / 4)
void putc_uart(int c) {
  UART_TX = c;
  for (volatile int i = 0; i < UART_TX_DELAY_ITERS; i++)
    ;
}

void puts_uart(const char *s) {
  while (*s)
    putc_uart(*s++);
}

// Returns non-zero if a received byte is waiting in the UART RX buffer.
int uart_rx_ready(void) {
#if defined(TARGET_ULX3S)
  return (int)USER_UART_RX_READY;
#else
  return (int)((UART_STATUS >> 1) & 1u);
#endif
}

// Read one received byte (caller must verify uart_rx_ready() first).
int getc_uart(void) {
#if defined(TARGET_ULX3S)
  return (int)(USER_UART_RX_DATA & 0xFFu);
#else
  return (int)(UART_TX & 0xFFu);
#endif
}

// Blocking UART read with a cycle timeout.  Returns the byte (0..255) or -1 if no
// byte arrived within `timeout` free-running cycles.
static int reload_getc(unsigned timeout) {
  unsigned start = rdcycle();
  while (!uart_rx_ready()) {
    if ((unsigned)(rdcycle() - start) > timeout) return -1;
  }
  return getc_uart();
}

// Serial firmware reload (0xB1).  Streams a fresh firmware image from the host
// into the SDRAM scratch region, then pulses a warm reset so the bootloader
// re-copies it to address 0 and the CPU reboots — all without resetting the
// video clock domain, so the HDMI monitor never loses sync.
//
// Wire format after the 0xB1 marker:
//   size (4 B LE) | image[size] | xor8(image)
//
// On any framing/timeout/checksum error this returns WITHOUT resetting, so a
// botched transfer can't brick the running firmware — the caller resumes its
// normal render loop and the host can retry.
void borg_serial_reload(void) {
  // ~80 ms/byte at 25 MHz — generous for host scheduling hiccups mid-stream.
  const unsigned TO = 2000000u;

  uint32_t sz = 0;
  for (int b = 0; b < 4; b++) {
    int c = reload_getc(TO);
    if (c < 0) { puts_uart("B1: size timeout\r\n"); return; }
    sz |= (uint32_t)(c & 0xFF) << (b * 8);
  }
  if (sz == 0 || sz > BORG_RELOAD_MAX) { puts_uart("B1: bad size\r\n"); return; }

  // Image bytes → scratch+4 as word stores (avoids per-byte read-modify-write).
  volatile uint32_t *dst = (volatile uint32_t *)(uintptr_t)(BORG_RELOAD_SCRATCH + 4);
  uint8_t xsum = 0;
  uint32_t i = 0, w = 0;
  while (i < sz) {
    uint32_t word = 0;
    for (int b = 0; b < 4 && i < sz; b++, i++) {
      int c = reload_getc(TO);
      if (c < 0) { puts_uart("B1: data timeout\r\n"); return; }
      xsum ^= (uint8_t)c;
      word |= (uint32_t)(c & 0xFF) << (b * 8);
    }
    dst[w++] = word;   // last word is zero-padded if sz % 4 != 0
  }

  int ck = reload_getc(TO);
  if (ck < 0 || (uint8_t)ck != xsum) { puts_uart("B1: csum fail\r\n"); return; }

  // Commit the size header the bootloader reads first, announce, then warm-reset.
  *(volatile uint32_t *)(uintptr_t)BORG_RELOAD_SCRATCH = sz;
  puts_uart("FW: reload OK, warm reset\r\n");
  for (volatile int d = 0; d < 200000; d++) { }   // let the UART TX drain first
  SOC_WARM_RESET = SOC_WARM_RESET_MAGIC;            // CPU + bootloader reboot
  for (;;) { }                                      // wait for the reset to hit
}

// --- Timing and debug printing ---
static inline unsigned int get_cycles(void) {
  unsigned int c;
  __asm__ volatile("csrr %0, cycle" : "=r"(c));  // Hutt free-running cycle CSR (0xC00)
  return c;
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

  // Read framebuffer dimensions from DRAM (written by host on sim).
  // On ULX3S there is no host — DRAM_IN lives in firmware .text and returns
  // garbage.  Validate: must be a non-zero power-of-2 in [4..256]; else fall
  // back to 128×128.  256 is the headless-CTS max (4096 tiles, see SEQ_MAX_TILES).
  borg_fb_width  = DRAM_IN(0);
  borg_fb_height = DRAM_IN(1);
  if (borg_fb_width < 4 || borg_fb_width > 256 ||
      (borg_fb_width & (borg_fb_width - 1)) != 0)
    borg_fb_width = 128;
  if (borg_fb_height < 4 || borg_fb_height > 256 ||
      (borg_fb_height & (borg_fb_height - 1)) != 0)
    borg_fb_height = 128;

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
  BORG_GPU->flush_fb_base = DRAM_OUT_SPI(0 * FRAME_STRIDE);

  // Program the HDMI scanout's framebuffer bases from the SAME layout constants
  // that drive the GPU flush/render base, so the display engine and the GPU can
  // never drift apart (a 0x80 drift here was the blinking green corner pixel).
  //   PERI_SCANOUT_FB0 @ 0x08000010, PERI_SCANOUT_FB1 @ 0x08000028.
  // Unconditional: these SoC registers exist on every target (Project.scala);
  // on targets with no scanout the writes are harmless no-ops.  This keeps the
  // firmware self-consistent across ULX3S and the BorgHdmiSimTop verilator sim.
  *(volatile uint32_t *)0x08000010u = DRAM_OUT_SPI(0 * FRAME_STRIDE);
  *(volatile uint32_t *)0x08000028u = DRAM_OUT_SPI(1 * FRAME_STRIDE);
  // log2(fbWidth) — fbWidth is always a power of 2
  unsigned int log2_w = 0;
  unsigned int w = (unsigned int)borg_fb_width;
  while (w > 1) {
    w >>= 1;
    log2_w++;
  }
  BORG_GPU->flush_width = log2_w;

  t_init_cycles = get_cycles() - t_init;

  // Step 32.0: Compute TBR DRAM geometry region base addresses.
  // These regions live after the output framebuffer (DRAM_OUT_OFFSET covers
  // one frame of borg_fb_width × borg_fb_height pixels × 2 words × 4 bytes).
  {
    int num_tiles = (borg_fb_width >> 2) * (borg_fb_height >> 2);
    // Two framebuffers (double-buffering): TBR starts after both.
    uint32_t fb_end_spi = (uint32_t)DRAM_SPI_BASE + (uint32_t)DRAM_OUT_OFFSET
                        + 2u * (uint32_t)FRAME_STRIDE * 4u;
    // Bin list: num_tiles rows, each TBR_BIN_ROW_BYTES wide.
    tbr_bin_base   = fb_end_spi;
    uint32_t bin_region_bytes = (uint32_t)num_tiles * TBR_BIN_ROW_BYTES;
    // Setup store: SEQ_MAX_TRI entries, each TBR_SETUP_ENTRY_BYTES wide.
    tbr_setup_base = tbr_bin_base + bin_region_bytes;
  }
}

void borgCreateGraphicsPipeline(const BorgShaderModule *vert,
                                const BorgShaderModule *rast,
                                const BorgShaderModule *frag) {
  spirb_parse(vert->code, &vert_shader);
  spirb_parse(rast->code, &rast_shader);
  spirb_parse(frag->code, &frag_shader);

  // Stage all four sequencer shader stages (vertex/setup/rast/frag) to DRAM.  This
  // makes the pipeline self-contained: the standalone triangle/vkcube firmware
  // (and the headless sims, which have no host) get working shaders here.  borgvk
  // overrides the vertex+fragment stages at runtime via borg_stage_shader (same
  // DRAM addresses), so the live serial path is unaffected.
  //
  // Vertex: the hand-written MVP+perspective-divide shader (seq_vert_shader), which
  // is HALT-terminated, so seq_vert_len = SEQ_VERT_SHADER_LEN (no +1).  The setup
  // shader is likewise the baked seq_setup_shader.  rast+frag are taken from the
  // caller's spirb_parse'd blobs (NOT HALT-terminated → append a 0 + report +1).
  for (int i = 0; i < (int)SEQ_VERT_SHADER_LEN; i++)
    DRAM_OUT_RAW(SEQ_VERT_SHADER_ADDR + (uint32_t)i * 4) = seq_vert_shader[i];

  for (int i = 0; i < (int)SEQ_SETUP_SHADER_LEN; i++)
    DRAM_OUT_RAW(SEQ_SETUP_SHADER_ADDR + (uint32_t)i * 4) = seq_setup_shader[i];

  BORG_GPU->seq_vert_addr  = SEQ_VERT_SHADER_ADDR;
  BORG_GPU->seq_vert_len   = SEQ_VERT_SHADER_LEN;
  BORG_GPU->seq_setup_addr = SEQ_SETUP_SHADER_ADDR;
  BORG_GPU->seq_setup_len  = SEQ_SETUP_SHADER_LEN;

  // Step 31: Stage rast+frag shaders to DRAM for autonomous re-DMA.
  // After vertex+setup, the sequencer needs to reload IMEM with rast+frag.
  for (int i = 0; i < (int)rast_shader.num_instrs; i++)
    DRAM_OUT_RAW(SEQ_RAST_SHADER_ADDR + (uint32_t)i * 4) = rast_shader.instrs[i];
  // HALT sentinel
  DRAM_OUT_RAW(SEQ_RAST_SHADER_ADDR + (uint32_t)rast_shader.num_instrs * 4) = 0;
  for (int i = 0; i < (int)frag_shader.num_instrs; i++)
    DRAM_OUT_RAW(SEQ_FRAG_SHADER_ADDR + (uint32_t)i * 4) = frag_shader.instrs[i];
  DRAM_OUT_RAW(SEQ_FRAG_SHADER_ADDR + (uint32_t)frag_shader.num_instrs * 4) = 0;
  BORG_GPU->seq_rast_addr = SEQ_RAST_SHADER_ADDR;
  BORG_GPU->seq_rast_len  = rast_shader.num_instrs + 1;  // +1 for HALT
  BORG_GPU->seq_frag_addr = SEQ_FRAG_SHADER_ADDR;
  BORG_GPU->seq_frag_len  = frag_shader.num_instrs + 1;  // +1 for HALT

  // Step 30.1c: Precompute 1/fb_width as exact FP16 (fb_width is a power of 2).
  // fp16(2^n) has exp = n+15, so fp16(2^-n) has exp = 15-n = 30 - exp_of_width.
  {
    fp16_t fb_fp16   = uint_to_fp16(borg_fb_width);
    fp16_t inv_width = (fp16_t)((30u - ((fb_fp16 >> 10) & 0x1Fu)) << 10);
    BORG_GPU->seq_inv_width = inv_width;
  }

  // Detect sequencer: trigger with triCount=0, then read seqDoneSticky.
  // Hardware repurposes reads at SEQ_TRIGGER to return a sticky done flag
  // that latches when the sequencer completes and is cleared by the next
  // seq_trigger write.  On platforms without a sequencer, reading this
  // address returns 0.
  BORG_GPU->seq_tri_count = 0;
  BORG_GPU->seq_desc_base = 0;
  BORG_GPU->seq_trigger = 1;      // clears sticky, starts sequencer
  // Wait for the sequencer to finish (≤2 cycles with triCount=0).
  // The sticky flag latches on completion.
  for (volatile int i = 0; i < 8; i++) {}
  has_sequencer = (BORG_GPU->seq_trigger & 1) ? 1 : 0;
  if (has_sequencer) {
    puts_uart("SEQ: hw\r\n");
  } else {
    puts_uart("SEQ: cpu\r\n");
  }
}

void borg_stage_shader(uint8_t stage, const uint8_t *blob) {
  // .borg blob layout (spirb_parse / emit_blob): byte 0 = num_instrs, then a
  // 6-byte header, then num_instrs little-endian u32 instruction words.  Only the
  // instruction words need staging — the sequencer re-DMAs them into IMEM each
  // render; the fragment's lightDir (r17-19) and u31=32.0 consts are written
  // separately in the render path.  borgc HALT-terminates the blob, so
  // seq_*_len = num_instrs (no +1).
  uint32_t n = blob[0];
  // IMEM-slot bounds: vertex DRAM slot is 128 B (32 words); fragment occupies
  // IMEM[13..71] (59 words).  Reject oversized blobs.
  if (stage == 0 && n > 32) return;
  if (stage == 1 && n > 59) return;

  const uint8_t *w = blob + 6;
  uint32_t addr = (stage == 0) ? SEQ_VERT_SHADER_ADDR : SEQ_FRAG_SHADER_ADDR;
  for (uint32_t i = 0; i < n; i++) {
    uint32_t word = (uint32_t)w[i * 4]            | ((uint32_t)w[i * 4 + 1] << 8) |
                    ((uint32_t)w[i * 4 + 2] << 16) | ((uint32_t)w[i * 4 + 3] << 24);
    DRAM_OUT_RAW(addr + i * 4) = word;
  }
  // NOTE: no UART print here.  The host streams the vert and frag 0xB0 packets
  // back-to-back (USB-CDC buffering absorbs the inter-packet usleep into vert's
  // own ~45 ms wire time, so frag immediately follows vert with no gap).  A
  // blocking puts_uart between the two stages would drop frag's leading bytes
  // from the shallow UART FIFO.  The drain loop prints a deferred confirmation
  // after the whole burst is absorbed instead.
  if (stage == 0) {
    BORG_GPU->seq_vert_addr = SEQ_VERT_SHADER_ADDR;
    BORG_GPU->seq_vert_len  = n;
  } else {
    BORG_GPU->seq_frag_addr = SEQ_FRAG_SHADER_ADDR;
    BORG_GPU->seq_frag_len  = n;
    // A host-uploaded fragment is borgc-compiled (e.g. cube.frag), which reads
    // model frag_pos via dFdx/dFdy — so the sequencer must stage frag_pos into
    // u19-u27 (FRAG_USES_FRAGPOS=1).  The standalone firmware sets vertex-color
    // mode for its baked texel×color frag; override it back here so the live
    // borgvk serial path renders correctly with the same firmware image.
    borg_frag_vertex_color = 0;
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

// TBR: Reset binning state. No DRAM clearing needed —
// clear color is written to empty tiles during borgBinRender.
static void borgBinReset(rgb16_t cc) {
  for (int i = 0; i < BORG_MAX_TILES; i++)
    bin_count[i] = 0;
  draw_call_count = 0;
  last_clear_color = cc;
}

void borgInvalidateCommandBuffer(void) { g_cmdbuf_valid = 0; }

int borgCommandBufferValid(void) { return g_cmdbuf_valid; }

// Fast-path frame begin: update clear color only, do NOT reset draw_call_count.
// Called instead of borg_clear_zbuffer when re-recording geometry is skipped.
void borgFastFrameBegin(rgb16_t clear_color) {
  last_clear_color = clear_color;
}

void borg_clear_zbuffer(int frame, rgb16_t clear_color) {
  unsigned int t_start = get_cycles();
  borgBinReset(clear_color);
  t_clear_cycles = get_cycles() - t_start;
}

void borg_set_texture(int tex_width, int tex_height) {
  tex = (texture_t){.dram_offset = 0, // unused, texture at fixed DRAM addr
                    .size = {tex_width, tex_height},
                    .w_fp16 = uint_to_fp16(tex_width),
                    .h_fp16 = uint_to_fp16(tex_height)};
  // Compute log2(tex_width) for the hardware UV clamp (tex_width is power-of-2).
  tex_log2_dim = 0;
  for (int d = tex_width; d > 1; d >>= 1)
    tex_log2_dim++;
  // Step 21.2: Enable hardware sTexFetch via TEX_CONFIG MMIO register.
  // Texture lives at TEX_DRAM_BYTE_ADDR_FIXED (defined in borg_layout.h),
  // BEFORE the framebuffer, so it always fits in the 16-bit base_addr field.
  BORG_GPU->tex_config =
      (TEX_DRAM_BYTE_ADDR_FIXED & TEX_CONFIG_REG_T__BASE_ADDR_bm) |
      TEX_CONFIG_REG_T__EN_bm |
      ((uint32_t)tex_log2_dim << TEX_CONFIG_REG_T__LOG2_DIM_bp);
}

void borg_clear_texture(void) {
  tex.dram_offset = -1;
  // Step 21.2: Disable hardware sTexFetch.
  BORG_GPU->tex_config = 0;
}

// Upload a row-major RGB-FP16 texture (6 bytes/texel: R, G, B each one FP16
// halfword) into the GPU texture region at TEX_DRAM_BYTE_ADDR_FIXED, Morton-
// encoded into the 2-word (8-byte) layout the hardware sTexFetch reads:
//   word0 = { G[15:0], R[15:0] }    word1 = { 0, B[15:0] }
// This mirrors the simulator's load_texture_to_dram() byte-for-byte so the
// hardware framebuffer matches sim.  In sim the host preloads DRAM; on ULX3S
// there is no host, so the CPU writes the texels into SDRAM itself.  Writes are
// full 32-bit words on 8-byte-aligned addresses, so no byte-write RMW occurs.
void borg_upload_texture(const uint8_t *rgb_fp16, int dim) {
  for (int y = 0; y < dim; y++) {
    for (int x = 0; x < dim; x++) {
      int src = y * dim + x;
      uint32_t dst = morton_encode((uint32_t)x, (uint32_t)y);
      uint16_t r = rgb_fp16[(src * 3 + 0) * 2] | (rgb_fp16[(src * 3 + 0) * 2 + 1] << 8);
      uint16_t g = rgb_fp16[(src * 3 + 1) * 2] | (rgb_fp16[(src * 3 + 1) * 2 + 1] << 8);
      uint16_t b = rgb_fp16[(src * 3 + 2) * 2] | (rgb_fp16[(src * 3 + 2) * 2 + 1] << 8);
      uint32_t byte_addr = TEX_DRAM_BYTE_ADDR_FIXED + dst * 8;
      DRAM_OUT_RAW(byte_addr)     = (uint32_t)r | ((uint32_t)g << 16);
      DRAM_OUT_RAW(byte_addr + 4) = (uint32_t)b;
    }
  }
}

// Upload a single texture row (Phase B): the host (borgvk) streams the app's
// texture one row at a time so no large assembly buffer is needed.  `row` is
// `dim` texels of RGB-FP16 (6 bytes each) for the given y, written Morton-encoded
// like borg_upload_texture above.
void borg_upload_texture_row(const uint8_t *row, int y, int dim) {
  for (int x = 0; x < dim; x++) {
    int src = x;
    uint32_t dst = morton_encode((uint32_t)x, (uint32_t)y);
    uint16_t r = row[(src * 3 + 0) * 2] | (row[(src * 3 + 0) * 2 + 1] << 8);
    uint16_t g = row[(src * 3 + 1) * 2] | (row[(src * 3 + 1) * 2 + 1] << 8);
    uint16_t b = row[(src * 3 + 2) * 2] | (row[(src * 3 + 2) * 2 + 1] << 8);
    uint32_t byte_addr = TEX_DRAM_BYTE_ADDR_FIXED + dst * 8;
    DRAM_OUT_RAW(byte_addr)     = (uint32_t)r | ((uint32_t)g << 16);
    DRAM_OUT_RAW(byte_addr + 4) = (uint32_t)b;
  }
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

// CPU fallback: load all uniforms for a draw call into the hardware pipeline.
// Used when hasSequencer=false. Sequencer path replaces this on Sim/ULX3S.
static void setup_tile_uniforms(const draw_call_t *dc) {
  const triangle_t *tri = &dc->tri;
  const texture_t *t = &dc->tex;

  // Ping-pong the uniform pages (Step 13.4)
  static int current_uniform_page = 0;
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

  // Fragment uniforms: FTEX layout
  //   uniform_regs[0]    = inv_area
  //   uniform_regs[1-3]  = U texture (v1, v0, v2) — pre-scaled by tex_w
  //   uniform_regs[4-6]  = V texture (v1, v0, v2) — pre-scaled by tex_h
  //   uniform_regs[7-9]  = color R   (v1, v0, v2)
  //   uniform_regs[10-12]= color G   (v1, v0, v2)
  //   uniform_regs[13-15]= color B   (v1, v0, v2)
  //   uniform_regs[16-18]= z         (v1, v0, v2)
  // Barycentric weight mapping: w2→v1, w1→v0, w0→v2
  const rgb16_t *colors = tri->colors.v;
  BORG_GPU->uniform[frag_shader.uniform_regs[0]] = tri->inv_area;

  // UV slots (indices 1-6)
  if (tri->has_uvs) {
    const uv16_t *uvs = tri->uvs.v;
    load_uniform_triple(&frag_shader, 1, borg_fp16_mul(uvs[1].u, t->w_fp16),
                        borg_fp16_mul(uvs[0].u, t->w_fp16),
                        borg_fp16_mul(uvs[2].u, t->w_fp16));
    load_uniform_triple(&frag_shader, 4, borg_fp16_mul(uvs[1].v, t->h_fp16),
                        borg_fp16_mul(uvs[0].v, t->h_fp16),
                        borg_fp16_mul(uvs[2].v, t->h_fp16));
  } else {
    // No UVs: write zero → FTEX with en=false returns white (1,1,1)
    load_uniform_triple(&frag_shader, 1, 0, 0, 0);
    load_uniform_triple(&frag_shader, 4, 0, 0, 0);
  }

  // Color slots (indices 7-15)
  load_uniform_triple(&frag_shader, 7,  colors[1].r, colors[0].r, colors[2].r);
  load_uniform_triple(&frag_shader, 10, colors[1].g, colors[0].g, colors[2].g);
  load_uniform_triple(&frag_shader, 13, colors[1].b, colors[0].b, colors[2].b);

  // Z slots (indices 16-18)
  load_uniform_triple(&frag_shader, 16, tri->z_vals.v[1], tri->z_vals.v[0],
                      tri->z_vals.v[2]);

  BORG_GPU->frag_pc = BORG_IMEM_FRAG_OFFSET;

  // Enable/disable texture for this draw call
  if (tri->has_uvs) {
    BORG_GPU->tex_config =
        (TEX_DRAM_BYTE_ADDR_FIXED & TEX_CONFIG_REG_T__BASE_ADDR_bm) |
        TEX_CONFIG_REG_T__EN_bm |
        ((uint32_t)tex_log2_dim << TEX_CONFIG_REG_T__LOG2_DIM_bp);
  } else {
    BORG_GPU->tex_config = 0;
  }
}

// Bin a single draw call into the per-tile lists.
static void __attribute__((unused)) borgBin(void) {
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

// Forward declarations for GPU vertex transform state (defined with borgCmdDraw).
#define BORG_MAX_UNIQUE_VERTS 16
static fp16_t g_current_raw_verts[9];
static fp16_t g_ts_mvp_cache[16];

// Record a draw call for later TBR rendering.
static void record_draw_call(const triangle_t *tri, const texture_t *t,
                             int frame) {
  if (draw_call_count >= BORG_MAX_DRAWS)
    return;
  int idx = draw_call_count;
  bbox_t bb = compute_bbox(&tri->screen_pos);
  draw_calls[idx] = (draw_call_t){
      .tri = *tri,
      .tex = *t,
      .bb = bb,
      .frame = frame,
  };

  // Write vertex descriptor to DRAM for the sequencer.
  // Layout (Phase 1): 96B model-space verts + 64B TS-baked MVP + 32B metadata = 256 bytes.
  uint32_t desc_base = SEQ_DESC_BASE_ADDR + (uint32_t)idx * SEQ_DESC_STRIDE;

  if (!g_cmdbuf_valid) {
    // Static geometry — model-space positions, pre-scaled UVs, and metadata.
    // Written once on the first frame; never changes between frames.
    for (int v = 0; v < 3; v++) {
      uint32_t vbase = desc_base + (uint32_t)v * 32;
      DRAM_OUT_RAW(vbase + 0) = g_current_raw_verts[v*3+0];  // model.x
      DRAM_OUT_RAW(vbase + 4) = g_current_raw_verts[v*3+1];  // model.y
      DRAM_OUT_RAW(vbase + 8) = g_current_raw_verts[v*3+2];  // model.z
      DRAM_OUT_RAW(vbase + 24) = tri->has_uvs
          ? (uint32_t)borg_fp16_mul(tri->uvs.v[v].u, t->w_fp16) : (uint32_t)FP16_ZERO;
      DRAM_OUT_RAW(vbase + 28) = tri->has_uvs
          ? (uint32_t)borg_fp16_mul(tri->uvs.v[v].v, t->h_fp16) : (uint32_t)FP16_ZERO;
    }
    DRAM_OUT_RAW(desc_base + SEQ_META_OFFSET + 0) = ((uint32_t)(bb.y0 & ~3) << 16) | (uint32_t)(bb.x0 & ~3);
    DRAM_OUT_RAW(desc_base + SEQ_META_OFFSET + 4) = ((uint32_t)(bb.y1) << 16) | (uint32_t)(bb.x1);
    DRAM_OUT_RAW(desc_base + SEQ_META_OFFSET + 8) = tri->has_uvs ? 1u : 0u;
  }

  // Dynamic state — MVP and vertex colors change every frame (rotation + lighting).
  for (int v = 0; v < 3; v++) {
    uint32_t vbase = desc_base + (uint32_t)v * 32;
    DRAM_OUT_RAW(vbase + 12) = tri->colors.v[v].r;
    DRAM_OUT_RAW(vbase + 16) = tri->colors.v[v].g;
    DRAM_OUT_RAW(vbase + 20) = tri->colors.v[v].b;
  }
  // TS-baked MVP at SEQ_MVP_OFFSET (96): 16 FP16 values, column-major.
  for (int i = 0; i < 16; i++)
    DRAM_OUT_RAW(desc_base + SEQ_MVP_OFFSET + (uint32_t)i * 4) = g_ts_mvp_cache[i];

  draw_call_count++;
}

// TBR tile-ordered render loop.
// NOTE: dead code (CPU software TBR path, pico-ice only — removed).  Its tile
// strides (tile_index * 32 / * 128) are stale for the RGB565 framebuffer; the
// live path is borgBinRenderAutonomous (hardware sequencer + flusher).
static void __attribute__((unused)) borgBinRender(int frame) {
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
        // Empty tile: write clear color directly to DRAM (32 words = 16 pixels
        // × 2).
        int base = fb_offset + tile_index * 32;
        for (int tile_idx = 0; tile_idx < 16; tile_idx++) {
          DRAM_OUT(base + tile_idx * 2 + 0) = cc_lo;
          DRAM_OUT(base + tile_idx * 2 + 1) = cc_hi;
        }
        continue;
      }

      // tileBase: written once per tile (same for all triangles on this tile).
      uint32_t tile_base_spi =
          DRAM_OUT_SPI(0 * FRAME_STRIDE) + (uint32_t)tile_index * 128;
      BORG_GPU->flush_fb_base = tile_base_spi;

      // Pre-fill all 16 tile SRAM pixels with {clear_color.RGB, Z=max}.
      // Uses the TILE_BZ shadow (set once above) + per-pixel TILE_CTRL/TILE_RG.
      for (int tile_idx = 0; tile_idx < 16; tile_idx++) {
        BORG_GPU->tile_ctrl = (uint32_t)tile_idx;
        BORG_GPU->tile_rg   = cc_hi;
      }

      for (int slot = 0; slot < count; slot++) {
        const draw_call_t *dc = &draw_calls[(int)bin_list[tile_index][slot]];

        if (has_sequencer) {
          // TODO: triggering sequencer with triCount=1 per-tile runs the full
          // autonomous FSM (vertex+setup+bin+render), conflicting with the
          // CPU-driven tile loop. Use CPU fallback until a dedicated
          // uniform-staging-only sequencer mode is added.
          setup_tile_uniforms(dc);
        } else {
          // CPU fallback (no sequencer).
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
        // when hasFlusher=true (Sim / ULX3S).  When hasFlusher=false it
        // is hardwired 0 → always takes the CPU flush path below.
        if (BORG_GPU->status & STATUS_REG_T__FLUSH_BUSY_bm) {
          // HW flusher active — wait for it to finish writing to DRAM.
          while (BORG_GPU->status & STATUS_REG_T__FLUSH_BUSY_bm);
        } else {
          // CPU tile flush (hasFlusher=false):
          // read 16 tile-buffer pixels from BRAM and write to DRAM.
          int base = fb_offset + tile_index * 32;
          for (int tile_idx = 0; tile_idx < 16; tile_idx++) {
            BORG_GPU->tile_ctrl = (uint32_t)tile_idx;  // trigger BRAM read
            // 2-cycle BRAM latency (SyncReadMem → readDataHeld)
            __asm__ volatile("nop"); __asm__ volatile("nop");
            __asm__ volatile("nop"); __asm__ volatile("nop");
            uint32_t bz = BORG_GPU->tile_bz;   // {B[31:16], Z[15:0]}
            uint32_t rg = BORG_GPU->tile_rg;   // {R[31:16], G[15:0]}
            DRAM_OUT(base + tile_idx * 2 + 0) = bz;
            DRAM_OUT(base + tile_idx * 2 + 1) = rg;
          }
        }

      }   // for (int slot...)
    }
  }
  (void)frame;
}

// Step 32.3/32.4: Autonomous two-pass TBR rendering.
// Pass 1 (geometry): sequencer runs vert+setup+bin+storeSetup for all triangles.
// Pass 2 (tile render): sequencer iterates ALL tiles, reads bin lists from DRAM,
// loads setup uniforms per triangle, rasterizes, and flushes each tile to DRAM.
// Empty tiles are flushed with the clear color written in sClearTile — no CPU
// pre-fill needed.
static void borgBinRenderAutonomous(int frame) {
  int fb_offset = frame * FRAME_STRIDE;
  int tiles_per_row = borg_fb_width >> 2;

  uint32_t cc_lo = ((uint32_t)last_clear_color.b << 16) | FP16_MAX_DEPTH;
  uint32_t cc_hi = ((uint32_t)last_clear_color.r << 16) | last_clear_color.g;

  BORG_GPU->seq_fb_base       = DRAM_OUT_SPI(fb_offset);
  BORG_GPU->seq_tiles_per_row = tiles_per_row;
  BORG_GPU->seq_clear_lo      = cc_lo;
  BORG_GPU->seq_clear_hi      = cc_hi;
  // Step 32.3: TBR geometry region registers
  BORG_GPU->seq_bin_base      = tbr_bin_base;
  BORG_GPU->seq_bin_row_bytes = TBR_BIN_ROW_BYTES;
  BORG_GPU->seq_setup_base    = tbr_setup_base;
  // seq_rast_addr/len and seq_frag_addr/len are set once in
  // borgCreateGraphicsPipeline() with the correct DRAM staging addresses.
  // Do NOT overwrite them here.

  // Texture config: set once per frame. The sequencer's sStageUniforms now
  // writes UV coords from the descriptor (pre-scaled by tex_w/tex_h in
  // record_draw_call). When has_uvs=false, UV descriptor words are zero;
  // FTEX returns white (texel(1,1,1) × vertexColor = vertexColor).
  // Scan draw calls: enable texture if ANY draw call uses a texture.
  // The global 'tex' state may have been cleared by borg_clear_texture()
  // between draw calls, but per-draw-call UVs are already recorded.
  {
    int any_textured = 0;
    for (int i = 0; i < draw_call_count; i++) {
      if (draw_calls[i].tex.dram_offset >= 0) { any_textured = 1; break; }
    }
    // Fragment uniform-staging mode (u19-u27): the hand frag.s reads vertex
    // colour there (bit=0), the borgc cube.frag reads model frag_pos (bit=1).
    // OR it into every tex_config write (incl. the non-textured branch) so the
    // sequencer always knows which the loaded fragment expects.
    uint32_t frag_mode = borg_frag_vertex_color
        ? 0 : TEX_CONFIG_REG_T__FRAG_USES_FRAGPOS_bm;
    uint32_t log2_bits = (uint32_t)tex_log2_dim << TEX_CONFIG_REG_T__LOG2_DIM_bp;
    BORG_GPU->tex_config = (any_textured
        ? ((TEX_DRAM_BYTE_ADDR_FIXED & TEX_CONFIG_REG_T__BASE_ADDR_bm) | TEX_CONFIG_REG_T__EN_bm)
        : 0) | frag_mode | log2_bits;
  }
  BORG_GPU->control = 0; // uniform page 0

  // Critical: set frag_pc so the dispatcher chains to the fragment shader.
  // Without this, fragPcReg stays 0 and the dispatcher guard
  // (fragPcReg != 0) prevents any fragment shading — all pixels stay at
  // clear color (the "black cube" bug).
  BORG_GPU->frag_pc = BORG_IMEM_FRAG_OFFSET;

  // Set tile_bz shadow register for the clear color (used by tile buffer clear).
  BORG_GPU->tile_bz = cc_lo;

  if (draw_call_count > 0) {
      // (Removed per-frame "A<n>"/"B" debug UART: ~7 blind-write putc/frame, each
      // ~2 ms while the CPU is instruction-starved during render → ~13 ms/frame
      // of pure debug overhead counted in `present`.)
      BORG_GPU->gpr[17] = BORGC_LIGHTDIR_R17;
      BORG_GPU->gpr[18] = BORGC_LIGHTDIR_R18;
      BORG_GPU->gpr[19] = BORGC_LIGHTDIR_R19;
      BORG_GPU->control = 0;  // uniform write page 0
      BORG_GPU->uniform[31] = BORGC_DDXSCALE_U31;
      BORG_GPU->control = CONTROL_REG_T__UNIFORM_WRITE_PAGE_bm;
      BORG_GPU->uniform[31] = BORGC_DDXSCALE_U31;
      BORG_GPU->control = 0;  // restore page 0 (matches the staging default)
      BORG_GPU->seq_desc_base = SEQ_DESC_BASE_ADDR;
      BORG_GPU->seq_tri_count = draw_call_count;
      BORG_GPU->seq_trigger = 1;
      while (BORG_GPU->status & STATUS_REG_T__SEQ_BUSY_bm)
        ;
      g_cmdbuf_valid = 1;
  }
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
  tri.has_uvs = (t->dram_offset >= 0);

  // TBR: record for tile-ordered rendering instead of immediate
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

// raw_pos_cache: model-space positions saved by borgTransformVerts for indexed draws.
static fp16_t raw_pos_cache[BORG_MAX_UNIQUE_VERTS * 3];

// Bake the viewport transform into the MVP for the GPU vertex shader's hardware
// perspective divide.  The shader computes clip'_x,
// clip'_y, clip_z, clip_w then screen = clip' / clip_w, so we fold hw and the +1
// viewport translate into the x'/y' rows: x' = hw*(M0row + M3row), giving
// screen_x = clip'_x/w = hw*(clip_x/w + 1) = hw*ndc_x + hw with no separate hw
// uniform.  z and w rows stay raw.  Column-major: d->uniforms[col*4+row]=M[row][col].
// Affine MVPs (M3 row = [0,0,0,1]) → clip_w = 1, divide is a no-op, and this
// reduces exactly to the previous orthographic baking.
static void cache_ts_mvp(const borg_draw_data_t *d) {
  fp16_t hw = fp16_half_width;
  for (int col = 0; col < 4; col++) {
    fp16_t m0 = d->uniforms[col*4+0];  // M[0][col]
    fp16_t m1 = d->uniforms[col*4+1];  // M[1][col]
    fp16_t m2 = d->uniforms[col*4+2];  // M[2][col]
    fp16_t m3 = d->uniforms[col*4+3];  // M[3][col]
    g_ts_mvp_cache[col*4+0] = borg_fp16_mul(hw, borg_fp16_add(m0, m3)); // x' = hw*(M0+M3)
    g_ts_mvp_cache[col*4+1] = borg_fp16_mul(hw, borg_fp16_add(m1, m3)); // y' = hw*(M1+M3)
    g_ts_mvp_cache[col*4+2] = m2;                                       // z  (raw)
    g_ts_mvp_cache[col*4+3] = m3;                                       // w  (raw)
  }
}

// Update the TS-baked MVP in every active descriptor slot.  Called each frame
// instead of re-recording when geometry is static (command-buffer record-once).
void borgUpdateUniforms(const borg_draw_data_t *d) {
  cache_ts_mvp(d);
  for (int i = 0; i < draw_call_count; i++) {
    uint32_t desc_base = SEQ_DESC_BASE_ADDR + (uint32_t)i * SEQ_DESC_STRIDE;
    for (int j = 0; j < 16; j++)
      DRAM_OUT_RAW(desc_base + SEQ_MVP_OFFSET + (uint32_t)j * 4) = g_ts_mvp_cache[j];
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

  // Save raw model positions and TS-baked MVP for GPU descriptor.
  cache_ts_mvp(d);
  for (int v = 0; v < NUM_VERTICES; v++) {
    g_current_raw_verts[v*3+0] = vertices[v].pos[0];
    g_current_raw_verts[v*3+1] = vertices[v].pos[1];
    g_current_raw_verts[v*3+2] = vertices[v].pos[2];
  }

  fp16_t vout[NUM_VERTICES * SPIRB_MAX_REGS];
  run_vertex_shader(d->uniforms, attrs, vout);

  clip_vertex_t clip_in[3];
  build_clip_vertices(vout, vert_shader.num_outputs, vertices, clip_in);

  // TBR: records draw call for tile-ordered rendering (no immediate render)
  clip_and_rasterize(clip_in, &tex, frame);
  t_draw_cycles += get_cycles() - t_start;
}

void borgTransformVerts(const borg_draw_data_t *d, const fp16_t *positions,
                        int count) {
  // GPU handles vertex transform via sequencer. Cache MVP and raw positions.
  cache_ts_mvp(d);
  for (int v = 0; v < count; v++) {
    raw_pos_cache[v*3+0] = positions[v*3+0];
    raw_pos_cache[v*3+1] = positions[v*3+1];
    raw_pos_cache[v*3+2] = positions[v*3+2];
  }
}

// Draw one triangle from cached raw positions (idx into raw_pos_cache)
// plus per-call color/uv from `vertices`.  Call borgTransformVerts() first.
void borgCmdDrawIndexed(const int idx[3], const borg_vertex_t vertices[3],
                        int frame) {
  unsigned int t_start = get_cycles();
  for (int v = 0; v < 3; v++) {
    g_current_raw_verts[v*3+0] = raw_pos_cache[idx[v]*3+0];
    g_current_raw_verts[v*3+1] = raw_pos_cache[idx[v]*3+1];
    g_current_raw_verts[v*3+2] = raw_pos_cache[idx[v]*3+2];
  }
  triangle_t tri;
  tri.has_uvs = (tex.dram_offset >= 0);
  for (int v = 0; v < 3; v++) {
    tri.colors.v[v] = (rgb16_t){vertices[v].color[0], vertices[v].color[1], vertices[v].color[2]};
    tri.uvs.v[v]    = (uv16_t){vertices[v].uv[0], vertices[v].uv[1]};
  }
  record_draw_call(&tri, &tex, frame);
  t_draw_cycles += get_cycles() - t_start;
}

void borg_present(int frame) {
  (void)frame;
  unsigned int t_wait = get_cycles();

  // Fully autonomous two-pass TBR rendering (Step 32.3/32.4):
  // Pass 1: sequencer runs vert+setup+bin for all triangles.
  // Pass 2: sequencer iterates all tiles, loads bin lists, rasterizes, flushes.
  borgBinRenderAutonomous(back_buf);

  // Wait for the GPU to finish the last tile flush.
  while (!(BORG_GPU->status & STATUS_REG_T__IDLE_bm))
    ;
  t_draw_cycles += get_cycles() - t_wait;

#ifndef TARGET_ULX3S
  // DONE_MARKER + timing for the sim/host viewer.  Skipped on ULX3S: the marker
  // is never read back (the present-wait below is hardware-side), and the
  // timing words at FRAME_FB_SIZE+1.. would land in the *other* buffer's first
  // pixels (FRAME_STRIDE = FRAME_FB_SIZE+1), corrupting the displayed frame.
  int base = back_buf * FRAME_STRIDE + FRAME_FB_SIZE;
  last_present_marker_offset = base;
  DRAM_OUT(base) = DONE_MARKER;
  DRAM_OUT(base + 1) = t_init_cycles & 0xFFFF;
  DRAM_OUT(base + 2) = (t_init_cycles >> 16) & 0xFFFF;
  DRAM_OUT(base + 3) = t_clear_cycles & 0xFFFF;
  DRAM_OUT(base + 4) = (t_clear_cycles >> 16) & 0xFFFF;
  DRAM_OUT(base + 5) = t_draw_cycles & 0xFFFF;
  DRAM_OUT(base + 6) = (t_draw_cycles >> 16) & 0xFFFF;
#endif

  // Double-buffer swap with scanout synchronization.
  //   PERI_FB_SELECT write (0x08000024) = which buffer the scanout displays.
  //   PERI_FB_SELECT read  (0x08000024) = which buffer the scanout is actually
  //     reading right now (it switches at its fill-loop wrap, ~one frame).
  // After presenting the just-rendered buffer we flip back_buf to the OLD front
  // buffer, then block until the scanout has switched to the new front buffer.
  // That guarantees the scanout has finished reading (released) the buffer the
  // GPU is about to render into — without this the free-running GPU laps the
  // ~33 ms scanout and overwrites the frame mid-display (tearing, partial tris).
  int front = back_buf;
  *(volatile uint32_t *)0x08000024u = (uint32_t)front;
  back_buf ^= 1;
  // Wait until the scanout has actually switched to displaying the new front
  // buffer (it flips at its fill-loop wrap, i.e. the next frame boundary). This
  // both eliminates tearing and guarantees the scanout has released the buffer
  // the GPU is about to render into, so the free-running GPU never overwrites a
  // frame mid-display. The cube renders slower than the scanout refresh, so this
  // wait is essentially free (it is not the frame-rate bottleneck — that is
  // CPU/GPU compute).
  while ((*(volatile uint32_t *)0x08000024u & 1u) != (uint32_t)front)
    ;
}

// DRAM_OUT() word offset of the DONE_MARKER from the most recent borg_present()
// call.  The sim/host viewer polls this address (via DRAM_OUT) to detect frame
// completion and to know when to clear the marker for the next frame — the
// double-buffer slot alternates every present, so callers must not hardcode it.
int borg_last_present_marker_offset(void) {
  return last_present_marker_offset;
}
