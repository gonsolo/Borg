// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// SPIR-B runtime shader loader for Borg.
// See docs/spirb.md for the binary format specification.

#pragma once

#include <stdint.h>

#define SPIRB_MAX_INSTRS  6
#define SPIRB_MAX_REGS   16

// Parsed in-memory representation of a SPIR-B shader blob.
typedef struct spirb_shader_t {
  uint8_t  num_instrs;
  uint8_t  num_uniforms;
  uint8_t  num_attributes;
  uint8_t  num_outputs;
  uint8_t  num_consts;
  uint16_t instrs[SPIRB_MAX_INSTRS];
  uint8_t  uniform_regs[SPIRB_MAX_REGS];
  uint8_t  attribute_regs[SPIRB_MAX_REGS];
  uint8_t  output_regs[SPIRB_MAX_REGS];
  uint8_t  const_regs[SPIRB_MAX_REGS];
  uint16_t const_vals[SPIRB_MAX_REGS];
} spirb_shader_t;

// Parse a SPIR-B blob from a byte array.
// Returns the number of bytes consumed (so the caller knows where
// the next data starts).
int spirb_parse(const uint8_t *blob, spirb_shader_t *s);
