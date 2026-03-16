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

// Triangle vertices: position (x, y) + color (r, g, b) in FP16
// Positions in normalized coordinates [-1, 1], driver scales to screen space
const borg_vertex_t vertices[3] = {
    { .pos = { 0x0000, 0xB8CD }, .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO } },  // ( 0.0, -0.6) red
    { .pos = { 0xB8CD, 0x38CD }, .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO } },  // (-0.6,  0.6) green
    { .pos = { 0x38CD, 0x38CD }, .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  } },  // ( 0.6,  0.6) blue
};

int main() {
    borg_init(vert_borg, vert_borg_len,
              rasterize_borg, rasterize_borg_len,
              frag_borg, frag_borg_len);

    borg_draw_data_t draw;
    borg_set_angle(&draw, FP16_36DEG);

    borg_cmd_draw(&draw, vertices, 0);
    borg_present(0);

    while (1)
        ;
    return 0;
}
