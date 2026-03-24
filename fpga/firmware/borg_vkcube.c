// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// vkcube — textured cube with Borg logo on all visible faces.
// Rotation: Ry(35°) then Rx(-25°) gives isometric view (front, top, right).

#include "borg_driver.h"
#include "borg_mmio.h"
#include "compiler/shader_blobs.h"

// UV corner macros for texture mapping
#define UV_00 { FP16_ZERO, FP16_ZERO }  // top-left
#define UV_10 { FP16_ONE,  FP16_ZERO }  // top-right
#define UV_01 { FP16_ZERO, FP16_ONE  }  // bottom-left
#define UV_11 { FP16_ONE,  FP16_ONE  }  // bottom-right

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

// White vertex color — texture provides the actual color
#define COL_WHITE  { FP16_ONE, FP16_ONE, FP16_ONE }

// Texture dimensions
#define TEX_WIDTH  32
#define TEX_HEIGHT 32

// Draw only the 3 VISIBLE faces (6 triangles). Backfaces have area > 0
// and are rejected by the rasterizer anyway, but skipping them saves time.
// UV mapping: each face gets the full texture (0,0)→(1,0)→(1,1)→(0,1).
static const borg_vertex_t cube_tris[6][3] = {
    // Front face (V0=TL, V3=BL, V2=BR, V1=TR)
    { { .pos={V0}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V3}, .color=COL_WHITE, .uv=UV_01 },
      { .pos={V2}, .color=COL_WHITE, .uv=UV_11 } },
    { { .pos={V0}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V2}, .color=COL_WHITE, .uv=UV_11 },
      { .pos={V1}, .color=COL_WHITE, .uv=UV_10 } },

    // Right face (V1=TL, V2=BL, V6=BR, V5=TR)
    { { .pos={V1}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V2}, .color=COL_WHITE, .uv=UV_01 },
      { .pos={V6}, .color=COL_WHITE, .uv=UV_11 } },
    { { .pos={V1}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V6}, .color=COL_WHITE, .uv=UV_11 },
      { .pos={V5}, .color=COL_WHITE, .uv=UV_10 } },

    // Top face (V3=TL, V7=BL, V6=BR, V2=TR)
    { { .pos={V3}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V7}, .color=COL_WHITE, .uv=UV_01 },
      { .pos={V6}, .color=COL_WHITE, .uv=UV_11 } },
    { { .pos={V3}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V6}, .color=COL_WHITE, .uv=UV_11 },
      { .pos={V2}, .color=COL_WHITE, .uv=UV_10 } },
};

int main() {
    int done_idx = 0 * (BORG_FB_WIDTH * BORG_FB_HEIGHT * 3 + BORG_FB_WIDTH * BORG_FB_HEIGHT + 7) + (BORG_FB_WIDTH * BORG_FB_HEIGHT * 3) + (BORG_FB_WIDTH * BORG_FB_HEIGHT);
    ((volatile uint16_t *)(0x44000000))[done_idx] = 0xBEEF;

    borg_init(vert_borg, vert_borg_len,
              rasterize_borg, rasterize_borg_len,
              frag_borg, frag_borg_len);

    // Vertex shader = identity (Phase 2 4x4 MVP)
    borg_draw_data_t draw;
    
    // Col 0
    draw.uniforms[0] = FP16_ONE;
    draw.uniforms[1] = FP16_ZERO;
    draw.uniforms[2] = FP16_ZERO;
    draw.uniforms[3] = FP16_ZERO;

    // Col 1
    draw.uniforms[4] = FP16_ZERO;
    draw.uniforms[5] = FP16_ONE;
    draw.uniforms[6] = FP16_ZERO;
    draw.uniforms[7] = FP16_ZERO;

    // Col 2
    draw.uniforms[8] = FP16_ZERO;
    draw.uniforms[9] = FP16_ZERO;
    draw.uniforms[10] = FP16_ONE;
    draw.uniforms[11] = FP16_ZERO;

    // Col 3
    draw.uniforms[12] = FP16_ZERO;
    draw.uniforms[13] = FP16_ZERO;
    draw.uniforms[14] = FP16_ZERO;
    draw.uniforms[15] = FP16_ONE;

    borg_clear_zbuffer(0);

    // Enable Borg texture for all faces
    borg_set_texture(TEX_PSRAM_OFFSET, TEX_WIDTH, TEX_HEIGHT);

    for (int i = 0; i < 6; i++) {
        borg_cmd_draw(&draw, cube_tris[i], 0);
    }

    borg_clear_texture();
    borg_present(0);

    while (1)
        ;
    return 0;
}
