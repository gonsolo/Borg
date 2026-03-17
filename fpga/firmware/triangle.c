// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Simple Vulkan-like triangle application.
// Renders a single frame of a colored triangle.
// 100% self-contained: shader blobs embedded in firmware binary.

#include "driver.h"
#include "borg_math.h"
#include "compiler/shader_blobs.h"

// FP16 36° in radians ≈ 0.6283
#define FP16_36DEG 0x3909

// @doc:triangle-app
// Front triangle: position (x, y, z) + color (r, g, b) in FP16
// Positions in normalized coordinates [-1, 1], driver scales to screen space
#define FP16_Z_NEAR 0x3266  // 0.2
#define FP16_Z_FAR  0x3A66  // 0.8

const borg_vertex_t front_tri[3] = {
    { .pos = { 0x0000, 0xB8CD, FP16_Z_NEAR }, .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO },
      .uv = { FP16_ZERO, FP16_ZERO } },
    { .pos = { 0xB8CD, 0x38CD, FP16_Z_NEAR }, .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO },
      .uv = { FP16_ZERO, FP16_ZERO } },
    { .pos = { 0x38CD, 0x38CD, FP16_Z_NEAR }, .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  },
      .uv = { FP16_ZERO, FP16_ZERO } },
};

// Back triangle: larger, textured, behind the front one
// UV maps: top vertex → (0.5, 0), bottom-left → (0, 1), bottom-right → (1, 1)
const borg_vertex_t back_tri[3] = {
    { .pos = { 0x0000, 0xBCCD, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ZERO, FP16_ZERO },
      .uv = { FP16_HALF, FP16_ZERO } },
    { .pos = { 0xBCCD, 0x3CCD, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ZERO, FP16_ZERO },
      .uv = { FP16_ZERO, FP16_ONE } },
    { .pos = { 0x3CCD, 0x3CCD, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ZERO, FP16_ZERO },
      .uv = { FP16_ONE, FP16_ONE } },
};

// Texture at PSRAM_IN offset past the framebuffer output region.
// PSRAM_OUT(n) = PSRAM_IN(n+32), framebuffer uses PSRAM_OUT 0..4096,
// so PSRAM_IN 32..4128 is occupied. Place texture well past that.
#define TEX_PSRAM_OFFSET 4200
#define TEX_WIDTH  32
#define TEX_HEIGHT 32

int main() {
    borg_init(vert_borg, vert_borg_len,
              rasterize_borg, rasterize_borg_len,
              frag_borg, frag_borg_len);

    borg_draw_data_t draw;
    borg_set_angle(&draw, FP16_36DEG);

    borg_clear_zbuffer(0);
    borg_cmd_draw(&draw, front_tri, 0);  // draw front (RGB) — no texture
    borg_set_texture(TEX_PSRAM_OFFSET, TEX_WIDTH, TEX_HEIGHT);
    borg_cmd_draw(&draw, back_tri, 0);   // draw back (textured)
    borg_clear_texture();
    borg_present(0);

    while (1)
        ;
    return 0;
}
// @doc:end
