// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// SPIR-B runtime shader loader for Borg.
// See docs/spirb.md for the binary format specification.

#pragma once

#include <stdint.h>

#define SPIRB_MAX_INSTRS  6
#define SPIRB_MAX_REGS   16

// Parsed in-memory representation of a SPIR-B shader blob.
typedef struct {
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
static int spirb_parse(const uint8_t *blob, spirb_shader_t *s) {
  const uint8_t *p = blob;

  s->num_instrs     = *p++;
  s->num_uniforms   = *p++;
  s->num_attributes = *p++;
  s->num_outputs    = *p++;
  s->num_consts     = *p++;
  p++; // reserved

  for (int i = 0; i < s->num_instrs; i++) {
    s->instrs[i] = p[0] | (p[1] << 8);
    p += 2;
  }
  for (int i = 0; i < s->num_uniforms; i++)
    s->uniform_regs[i] = *p++;
  for (int i = 0; i < s->num_attributes; i++)
    s->attribute_regs[i] = *p++;
  for (int i = 0; i < s->num_outputs; i++)
    s->output_regs[i] = *p++;
  for (int i = 0; i < s->num_consts; i++)
    s->const_regs[i] = *p++;
  for (int i = 0; i < s->num_consts; i++) {
    s->const_vals[i] = p[0] | (p[1] << 8);
    p += 2;
  }

  return (int)(p - blob);
}
