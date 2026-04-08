// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg rasterization primitives — edge tests, barycentric interpolation.

#include "borg_raster.h"
#include "borg_fpu.h"
#include "borg_sys.h"
#include "borg_isa.h"
#include "borg_spirb.h"

#define NUM_VERTICES 3

void screen_space_translate(const spirb_shader_t *vert_shader,
                            const fp16_t *vout, xy16_t *screen_pos,
                            fp16_t fp16_half_width) {
  int stride = vert_shader->num_outputs;
  for (int v = 0; v < NUM_VERTICES; v++) {
    screen_pos[v] = (xy16_t){
        borg_fp16_fmadd(vout[v * stride + 0], fp16_half_width, fp16_half_width),
        borg_fp16_fmadd(vout[v * stride + 1], fp16_half_width,
                        fp16_half_width)};
  }
}

void compute_edge_vectors(const xy16_t *screen_pos, xy16_t *edges) {
  for (int i = 0; i < 3; i++) {
    int next = (i == 2) ? 0 : i + 1;
    edges[i].x = BORG_FP16_SUB(screen_pos[i].x, screen_pos[next].x);
    edges[i].y = BORG_FP16_SUB(screen_pos[next].y, screen_pos[i].y);
  }
}



// Load edge constants (uniforms) once per triangle.
void borg_load_edge_constants(const spirb_shader_t *s, const xy16_t *edges) {
  // Uniforms: dx0, neg_dy0, dx1, neg_dy1, dx2, neg_dy2
  for (int i = 0; i < 3; i++) {
    BORG_GPU->uniform[s->uniform_regs[i * 2 + 0]] = edges[i].x;
    BORG_GPU->uniform[s->uniform_regs[i * 2 + 1]] = edges[i].y;
  }
}



// Load 3 consecutive per-vertex values into uniform registers at base_reg.
void load_uniform_triple(const spirb_shader_t *s, int base_reg,
                                fp16_t v0, fp16_t v1, fp16_t v2) {
  BORG_GPU->uniform[s->uniform_regs[base_reg + 0]] = v0;
  BORG_GPU->uniform[s->uniform_regs[base_reg + 1]] = v1;
  BORG_GPU->uniform[s->uniform_regs[base_reg + 2]] = v2;
}

// Read one output register as an fp16.
fp16_t read_output_reg(const spirb_shader_t *s, int reg) {
  return BORG_GPU->gpr[s->output_regs[reg]] & 0xFFFF;
}

// Read 3 consecutive output registers as an rgb16_t.
rgb16_t read_output_rgb(const spirb_shader_t *s, int base_reg) {
  return (rgb16_t){read_output_reg(s, base_reg + 0),
                   read_output_reg(s, base_reg + 1),
                   read_output_reg(s, base_reg + 2)};
}



texcoord_t uv_to_texcoord(uv16_t uv, fp16_t w_fp16, fp16_t h_fp16) {
  return (texcoord_t){fp16_to_uint(borg_fp16_mul(uv.u, w_fp16)),
                      fp16_to_uint(borg_fp16_mul(uv.v, h_fp16))};
}

// Run fragment shader only — edge values already in r0/r1/r2.
// Step 10.6.1: fragment shader reads e0/e1/e2 directly from rasterizer
// output registers (r0/r1/r2) — no attribute copy needed.
int borg_run_fragment(const spirb_shader_t *rast_shader,
                      const spirb_shader_t *frag_shader, const triangle_t *tri,
                      frag_result_t *result) {
  // Copy edge values from rasterizer output regs (r0/r1/r2) into fragment
  // shader attribute registers. We must read them FIRST before loading uniforms,
  // because fragment uniforms might physically overlap with rasterizer outputs!
  uint16_t tmp_attrs[16];
  for (int i = 0; i < frag_shader->num_attributes; i++) {
    tmp_attrs[i] = BORG_GPU->gpr[rast_shader->output_regs[i]] & 0xFFFF;
  }

  // Load fragment uniforms (per-triangle constants)
  const rgb16_t *colors = tri->colors.v;
  BORG_GPU->uniform[frag_shader->uniform_regs[0]] = tri->inv_area;
  load_uniform_triple(frag_shader, 1, colors[0].r, colors[1].r, colors[2].r);
  load_uniform_triple(frag_shader, 4, colors[0].g, colors[1].g, colors[2].g);
  load_uniform_triple(frag_shader, 7, colors[0].b, colors[1].b, colors[2].b);
  load_uniform_triple(frag_shader, 10, tri->z_vals.v[0], tri->z_vals.v[1],
                       tri->z_vals.v[2]);

  if (tri->has_uvs) {
    const uv16_t *uvs = tri->uvs.v;
    load_uniform_triple(frag_shader, 13, uvs[0].u, uvs[1].u, uvs[2].u);
    load_uniform_triple(frag_shader, 16, uvs[0].v, uvs[1].v, uvs[2].v);
  } else {
    for (int i = 13; i <= 18; i++)
      BORG_GPU->uniform[frag_shader->uniform_regs[i]] = 0;
  }

  // Now that fragment uniforms are loaded (potentially overwriting rasterizer
  // output slots), we can safely push the cached attributes to the frag registers.
  for (int i = 0; i < frag_shader->num_attributes; i++) {
    BORG_GPU->gpr[frag_shader->attribute_regs[i]] = tmp_attrs[i];
  }

  borg_run(BORG_IMEM_FRAG_OFFSET);

  result->color = read_output_rgb(frag_shader, 0);
  result->z = read_output_reg(frag_shader, 3);
  if (tri->has_uvs) {
    result->uv.u = read_output_reg(frag_shader, 4);
    result->uv.v = read_output_reg(frag_shader, 5);
  }

  return 1;
}
