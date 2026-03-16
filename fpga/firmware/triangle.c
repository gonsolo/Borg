// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Simple Vulkan-like triangle application.
// Uses the Borg GPU driver to render a single colored triangle.

#include "driver.h"

int main() {
    borg_init();

    borg_draw_data_t draw;
    borg_read_draw_data(&draw);

    borg_cmd_draw(&draw);

    borg_present();

    while (1)
        ;
    return 0;
}
