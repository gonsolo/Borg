// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Debug GPU pipeline: single-frame vertex shader + rasterizer.
// Writes diagnostic data to PSRAM for step-by-step verification.

#include "borg_math.h"
#include "compiler/rasterize.borg.h"
#include "compiler/vert.borg.h"

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

// PSRAM layout:
// Input at 0x01001000:
//   [0] = angle (FP16 radians)
//   [1..6] = 3 vertices (vx0,vy0, vx1,vy1, vx2,vy2) in FP16
//
// Output at 0x01001000 + 128:
//   [0..5]   = rotated vertices (rx0,ry0, rx1,ry1, rx2,ry2) in FP16
//   [6..11]  = screen-space vertices (sx0,sy0, sx1,sy1, sx2,sy2) in FP16
//   [12..14] = edge dx[0..2] in FP16
//   [15..17] = edge neg_dy[0..2] in FP16
//   [18..20] = edge results for pixel (8,8) — center pixel
//   [21]     = inside flag for pixel (8,8)
//   [22..277] = 16×16 framebuffer (1 word per pixel, 0 or 1)
//   [278]    = DONE marker (0xDEAD)

#define PSRAM_IN(n) (*(volatile uint32_t *)(0x01001000 + (n) * 4))
#define PSRAM_OUT(n) (*(volatile uint32_t *)(0x01001000 + 128 + (n) * 4))

#define FB_WIDTH 16
#define FB_HEIGHT 16
#define FB_OFFSET 32 // framebuffer starts at output word 32

#define FP16_HALF 0x3800  // 0.5
#define FP16_EIGHT 0x4800 // 8.0

// --- UART ---
void putc_uart(int c) {
  while (UART_STATUS & 1)
    ;
  UART_TX = c;
}
#define puts_uart(s)                                                           \
  do {                                                                         \
    char *_p = (s);                                                            \
    while (*_p)                                                                \
      putc_uart(*_p++);                                                        \
  } while (0)

static char hex_chars[] = "0123456789abcdef";
#define print_hex16(v)                                                         \
  do {                                                                         \
    unsigned int _v = (v);                                                     \
    putc_uart('0');                                                            \
    putc_uart('x');                                                            \
    putc_uart(hex_chars[(_v >> 12) & 0xF]);                                    \
    putc_uart(hex_chars[(_v >> 8) & 0xF]);                                     \
    putc_uart(hex_chars[(_v >> 4) & 0xF]);                                     \
    putc_uart(hex_chars[_v & 0xF]);                                            \
  } while (0)

static void borg_run(void) {
  // Reset PC to 0
  BORG_CONTROL = 2;

  // IMPORTANT: Wait at least 1 cycle for reset to take effect over the bus
  // (borg_rotate.c does a REG_READ here which acts as a synchronization
  // barrier)
  (void)BORG_STATUS;

  // Start execution
  BORG_CONTROL = 1;

  // Wait for halt (bit 1 of STATUS is 1 when halted/idle)
  int timeout = 100000;
  while (!(BORG_STATUS & 2) && timeout > 0) {
    timeout--;
  }
}

// Use Borg hardware for FP16 add: result = a + b
static uint16_t borg_fp16_add(uint16_t a, uint16_t b) {
  // Load 1-instruction ADD shader: fadd r0, r1, r2
  BORG_IMEM(0) = 0x0220; // fadd r0, r1, r2
  BORG_IMEM(1) = 0x0000; // halt
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run(); // Resets PC and runs
  return BORG_REG(0) & 0xFFFF;
}

// Macro versions to guarantee no function call overhead on RV32E
#define BORG_FP16_SUB(a, b) borg_fp16_add((a), (b) ^ 0x8000)
#define BORG_FP16_NEG(x) ((x) ^ 0x8000)

// Raw register-level FP16 sub: assumes ADD shader already loaded in IMEM
#define BORG_FP16_SUB_RAW(a, b) ( \
    BORG_REG(1) = (a),            \
    BORG_REG(2) = (b) ^ 0x8000,   \
    borg_run(),                    \
    BORG_REG(0) & 0xFFFF)

// Run rasterize shader for one edge: returns edge value
// Assumes fmul/fmadd/halt shader already loaded in IMEM
#define BORG_EDGE_TEST(dx_e, neg_dy_e, dpx_e, dpy_e) (         \
    BORG_REG(RASTERIZE_BORG_REG_DX) = (dx_e),                  \
    BORG_REG(RASTERIZE_BORG_REG_NEG_DY) = (neg_dy_e),          \
    BORG_REG(RASTERIZE_BORG_REG_DPX) = (dpx_e),                \
    BORG_REG(RASTERIZE_BORG_REG_DPY) = (dpy_e),                \
    borg_run(),                                                 \
    BORG_REG(RASTERIZE_BORG_REG_EDGE) & 0xFFFF)

// Compute pixel-to-vertex distances for all 3 edges
// Assumes ADD shader already loaded in IMEM
#define COMPUTE_PIXEL_DELTAS(pcx, pcy, sx, sy, dpx, dpy) \
  do { for (int e = 0; e < 3; e++) {                     \
    dpx[e] = BORG_FP16_SUB_RAW(pcx, sx[e]);              \
    dpy[e] = BORG_FP16_SUB_RAW(pcy, sy[e]);              \
  } } while (0)

// Test all 3 edges and set 'inside' flag
// Assumes rasterize shader already loaded in IMEM
#define TEST_EDGES(dx, neg_dy, dpx, dpy, inside)                        \
  do { inside = 1;                                                      \
    for (int e = 0; e < 3; e++) {                                       \
      uint16_t edge = BORG_EDGE_TEST(dx[e], neg_dy[e], dpx[e], dpy[e]); \
      if (fp16_ge_zero(edge) && edge != 0) { inside = 0; break; }       \
    }                                                                   \
  } while (0)

// Rasterize one pixel: compute deltas, test edges, write fragment
#define RASTERIZE_PIXEL(pcx, pcy, sx, sy, dx, neg_dy, py, px)   \
  do {                                                          \
    BORG_IMEM(0) = 0x0220;                                      \
    BORG_IMEM(1) = 0x0000;                                      \
    uint16_t dpx_arr[3], dpy_arr[3];                            \
    COMPUTE_PIXEL_DELTAS(pcx, pcy, sx, sy, dpx_arr, dpy_arr);   \
    BORG_IMEM(0) = 0x2420;                                      \
    BORG_IMEM(1) = 0x4340;                                      \
    BORG_IMEM(2) = 0x0000;                                      \
    int inside;                                                 \
    TEST_EDGES(dx, neg_dy, dpx_arr, dpy_arr, inside);           \
    PSRAM_OUT(FB_OFFSET + (py) * FB_WIDTH + (px)) = inside ? 0x3C00 : 0; \
  } while (0)

// Rasterize entire framebuffer
#define RASTERIZE_FRAMEBUFFER(pc_lut, sx, sy, dx, neg_dy)            \
  do { for (int py = 0; py < FB_HEIGHT; py++) {                      \
    uint16_t pcy = pc_lut[py];                                       \
    for (int px = 0; px < FB_WIDTH; px++) {                          \
      uint16_t pcx = pc_lut[px];                                     \
      RASTERIZE_PIXEL(pcx, pcy, sx, sy, dx, neg_dy, py, px);        \
    }                                                                \
  } } while (0)

static inline int fp16_ge_zero(uint16_t v) { return (v & 0x8000) == 0; }

typedef struct {
  uint16_t rx[3], ry[3];
} VertexOutput;

typedef struct {
  uint16_t cos_val, sin_val, nsin_val;
  uint16_t vx[3], vy[3];
} PipelineInput;

static void read_input(PipelineInput *in) {

  // Host pre-computes cos/sin/nsin and writes them to PSRAM
  in->cos_val = PSRAM_IN(0);
  for (int v = 0; v < 3; v++) {
    in->vx[v] = PSRAM_IN(1 + v * 2);
    in->vy[v] = PSRAM_IN(2 + v * 2);
  }
  in->sin_val = PSRAM_IN(7);
  in->nsin_val = PSRAM_IN(8);

  // Clear the DONE marker from previous runs so host doesn't read stale data
  PSRAM_OUT(FB_OFFSET + FB_WIDTH * FB_HEIGHT) = 0;

  puts_uart("cos=");
  print_hex16(in->cos_val);
  puts_uart("\r\n");

  // Write raw inputs to PSRAM output for host verification
  // Output layout: [0..6] = raw inputs (angle, vx0,vy0, vx1,vy1, vx2,vy2)
  // [7..9] = sin, cos, nsin
  // [10..15] = rotated vertices (rx0,ry0, rx1,ry1, rx2,ry2), etc.
  // Write raw inputs to PSRAM output for host verification
  PSRAM_OUT(0) = in->cos_val;
  for (int v = 0; v < 3; v++) {
    PSRAM_OUT(1 + v * 2) = in->vx[v];
    PSRAM_OUT(2 + v * 2) = in->vy[v];
  }
  puts_uart("A\r\n");
}

static void run_vertex_shader(const PipelineInput *in, VertexOutput *out) {

  // Registers/IMEM can be safely written while BORG is halted
  BORG_IMEM(0) = 0x2560; // fmul r0, r3, r5  (c*x)
  BORG_IMEM(1) = 0x4680; // fmadd r0, r4, r6, r0  (rx = -s*y + c*x)
  BORG_IMEM(2) = 0x2544; // fmul r1, r2, r5  (s*x)
  BORG_IMEM(3) = 0x4E64; // fmadd r1, r3, r6, r1  (ry = c*y + s*x)
  BORG_IMEM(4) = 0x0000; // halt
  puts_uart("B\r\n");

  // sin/cos/nsin already read from PSRAM above
  PSRAM_OUT(7) = in->sin_val;
  PSRAM_OUT(8) = in->cos_val;
  PSRAM_OUT(9) = in->nsin_val;

  puts_uart("sin=");
  print_hex16(in->sin_val);
  puts_uart(" cos=");
  print_hex16(in->cos_val);
  puts_uart("\r\n");

  for (int v = 0; v < 3; v++) {
    BORG_CONTROL = 2; // Reset PC before writing registers
    BORG_REG(VERT_BORG_REG_COS) = in->cos_val;
    BORG_REG(VERT_BORG_REG_X) = in->vx[v];
    BORG_REG(VERT_BORG_REG_NSIN) = in->nsin_val;
    BORG_REG(VERT_BORG_REG_SIN) = in->sin_val;
    BORG_REG(VERT_BORG_REG_Y) = in->vy[v];

    borg_run(); // Start only, no reset

    out->rx[v] = BORG_REG(VERT_BORG_REG_RX) & 0xFFFF;
    out->ry[v] = BORG_REG(VERT_BORG_REG_RY) & 0xFFFF;

    puts_uart("V");
    putc_uart('0' + v);
    puts_uart(" rx=");
    print_hex16(out->rx[v]);
    puts_uart(" ry=");
    print_hex16(out->ry[v]);
    puts_uart("\r\n");
  }

  // Write rotated vertices to output [10..15]
  for (int v = 0; v < 3; v++) {
    PSRAM_OUT(10 + v * 2 + 0) = out->rx[v];
    PSRAM_OUT(10 + v * 2 + 1) = out->ry[v];
  }
  puts_uart("D\r\n");
}

static void screen_space_translate(const uint16_t *rx, const uint16_t *ry,
                                   uint16_t *sx, uint16_t *sy) {
  puts_uart("E\r\n");
  for (int v = 0; v < 3; v++) {
    sx[v] = borg_fp16_add(rx[v], FP16_EIGHT);
    sy[v] = borg_fp16_add(ry[v], FP16_EIGHT);
    PSRAM_OUT(16 + v * 2 + 0) = sx[v];
    PSRAM_OUT(16 + v * 2 + 1) = sy[v];

    puts_uart("S");
    putc_uart('0' + v);
    puts_uart(" sx=");
    print_hex16(sx[v]);
    puts_uart(" sy=");
    print_hex16(sy[v]);
    puts_uart("\r\n");
  }
}


#define COMPUTE_EDGE_VECTORS(sx, sy, dx, neg_dy)                               \
  do {                                                                         \
    dx[0] = BORG_FP16_SUB(sx[1], sx[0]);                                       \
    neg_dy[0] = BORG_FP16_NEG(BORG_FP16_SUB(sy[1], sy[0]));                   \
    dx[1] = BORG_FP16_SUB(sx[2], sx[1]);                                       \
    neg_dy[1] = BORG_FP16_NEG(BORG_FP16_SUB(sy[2], sy[1]));                   \
    dx[2] = BORG_FP16_SUB(sx[0], sx[2]);                                       \
    neg_dy[2] = BORG_FP16_NEG(BORG_FP16_SUB(sy[0], sy[2]));                   \
    for (int e = 0; e < 3; e++) {                                              \
      PSRAM_OUT(22 + e * 2 + 0) = dx[e];                                       \
      PSRAM_OUT(22 + e * 2 + 1) = neg_dy[e];                                   \
      puts_uart("E");                                                          \
      putc_uart('0' + e);                                                      \
      puts_uart(" dx=");                                                       \
      print_hex16(dx[e]);                                                      \
      puts_uart(" ndy=");                                                      \
      print_hex16(neg_dy[e]);                                                  \
      puts_uart("\r\n");                                                       \
    }                                                                          \
    puts_uart("F\r\n");                                                        \
  } while (0)

// Precomputed FP16 pixel center coordinates: 0.5, 1.5, ..., 15.5
// (avoids any CPU-side fp16 conversion which crashes TinyQV)
static const uint16_t pc_lut[16] = {
    0x3800, 0x3E00, 0x4100, 0x4300, // 0.5, 1.5, 2.5, 3.5
    0x4480, 0x4580, 0x4680, 0x4780, // 4.5, 5.5, 6.5, 7.5
    0x4840, 0x48C0, 0x4940, 0x49C0, // 8.5, 9.5, 10.5, 11.5
    0x4A40, 0x4AC0, 0x4B40, 0x4BC0  // 12.5, 13.5, 14.5, 15.5
};

int main() {
  STARTUP_DELAY();
  UART_BAUD = 34;

  puts_uart("Borg debug pipeline v1\r\n");

  // Read input
  PipelineInput in;
  read_input(&in);

  // Vertex shader
  VertexOutput vout;
  run_vertex_shader(&in, &vout);

  // Screen-space translation: add 8.0
  uint16_t sx[3], sy[3];
  screen_space_translate(vout.rx, vout.ry, sx, sy);

  uint16_t dx[3], neg_dy[3];
  COMPUTE_EDGE_VECTORS(sx, sy, dx, neg_dy);

  // Rasterize framebuffer
  RASTERIZE_FRAMEBUFFER(pc_lut, sx, sy, dx, neg_dy);

  // Done
  PSRAM_OUT(FB_OFFSET + FB_WIDTH * FB_HEIGHT) = 0xDEAD;
  puts_uart("DONE\r\n");

  while (1)
    ;
  return 0;
}
