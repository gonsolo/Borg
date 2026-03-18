// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg rasterization primitives — edge tests, barycentric interpolation.

#pragma once

#include <stdint.h>

// Forward declaration — full definition in spirb.h
struct spirb_shader_t;
typedef struct spirb_shader_t spirb_shader_t;

typedef struct { uint16_t u, v; } uv16_t;
typedef struct { uint16_t r, g, b; } rgb16_t;
typedef struct { int x, y; } texcoord_t;

// Screen-space translation: NDC → pixel coordinates
void screen_space_translate(const spirb_shader_t *vert_shader,
                            const uint16_t *vout, uv16_t *spos,
                            uint16_t fp16_half_width);

// Compute edge normal vectors from screen-space triangle positions
void compute_edge_vectors(const uv16_t *spos, uv16_t *edges);

// Compute per-pixel deltas from pixel center to each vertex
void compute_pixel_deltas(uv16_t pc, const uv16_t *spos, uv16_t *deltas);

// Rasterize one edge: returns edge function value (FP16)
uint16_t borg_rasterize_edge(const spirb_shader_t *rast_shader,
                              uint16_t dx_e, uint16_t neg_dy_e,
                              uint16_t dpx_e, uint16_t dpy_e);

// Fragment shader: compute one interpolated channel
uint16_t borg_frag_channel(const spirb_shader_t *frag_shader,
    uint16_t e0, uint16_t e1, uint16_t e2,
    uint16_t inv_area, uint16_t c0, uint16_t c1, uint16_t c2);

// Full barycentric rasterization: edge test + fragment shading for one pixel
int borg_bary_rgb(const spirb_shader_t *rast_shader,
    const spirb_shader_t *frag_shader,
    const uv16_t *edges, const uv16_t *deltas,
    uint16_t inv_area, uint16_t colors[3][3],
    const uint16_t z_vals[3],
    const uv16_t uvs[3],
    rgb16_t *color_out,
    uint16_t *z_out, uv16_t *uv_out);

// UV to texel coordinate conversion
texcoord_t uv_to_texcoord(uv16_t uv, uint16_t w_fp16, uint16_t h_fp16);
