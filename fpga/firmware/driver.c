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

#define PSRAM_IN(n) (*(volatile uint32_t *)(0x01001000 + (n) * 4))
#define PSRAM_OUT(n) (*(volatile uint32_t *)(0x01001000 + 128 + (n) * 4))

#define FB_OFFSET 32
#define FP16_EIGHT 0x4800

#define NUM_VERTICES 3
#define SPIRB_MAX_BLOB_WORDS 16

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
static int shader_data_offset;

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

// --- Shader parsing ---
static void parse_shaders(void) {
  int offset = 0;
  uint8_t blob[SPIRB_MAX_BLOB_WORDS * 4];

  // Parse vert shader blob
  uint16_t blob_len = PSRAM_IN(offset) & 0xFFFF; offset++;
  int blob_words = (blob_len + 3) / 4;
  for (int i = 0; i < blob_words; i++) {
    uint32_t w = PSRAM_IN(offset + i);
    blob[i * 4 + 0] = w & 0xFF;
    blob[i * 4 + 1] = (w >> 8) & 0xFF;
    blob[i * 4 + 2] = (w >> 16) & 0xFF;
    blob[i * 4 + 3] = (w >> 24) & 0xFF;
  }
  spirb_parse(blob, &vert_shader);
  offset += blob_words;

  // Parse rasterize shader blob
  blob_len = PSRAM_IN(offset) & 0xFFFF; offset++;
  blob_words = (blob_len + 3) / 4;
  for (int i = 0; i < blob_words; i++) {
    uint32_t w = PSRAM_IN(offset + i);
    blob[i * 4 + 0] = w & 0xFF;
    blob[i * 4 + 1] = (w >> 8) & 0xFF;
    blob[i * 4 + 2] = (w >> 16) & 0xFF;
    blob[i * 4 + 3] = (w >> 24) & 0xFF;
  }
  spirb_parse(blob, &rast_shader);
  offset += blob_words;

  // Parse frag shader blob
  blob_len = PSRAM_IN(offset) & 0xFFFF; offset++;
  blob_words = (blob_len + 3) / 4;
  for (int i = 0; i < blob_words; i++) {
    uint32_t w = PSRAM_IN(offset + i);
    blob[i * 4 + 0] = w & 0xFF;
    blob[i * 4 + 1] = (w >> 8) & 0xFF;
    blob[i * 4 + 2] = (w >> 16) & 0xFF;
    blob[i * 4 + 3] = (w >> 24) & 0xFF;
  }
  spirb_parse(blob, &frag_shader);
  offset += blob_words;

  shader_data_offset = offset;
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
    sx[v] = borg_fp16_add(vout[v * stride + 0], FP16_EIGHT);
    sy[v] = borg_fp16_add(vout[v * stride + 1], FP16_EIGHT);
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

// Precomputed FP16 pixel center coordinates: 0.5, 1.5, ..., 15.5
static const uint16_t pc_lut[16] = {
    0x3800, 0x3E00, 0x4100, 0x4300,
    0x4480, 0x4580, 0x4680, 0x4780,
    0x4840, 0x48C0, 0x4940, 0x49C0,
    0x4A40, 0x4AC0, 0x4B40, 0x4BC0
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

void borg_init(void) {
  STARTUP_DELAY();
  UART_BAUD = 34;
  puts_uart("Borg pipeline\r\n");
  parse_shaders();
}

void borg_set_angle(borg_draw_data_t *d, uint16_t angle_fp16) {
  // Compute vertex shader uniforms from angle
  // The vertex shader expects: uniform[0]=sin, uniform[1]=cos, uniform[2]=-sin
  d->uniforms[0] = fp16_sin(angle_fp16);
  d->uniforms[1] = fp16_cos(angle_fp16);
  d->uniforms[2] = fp16_neg(d->uniforms[0]);

  // Clear stale DONE marker
  PSRAM_OUT(FB_OFFSET + BORG_FB_WIDTH * BORG_FB_HEIGHT * 3) = 0;
  puts_uart("A\r\n");
}

void borg_cmd_draw(const borg_draw_data_t *d, const borg_vertex_t vertices[3]) {
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
      int base = FB_OFFSET + (py * BORG_FB_WIDTH + px) * 3;
      PSRAM_OUT(base + 0) = r;
      PSRAM_OUT(base + 1) = g;
      PSRAM_OUT(base + 2) = b;
    }
  }
}

void borg_present(void) {
  PSRAM_OUT(FB_OFFSET + BORG_FB_WIDTH * BORG_FB_HEIGHT * 3) = 0xDEAD;
  puts_uart("DONE\r\n");
}
