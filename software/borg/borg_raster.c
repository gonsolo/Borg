// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg rasterization primitives — edge tests, barycentric interpolation.

#include "borg_raster.h"
#include "borg_fpu.h"
#include "borg_spirb.h"
#include "borg_mmio.h"

#define NUM_VERTICES 3

void screen_space_translate(const spirb_shader_t *vert_shader,
                            const fp16_t *vout, xy16_t *screen_pos,
                            fp16_t fp16_half_width) {
  int stride = vert_shader->num_outputs;
  for (int v = 0; v < NUM_VERTICES; v++) {
    screen_pos[v] = (xy16_t){
      borg_fp16_fmadd(vout[v * stride + 0], fp16_half_width, fp16_half_width),
      borg_fp16_fmadd(vout[v * stride + 1], fp16_half_width, fp16_half_width)
    };
  }
}

void compute_edge_vectors(const xy16_t *screen_pos, xy16_t *edges) {
  edges[0] = (xy16_t){
    BORG_FP16_SUB(screen_pos[1].x, screen_pos[0].x),
    BORG_FP16_NEG(BORG_FP16_SUB(screen_pos[1].y, screen_pos[0].y))
  };
  edges[1] = (xy16_t){
    BORG_FP16_SUB(screen_pos[2].x, screen_pos[1].x),
    BORG_FP16_NEG(BORG_FP16_SUB(screen_pos[2].y, screen_pos[1].y))
  };
  edges[2] = (xy16_t){
    BORG_FP16_SUB(screen_pos[0].x, screen_pos[2].x),
    BORG_FP16_NEG(BORG_FP16_SUB(screen_pos[0].y, screen_pos[2].y))
  };
}

void compute_pixel_deltas(xy16_t pc, const xy16_t *screen_pos,
                                  xy16_t *deltas) {
  for (int e = 0; e < 3; e++) {
    deltas[e] = (xy16_t){
      borg_fp16_sub_raw(pc.x, screen_pos[e].x),
      borg_fp16_sub_raw(pc.y, screen_pos[e].y)
    };
  }
}

// Load edge constants (uniforms) once per triangle.
static void borg_load_edge_constants(const spirb_shader_t *s, const xy16_t *edges) {
  // Uniforms: dx0, neg_dy0, dx1, neg_dy1, dx2, neg_dy2
  for (int i = 0; i < 3; i++) {
    BORG_REG(s->uniform_regs[i * 2 + 0]) = edges[i].x;
    BORG_REG(s->uniform_regs[i * 2 + 1]) = edges[i].y;
  }
}

// Evaluate all 3 edge functions in a single borg_run().
static edges3_t borg_rasterize_edges(const spirb_shader_t *s, const xy16_t *deltas) {
  // Attributes: dpx0, dpy0, dpx1, dpy1, dpx2, dpy2
  for (int i = 0; i < 3; i++) {
    BORG_REG(s->attribute_regs[i * 2 + 0]) = deltas[i].x;
    BORG_REG(s->attribute_regs[i * 2 + 1]) = deltas[i].y;
  }
  borg_run(BORG_IMEM_RAST_OFFSET);
  return (edges3_t){
    BORG_REG(s->output_regs[0]) & 0xFFFF,
    BORG_REG(s->output_regs[1]) & 0xFFFF,
    BORG_REG(s->output_regs[2]) & 0xFFFF
  };
}

// Load 3 consecutive per-vertex values into uniform registers at base_reg.
static void load_uniform_triple(const spirb_shader_t *s, int base_reg,
                                fp16_t v0, fp16_t v1, fp16_t v2) {
  BORG_REG(s->uniform_regs[base_reg + 0]) = v0;
  BORG_REG(s->uniform_regs[base_reg + 1]) = v1;
  BORG_REG(s->uniform_regs[base_reg + 2]) = v2;
}


// Read one output register as an fp16.
static fp16_t read_output_reg(const spirb_shader_t *s, int reg) {
  return BORG_REG(s->output_regs[reg]) & 0xFFFF;
}

// Read 3 consecutive output registers as an rgb16_t.
static rgb16_t read_output_rgb(const spirb_shader_t *s, int base_reg) {
  return (rgb16_t){
    read_output_reg(s, base_reg + 0),
    read_output_reg(s, base_reg + 1),
    read_output_reg(s, base_reg + 2)
  };
}

int __attribute__((noinline)) borg_shade_fragment(
    const spirb_shader_t *rast_shader,
    const spirb_shader_t *frag_shader,
    const triangle_t *tri,
    const xy16_t deltas[3],
    frag_result_t *result) {
  borg_load_edge_constants(rast_shader, tri->edges.v);
  edges3_t e = borg_rasterize_edges(rast_shader, deltas);
  if ((fp16_ge_zero(e.e0) && e.e0 != 0) ||
      (fp16_ge_zero(e.e1) && e.e1 != 0) ||
      (fp16_ge_zero(e.e2) && e.e2 != 0))
    return 0;

  const rgb16_t *colors = tri->colors.v;
  BORG_REG(frag_shader->uniform_regs[0]) = tri->inv_area;
  load_uniform_triple(frag_shader, 1,  colors[0].r, colors[1].r, colors[2].r);
  load_uniform_triple(frag_shader, 4,  colors[0].g, colors[1].g, colors[2].g);
  load_uniform_triple(frag_shader, 7,  colors[0].b, colors[1].b, colors[2].b);
  load_uniform_triple(frag_shader, 10, tri->z_vals.v[0], tri->z_vals.v[1], tri->z_vals.v[2]);

  if (tri->has_uvs) {
    const uv16_t *uvs = tri->uvs.v;
    load_uniform_triple(frag_shader, 13, uvs[0].u, uvs[1].u, uvs[2].u);
    load_uniform_triple(frag_shader, 16, uvs[0].v, uvs[1].v, uvs[2].v);
  } else {
    for (int i = 13; i <= 18; i++) BORG_REG(frag_shader->uniform_regs[i]) = 0;
  }

  BORG_REG(frag_shader->attribute_regs[0]) = e.e0;
  BORG_REG(frag_shader->attribute_regs[1]) = e.e1;
  BORG_REG(frag_shader->attribute_regs[2]) = e.e2;

  borg_run(BORG_IMEM_FRAG_OFFSET);

  result->color = read_output_rgb(frag_shader, 0);
  result->z     = read_output_reg(frag_shader, 3);
  if (tri->has_uvs) {
    result->uv.u = read_output_reg(frag_shader, 4);
    result->uv.v = read_output_reg(frag_shader, 5);
  }

  return 1;
}

texcoord_t uv_to_texcoord(uv16_t uv, fp16_t w_fp16, fp16_t h_fp16) {
  return (texcoord_t){
    fp16_to_uint(borg_fp16_mul(uv.u, w_fp16)),
    fp16_to_uint(borg_fp16_mul(uv.v, h_fp16))
  };
}
