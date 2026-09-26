// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg GPU driver — pipeline orchestration, hardware init, draw commands.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_math.h"
#include "borg_spirb.h"
#include <stdbool.h>
#include <stdint.h>

// @doc:mmio-map
#include "borg_isa.h" // IWYU pragma: keep — used by @doc extractor + ISA macros
#include "borg_sys.h"
// @doc:end

// --- DRAM frame layout (tiled: 2 words per pixel) ---
// RGB565 tiled framebuffer: the flusher writes one 16-bit RGB565 halfword per
// pixel (R[15:11] G[10:5] B[4:0]); depth lives only in the on-chip tile buffer.
// FRAME_FB_SIZE = W*H/2 32-bit words (2 bytes/pixel, 2 pixels per 32-bit word).
#define FRAME_FB_SIZE (BORG_FB_WIDTH * BORG_FB_HEIGHT / 2)
#define FRAME_ZB_SIZE 0
#define FRAME_STRIDE (FRAME_FB_SIZE + 1) // FB + DONE marker

// In-RAM draw-call buffer — mirrors SEQ_MAX_DRAWS from borg_layout.h which
// also sizes the DRAM descriptor window (SEQ_MAX_DRAWS × SEQ_DESC_STRIDE).
#define BORG_MAX_DRAWS SEQ_MAX_DRAWS

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
// The fragment's constants (e.g. cube.frag's lightDir, pinned by borgc to the
// reserved GPRs r17-r19) come from the staged blob's const pool -- see
// borg_stage_shader and borgBinRenderAutonomous. They used to be hand-copied
// here, and drifted from borgc's output without anything noticing.

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
//   r8-r16 are working registers; the setup shader touches nothing above r16.
//   r17-r19 and r23 are RESERVED: they carry the fragment's constants (borgc's
//   CONST_REGS, e.g. cube.frag's lightDir), written by the firmware once before
//   seq_trigger and read in Pass 2 -- do not clobber.
static const uint32_t seq_setup_shader[] = {
  // Copy screen coords from uniforms u0-u5 into working regs r8-r13
  BORG_INSTR_FADD( 8, 0, 31, 1),  // r8  = v0.x
  BORG_INSTR_FADD( 9, 1, 31, 1),  // r9  = v0.y
  BORG_INSTR_FADD(10, 2, 31, 1),  // r10 = v1.x
  BORG_INSTR_FADD(11, 3, 31, 1),  // r11 = v1.y
  BORG_INSTR_FADD(12, 4, 31, 1),  // r12 = v2.x
  BORG_INSTR_FADD(13, 5, 31, 1),  // r13 = v2.y
  // Edge vectors. Every negation is consumed by the very next FADD, so one
  // scratch register (r14) serves them all -- keeping r17-r24 untouched.
  BORG_INSTR_FNEG(14, 10, 0),     // r14 = -v1.x
  BORG_INSTR_FADD( 0,  8, 14, 0), // r0  = v0.x - v1.x  (e0.dx)
  BORG_INSTR_FNEG(14,  9, 0),     // r14 = -v0.y
  BORG_INSTR_FADD( 1, 11, 14, 0), // r1  = v1.y - v0.y  (e0.dy)
  BORG_INSTR_FNEG(14, 12, 0),     // r14 = -v2.x
  BORG_INSTR_FADD( 2, 10, 14, 0), // r2  = v1.x - v2.x  (e1.dx)
  BORG_INSTR_FNEG(14, 11, 0),     // r14 = -v1.y
  BORG_INSTR_FADD( 3, 13, 14, 0), // r3  = v2.y - v1.y  (e1.dy)
  BORG_INSTR_FNEG(14,  8, 0),     // r14 = -v0.x
  BORG_INSTR_FADD( 4, 12, 14, 0), // r4  = v2.x - v0.x  (e2.dx)
  BORG_INSTR_FNEG(14, 13, 0),     // r14 = -v2.y
  BORG_INSTR_FADD( 5,  9, 14, 0), // r5  = v0.y - v2.y  (e2.dy)
  // Area = e0.dx * e2.dy + e2.dx * (-e0.dy)
  BORG_INSTR_FMUL(15,  0,  5, 0), // r15 = e0.dx * e2.dy
  BORG_INSTR_FNEG(16,  1, 0),     // r16 = -e0.dy
  BORG_INSTR_FMADD(6, 4, 16, 15, 0), // r6 = e2.dx * r16 + r15 = area
  // Negate the area: the rasterizer's edge functions expect it (same sign
  // convention the removed CPU triangle setup used).
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
  // ---- Step 50.2b: per-edge MSAA sample deltas -------------------------
  // The rasterizer evaluates e = A*dpy + B*dpx, where for edge k
  //   A = r[2k] (the coefficient multiplying dpy, staged to u0/u2/u4)
  //   B = r[2k+1]                                  (staged to u1/u3/u5)
  // Shifting the sample point by (dx_s, dy_s) shifts the edge value by the
  // per-triangle CONSTANT  delta_s = A*dy_s + B*dx_s, so per-sample coverage
  // costs no per-pixel arithmetic -- the hardware just compares e against
  // -delta_s (see BorgShaderDispatcher's coverage logic).
  //
  // Standard Vulkan/D3D 4x sample offsets from the pixel centre:
  //   s0 (-0.125, -0.375)   s1 ( 0.375, -0.125)
  //   s2 (-0.375,  0.125)   s3 ( 0.125,  0.375)
  // These are +/-symmetric (s2 = -s1, s3 = -s0), so only TWO deltas per edge
  // are computed here; hardware derives the other two by sign flip.
  //   d0 = A*(-0.375) + B*(-0.125)      u7 = -0.375
  //   d1 = A*(-0.125) + B*( 0.375)      u8 = -0.125, u9 = +0.375
  //
  // MUST come after the inv_width normalization above: the rasterizer's edge
  // values are in normalized space, so the deltas have to be too.
  // Scratch r14 only; outputs r8-r13 (the screen-coord copies loaded at the
  // top are dead by now).  r17-r24 remain untouched -- r17-r19 and r23 carry the
  // fragment's constants into Pass 2.
  BORG_INSTR_FMUL (14,  0,  7, 2),      // r14 = A0 * -0.375
  BORG_INSTR_FMADD( 8,  1,  8, 14, 2),  // r8  = B0 * -0.125 + r14  = d0[0]
  BORG_INSTR_FMUL (14,  0,  8, 2),      // r14 = A0 * -0.125
  BORG_INSTR_FMADD( 9,  1,  9, 14, 2),  // r9  = B0 *  0.375 + r14  = d1[0]
  BORG_INSTR_FMUL (14,  2,  7, 2),      // r14 = A1 * -0.375
  BORG_INSTR_FMADD(10,  3,  8, 14, 2),  // r10 = B1 * -0.125 + r14  = d0[1]
  BORG_INSTR_FMUL (14,  2,  8, 2),      // r14 = A1 * -0.125
  BORG_INSTR_FMADD(11,  3,  9, 14, 2),  // r11 = B1 *  0.375 + r14  = d1[1]
  BORG_INSTR_FMUL (14,  4,  7, 2),      // r14 = A2 * -0.375
  BORG_INSTR_FMADD(12,  5,  8, 14, 2),  // r12 = B2 * -0.125 + r14  = d0[2]
  BORG_INSTR_FMUL (14,  4,  8, 2),      // r14 = A2 * -0.125
  BORG_INSTR_FMADD(13,  5,  9, 14, 2),  // r13 = B2 *  0.375 + r14  = d1[2]
  BORG_INSTR_HALT,
};
#define SEQ_SETUP_SHADER_LEN (sizeof(seq_setup_shader) / sizeof(seq_setup_shader[0]))

typedef struct {
  int w, h;
} dim2_t;

typedef struct {
  int dram_offset;        // -1 = no texture
  dim2_t size;             // integer dimensions
} texture_t;

// Runtime framebuffer dimensions and derived values
int borg_fb_width;
int borg_fb_height;
static borg_float_t half_width_f;
static borg_float_t half_height_f;  // used by the draw front end's viewport registers

// Draw front end (docs/B1_geometry_front_end.md) activation -- off by
// default so borg_present() keeps driving the legacy per-triangle path.
static int g_draw_mode_active = 0;
static int g_draw_vertex_count = 0;
// The staged draw-mode vertex shader's blob: its varying count sizes the
// records, its draw extension fills the DRAW_VS_CONST window.
static spirb_shader_t g_draw_vert;
static int g_draw_vert_ok = 0;
void borg_set_draw_mode(int enable) { g_draw_mode_active = enable; }

// Sampler descriptor 0 (docs/B2_texture_unit.md) until borgvk sends the app's:
// nearest filtering, CLAMP_TO_EDGE (VkSamplerAddressMode 2) on U, V and W, no
// LOD bias, LOD clamped to [0, 0].
static uint32_t g_sampler_desc[4] = {(2u << 3) | (2u << 6) | (2u << 9), 0, 0, 0};

// Fragment uniform-staging mode for u19-u27 (see record_draw_call / BorgSequencer):
//   0 (default) = the frag reads model frag_pos there (borgc cube.frag lighting
//                 via dFdx/dFdy) → sequencer loads clipRegs.  FRAG_USES_FRAGPOS=1.
//   1           = the frag reads interpolated per-vertex COLOR there (CTS
//                 out_color=in_color / flat-shaded) → sequencer loads colorRegs.
//                 FRAG_USES_FRAGPOS=0.
// Set via borg_set_frag_vertex_color() before borg_present().
int borg_frag_vertex_color = 0;
void borg_set_frag_vertex_color(int enable) { borg_frag_vertex_color = enable; }

// Step 32.0: TBR DRAM geometry region base addresses.
// Computed in borgCreateDevice() after framebuffer size is known.
// Layout (SPI byte addresses, sequential after the output framebuffer):
//   tbr_bin_base:   per-tile bin lists  (num_tiles × TBR_BIN_ROW_BYTES)
//   tbr_setup_base: per-triangle store  (SEQ_MAX_TRI × TBR_SETUP_ENTRY_BYTES)
uint32_t tbr_bin_base   = 0;  // set in borgCreateDevice
uint32_t tbr_setup_base = 0;  // set in borgCreateDevice

// Per-triangle attributes recorded into the sequencer descriptor.
typedef struct {
  borg_float_t r, g, b;
} rgbf_t;
typedef struct {
  borg_float_t u, v;
} uvf_t;
typedef struct {
  rgbf_t colors[3];
  uvf_t uvs[3];
  int has_uvs;
} triangle_t;

// Snapshot of one submitted draw call.
typedef struct {
  triangle_t tri;
  texture_t tex;  // copy of texture state at draw time
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
#define UART_BAUD_RATE       BORG_UART_BAUD  // distinct from UART_BAUD (the MMIO register)
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
// The fragment stage's parsed blob: its const pool is written to the GPRs
// before every render (vertex constants are not supported yet -- borgc emits
// none for the cube's vertex shader).
static spirb_shader_t frag_shader;

// --- Public API ---

void borgCreateDevice(void) {
  STARTUP_DELAY();
  UART_BAUD = UART_BAUD_DEFAULT;
  puts_uart("Borg pipeline\r\n");
  borg_check_float_width();
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

  // Half the framebuffer width, for the viewport transform baked into the MVP.
  half_width_f = borg_float_from_uint((uint32_t)borg_fb_width / 2);
  // Half the framebuffer height, for the draw front end's VIEWPORT_SY/OY
  // (the legacy path's viewport bake is width-only, square-framebuffer only).
  half_height_f = borg_float_from_uint((uint32_t)borg_fb_height / 2);

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
  (void)vert;  // the sequencer runs the hand-written seq_vert_shader below
  (void)rast;  // the rasterizer is a hardware ROM (BorgRasterRom)
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

  // Step 31: Stage frag shader to DRAM for autonomous re-DMA. After vertex+
  // setup, the sequencer needs to reload IMEM with frag. The rasterizer edge-
  // test shader is a permanent hardware ROM (BorgRasterRom) now -- it is no
  // longer staged or DMA'd.
  for (int i = 0; i < (int)frag_shader.num_instrs; i++)
    DRAM_OUT_RAW(SEQ_FRAG_SHADER_ADDR + (uint32_t)i * 4) = frag_shader.instrs[i];
  DRAM_OUT_RAW(SEQ_FRAG_SHADER_ADDR + (uint32_t)frag_shader.num_instrs * 4) = 0;
  BORG_GPU->seq_frag_addr = SEQ_FRAG_SHADER_ADDR;
  BORG_GPU->seq_frag_len  = frag_shader.num_instrs + 1;  // +1 for HALT

  // Step 30.1c: 1/fb_width, exact (fb_width is a power of 2).
  {
    unsigned log2_w = 0;
    for (int w = borg_fb_width; w > 1; w >>= 1) log2_w++;
    BORG_GPU->seq_inv_width = borg_float_inv_pow2(log2_w);
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
  // render.  The fragment's const pool is parsed here and written to its GPRs
  // in the render path.  borgc HALT-terminates the blob, so seq_*_len =
  // num_instrs (no +1).
  uint32_t n = blob[0];
  // IMEM-slot bounds: vertex DRAM slot is 128 B (32 words); fragment occupies
  // IMEM[BORG_IMEM_FRAG_OFFSET..BORG_IMEM_DEPTH-1] (BORG_IMEM_FRAG_LEN words).
  // Reject oversized blobs.
  // In draw mode the vertex shader goes to its own, larger slot instead
  // (DRAW_VERT_SHADER_SPI): it pulls and transforms vertices itself, and
  // silently dropping it here would leave the baked legacy seq_vert_shader
  // running under the draw walker.
  uint32_t vert_addr = g_draw_mode_active ? DRAW_VERT_SHADER_SPI : SEQ_VERT_SHADER_ADDR;
  uint32_t vert_max  = g_draw_mode_active ? DRAW_VERT_SHADER_MAX_WORDS : 32;
  if (stage == 0 && n > vert_max) return;
  if (stage == 1 && n > BORG_IMEM_FRAG_LEN) return;
  // A draw-mode blob must carry its draw extension, and every window word
  // must land inside its stage's window (u25-u31 vertex, u20-u31 fragment):
  // the firmware writes it to the window base + 4*(u - first index).
  static spirb_shader_t parsed;
  if (g_draw_mode_active) {
    uint8_t u0 = (stage == 0) ? DRAW_VS_CONST_U0 : DRAW_FS_CONST_U0;
    uint8_t words = (stage == 0) ? DRAW_VS_CONST_MAX_WORDS : DRAW_FS_CONST_WORDS;
    if (spirb_parse(blob, &parsed) < 0 || !parsed.has_draw_ext) return;
    for (int i = 0; i < parsed.num_window; i++)
      if (parsed.window_regs[i] < u0 || parsed.window_regs[i] >= u0 + words) return;
  }

  const uint8_t *w = blob + 6;
  uint32_t addr = (stage == 0) ? vert_addr : SEQ_FRAG_SHADER_ADDR;
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
    BORG_GPU->seq_vert_addr = vert_addr;
    BORG_GPU->seq_vert_len  = n;
    if (g_draw_mode_active) {
      g_draw_vert = parsed;
      g_draw_vert_ok = 1;
    }
  } else {
    BORG_GPU->seq_frag_addr = SEQ_FRAG_SHADER_ADDR;
    BORG_GPU->seq_frag_len  = n;
    if (spirb_parse(blob, &parsed) >= 0) frag_shader = parsed;
    // A host-uploaded fragment is borgc-compiled (e.g. cube.frag), which reads
    // model frag_pos via dFdx/dFdy — so the sequencer must stage frag_pos into
    // u19-u27 (FRAG_USES_FRAGPOS=1).  The standalone firmware sets vertex-color
    // mode for its baked texel×color frag; override it back here so the live
    // borgvk serial path renders correctly with the same firmware image.
    borg_frag_vertex_color = 0;
  }
}

// TBR: Reset binning state. No DRAM clearing needed —
// clear color is written to empty tiles during borgBinRender.
static void borgBinReset(rgb16_t cc) {
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

void borg_set_sampler(const uint32_t desc[4]) {
  for (int i = 0; i < 4; i++)
    g_sampler_desc[i] = desc[i];
}

void borg_set_texture(int tex_width, int tex_height) {
  tex = (texture_t){.dram_offset = 0, // unused, texture at fixed DRAM addr
                    .size = {tex_width, tex_height}};
  // Texture descriptor 0 (docs/B2_texture_unit.md): a 2D, one-level,
  // one-layer RGBA8 image, linear, identity swizzle.
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 0) = TEX_TEXEL_ADDR;
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 4) =
      (uint32_t)(tex_width - 1) | ((uint32_t)(tex_height - 1) << 16) |
      (1u << 28) |  // type 2D
      (1u << 30);   // linear layout
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 8) =
      (uint32_t)BORG_TEX_FORMAT_R8G8B8A8_UNORM << 14;
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 12) = (uint32_t)tex_width * 4; // bytes per row
  for (int w = 4; w < 16; w++)
    DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + (uint32_t)w * 4) = 0;
  for (int w = 0; w < 4; w++)
    DRAM_OUT_RAW(SAMPLER_DESC_TABLE_ADDR + (uint32_t)w * 4) = g_sampler_desc[w];
  // Written after the tables: a write to either register also drops the one
  // texture and one sampler descriptor the unit caches.
  BORG_GPU->tex_desc_base = TEX_DESC_TABLE_ADDR;
  BORG_GPU->sampler_desc_base = SAMPLER_DESC_TABLE_ADDR;
}

void borg_clear_texture(void) {
  tex.dram_offset = -1;
}

// Step 50 item 13: stage a push-constant range and point LS_BASE at it.
//
// No new hardware: LOAD/STORE and the LS_BASE register already exist
// (BorgConfig.hasMemoryOps, ls_base_reg_t in the RDL), and borgc already
// lowers load_push_constant to `LOAD rd, rs1` with rs1 pinned to the field's
// word index.  The only thing that was missing is this -- putting the bytes
// where those loads look.
//
// LS_BASE is written on every call rather than once at init because it is
// also the base for ordinary SSBO-style LOAD/STORE: whoever points it
// somewhere else must not silently break the next draw's push constants, and
// re-asserting it here is one register write against a UART packet's cost.
void borg_set_push_constants(const uint32_t *words, uint32_t off_words,
                             uint32_t nwords) {
  if (!words || nwords == 0) return;
  // Clamp rather than trust: the range arrives over a serial link, and a
  // corrupted length that survived the checksum would otherwise scribble
  // across the CTS mailbox below or the firmware stack above.
  if (off_words >= BORG_PUSH_CONST_MAX_WORDS) return;
  if (nwords > BORG_PUSH_CONST_MAX_WORDS - off_words)
    nwords = BORG_PUSH_CONST_MAX_WORDS - off_words;

  for (uint32_t i = 0; i < nwords; i++)
    DRAM_OUT_RAW(BORG_PUSH_CONST_SPI + (off_words + i) * 4) = words[i];

  BORG_GPU->ls_base = BORG_PUSH_CONST_SPI & LS_BASE_REG_T__BASE_ADDR_bm;
}

// Upload a single texture row: the host (borgvk) streams the app's texture one
// row at a time so no large assembly buffer is needed. `row` is `dim` RGBA8
// texels for the given y, stored linear (row-major) at TEX_TEXEL_ADDR -- what
// texture descriptor 0 describes. One 32-bit write per texel, word-aligned, so
// no byte-write RMW occurs.
void borg_upload_texture_row(const uint8_t *row, int y, int dim) {
  uint32_t base = TEX_TEXEL_ADDR + (uint32_t)y * (uint32_t)dim * 4;
  for (int x = 0; x < dim; x++) {
    const uint8_t *p = &row[x * 4];
    DRAM_OUT_RAW(base + (uint32_t)x * 4) =
        (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
        ((uint32_t)p[3] << 24);
  }
}

// Forward declarations for GPU vertex transform state (defined with borgCmdDraw).
#define BORG_MAX_UNIQUE_VERTS 16
static borg_float_t g_current_raw_verts[9];
static borg_float_t g_ts_mvp_cache[16];

// Record a draw call for later TBR rendering.
static void record_draw_call(const triangle_t *tri, const texture_t *t,
                             int frame) {
  if (draw_call_count >= BORG_MAX_DRAWS)
    return;
  int idx = draw_call_count;
  draw_calls[idx] = (draw_call_t){
      .tri = *tri,
      .tex = *t,
      .frame = frame,
  };

  // Write vertex descriptor to DRAM for the sequencer.
  // Layout: 96B model-space verts + 64B TS-baked MVP + 32B metadata = 256 bytes,
  // every value one datapath-float word.
  uint32_t desc_base = SEQ_DESC_BASE_ADDR + (uint32_t)idx * SEQ_DESC_STRIDE;

  if (!g_cmdbuf_valid) {
    // Static geometry — model-space positions, UVs, and metadata. UVs are
    // normalized, as TEX takes them (the texture descriptor has the size).
    // Written once on the first frame; never changes between frames.
    for (int v = 0; v < 3; v++) {
      uint32_t vbase = desc_base + (uint32_t)v * 32;
      DRAM_OUT_RAW(vbase + 0) = g_current_raw_verts[v*3+0];  // model.x
      DRAM_OUT_RAW(vbase + 4) = g_current_raw_verts[v*3+1];  // model.y
      DRAM_OUT_RAW(vbase + 8) = g_current_raw_verts[v*3+2];  // model.z
      DRAM_OUT_RAW(vbase + 24) = tri->has_uvs ? tri->uvs[v].u : BORG_FLOAT_ZERO;
      DRAM_OUT_RAW(vbase + 28) = tri->has_uvs ? tri->uvs[v].v : BORG_FLOAT_ZERO;
    }
    // Metadata: only has_uvs is read by the hardware (desc + 168); the binner
    // computes each triangle's bbox from the transformed vertices itself.
    DRAM_OUT_RAW(desc_base + SEQ_META_OFFSET + 8) = tri->has_uvs ? 1u : 0u;
  }

  // Dynamic state — MVP and vertex colors change every frame (rotation + lighting).
  for (int v = 0; v < 3; v++) {
    uint32_t vbase = desc_base + (uint32_t)v * 32;
    DRAM_OUT_RAW(vbase + 12) = tri->colors[v].r;
    DRAM_OUT_RAW(vbase + 16) = tri->colors[v].g;
    DRAM_OUT_RAW(vbase + 20) = tri->colors[v].b;
  }
  // TS-baked MVP at SEQ_MVP_OFFSET (96): 16 values, column-major.
  for (int i = 0; i < 16; i++)
    DRAM_OUT_RAW(desc_base + SEQ_MVP_OFFSET + (uint32_t)i * 4) = g_ts_mvp_cache[i];

  draw_call_count++;
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

  // Fragment uniform-staging mode (u19-u27): the hand frag.s reads vertex
  // colour there (bit=0), the borgc cube.frag reads model frag_pos (bit=1).
  // It is the only live field of TEX_CONFIG; the rest belonged to the retired
  // FTEX texture path (texturing is TEX descriptors now, see borg_set_texture).
  BORG_GPU->tex_config = borg_frag_vertex_color
      ? 0 : TEX_CONFIG_REG_T__FRAG_USES_FRAGPOS_bm;
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
      // The fragment's constants, from its blob. They must survive Pass 1:
      // borgc pins them to GPRs the vertex and setup shaders leave alone
      // (r17-r19 for cube.frag's lightDir).
      for (int i = 0; i < frag_shader.num_consts; i++)
        BORG_GPU->gpr[frag_shader.const_regs[i]] = frag_shader.const_vals[i];
      BORG_GPU->seq_desc_base = SEQ_DESC_BASE_ADDR;
      BORG_GPU->seq_tri_count = draw_call_count;
      BORG_GPU->seq_trigger = 1;
      while (BORG_GPU->status & STATUS_REG_T__SEQ_BUSY_bm)
        ;
      g_cmdbuf_valid = 1;
  }
}

// raw_pos_cache: model-space positions saved by borgTransformVerts for indexed draws.
static borg_float_t raw_pos_cache[BORG_MAX_UNIQUE_VERTS * 3];

// Bake the viewport transform into the MVP for the GPU vertex shader's hardware
// perspective divide.  The shader computes clip'_x,
// clip'_y, clip_z, clip_w then screen = clip' / clip_w, so we fold hw and the +1
// viewport translate into the x'/y' rows: x' = hw*(M0row + M3row), giving
// screen_x = clip'_x/w = hw*(clip_x/w + 1) = hw*ndc_x + hw with no separate hw
// uniform.  z and w rows stay raw.  Column-major: d->uniforms[col*4+row]=M[row][col].
// Affine MVPs (M3 row = [0,0,0,1]) → clip_w = 1, divide is a no-op, and this
// reduces exactly to the previous orthographic baking.
static void cache_ts_mvp(const borg_draw_data_t *d) {
  borg_float_t hw = half_width_f;
  for (int col = 0; col < 4; col++) {
    borg_float_t m0 = d->uniforms[col*4+0];  // M[0][col]
    borg_float_t m1 = d->uniforms[col*4+1];  // M[1][col]
    borg_float_t m2 = d->uniforms[col*4+2];  // M[2][col]
    borg_float_t m3 = d->uniforms[col*4+3];  // M[3][col]
    g_ts_mvp_cache[col*4+0] = borg_float_mul(hw, borg_float_add(m0, m3)); // x' = hw*(M0+M3)
    g_ts_mvp_cache[col*4+1] = borg_float_mul(hw, borg_float_add(m1, m3)); // y' = hw*(M1+M3)
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

void borgTransformVerts(const borg_draw_data_t *d, const borg_float_t *positions,
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
    tri.colors[v] = (rgbf_t){vertices[v].color[0], vertices[v].color[1], vertices[v].color[2]};
    tri.uvs[v]    = (uvf_t){vertices[v].uv[0], vertices[v].uv[1]};
  }
  record_draw_call(&tri, &tex, frame);
  t_draw_cycles += get_cycles() - t_start;
}

// --- Draw front end (docs/B1_geometry_front_end.md) ---
//
// Records: 48 + 3*N words for N varying components (the Records table),
// in the smallest power-of-two stride that holds them, 256 B (record_shift
// 8, up to five components) to 1 KB (10, up to 64). Too small a stride lets
// each record's varyings spill into the next record, which the next triangle
// then overwrites. Returns -1 when N does not fit.
#define DRAW_RECORD_SHIFT_MIN 8
#define DRAW_RECORD_SHIFT_MAX 10
_Static_assert(((DRAW_UBO_MAX_VERTS / 3) << DRAW_RECORD_SHIFT_MAX) <= SEQ_MAX_TRI * TBR_SETUP_ENTRY_BYTES,
               "draw-mode records overflow the TBR setup region");
static int draw_record_shift(int num_varyings) {
  uint32_t bytes = (48u + 3u * (uint32_t)num_varyings) * 4u;
  for (int shift = DRAW_RECORD_SHIFT_MIN; shift <= DRAW_RECORD_SHIFT_MAX; shift++)
    if (bytes <= (1u << shift)) return shift;
  return -1;
}

// Stage one frame's geometry into cube.vert's UBO. VertexIndex for a
// non-indexed "list" draw is 3*triangle + corner, so this expands the
// deduplicated positions the same way draw_received_geom() already does for
// the legacy path -- just written flat instead of walked per-triangle.
void borgDrawSubmitGeom(const borg_draw_data_t *d, const borg_float_t *positions,
                        int nverts, const uint8_t *idx, const borg_float_t *uv,
                        int ntris) {
  (void)nverts;
  for (int i = 0; i < 16; i++)
    DRAM_OUT_RAW(DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_MVP_WORD + i) * 4) = d->uniforms[i];
  for (int t = 0; t < ntris; t++) {
    for (int v = 0; v < 3; v++) {
      int i = t * 3 + v;   // gl_VertexIndex
      int vi = idx[i];
      uint32_t pbase = DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_POS_WORD  + 4 * i) * 4;
      uint32_t abase = DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_ATTR_WORD + 4 * i) * 4;
      DRAM_OUT_RAW(pbase + 0)  = positions[vi * 3 + 0];
      DRAM_OUT_RAW(pbase + 4)  = positions[vi * 3 + 1];
      DRAM_OUT_RAW(pbase + 8)  = positions[vi * 3 + 2];
      DRAM_OUT_RAW(pbase + 12) = BORG_FLOAT_ONE;
      DRAM_OUT_RAW(abase + 0)  = uv[i * 2 + 0];
      DRAM_OUT_RAW(abase + 4)  = uv[i * 2 + 1];
      DRAM_OUT_RAW(abase + 8)  = BORG_FLOAT_ZERO;
      DRAM_OUT_RAW(abase + 12) = BORG_FLOAT_ZERO;
    }
  }
  g_draw_vertex_count = ntris * 3;
}

// Render one frame through the hardware draw front end. Mirrors
// borgBinRenderAutonomous's register sequence where the two paths share
// registers (fb/clear/bin/setup/frag_pc), and adds the DRAW_CFG family
// (docs/B1_geometry_front_end.md's register table). Geometry and the MVP
// must already be staged via borgDrawSubmitGeom.
static void borgDrawRenderAutonomous(int frame) {
  // Nothing to draw before a draw-mode vertex shader arrives, or when its
  // varyings do not fit the largest record.
  int record_shift = draw_record_shift(g_draw_vert.num_varyings);
  if (!g_draw_vert_ok || record_shift < 0) return;

  int fb_offset = frame * FRAME_STRIDE;
  uint32_t cc_lo = ((uint32_t)last_clear_color.b << 16) | FP16_MAX_DEPTH;
  uint32_t cc_hi = ((uint32_t)last_clear_color.r << 16) | last_clear_color.g;

  BORG_GPU->seq_fb_base       = DRAM_OUT_SPI(fb_offset);
  BORG_GPU->seq_tiles_per_row = borg_fb_width >> 2;
  BORG_GPU->seq_clear_lo      = cc_lo;
  BORG_GPU->seq_clear_hi      = cc_hi;
  BORG_GPU->seq_bin_base      = tbr_bin_base;
  BORG_GPU->seq_bin_row_bytes = TBR_BIN_ROW_BYTES;
  BORG_GPU->seq_setup_base    = tbr_setup_base;
  BORG_GPU->tile_bz           = cc_lo;
  // Chains the dispatcher to the fragment shader (see borgBinRenderAutonomous's
  // own comment on the "black cube" failure mode this guards against).
  BORG_GPU->frag_pc = BORG_IMEM_FRAG_OFFSET;

  BORG_GPU->viewport_sx = half_width_f;  BORG_GPU->viewport_sy = half_height_f;
  BORG_GPU->viewport_ox = half_width_f;  BORG_GPU->viewport_oy = half_height_f;
  BORG_GPU->depth_scale = BORG_FLOAT_ONE; BORG_GPU->depth_offset = BORG_FLOAT_ZERO;

  BORG_GPU->ls_base = DRAW_UBO_SPI & LS_BASE_REG_T__BASE_ADDR_bm;
  // The shaders' constant windows, from their blobs' draw extensions
  // (indices range-checked in borg_stage_shader).
  for (int i = 0; i < g_draw_vert.num_window; i++)
    DRAM_OUT_RAW(DRAW_VS_CONST_SPI + (uint32_t)(g_draw_vert.window_regs[i] - DRAW_VS_CONST_U0) * 4) =
        g_draw_vert.window_vals[i];
  for (int i = 0; i < frag_shader.num_window; i++)
    DRAM_OUT_RAW(DRAW_FS_CONST_SPI + (uint32_t)(frag_shader.window_regs[i] - DRAW_FS_CONST_U0) * 4) =
        frag_shader.window_vals[i];

  BORG_GPU->draw_vs_const = DRAW_VS_CONST_SPI;
  BORG_GPU->draw_fs_const = DRAW_FS_CONST_SPI;
  BORG_GPU->tex_desc_base     = TEX_DESC_TABLE_ADDR;
  BORG_GPU->sampler_desc_base = SAMPLER_DESC_TABLE_ADDR;
  // cube.c: rasterizationSamples = VK_SAMPLE_COUNT_1_BIT (bit 6), all mask
  // bits set. At one sample the raster ROM also skips the per-sample depths.
  BORG_GPU->sample_mask_cfg   = 0xF | (1u << 6);
  // cube.c: VK_CULL_MODE_BACK_BIT, VK_FRONT_FACE_COUNTER_CLOCKWISE, which is
  // the walker's front face with front_face_invert clear.
  BORG_GPU->cull_cfg = 2u << CULL_CFG_REG_T__CULL_MODE_bp;

  BORG_GPU->draw_cfg = 1u | ((uint32_t)record_shift << 6);  // mode=1, list, no indices, no restart
  BORG_GPU->draw_vertex_count   = g_draw_vertex_count;
  BORG_GPU->draw_instance_count = 1;
  BORG_GPU->draw_first_vertex   = 0; BORG_GPU->draw_first_instance = 0;
  BORG_GPU->draw_vertex_offset  = 0; BORG_GPU->draw_index_base     = 0;

  if (g_draw_vertex_count > 0) {
    BORG_GPU->seq_trigger = 1;
    while (BORG_GPU->status & STATUS_REG_T__SEQ_BUSY_bm)
      ;
  }
}

void borg_present(int frame) {
  (void)frame;
  unsigned int t_wait = get_cycles();

  // Fully autonomous two-pass TBR rendering (Step 32.3/32.4):
  // Pass 1: sequencer runs vert+setup+bin for all triangles.
  // Pass 2: sequencer iterates all tiles, loads bin lists, rasterizes, flushes.
  if (g_draw_mode_active)
    borgDrawRenderAutonomous(back_buf);
  else
    borgBinRenderAutonomous(back_buf);

  // Wait for the GPU to finish the last tile flush.
  while (!(BORG_GPU->status & STATUS_REG_T__IDLE_bm))
    ;
  t_draw_cycles += get_cycles() - t_wait;

  // Hardware perf-counter snapshot at a safe DRAM offset (300020+, clear
  // of the TBR bin/setup regions) for sim tooling to read back.
  DRAM_OUT(300020) = BORG_GPU->perf_total;
  DRAM_OUT(300021) = BORG_GPU->perf_frag;
  DRAM_OUT(300022) = BORG_GPU->perf_flush;
  DRAM_OUT(300023) = BORG_GPU->perf_stall;
  DRAM_OUT(300024) = BORG_GPU->perf_dma;

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
