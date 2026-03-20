// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// vkcube — 12-triangle gray cube with 3D rotation and directional lighting.
// Rotation: Ry(35°) then Rx(-25°) gives isometric view (front, top, right).
// Light direction ~(0.45, 0.72, -0.54).  Gray base color.

#include "borg_driver.h"
#include "borg_mmio.h"
#include "compiler/shader_blobs.h"

#define NO_UV { FP16_ZERO, FP16_ZERO }

// 8 pre-rotated vertices: Ry(35°) * Rx(-25°) at ±0.4.
// Z = rotated depth mapped to [0.1, 0.9] (0=near, 1=far).
#define V0  0xB875, 0xB677, 0x3862   // (-0.557, -0.404, z=0.548)
#define V1  0x2E49, 0xB8C9, 0x34C7   // (+0.098, -0.598, z=0.298)
#define V2  0x2E49, 0x3011, 0x2E1F   // (+0.098, +0.127, z=0.096)
#define V3  0xB875, 0x3523, 0x3586   // (-0.557, +0.321, z=0.345)
#define V4  0xAE49, 0xB011, 0x3B3C   // (-0.098, -0.127, z=0.904)
#define V5  0x3875, 0xB523, 0x393D   // (+0.557, -0.321, z=0.655)
#define V6  0x3875, 0x3677, 0x373B   // (+0.557, +0.404, z=0.452)
#define V7  0xAE49, 0x38C9, 0x399D   // (-0.098, +0.598, z=0.702)

// Gray lit per-face colors (N dot L + ambient)
// Top:   0.87 brightness -> gray(0.87, 0.87, 0.87)
// Front: 0.69 brightness -> gray(0.69, 0.69, 0.69)
// Right: 0.60 brightness -> gray(0.60, 0.60, 0.60)
// Dim:   0.15 brightness -> gray(0.15, 0.15, 0.15)
#define COL_TOP    { 0x3AF5, 0x3AF5, 0x3AF5 }  // 0.87
#define COL_FRONT  { 0x3986, 0x3986, 0x3986 }  // 0.69
#define COL_RIGHT  { 0x38CD, 0x38CD, 0x38CD }  // 0.60
#define COL_DIM    { 0x30CD, 0x30CD, 0x30CD }  // 0.15

// Draw only the 3 VISIBLE faces (6 triangles). Backfaces have area > 0
// and are rejected by the rasterizer anyway, but skipping them saves time.
static const borg_vertex_t cube_tris[6][3] = {
    // Front face (z- normal)
    { { .pos={V0}, .color=COL_FRONT, .uv=NO_UV },
      { .pos={V3}, .color=COL_FRONT, .uv=NO_UV },
      { .pos={V2}, .color=COL_FRONT, .uv=NO_UV } },
    { { .pos={V0}, .color=COL_FRONT, .uv=NO_UV },
      { .pos={V2}, .color=COL_FRONT, .uv=NO_UV },
      { .pos={V1}, .color=COL_FRONT, .uv=NO_UV } },

    // Right face (x+ normal)
    { { .pos={V1}, .color=COL_RIGHT, .uv=NO_UV },
      { .pos={V2}, .color=COL_RIGHT, .uv=NO_UV },
      { .pos={V6}, .color=COL_RIGHT, .uv=NO_UV } },
    { { .pos={V1}, .color=COL_RIGHT, .uv=NO_UV },
      { .pos={V6}, .color=COL_RIGHT, .uv=NO_UV },
      { .pos={V5}, .color=COL_RIGHT, .uv=NO_UV } },

    // Top face (y+ normal)
    { { .pos={V3}, .color=COL_TOP, .uv=NO_UV },
      { .pos={V7}, .color=COL_TOP, .uv=NO_UV },
      { .pos={V6}, .color=COL_TOP, .uv=NO_UV } },
    { { .pos={V3}, .color=COL_TOP, .uv=NO_UV },
      { .pos={V6}, .color=COL_TOP, .uv=NO_UV },
      { .pos={V2}, .color=COL_TOP, .uv=NO_UV } },
};

int main() {
    borg_init(vert_borg, vert_borg_len,
              rasterize_borg, rasterize_borg_len,
              frag_borg, frag_borg_len);

    // Vertex shader = identity (no additional 2D rotation)
    borg_draw_data_t draw;
    draw.uniforms[0] = FP16_ZERO;   // sin(0) = 0
    draw.uniforms[1] = FP16_ONE;    // cos(0) = 1
    draw.uniforms[2] = FP16_ZERO;   // -sin(0) = 0

    borg_clear_zbuffer(0);

    for (int i = 0; i < 6; i++) {
        borg_cmd_draw(&draw, cube_tris[i], 0);
        while (UART_STATUS & 1)
            ;
        UART_TX = '0' + i;
    }

    borg_present(0);

    while (UART_STATUS & 1)
        ;
    UART_TX = 'X';

    while (1)
        ;
    return 0;
}
