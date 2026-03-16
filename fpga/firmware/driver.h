// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Borg GPU driver — handles hardware, shaders, rasterization.

#pragma once

#include <stdint.h>

// FP16 constants
#define FP16_ONE  0x3C00
#define FP16_HALF 0x3800
#define FP16_ZERO 0x0000

// Vertex with position and per-vertex color (all FP16)
typedef struct {
    uint16_t pos[2];    // x, y
    uint16_t color[3];  // r, g, b
} borg_vertex_t;

// Framebuffer dimensions (set at runtime from host)
extern int borg_fb_width;
extern int borg_fb_height;
#define BORG_FB_WIDTH  borg_fb_width
#define BORG_FB_HEIGHT borg_fb_height
#define BORG_MAX_FB_DIM 64

// Draw state (uniforms computed from angle)
typedef struct {
    uint16_t uniforms[16];
} borg_draw_data_t;

// Initialize hardware, parse embedded shader blobs, read resolution from PSRAM
void borg_init(const uint8_t *vert_blob, unsigned int vert_len,
               const uint8_t *rast_blob, unsigned int rast_len,
               const uint8_t *frag_blob, unsigned int frag_len);

// Set up draw data from a rotation angle (FP16 radians)
void borg_set_angle(borg_draw_data_t *d, uint16_t angle_fp16);

// Render a triangle: vertex shade → rasterize → fragment shade → framebuffer
void borg_cmd_draw(const borg_draw_data_t *d, const borg_vertex_t vertices[3], int frame);

// Write DONE marker for a frame to PSRAM
void borg_present(int frame);
