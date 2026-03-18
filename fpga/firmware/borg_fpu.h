// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg FPU helpers — hardware-accelerated FP16 arithmetic via MMIO.

#pragma once

#include <stdint.h>

// Forward declaration — full definition in spirb.h
struct spirb_shader_t;
typedef struct spirb_shader_t spirb_shader_t;

// --- Borg FPU wrappers ---
void borg_run(void);
uint16_t borg_fp16_add(uint16_t a, uint16_t b);
uint16_t borg_fp16_mul(uint16_t a, uint16_t b);
uint16_t borg_fp16_fmadd(uint16_t a, uint16_t b, uint16_t c);
uint16_t borg_fp16_rcp(uint16_t x);

// --- Inline macros ---
#define BORG_FP16_SUB(a, b) borg_fp16_add((a), (b) ^ 0x8000)
#define BORG_FP16_NEG(x) ((x) ^ 0x8000)
#define FP16_TWO  0x4000

// --- FP16 conversion utilities ---
static inline int fp16_ge_zero(uint16_t v) { return (v & 0x8000) == 0; }
int fp16_to_uint(uint16_t fp16);
uint16_t uint_to_fp16(int val);

// --- Shader loader helpers ---
void borg_load_spirb_shader(const spirb_shader_t *s);
void borg_load_add_shader(void);
uint16_t borg_fp16_sub_raw(uint16_t a, uint16_t b);
