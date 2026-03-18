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

fp16_t borg_rasterize_edge(const spirb_shader_t *rast_shader,
                              fp16_t dx_e, fp16_t neg_dy_e,
                              fp16_t dpx_e, fp16_t dpy_e) {
  BORG_REG(rast_shader->attribute_regs[0]) = dx_e;
  BORG_REG(rast_shader->attribute_regs[1]) = neg_dy_e;
  BORG_REG(rast_shader->attribute_regs[2]) = dpx_e;
  BORG_REG(rast_shader->attribute_regs[3]) = dpy_e;
  borg_run();
  return BORG_REG(rast_shader->output_regs[0]) & 0xFFFF;
}

fp16_t __attribute__((noinline)) borg_frag_channel(
    const spirb_shader_t *frag_shader,
    fp16_t e0, fp16_t e1, fp16_t e2,
    fp16_t inv_area, fp16_t c0, fp16_t c1, fp16_t c2) {
  BORG_REG(frag_shader->attribute_regs[0]) = e0;
  BORG_REG(frag_shader->attribute_regs[1]) = e1;
  BORG_REG(frag_shader->attribute_regs[2]) = e2;
  BORG_REG(frag_shader->uniform_regs[0]) = inv_area;
  BORG_REG(frag_shader->uniform_regs[1]) = c0;
  BORG_REG(frag_shader->uniform_regs[2]) = c1;
  BORG_REG(frag_shader->uniform_regs[3]) = c2;
  borg_run();
  return BORG_REG(frag_shader->output_regs[0]) & 0xFFFF;
}

int __attribute__((noinline)) borg_bary_rgb(
    const spirb_shader_t *rast_shader,
    const spirb_shader_t *frag_shader,
    const uv16_t *edges, const uv16_t *deltas,
    fp16_t inv_area, fp16_t colors[3][3],
    const fp16_t z_vals[3],
    const uv16_t uvs[3],
    rgb16_t *color_out,
    fp16_t *z_out, uv16_t *uv_out) {
  borg_load_spirb_shader(rast_shader);
  fp16_t e0 = borg_rasterize_edge(rast_shader, edges[0].u, edges[0].v, deltas[0].u, deltas[0].v);
  fp16_t e1 = borg_rasterize_edge(rast_shader, edges[1].u, edges[1].v, deltas[1].u, deltas[1].v);
  fp16_t e2 = borg_rasterize_edge(rast_shader, edges[2].u, edges[2].v, deltas[2].u, deltas[2].v);
  if ((fp16_ge_zero(e0) && e0 != 0) ||
      (fp16_ge_zero(e1) && e1 != 0) ||
      (fp16_ge_zero(e2) && e2 != 0))
    return 0;
  borg_load_spirb_shader(frag_shader);
  color_out->r = borg_frag_channel(frag_shader, e0, e1, e2, inv_area,
                              colors[0][0], colors[1][0], colors[2][0]);
  color_out->g = borg_frag_channel(frag_shader, e0, e1, e2, inv_area,
                              colors[0][1], colors[1][1], colors[2][1]);
  color_out->b = borg_frag_channel(frag_shader, e0, e1, e2, inv_area,
                              colors[0][2], colors[1][2], colors[2][2]);
  *z_out = borg_frag_channel(frag_shader, e0, e1, e2, inv_area,
                              z_vals[0], z_vals[1], z_vals[2]);
  // UV interpolation (only when textured)
  if (uvs) {
    uv_out->u = borg_frag_channel(frag_shader, e0, e1, e2, inv_area,
                                uvs[0].u, uvs[1].u, uvs[2].u);
    uv_out->v = borg_frag_channel(frag_shader, e0, e1, e2, inv_area,
                                uvs[0].v, uvs[1].v, uvs[2].v);
  }
  return 1;
}

texcoord_t uv_to_texcoord(uv16_t uv, fp16_t w_fp16, fp16_t h_fp16) {
  return (texcoord_t){
    fp16_to_uint(borg_fp16_mul(uv.u, w_fp16)),
    fp16_to_uint(borg_fp16_mul(uv.v, h_fp16))
  };
}
