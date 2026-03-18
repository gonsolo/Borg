// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg FPU helpers — hardware-accelerated FP16 arithmetic via MMIO.

#include "borg_fpu.h"
#include "borg_spirb.h"
#include "borg_mmio.h"

// @doc:fpu-helpers
// --- Borg FPU helpers ---
void borg_run(void) {
  BORG_CONTROL = BORG_CTL_RESET;
  (void)BORG_STATUS;
  BORG_CONTROL = BORG_CTL_START;
  int timeout = 100000;
  while (!(BORG_STATUS & BORG_STS_IDLE) && timeout > 0)
    timeout--;
}

fp16_t borg_fp16_add(fp16_t a, fp16_t b) {
  BORG_IMEM(0) = 0x0210;
  BORG_IMEM(1) = 0x0000;
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

fp16_t borg_fp16_mul(fp16_t a, fp16_t b) {
  // fmul r0, r1, r2: [15:14]=01, [11:8]=r2, [7:4]=r1, [3:0]=r0
  BORG_IMEM(0) = 0x4210;
  BORG_IMEM(1) = 0x0000; // halt
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
// @doc:end
}

fp16_t borg_fp16_fmadd(fp16_t a, fp16_t b, fp16_t c) {
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
fp16_t borg_fp16_rcp(fp16_t x) {
  fp16_t sign = x & 0x8000;
  uint16_t exp = (x >> 10) & 0x1F;
  if (exp == 0 || exp == 31) return 0;
  // Initial estimate: flip exponent around bias, zero mantissa
  uint16_t est_exp = 30 - exp;
  if (est_exp >= 31) return sign | 0x7C00;
  fp16_t y = sign | (est_exp << 10);
  // Newton-Raphson: y = y * (2 - x * y), 2 iterations
  for (int i = 0; i < 2; i++) {
    fp16_t xy = borg_fp16_mul(x, y);
    fp16_t correction = BORG_FP16_SUB(FP16_TWO, xy);
    y = borg_fp16_mul(y, correction);
  }
  return y;
}

void borg_load_spirb_shader(const spirb_shader_t *s) {
  for (int i = 0; i < s->num_instrs; i++)
    BORG_IMEM(i) = s->instrs[i];
  BORG_IMEM(s->num_instrs) = 0x0000;
}

void borg_load_add_shader(void) {
  BORG_IMEM(0) = 0x0210;
  BORG_IMEM(1) = 0x0000;
  BORG_IMEM(2) = 0x0000;
  BORG_IMEM(3) = 0x0000;
}

fp16_t borg_fp16_sub_raw(fp16_t a, fp16_t b) {
  BORG_REG(1) = a;
  BORG_REG(2) = b ^ 0x8000;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

// Convert FP16 (positive) to unsigned integer (truncate)
int fp16_to_uint(fp16_t fp16) {
  int exp = (fp16 >> 10) & 0x1F;
  int frac = fp16 & 0x3FF;
  if (exp < 15) return 0;  // value < 1.0, integer part is 0
  if (exp == 0) return 0;  // zero/subnormal
  int mantissa = 1024 + frac;  // 1.frac in Q10
  int shift = exp - 15;       // number of integer bits
  if (shift >= 10) return mantissa << (shift - 10);
  return mantissa >> (10 - shift);
}

// Convert integer to FP16 (for small positive integers)
fp16_t uint_to_fp16(int val) {
  if (val == 0) return 0;
  int exp = 25;  // bias(15) + Q10 offset(10)
  int mantissa = val;
  // Normalize: shift until mantissa is in [1024, 2048)
  while (mantissa >= 2048) { mantissa >>= 1; exp++; }
  while (mantissa < 1024)  { mantissa <<= 1; exp--; }
  return (uint16_t)((exp << 10) | (mantissa & 0x3FF));
}
