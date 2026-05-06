// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Simple Vulkan-like triangle application.
// Renders a single frame of a colored triangle.
// 100% self-contained: shader blobs embedded in firmware binary.

#include "borg_driver.h"
#include "borg_math.h"
#include "borg_sys.h"
#include "borg_isa.h"
#include "compiler/shader_blobs.h"

// FP16 vertex position constants (can't use fp16_from_float in const initializers)
#define FP16_POS_055  0x38CD  //  0.55
#define FP16_NEG_055  0xB8CD  // -0.55
#define FP16_POS_120  0x3CCD  //  1.2
#define FP16_NEG_120  0xBCCD  // -1.2
#define FP16_Z_NEAR   0x3266  //  0.2
#define FP16_Z_FAR    0x3A66  //  0.8

// @doc:triangle-app
// Front triangle: small, colored (RGB per-vertex), at Z=0.2
const borg_vertex_t front_tri[3] = {
    { .pos = { FP16_ZERO,    FP16_NEG_055, FP16_Z_NEAR }, .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO },
      .uv = { FP16_ZERO, FP16_ZERO } },
    { .pos = { FP16_NEG_055, FP16_POS_055, FP16_Z_NEAR }, .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO },
      .uv = { FP16_ZERO, FP16_ZERO } },
    { .pos = { FP16_POS_055, FP16_POS_055, FP16_Z_NEAR }, .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  },
      .uv = { FP16_ZERO, FP16_ZERO } },
};

// Back triangle: larger, textured, at Z=0.8
const borg_vertex_t back_tri[3] = {
    { .pos = { FP16_ZERO,    FP16_NEG_120, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ONE, FP16_ONE },
      .uv = { FP16_HALF, FP16_ZERO } },
    { .pos = { FP16_NEG_120, FP16_POS_120, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ONE, FP16_ONE },
      .uv = { FP16_ZERO, FP16_ONE } },
    { .pos = { FP16_POS_120, FP16_POS_120, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ONE, FP16_ONE },
      .uv = { FP16_ONE, FP16_ONE } },
};

#define TEX_WIDTH  32
#define TEX_HEIGHT 32

int main() {
    PSRAM_OUT(0) = 0x1234;
    borgCreateDevice();

    BorgShaderModule vert, rast, frag;
    borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
    borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
    borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
    borgCreateGraphicsPipeline(&vert, &rast, &frag);

    borg_draw_data_t draw;
    borg_set_angle(&draw, fp16_from_float(0.6283f));  // 36 degrees

    borg_clear_zbuffer(0, (rgb16_t){FP16_ZERO, FP16_ZERO, FP16_ZERO});
    borg_set_texture(TEX_WIDTH, TEX_HEIGHT);
    borgCmdDraw(&draw, back_tri, 0);   // draw back (textured)
    borg_clear_texture();
    borgCmdDraw(&draw, front_tri, 0);  // draw front (RGB vertex colors, no texture)
    borg_present(0);

    while (1)
        ;
    return 0;
}
// @doc:end
