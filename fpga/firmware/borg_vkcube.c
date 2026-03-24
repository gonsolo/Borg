// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// vkcube — textured cube with Borg logo on all visible faces.
// Dynamic GPU Matrix Hardware rendering bounds testing Khronos geometry coordinates directly.

#include "borg_driver.h"
#include "borg_mmio.h"
#include "borg_fpu.h"
#include "borg_math.h"
#include "compiler/shader_blobs.h"


// UV corner macros for texture mapping
#define UV_00 { FP16_ZERO, FP16_ZERO }  // top-left
#define UV_10 { FP16_ONE,  FP16_ZERO }  // top-right
#define UV_01 { FP16_ZERO, FP16_ONE  }  // bottom-left
#define UV_11 { FP16_ONE,  FP16_ONE  }  // bottom-right

#define FP16_N1 0xBC00
#define FP16_P1 0x3C00

// 8 un-rotated Khronos vertices: pure [-1, 1] axes!
#define V0  FP16_N1, FP16_N1, FP16_N1   // Front Top-Left
#define V1  FP16_P1, FP16_N1, FP16_N1   // Front Top-Right
#define V2  FP16_P1, FP16_P1, FP16_N1   // Front Bottom-Right
#define V3  FP16_N1, FP16_P1, FP16_N1   // Front Bottom-Left
#define V4  FP16_N1, FP16_N1, FP16_P1   // Back Top-Left
#define V5  FP16_P1, FP16_N1, FP16_P1   // Back Top-Right
#define V6  FP16_P1, FP16_P1, FP16_P1   // Back Bottom-Right
#define V7  FP16_N1, FP16_P1, FP16_P1   // Back Bottom-Left

// White vertex color — texture provides the actual color
#define COL_WHITE  { FP16_ONE, FP16_ONE, FP16_ONE }

// Texture dimensions
#define TEX_WIDTH  32
#define TEX_HEIGHT 32

// Draw ALL 6 faces (12 triangles) to test Z-buffer depth sorting.
static const borg_vertex_t cube_tris[12][3] = {
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

    // Top face (V4=TL, V0=BL, V1=BR, V5=TR) -- Wait! Y is down so back to front is top?
    // Let's just map Top Face: (V4=TL, V0=BL, V1=BR, V5=TR)
    { { .pos={V4}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V0}, .color=COL_WHITE, .uv=UV_01 },
      { .pos={V1}, .color=COL_WHITE, .uv=UV_11 } },
    { { .pos={V4}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V1}, .color=COL_WHITE, .uv=UV_11 },
      { .pos={V5}, .color=COL_WHITE, .uv=UV_10 } },

    // Back face (V5=TL, V6=BL, V7=BR, V4=TR)
    { { .pos={V5}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V6}, .color=COL_WHITE, .uv=UV_01 },
      { .pos={V7}, .color=COL_WHITE, .uv=UV_11 } },
    { { .pos={V5}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V7}, .color=COL_WHITE, .uv=UV_11 },
      { .pos={V4}, .color=COL_WHITE, .uv=UV_10 } },

    // Left face (V4=TL, V7=BL, V3=BR, V0=TR)
    { { .pos={V4}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V7}, .color=COL_WHITE, .uv=UV_01 },
      { .pos={V3}, .color=COL_WHITE, .uv=UV_11 } },
    { { .pos={V4}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V3}, .color=COL_WHITE, .uv=UV_11 },
      { .pos={V0}, .color=COL_WHITE, .uv=UV_10 } },

    // Bottom face (V3=TL, V7=BL, V6=BR, V2=TR)
    { { .pos={V3}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V7}, .color=COL_WHITE, .uv=UV_01 },
      { .pos={V6}, .color=COL_WHITE, .uv=UV_11 } },
    { { .pos={V3}, .color=COL_WHITE, .uv=UV_00 },
      { .pos={V6}, .color=COL_WHITE, .uv=UV_11 },
      { .pos={V2}, .color=COL_WHITE, .uv=UV_10 } },
};

static void mat4_identity(fp16_t m[16]) {
    for (int i = 0; i < 16; i++) m[i] = FP16_ZERO;
    m[0] = FP16_ONE; m[5] = FP16_ONE; m[10] = FP16_ONE; m[15] = FP16_ONE;
}

static void mat4_mul(fp16_t out[16], const fp16_t a[16], const fp16_t b[16]) {
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            fp16_t sum = FP16_ZERO;
            for (int k = 0; k < 4; k++) {
                fp16_t term = borg_fp16_mul(a[k * 4 + row], b[col * 4 + k]);
                sum = borg_fp16_add(sum, term);
            }
            out[col * 4 + row] = sum;
        }
    }
}

int main() {
    borg_init(vert_borg, vert_borg_len,
              rasterize_borg, rasterize_borg_len,
              frag_borg, frag_borg_len);

    borg_draw_data_t draw;
    fp16_t scale[16], rx[16], ry[16], trans[16];
    fp16_t t1[16], t2[16];

    // Scale (0.4) = 0x3666 (roughly)
    mat4_identity(scale);
    scale[0] = 0x3666; scale[5] = 0x3666; scale[10] = 0x3666;

    // Rx(-25 degrees) -> -0.436 radians (fp16_neg(0x36F8))
    mat4_identity(rx);
    fp16_t ax = fp16_neg(0x36F8);
    fp16_t sx = fp16_sin(ax), cx = fp16_cos(ax);
    rx[5] = cx; rx[9] = fp16_neg(sx);
    rx[6] = sx; rx[10] = cx;

    // Ry(35 degrees) -> 0.610 radians (0x38E1)
    mat4_identity(ry);
    fp16_t ay = 0x38E1;
    fp16_t sy = fp16_sin(ay), cy = fp16_cos(ay);
    ry[0] = cy; ry[8] = sy;
    ry[2] = fp16_neg(sy); ry[10] = cy;

    // Translate Z=0.5 (0x3800)
    mat4_identity(trans);
    trans[14] = 0x3800; // Col 3, Row 2 (Z trans)

    // Compute final MVP = Translate * Scale * Rx * Ry (apply left-to-right on vertices = Ry first)
    // T * S * Rx * Ry
    mat4_mul(t1, rx, ry);
    mat4_mul(t2, scale, t1);
    mat4_mul(draw.uniforms, trans, t2);

    borg_clear_zbuffer(0);

    // Enable Borg texture for all faces
    borg_set_texture(TEX_PSRAM_OFFSET, TEX_WIDTH, TEX_HEIGHT);

    for (int i = 0; i < 12; i++)
        borg_cmd_draw(&draw, cube_tris[i], 0);

    borg_clear_texture();
    borg_present(0);

    while (1)
        ;
    return 0;
}
