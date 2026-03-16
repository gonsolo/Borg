// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Simple Vulkan-like triangle application.
// Defines vertex data and renders a single colored triangle.

#include "driver.h"

const borg_vertex_t vertices[3] = {
    { .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO } },  // red
    { .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO } },  // green
    { .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  } },  // blue
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
