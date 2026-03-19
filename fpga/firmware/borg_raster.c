// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg rasterization primitives — edge tests, barycentric interpolation.

#include "borg_raster.h"
#include "borg_fpu.h"
#include "borg_spirb.h"
#include "borg_mmio.h"

#define NUM_VERTICES 3

void screen_space_translate(const spirb_shader_t *vert_shader,
                            const fp16_t *vout, uv16_t *spos,
                            fp16_t fp16_half_width) {
  int stride = vert_shader->num_outputs;
  for (int v = 0; v < NUM_VERTICES; v++) {
    spos[v] = (uv16_t){
      borg_fp16_fmadd(vout[v * stride + 0], fp16_half_width, fp16_half_width),
      borg_fp16_fmadd(vout[v * stride + 1], fp16_half_width, fp16_half_width)
    };
  }
}

void compute_edge_vectors(const uv16_t *spos, uv16_t *edges) {
  edges[0] = (uv16_t){
    BORG_FP16_SUB(spos[1].u, spos[0].u),
    BORG_FP16_NEG(BORG_FP16_SUB(spos[1].v, spos[0].v))
  };
  edges[1] = (uv16_t){
    BORG_FP16_SUB(spos[2].u, spos[1].u),
    BORG_FP16_NEG(BORG_FP16_SUB(spos[2].v, spos[1].v))
  };
  edges[2] = (uv16_t){
    BORG_FP16_SUB(spos[0].u, spos[2].u),
    BORG_FP16_NEG(BORG_FP16_SUB(spos[0].v, spos[2].v))
  };
}

void compute_pixel_deltas(uv16_t pc, const uv16_t *spos,
                                  uv16_t *deltas) {
  borg_load_add_shader();
  for (int e = 0; e < 3; e++) {
    deltas[e] = (uv16_t){
      borg_fp16_sub_raw(pc.u, spos[e].u),
      borg_fp16_sub_raw(pc.v, spos[e].v)
    };
  }
}

// Load edge constants (uniforms) once per triangle.
static void borg_load_edge_constants(const spirb_shader_t *s, const uv16_t *edges) {
  // Uniforms: dx0, neg_dy0, dx1, neg_dy1, dx2, neg_dy2
  for (int i = 0; i < 3; i++) {
    BORG_REG(s->uniform_regs[i * 2 + 0]) = edges[i].u;
    BORG_REG(s->uniform_regs[i * 2 + 1]) = edges[i].v;
  }
}

// Evaluate all 3 edge functions in a single borg_run().
static edges3_t borg_rasterize_edges(const spirb_shader_t *s, const uv16_t *deltas) {
  // Attributes: dpx0, dpy0, dpx1, dpy1, dpx2, dpy2
  for (int i = 0; i < 3; i++) {
    BORG_REG(s->attribute_regs[i * 2 + 0]) = deltas[i].u;
    BORG_REG(s->attribute_regs[i * 2 + 1]) = deltas[i].v;
  }
  borg_run();
  return (edges3_t){
    BORG_REG(s->output_regs[0]) & 0xFFFF,
    BORG_REG(s->output_regs[1]) & 0xFFFF,
    BORG_REG(s->output_regs[2]) & 0xFFFF
  };
}

fp16_t __attribute__((noinline)) borg_frag_channel(
    const spirb_shader_t *frag_shader,
    edges3_t e,
    fp16_t inv_area, fp16x3_t c) {
  BORG_REG(frag_shader->attribute_regs[0]) = e.e0;
  BORG_REG(frag_shader->attribute_regs[1]) = e.e1;
  BORG_REG(frag_shader->attribute_regs[2]) = e.e2;
  BORG_REG(frag_shader->uniform_regs[0]) = inv_area;
  BORG_REG(frag_shader->uniform_regs[1]) = c.a;
  BORG_REG(frag_shader->uniform_regs[2]) = c.b;
  BORG_REG(frag_shader->uniform_regs[3]) = c.c;
  borg_run();
  return BORG_REG(frag_shader->output_regs[0]) & 0xFFFF;
}

int __attribute__((noinline)) borg_bary_rgb(
    const spirb_shader_t *rast_shader,
    const spirb_shader_t *frag_shader,
    const uv16_t *edges, const uv16_t *deltas,
    fp16_t inv_area, const rgb16_t colors[3],
    fp16x3_t z_vals,
    const uv16_t uvs[3],
    rgb16_t *color_out,
    fp16_t *z_out, uv16_t *uv_out) {
  // Batched edge evaluation via SPIR-B rasterize shader
  borg_load_spirb_shader(rast_shader);
  borg_load_edge_constants(rast_shader, edges);
  edges3_t e = borg_rasterize_edges(rast_shader, deltas);
  if ((fp16_ge_zero(e.e0) && e.e0 != 0) ||
      (fp16_ge_zero(e.e1) && e.e1 != 0) ||
      (fp16_ge_zero(e.e2) && e.e2 != 0))
    return 0;
  borg_load_spirb_shader(frag_shader);
  color_out->r = borg_frag_channel(frag_shader, e, inv_area, GATHER(colors, r));
  color_out->g = borg_frag_channel(frag_shader, e, inv_area, GATHER(colors, g));
  color_out->b = borg_frag_channel(frag_shader, e, inv_area, GATHER(colors, b));
  *z_out = borg_frag_channel(frag_shader, e, inv_area, z_vals);
  // UV interpolation (only when textured)
  if (uvs) {
    uv_out->u = borg_frag_channel(frag_shader, e, inv_area, GATHER(uvs, u));
    uv_out->v = borg_frag_channel(frag_shader, e, inv_area, GATHER(uvs, v));
  }
  return 1;
}

texcoord_t uv_to_texcoord(uv16_t uv, fp16_t w_fp16, fp16_t h_fp16) {
  return (texcoord_t){
    fp16_to_uint(borg_fp16_mul(uv.u, w_fp16)),
    fp16_to_uint(borg_fp16_mul(uv.v, h_fp16))
  };
}
