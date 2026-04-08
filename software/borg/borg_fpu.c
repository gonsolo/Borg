// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg FPU helpers — hardware-accelerated FP16 arithmetic via MMIO.

#include "borg_fpu.h"
#include "borg_spirb.h"
#include "borg_mmio.h"

// @doc:fpu-helpers
// --- Borg FPU helpers ---
void borg_run(uint32_t start_pc) {
  // We MUST wait for the GPU to be fully idle before resetting it for FPU use.
  while (!(BORG_GPU->status & STATUS_REG_T__IDLE_bm))
    ;

  BORG_GPU->control = CONTROL_REG_T__RESET_PIPELINE_bm | (start_pc << CONTROL_REG_T__START_PC_bp);
  (void)BORG_GPU->status;
  BORG_GPU->control = CONTROL_REG_T__START_bm;
  int timeout = 100000;
  // Wait until it becomes idle again
  while (!(BORG_GPU->status & STATUS_REG_T__IDLE_bm) && timeout > 0)
    timeout--;
}

fp16_t borg_fp16_add(fp16_t a, fp16_t b) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FADD(0, 1, 2, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = a;
  BORG_GPU->gpr[2] = b;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0] & 0xFFFF;
}

fp16_t borg_fp16_mul(fp16_t a, fp16_t b) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FMUL(0, 1, 2, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = a;
  BORG_GPU->gpr[2] = b;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0] & 0xFFFF;
// @doc:end
}

fp16_t borg_fp16_fmadd(fp16_t a, fp16_t b, fp16_t c) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FMADD(0, 1, 2, 3, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = a;
  BORG_GPU->gpr[2] = b;
  BORG_GPU->gpr[3] = c;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0] & 0xFFFF;
}

// FP16 reciprocal: 1/x via hardware FRCP instruction (LUT + interpolation).
fp16_t borg_fp16_rcp(fp16_t x) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FRCP(0, 1, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = x;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0] & 0xFFFF;
}

void borg_load_spirb_shader_at(const spirb_shader_t *s, int offset) {
  for (int i = 0; i < s->num_instrs; i++)
    BORG_GPU->imem[offset + i] = s->instrs[i];
  BORG_GPU->imem[offset + s->num_instrs] = BORG_INSTR_HALT;
}

void borg_load_spirb_shader(const spirb_shader_t *s) {
  borg_load_spirb_shader_at(s, 0);
}

void borg_load_add_shader(void) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FADD(0, 1, 2, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
}

fp16_t borg_fp16_sub_raw(fp16_t a, fp16_t b) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FADD(0, 1, 2, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = a;
  BORG_GPU->gpr[2] = b ^ 0x8000;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0] & 0xFFFF;
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
