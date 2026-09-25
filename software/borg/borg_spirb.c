// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// SPIR-B runtime shader loader for Borg.

#include "borg_spirb.h"

int spirb_parse(const uint8_t *blob, spirb_shader_t *s) {
  if (!blob || !s) return -1;

  const uint8_t *p = blob;

  s->num_instrs     = *p++;
  s->num_uniforms   = *p++;
  s->num_attributes = *p++;
  s->num_outputs    = *p++;
  s->num_consts     = *p++;
  uint8_t ext       = *p++;

  if (s->num_instrs   > SPIRB_MAX_INSTRS) return -1;
  if (s->num_uniforms > SPIRB_MAX_REGS)   return -1;
  if (s->num_attributes > SPIRB_MAX_REGS) return -1;
  if (s->num_outputs  > SPIRB_MAX_REGS)   return -1;
  if (s->num_consts   > SPIRB_MAX_REGS)   return -1;

  for (int i = 0; i < s->num_instrs; i++) {
    s->instrs[i] = p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24);
    p += 4;
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
    s->const_vals[i] = p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24);
    p += 4;
  }

  s->has_draw_ext = (ext & SPIRB_EXT_DRAW) ? 1 : 0;
  s->num_varyings = 0;
  s->num_window   = 0;
  if (s->has_draw_ext) {
    s->num_varyings = *p++;
    s->num_window   = *p++;
    if (s->num_window > SPIRB_MAX_REGS) return -1;
    for (int i = 0; i < s->num_window; i++)
      s->window_regs[i] = *p++;
    for (int i = 0; i < s->num_window; i++) {
      s->window_vals[i] = p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24);
      p += 4;
    }
  }

  return (int)(p - blob);
}
