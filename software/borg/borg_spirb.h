// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// SPIR-B runtime shader loader for Borg.
// See docs/spirb.md for the binary format specification.

#pragma once

#include <stdint.h>

// A draw-mode vertex shader runs from DRAM through the instruction cache,
// so it may be longer than IMEM (borgc's cube.vert is 74 words); a
// fragment shader is still bounded by BORG_IMEM_FRAG_LEN where it is staged.
#define SPIRB_MAX_INSTRS 128
// Header byte 5, bit 0: the draw extension follows const_vals (docs/spirb.md).
#define SPIRB_EXT_DRAW   0x01
#define SPIRB_MAX_REGS   32

// Parsed in-memory representation of a SPIR-B shader blob.
typedef struct spirb_shader_t {
  uint8_t  num_instrs;
  uint8_t  num_uniforms;
  uint8_t  num_attributes;
  uint8_t  num_outputs;
  uint8_t  num_consts;
  uint32_t instrs[SPIRB_MAX_INSTRS];
  uint8_t  uniform_regs[SPIRB_MAX_REGS];
  uint8_t  attribute_regs[SPIRB_MAX_REGS];
  uint8_t  output_regs[SPIRB_MAX_REGS];
  uint8_t  const_regs[SPIRB_MAX_REGS];
  // One datapath float (or a raw integer, e.g. a push-constant word index)
  // per constant, written to its GPR unchanged.
  uint32_t const_vals[SPIRB_MAX_REGS];
  // Draw extension (docs/spirb.md), all zero without it: the varying
  // components a vertex shader SOUTs, and the constant window -- u-index
  // (u25-u31 vertex, u20-u31 fragment) and bits of each word.
  uint8_t  has_draw_ext;
  uint8_t  num_varyings;
  uint8_t  num_window;
  uint8_t  window_regs[SPIRB_MAX_REGS];
  uint32_t window_vals[SPIRB_MAX_REGS];
} spirb_shader_t;

// Parse a SPIR-B blob from a byte array.
// Returns the number of bytes consumed (so the caller knows where
// the next data starts).
int spirb_parse(const uint8_t *blob, spirb_shader_t *s);
