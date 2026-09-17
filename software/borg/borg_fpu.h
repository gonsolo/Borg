// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg FPU helpers — the shader datapath's float arithmetic via MMIO, plus the
// float encodings the firmware hands to the hardware.

#pragma once

#include <stdint.h>

// A datapath float: the bit pattern of one value in the shader core's float
// format -- IEEE 754 binary32 (FP32) in every current BorgConfig. GPRs,
// uniforms, vertex descriptor words, the setup store and push constants all
// hold these. borg_check_float_width() refuses to run on anything else.
typedef uint32_t borg_float_t;

// FP16 stays where the hardware itself is FP16: texels (the texture unit),
// the tile buffer's colour/Z and its clear value. Nothing on the shader
// datapath is FP16.
#ifndef FP16_T_DEFINED
#define FP16_T_DEFINED
typedef uint16_t fp16_t;
#endif

// Forward declaration — full definition in borg_spirb.h
struct spirb_shader_t;
typedef struct spirb_shader_t spirb_shader_t;

// --- Borg FPU wrappers (one instruction run on the shader core) ---
void borg_run(uint32_t start_pc);
borg_float_t borg_float_add(borg_float_t a, borg_float_t b);
borg_float_t borg_float_mul(borg_float_t a, borg_float_t b);
borg_float_t borg_float_fmadd(borg_float_t a, borg_float_t b, borg_float_t c);

// --- Datapath float constants ---
#define BORG_FLOAT_ZERO 0x00000000u
#define BORG_FLOAT_HALF 0x3F000000u  // 0.5
#define BORG_FLOAT_ONE  0x3F800000u  // 1.0

// --- FP16 constants (texels, tile colour/Z) ---
#define FP16_ONE       0x3C00  // 1.0 (texel)
#define FP16_MAX_DEPTH 0x7BFF  // 65504 (max finite FP16), tile-buffer Z clear

// C float -> datapath float: the IEEE bits, no conversion. memcpy-style union
// copy, so no soft-float arithmetic is involved on RV32.
static inline borg_float_t borg_float_from_f(float f) {
  union { float f; uint32_t u; } v = { f };
  return v.u;
}

// Non-negative integer -> datapath float, exact for v < 2^24. Integer-only.
static inline borg_float_t borg_float_from_uint(uint32_t v) {
  if (v == 0) return BORG_FLOAT_ZERO;
  int msb = 31;
  while (!(v & (1u << msb))) msb--;
  uint32_t mant = (msb >= 23) ? (v >> (msb - 23)) : (v << (23 - msb));
  return ((uint32_t)(127 + msb) << 23) | (mant & 0x7FFFFFu);
}

// 1/2^n as a datapath float (exact).
static inline borg_float_t borg_float_inv_pow2(unsigned n) {
  return (uint32_t)(127 - n) << 23;
}

// Halts with a UART message unless the shader core really holds FP32 words.
// An FP16 build keeps only the low 16 bits of a GPR write, and every float the
// firmware sends would then be garbage that renders nothing, silently.
void borg_check_float_width(void);

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

// --- DMA shader/uniform loaders (Step 26.4, hasDMA=true path) ---
// Firmware must hold dram_byte_addr / num / offset stable; caller ensures GPU is idle.
void dma_load_shader(uint32_t dram_byte_addr, int num_instrs, int imem_offset);
void dma_load_uniforms(uint32_t dram_byte_addr, int num_uniforms,
                       int uniform_offset, int page);
