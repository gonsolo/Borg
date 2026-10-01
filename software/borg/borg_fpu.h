// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg FPU helpers — hardware-accelerated FP16 arithmetic via MMIO.

#pragma once

#include <stdint.h>

// Documentary typedef: distinguishes FP16 float values from raw integers.
#ifndef FP16_T_DEFINED
#define FP16_T_DEFINED
typedef uint16_t fp16_t;
#endif

// Forward declaration — full definition in borg_spirb.h
struct spirb_shader_t;
typedef struct spirb_shader_t spirb_shader_t;

// --- Borg FPU wrappers ---
void borg_run(uint32_t start_pc);
fp16_t borg_fp16_add(fp16_t a, fp16_t b);
fp16_t borg_fp16_mul(fp16_t a, fp16_t b);
fp16_t borg_fp16_fmadd(fp16_t a, fp16_t b, fp16_t c);
fp16_t borg_fp16_rcp(fp16_t x);

// --- FP16 constants (single source of truth) ---
#define FP16_ZERO      0x0000
#define FP16_HALF      0x3800  // 0.5
#define FP16_ONE       0x3C00  // 1.0
#define FP16_TWO       0x4000  // 2.0
#define FP16_MAX_DEPTH 0x7BFF  // 65504 (max finite FP16)

// --- Inline macros ---
#define BORG_FP16_SUB(a, b) borg_fp16_add((a), (b) ^ 0x8000)
#define BORG_FP16_NEG(x) ((x) ^ 0x8000)

// --- FP16 conversion utilities ---
static inline int fp16_ge_zero(fp16_t v) { return (v & 0x8000) == 0; }
// Signed FP16 less-than: handles negative values correctly.
static inline int fp16_lt(fp16_t a, fp16_t b) {
  int sa = a >> 15, sb = b >> 15;
  if (sa != sb) return sa > sb;       // negative < positive
  return sa ? (a > b) : (a < b);      // both neg: larger bits = more negative
}
int fp16_to_uint(fp16_t fp16);
fp16_t uint_to_fp16(int val);

// --- Shader loader helpers ---
// The rasterizer edge-test shader is a permanent hardware ROM (BorgRasterRom
// in hardware/borg/src/) — it no longer lives in the writable IMEM and is
// never DMA'd or MMIO-uploaded (BORG_IMEM_RAST_OFFSET/LEN removed accordingly).
#define BORG_IMEM_VERT_OFFSET 0
#define BORG_IMEM_FRAG_OFFSET 1
// The scalar-FPU helper REWRITES its 2-word program (op + HALT) before every
// borg_run, so it needs no persistent slot.  It shares offset 0 with the
// vertex shader (mutually exclusive in time: Pass 1 vs. direct CPU calls).
#define BORG_IMEM_ADD_OFFSET  0
#define BORG_IMEM_DEPTH       72

// Instruction count for hardware sequencer DMA shader reload (Step 31.2).
// FRAG occupies IMEM[1..71] (up to 71 words). NOTE: this is the Default/ULX3S
// hardware bound (BorgConfig.Default.maxInstructions=72); ASIC's real IMEM is
// only 64 entries (BorgConfig.Asic) -- firmware here has no per-target build
// knob yet, so ASIC builds must keep frag well under BORG_IMEM_FRAG_LEN.
#define BORG_IMEM_FRAG_LEN  (BORG_IMEM_DEPTH       - BORG_IMEM_FRAG_OFFSET)

void borg_load_spirb_shader(const spirb_shader_t *s);
void borg_load_spirb_shader_at(const spirb_shader_t *s, int offset);
void borg_load_add_shader(void);
fp16_t borg_fp16_sub_raw(fp16_t a, fp16_t b);

// --- DMA shader/uniform loaders (Step 26.4, hasDMA=true path) ---
// Firmware must hold dram_byte_addr / num / offset stable; caller ensures GPU is idle.
void dma_load_shader(uint32_t dram_byte_addr, int num_instrs, int imem_offset);
void dma_load_uniforms(uint32_t dram_byte_addr, int num_uniforms,
                       int uniform_offset, int page);
