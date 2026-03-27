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
  // BORG_STATUS returns {running (1-bit), busy_counter (3-bits)}.
  // So 'running' is bit 3, which is 8.
  while ((BORG_STATUS & 8) && timeout > 0)
    timeout--;
}

fp16_t borg_fp16_add(fp16_t a, fp16_t b) {
  BORG_IMEM(0) = BORG_INSTR_FADD(0, 1, 2);
  BORG_IMEM(1) = BORG_INSTR_HALT;
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

fp16_t borg_fp16_mul(fp16_t a, fp16_t b) {
  BORG_IMEM(0) = BORG_INSTR_FMUL(0, 1, 2);
  BORG_IMEM(1) = BORG_INSTR_HALT;
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
// @doc:end
}

fp16_t borg_fp16_fmadd(fp16_t a, fp16_t b, fp16_t c) {
  BORG_IMEM(0) = BORG_INSTR_FMADD(0, 1, 2, 3);
  BORG_IMEM(1) = BORG_INSTR_HALT;
  BORG_REG(1) = a;
  BORG_REG(2) = b;
  BORG_REG(3) = c;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

// FP16 reciprocal: 1/x via hardware FRCP instruction (LUT + interpolation).
fp16_t borg_fp16_rcp(fp16_t x) {
  BORG_IMEM(0) = BORG_INSTR_FRCP(0, 1);
  BORG_IMEM(1) = BORG_INSTR_HALT;
  BORG_REG(1) = x;
  borg_run();
  return BORG_REG(0) & 0xFFFF;
}

void borg_load_spirb_shader(const spirb_shader_t *s) {
  for (int i = 0; i < s->num_instrs; i++)
    BORG_IMEM(i) = s->instrs[i];
  BORG_IMEM(s->num_instrs) = BORG_INSTR_HALT;
}

void borg_load_add_shader(void) {
  BORG_IMEM(0) = BORG_INSTR_FADD(0, 1, 2);
  BORG_IMEM(1) = BORG_INSTR_HALT;
  BORG_IMEM(2) = BORG_INSTR_HALT;
  BORG_IMEM(3) = BORG_INSTR_HALT;
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
