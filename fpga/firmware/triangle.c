// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Simple Vulkan-like triangle application.
// Renders a single frame of a colored triangle at a fixed rotation.

#include "driver.h"
#include "borg_math.h"

// FP16 36° in radians ≈ 0.6283
#define FP16_36DEG 0x3909

// Triangle vertices: position (x, y) + color (r, g, b) in FP16
// Centered at origin, scaled to 60% of half-width (4.8 units)
const borg_vertex_t vertices[3] = {
    { .pos = { 0x0000, 0xC4CD }, .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO } },  // ( 0.0, -4.8) red
    { .pos = { 0xC4CD, 0x44CD }, .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO } },  // (-4.8,  4.8) green
    { .pos = { 0x44CD, 0x44CD }, .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  } },  // ( 4.8,  4.8) blue
};

int main() {
    borg_init();

    // Render a single frame at 36° rotation
    borg_draw_data_t draw;
    borg_set_angle(&draw, FP16_36DEG);

    borg_cmd_draw(&draw, vertices);
    borg_present();

    while (1)
        ;
    return 0;
}
