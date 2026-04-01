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
#define BORG_IMEM_VERT_OFFSET 0
#define BORG_IMEM_RAST_OFFSET 0
#define BORG_IMEM_FRAG_OFFSET 13
#define BORG_IMEM_ADD_OFFSET  35

void borg_load_spirb_shader(const spirb_shader_t *s);
void borg_load_spirb_shader_at(const spirb_shader_t *s, int offset);
void borg_load_add_shader(void);
fp16_t borg_fp16_sub_raw(fp16_t a, fp16_t b);
