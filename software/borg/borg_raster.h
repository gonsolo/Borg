// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg rasterization primitives — edge tests, barycentric interpolation.

#pragma once

#include "borg_fpu.h"
#include <stdint.h>

// Forward declaration — full definition in borg_spirb.h
struct spirb_shader_t;
typedef struct spirb_shader_t spirb_shader_t;

typedef struct {
  fp16_t x, y;
} xy16_t; // screen-space: positions, edges, deltas
typedef struct {
  fp16_t u, v;
} uv16_t; // texture coordinates
typedef struct {
  fp16_t r, g, b;
} rgb16_t;
typedef struct {
  fp16_t e0, e1, e2;
} edges3_t;
typedef struct {
  fp16_t v[3];
} fp16x3_t;
typedef struct {
  int x, y;
} texcoord_t;

// Per-vertex triple types — all members accessible via .v[0..2].
typedef struct {
  xy16_t v[3];
} xy16x3_t;
typedef struct {
  rgb16_t v[3];
} rgb16x3_t;
typedef struct {
  uv16_t v[3];
} uv16x3_t;

// Per-triangle rasterization state, computed once per triangle.
typedef struct {
  xy16x3_t screen_pos; // screen-space vertex positions
  xy16x3_t edges;      // edge normal vectors
  fp16_t inv_area;     // 1 / signed area
  rgb16x3_t colors;    // per-vertex colors
  fp16x3_t z_vals;     // per-vertex z depths
  uv16x3_t uvs;        // per-vertex texture UVs
  int has_uvs;         // nonzero if textured
} triangle_t;

// Output of fragment shading for one pixel.
typedef struct {
  rgb16_t color;
  fp16_t z;
  uv16_t uv;
} frag_result_t;

// Screen-space translation: NDC → pixel coordinates
void screen_space_translate(const spirb_shader_t *vert_shader,
                            const fp16_t *vout, xy16_t *screen_pos,
                            fp16_t fp16_half_width);

// Compute edge normal vectors from screen-space triangle positions
void compute_edge_vectors(const xy16_t *screen_pos, xy16_t *edges);

// Load edge constants (uniforms) into rasterizer shader registers (once per
// triangle).
void borg_load_edge_constants(const spirb_shader_t *s, const xy16_t *edges);

// Run fragment shader only — assumes edge shader already ran (values in rast
// output regs). Returns 1 always (caller should have already checked
// inside_flag).
int borg_run_fragment(const spirb_shader_t *rast_shader,
                      const spirb_shader_t *frag_shader, const triangle_t *tri,
                      frag_result_t *result);

// UV to texel coordinate conversion
texcoord_t uv_to_texcoord(uv16_t uv, fp16_t w_fp16, fp16_t h_fp16);
