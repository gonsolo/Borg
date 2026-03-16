// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Simple Vulkan-like triangle application.
// Defines vertex data and renders a single colored triangle.

#include "driver.h"

// Triangle vertices: position (x, y) + color (r, g, b) in FP16
// Centered at origin, scaled to 60% of half-width (4.8 units)
const borg_vertex_t vertices[3] = {
    { .pos = { 0x0000, 0xC4CD }, .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO } },  // ( 0.0, -4.8) red
    { .pos = { 0xC4CD, 0x44CD }, .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO } },  // (-4.8,  4.8) green
    { .pos = { 0x44CD, 0x44CD }, .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  } },  // ( 4.8,  4.8) blue
};

int main() {
    borg_init();

    borg_draw_data_t draw;
    borg_read_draw_data(&draw);

    borg_cmd_draw(&draw, vertices);

    borg_present();

    while (1)
        ;
    return 0;
}
