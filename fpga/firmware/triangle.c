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
static void puts_uart(const char *s) {
  while (*s)
    putc_uart(*s++);
}

static char hex_chars[] = "0123456789abcdef";
static void print_hex16(unsigned int v) {
  putc_uart('0');
  putc_uart('x');
  putc_uart(hex_chars[(v >> 12) & 0xF]);
  putc_uart(hex_chars[(v >> 8) & 0xF]);
  putc_uart(hex_chars[(v >> 4) & 0xF]);
  putc_uart(hex_chars[v & 0xF]);
}

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
  BORG_IMEM(0) = 0x0210; // fadd r0, r1, r2
  BORG_IMEM(1) = 0x0000; // halt
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run(); // Resets PC and runs
  return BORG_REG(0) & 0xFFFF;
}

// Macro versions to guarantee no function call overhead on RV32E
#define BORG_FP16_SUB(a, b) borg_fp16_add((a), (b) ^ 0x8000)
#define BORG_FP16_NEG(x) ((x) ^ 0x8000)

// Vertex shader: load compiled program from generated header
static void borg_load_vert_shader(void) {
  for (int i = 0; i < VERT_BORG_PROGRAM_LEN; i++)
    BORG_IMEM(i) = vert_borg_program[i];
  BORG_IMEM(VERT_BORG_PROGRAM_LEN) = 0x0000; // halt
}

static void borg_load_add_shader(void) {
  BORG_IMEM(0) = 0x0210;
  BORG_IMEM(1) = 0x0000;
  BORG_IMEM(2) = 0x0000;
  BORG_IMEM(3) = 0x0000;
}

// FP16 sub via Borg registers.
// Requires ADD shader (fadd r0,r1,r2 + halt) pre-loaded in IMEM.
static uint16_t borg_fp16_sub_raw(uint16_t a, uint16_t b) {
  BORG_REG(1) = a;
  BORG_REG(2) = b ^ 0x8000;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

// Rasterize shader: edge function evaluation (fmul + fmadd, no fstep)
static void borg_load_rasterize_shader(void) {
  BORG_IMEM(0) = 0x4410;
  BORG_IMEM(1) = 0x8320;
  BORG_IMEM(2) = 0x0000;
  BORG_IMEM(3) = 0x0000;
}

// Fragment shader: barycentric weight computation + color interpolation.
// Uses r8-r10 (pipeline-only) for intermediate weights.
// Inputs: r0=e0, r1=e1, r2=e2 (edge values), r3=inv_area,
//         r4=c0, r5=c1, r6=c2 (vertex colors)
// Output: r0 = interpolated color
static void borg_load_frag_shader(void) {
  BORG_IMEM(0) = 0x4308;  // fmul r8, r0, r3      (w0 = e0 * inv_area)
  BORG_IMEM(1) = 0x4319;  // fmul r9, r1, r3      (w1 = e1 * inv_area)
  BORG_IMEM(2) = 0x432A;  // fmul r10, r2, r3     (w2 = e2 * inv_area)
  BORG_IMEM(3) = 0x4480;  // fmul r0, r8, r4      (acc = w0 * c0)
  BORG_IMEM(4) = 0x8590;  // fmadd r0, r9, r5, r0 (acc += w1 * c1)
  BORG_IMEM(5) = 0x86A0;  // fmadd r0, r10, r6, r0 (result = acc + w2 * c2)
  BORG_IMEM(6) = 0x0000;  // halt
}

static uint16_t borg_rasterize_edge(uint16_t dx_e, uint16_t neg_dy_e, uint16_t dpx_e, uint16_t dpy_e) {
  BORG_REG(1) = dx_e;
  BORG_REG(2) = neg_dy_e;
  BORG_REG(3) = dpx_e;
  BORG_REG(4) = dpy_e;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
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


// Standardized PSRAM input layout:
//   [0..NUM_UNIFORMS-1] = shader uniforms
//   [NUM_UNIFORMS..NUM_UNIFORMS+3*NUM_ATTRIBUTES-1] = vertex attributes (3 vertices)
#define NUM_VERTICES 3

static void read_input(uint16_t *uniforms, uint16_t attrs[][VERT_NUM_ATTRIBUTES]) {
  // Read uniforms
  for (int i = 0; i < VERT_NUM_UNIFORMS; i++)
    uniforms[i] = PSRAM_IN(i);

  // Read vertex attributes
  for (int v = 0; v < NUM_VERTICES; v++)
    for (int i = 0; i < VERT_NUM_ATTRIBUTES; i++)
      attrs[v][i] = PSRAM_IN(VERT_NUM_UNIFORMS + v * VERT_NUM_ATTRIBUTES + i);

  // Clear the DONE marker from previous runs so host doesn't read stale data
  PSRAM_OUT(FB_OFFSET + FB_WIDTH * FB_HEIGHT) = 0;
  puts_uart("A\r\n");
}

static void run_vertex_shader(const uint16_t *uniforms,
                               const uint16_t attrs[][VERT_NUM_ATTRIBUTES],
                               uint16_t outputs[][VERT_NUM_OUTPUTS]) {
  borg_load_vert_shader();

  // Load uniforms once (they persist across borg_run calls)
  for (int i = 0; i < VERT_NUM_UNIFORMS; i++)
    BORG_REG(vert_uniform_regs[i]) = uniforms[i];

  for (int v = 0; v < NUM_VERTICES; v++) {
    BORG_CONTROL = 2; // Reset PC
    // Reload uniforms after PC reset (reset clears register writes in flight)
    for (int i = 0; i < VERT_NUM_UNIFORMS; i++)
      BORG_REG(vert_uniform_regs[i]) = uniforms[i];
    for (int i = 0; i < VERT_NUM_ATTRIBUTES; i++)
      BORG_REG(vert_attribute_regs[i]) = attrs[v][i];
#if VERT_NUM_CONSTS > 0
    for (int i = 0; i < VERT_NUM_CONSTS; i++)
      BORG_REG(vert_const_regs[i]) = vert_const_vals[i];
#endif
    borg_run();
    for (int i = 0; i < VERT_NUM_OUTPUTS; i++)
      outputs[v][i] = BORG_REG(vert_output_regs[i]) & 0xFFFF;
  }
  puts_uart("D\r\n");
}

static void screen_space_translate(const uint16_t vout[][VERT_NUM_OUTPUTS],
                                    uint16_t *sx, uint16_t *sy) {
  for (int v = 0; v < NUM_VERTICES; v++) {
    sx[v] = borg_fp16_add(vout[v][0], FP16_EIGHT);
    sy[v] = borg_fp16_add(vout[v][1], FP16_EIGHT);
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
  for (int e = 0; e < 3; e++) {
    PSRAM_OUT(22 + e * 2 + 0) = dx[e];
    PSRAM_OUT(22 + e * 2 + 1) = neg_dy[e];
    puts_uart("E");
    putc_uart('0' + e);
    puts_uart(" dx=");
    print_hex16(dx[e]);
    puts_uart(" ndy=");
    print_hex16(neg_dy[e]);
    puts_uart("\r\n");
  }
  puts_uart("F\r\n");
}

// Precomputed FP16 pixel center coordinates: 0.5, 1.5, ..., 15.5
// (avoids any CPU-side fp16 conversion which crashes TinyQV)
static const uint16_t pc_lut[16] = {
    0x3800, 0x3E00, 0x4100, 0x4300, // 0.5, 1.5, 2.5, 3.5
    0x4480, 0x4580, 0x4680, 0x4780, // 4.5, 5.5, 6.5, 7.5
    0x4840, 0x48C0, 0x4940, 0x49C0, // 8.5, 9.5, 10.5, 11.5
    0x4A40, 0x4AC0, 0x4B40, 0x4BC0  // 12.5, 13.5, 14.5, 15.5
};

// Barycentric interpolation for one pixel (noinline to keep main small)
// Returns 0 for outside pixels, interpolated color for inside pixels.
static uint16_t __attribute__((noinline)) borg_bary_color(
    uint16_t *dx, uint16_t *neg_dy,
    uint16_t *dpx, uint16_t *dpy,
    uint16_t inv_area, uint16_t *colors) {
  // Rasterize shader: compute edge values
  borg_load_rasterize_shader();
  uint16_t e0 = borg_rasterize_edge(dx[0], neg_dy[0], dpx[0], dpy[0]);
  uint16_t e1 = borg_rasterize_edge(dx[1], neg_dy[1], dpx[1], dpy[1]);
  uint16_t e2 = borg_rasterize_edge(dx[2], neg_dy[2], dpx[2], dpy[2]);
  // Inside test: all edges must be negative or zero (sign bit set or zero)
  if ((fp16_ge_zero(e0) && e0 != 0) ||
      (fp16_ge_zero(e1) && e1 != 0) ||
      (fp16_ge_zero(e2) && e2 != 0))
    return 0;
  // Fragment shader: weights + color interpolation in one pass
  borg_load_frag_shader();
  BORG_REG(0) = e0;
  BORG_REG(1) = e1;
  BORG_REG(2) = e2;
  BORG_REG(3) = inv_area;
  BORG_REG(4) = colors[0];
  BORG_REG(5) = colors[1];
  BORG_REG(6) = colors[2];
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

int main() {
  STARTUP_DELAY();
  UART_BAUD = 34;

  puts_uart("Borg pipeline\r\n");

  // Read input from PSRAM (standardized layout: uniforms, then vertex attributes)
  uint16_t uniforms[VERT_NUM_UNIFORMS];
  uint16_t attrs[NUM_VERTICES][VERT_NUM_ATTRIBUTES];
  read_input(uniforms, attrs);

  // Vertex shader (generic dispatch)
  uint16_t vout[NUM_VERTICES][VERT_NUM_OUTPUTS];
  run_vertex_shader(uniforms, attrs, vout);

  // Screen-space translation: add 8.0
  uint16_t sx[3], sy[3];
  screen_space_translate(vout, sx, sy);

  uint16_t dx[3], neg_dy[3];
  compute_edge_vectors(sx, sy, dx, neg_dy);

  // Read inv_area from PSRAM (after uniforms + vertex data)
  uint16_t inv_area = PSRAM_IN(VERT_NUM_UNIFORMS + NUM_VERTICES * VERT_NUM_ATTRIBUTES) & 0xFFFF;
  uint16_t colors[3] = { FP16_ONE, FP16_HALF, 0 };

  // Nested loop (for disassembly comparison - this hangs on TinyQV)
  for (int py = 0; py < FB_HEIGHT; py++) {
    uint16_t pcy = pc_lut[py];
    for (int px = 0; px < FB_WIDTH; px++) {
      uint16_t pcx = pc_lut[px];
      uint16_t dpx_arr[3], dpy_arr[3];
      compute_pixel_deltas(pcx, pcy, sx, sy, dpx_arr, dpy_arr);
      uint16_t c = borg_bary_color(dx, neg_dy, dpx_arr, dpy_arr, inv_area, colors);
      PSRAM_OUT(FB_OFFSET + py * FB_WIDTH + px) = c;
    }
  }

  // Done
  PSRAM_OUT(FB_OFFSET + FB_WIDTH * FB_HEIGHT) = 0xDEAD;
  puts_uart("DONE\r\n");

  while (1)
    ;
  return 0;
}
