// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Borg GPU driver — handles hardware, shaders, rasterization.

#pragma once

#include <stdint.h>

// FP16 constants
#define FP16_ONE  0x3C00
#define FP16_HALF 0x3800
#define FP16_ZERO 0x0000

// Vertex with per-vertex color (RGB in FP16)
typedef struct {
    uint16_t color[3];  // r, g, b
} borg_vertex_t;

// Framebuffer dimensions
#define BORG_FB_WIDTH  16
#define BORG_FB_HEIGHT 16

// Draw data read from PSRAM (populated by borg_read_draw_data)
typedef struct {
    uint16_t uniforms[16];
    uint16_t attrs[3 * 16];   // 3 vertices × max 16 attributes
    uint16_t inv_area;
} borg_draw_data_t;

// Initialize hardware (UART, parse shaders from PSRAM)
void borg_init(void);

// Read draw data from PSRAM (uniforms, vertex attributes, inv_area, colors)
void borg_read_draw_data(borg_draw_data_t *d);

// Render a triangle: vertex shade → rasterize → fragment shade → framebuffer
void borg_cmd_draw(const borg_draw_data_t *d, const borg_vertex_t vertices[3]);

// Write DONE marker to PSRAM
void borg_present(void);
