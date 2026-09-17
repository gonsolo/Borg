// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg FPU helpers — the shader datapath's float arithmetic via MMIO.

#include "borg_fpu.h"
#include "borg_spirb.h"
#include "borg_sys.h"
#include "borg_regs.h"  // borg_gpu_t + register bit-masks
#include "borg_isa.h"
#define BORG_GPU ((volatile borg_gpu_t*) BORG_BASE)

void putc_uart(int c);
void puts_uart(const char *s);

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

borg_float_t borg_float_add(borg_float_t a, borg_float_t b) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FADD(0, 1, 2, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = a;
  BORG_GPU->gpr[2] = b;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0];
}

borg_float_t borg_float_mul(borg_float_t a, borg_float_t b) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FMUL(0, 1, 2, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = a;
  BORG_GPU->gpr[2] = b;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0];
// @doc:end
}

borg_float_t borg_float_fmadd(borg_float_t a, borg_float_t b, borg_float_t c) {
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET] = BORG_INSTR_FMADD(0, 1, 2, 3, 0);
  BORG_GPU->imem[BORG_IMEM_ADD_OFFSET + 1] = BORG_INSTR_HALT;
  BORG_GPU->gpr[1] = a;
  BORG_GPU->gpr[2] = b;
  BORG_GPU->gpr[3] = c;
  borg_run(BORG_IMEM_ADD_OFFSET);
  return BORG_GPU->gpr[0];
}

void borg_check_float_width(void) {
  // Read a GPR back: the register file stores cfg.totalBits bits, so FP16
  // hardware returns 0x0000FFFF here. The GPU is idle at boot; gpr[0] is
  // scratch for the FPU helpers above.
  BORG_GPU->gpr[0] = 0xFFFFFFFFu;
  uint32_t got = BORG_GPU->gpr[0];
  BORG_GPU->gpr[0] = 0;
  if (got == 0xFFFFFFFFu) return;
  static const char hex[] = "0123456789abcdef";
  puts_uart("FW: Borg shader datapath is not FP32 (GPR read back 0x");
  for (int i = 28; i >= 0; i -= 4) putc_uart(hex[(got >> i) & 0xF]);
  puts_uart("); this firmware only drives FP32. Halted.\r\n");
  for (;;) { }
}

void borg_load_spirb_shader_at(const spirb_shader_t *s, int offset) {
  for (int i = 0; i < s->num_instrs; i++)
    BORG_GPU->imem[offset + i] = s->instrs[i];
  BORG_GPU->imem[offset + s->num_instrs] = BORG_INSTR_HALT;
}

/** dma_load_shader — bulk-copy shader instructions from DRAM to IMEM via DMA.
 *
 * @param dram_byte_addr  4-byte-aligned DRAM source address of the shader blob
 *                         (num_instrs 32-bit words; HALT is written separately below)
 * @param num_instrs       number of 32-bit instruction words to DMA
 * @param imem_offset      destination word index in IMEM (0 = start of IMEM)
 *
 * Caller must ensure no GPU core is running (STATUS.idle == 1).
 * DMA_DRAM holds only the data words; the HALT terminator is pre-written via
 * MMIO before triggering the DMA so it is in place when execution starts.
 */
void dma_load_shader(uint32_t dram_byte_addr, int num_instrs, int imem_offset) {
  /* Pre-write the HALT terminator — DMA does not cover it */
  BORG_GPU->imem[imem_offset + num_instrs] = BORG_INSTR_HALT;

  /* DMA_DRAM: 20-bit byte-aligned base address */
  BORG_GPU->dma_dram = dram_byte_addr & DMA_DRAM_REG_T_NOGEN_T__BASE_bm;

  /* DMA_CONFIG: length[6:1] | dest=0(IMEM)[8:7] | offset[14:9] | start[0] */
  BORG_GPU->dma_config =
      ((uint32_t)num_instrs   << DMA_CONFIG_REG_T_NOGEN_T__LENGTH_bp) |
      (0U                     << DMA_CONFIG_REG_T_NOGEN_T__DEST_bp)   |
      ((uint32_t)imem_offset  << DMA_CONFIG_REG_T_NOGEN_T__OFFSET_bp) |
      DMA_CONFIG_REG_T_NOGEN_T__START_bm;

  /* Poll until DMA completes */
  while (BORG_GPU->status & STATUS_REG_T__DMA_BUSY_bm)
    ;
}

/** dma_load_uniforms — bulk-copy uniforms from DRAM to the uniform buffer.
 *
 * @param dram_byte_addr  4-byte-aligned DRAM source address
 *                         Each DRAM word holds one datapath float.
 * @param num_uniforms     number of uniform values to transfer
 * @param uniform_offset   starting index in the uniform buffer (0..31)
 * @param page             uniform buffer page: 0 or 1
 */
void dma_load_uniforms(uint32_t dram_byte_addr, int num_uniforms,
                       int uniform_offset, int page) {
  /* dest encoding: page 0 → 1, page 1 → 2 */
  uint32_t dest = (page == 0) ? 1U : 2U;

  BORG_GPU->dma_dram = dram_byte_addr & DMA_DRAM_REG_T_NOGEN_T__BASE_bm;

  BORG_GPU->dma_config =
      ((uint32_t)num_uniforms   << DMA_CONFIG_REG_T_NOGEN_T__LENGTH_bp) |
      (dest                     << DMA_CONFIG_REG_T_NOGEN_T__DEST_bp)   |
      ((uint32_t)uniform_offset << DMA_CONFIG_REG_T_NOGEN_T__OFFSET_bp) |
      DMA_CONFIG_REG_T_NOGEN_T__START_bm;

  while (BORG_GPU->status & STATUS_REG_T__DMA_BUSY_bm)
    ;
}

void borg_load_spirb_shader(const spirb_shader_t *s) {
  borg_load_spirb_shader_at(s, 0);
}

