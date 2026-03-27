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
  // add shader is loaded globally at offset 28
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
  borg_run(BORG_IMEM_RAST_OFFSET);
  return (edges3_t){
    BORG_REG(s->output_regs[0]) & 0xFFFF,
    BORG_REG(s->output_regs[1]) & 0xFFFF,
    BORG_REG(s->output_regs[2]) & 0xFFFF
  };
}


int __attribute__((noinline)) borg_shade_fragment(
    const spirb_shader_t *rast_shader,
    const spirb_shader_t *frag_shader,
    const uv16_t *edges, const uv16_t *deltas,
    fp16_t inv_area, const rgb16_t colors[3],
    fp16x3_t z_vals,
    const uv16_t uvs[3],
    rgb16_t *color_out,
    fp16_t *z_out, uv16_t *uv_out) {
  // Batched edge evaluation via SPIR-B rasterize shader (loaded at offset 0)
  borg_load_edge_constants(rast_shader, edges);
  edges3_t e = borg_rasterize_edges(rast_shader, deltas);
  if ((fp16_ge_zero(e.e0) && e.e0 != 0) ||
      (fp16_ge_zero(e.e1) && e.e1 != 0) ||
      (fp16_ge_zero(e.e2) && e.e2 != 0))
    return 0;
  // Fragment shader is loaded at offset 16

  // Load batched fragment shader uniforms
  BORG_REG(frag_shader->uniform_regs[0]) = inv_area;
  BORG_REG(frag_shader->uniform_regs[1]) = colors[0].r;
  BORG_REG(frag_shader->uniform_regs[2]) = colors[1].r;
  BORG_REG(frag_shader->uniform_regs[3]) = colors[2].r;
  BORG_REG(frag_shader->uniform_regs[4]) = colors[0].g;
  BORG_REG(frag_shader->uniform_regs[5]) = colors[1].g;
  BORG_REG(frag_shader->uniform_regs[6]) = colors[2].g;
  BORG_REG(frag_shader->uniform_regs[7]) = colors[0].b;
  BORG_REG(frag_shader->uniform_regs[8]) = colors[1].b;
  BORG_REG(frag_shader->uniform_regs[9]) = colors[2].b;
  BORG_REG(frag_shader->uniform_regs[10]) = z_vals.a;
  BORG_REG(frag_shader->uniform_regs[11]) = z_vals.b;
  BORG_REG(frag_shader->uniform_regs[12]) = z_vals.c;

  if (uvs) {
    BORG_REG(frag_shader->uniform_regs[13]) = uvs[0].u;
    BORG_REG(frag_shader->uniform_regs[14]) = uvs[1].u;
    BORG_REG(frag_shader->uniform_regs[15]) = uvs[2].u;
    BORG_REG(frag_shader->uniform_regs[16]) = uvs[0].v;
    BORG_REG(frag_shader->uniform_regs[17]) = uvs[1].v;
    BORG_REG(frag_shader->uniform_regs[18]) = uvs[2].v;
  } else {
    for (int i=13; i<=18; i++) BORG_REG(frag_shader->uniform_regs[i]) = 0;
  }

  // Reload per-pixel attributes
  BORG_REG(frag_shader->attribute_regs[0]) = e.e0;
  BORG_REG(frag_shader->attribute_regs[1]) = e.e1;
  BORG_REG(frag_shader->attribute_regs[2]) = e.e2;

  borg_run(BORG_IMEM_FRAG_OFFSET);

  // Read batched outputs
  color_out->r = BORG_REG(frag_shader->output_regs[0]) & 0xFFFF;
  color_out->g = BORG_REG(frag_shader->output_regs[1]) & 0xFFFF;
  color_out->b = BORG_REG(frag_shader->output_regs[2]) & 0xFFFF;
  *z_out = BORG_REG(frag_shader->output_regs[3]) & 0xFFFF;
  if (uvs) {
    uv_out->u = BORG_REG(frag_shader->output_regs[4]) & 0xFFFF;
    uv_out->v = BORG_REG(frag_shader->output_regs[5]) & 0xFFFF;
  }

  return 1;
}

texcoord_t uv_to_texcoord(uv16_t uv, fp16_t w_fp16, fp16_t h_fp16) {
  return (texcoord_t){
    fp16_to_uint(borg_fp16_mul(uv.u, w_fp16)),
    fp16_to_uint(borg_fp16_mul(uv.v, h_fp16))
  };
}
