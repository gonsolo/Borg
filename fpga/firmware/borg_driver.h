// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg GPU driver — handles hardware, shaders, rasterization.

#pragma once

#include <stdint.h>

// FP16 constants
#define FP16_ONE  0x3C00
#define FP16_HALF 0x3800
#define FP16_ZERO 0x0000
#define FP16_MAX_DEPTH 0x7BFF  // max finite FP16 (65504)

// Vertex with position, color, and optional UV (all FP16)
typedef struct {
    uint16_t pos[3];    // x, y, z
    uint16_t color[3];  // r, g, b
    uint16_t uv[2];     // u, v texture coordinates (0..1)
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

// Clear z-buffer for a frame to FP16_MAX_DEPTH
void borg_clear_zbuffer(int frame);

// Set texture for subsequent draw calls (PSRAM offset, dimensions)
void borg_set_texture(int psram_offset, int width, int height);

// Disable texturing for subsequent draw calls
void borg_clear_texture(void);

// Render a triangle: vertex shade → rasterize → z-test → fragment shade → framebuffer
void borg_cmd_draw(const borg_draw_data_t *d, const borg_vertex_t vertices[3], int frame);

// Write DONE marker for a frame to PSRAM
void borg_present(int frame);
