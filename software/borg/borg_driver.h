// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg GPU driver — handles hardware, shaders, rasterization.

#pragma once

#include <stdint.h>
#include "borg_fpu.h"
#include "borg_raster.h"
#include "borg_regs.h"   // IWYU pragma: keep — borg_gpu_t used in BORG_GPU macro
#include "borg_sys.h"    // BORG_BASE and system constants
#define BORG_GPU ((volatile borg_gpu_t*)(uintptr_t) BORG_BASE)

// FP16 constants from borg_fpu.h (single source of truth)

// Vertex with position, color, and optional UV (all FP16)
typedef struct {
    fp16_t pos[3];    // x, y, z
    fp16_t color[3];  // r, g, b
    fp16_t uv[2];     // u, v texture coordinates (0..1)
} borg_vertex_t;

// Framebuffer dimensions (set at runtime from host)
extern int borg_fb_width;
extern int borg_fb_height;
#define BORG_FB_WIDTH  borg_fb_width
#define BORG_FB_HEIGHT borg_fb_height
#define BORG_MAX_FB_DIM 512

// Draw state (uniforms computed from angle)
typedef struct {
    fp16_t uniforms[16];
} borg_draw_data_t;

// Shader module — mirrors Vulkan's VkShaderModule.
// Created from compiled SPIR-B bytecode via borgCreateShaderModule().
typedef struct {
    const uint8_t *code;
    unsigned int codeSize;
} BorgShaderModule;

// Create a shader module from compiled SPIR-B bytecode.
// Mirrors vkCreateShaderModule().
static inline void borgCreateShaderModule(BorgShaderModule *module,
                                          const uint8_t *code,
                                          unsigned int codeSize) {
    module->code = code;
    module->codeSize = codeSize;
}

// Initialize hardware: UART, PSRAM resolution, LUTs.
// Mirrors vkCreateDevice().
void borgCreateDevice(void);

// Parse and bind shader modules to the pipeline stages.
// Mirrors vkCreateGraphicsPipelines().
void borgCreateGraphicsPipeline(const BorgShaderModule *vert,
                                const BorgShaderModule *rast,
                                const BorgShaderModule *frag);

// Set up draw data from a rotation angle (FP16 radians)
void borg_set_angle(borg_draw_data_t *d, fp16_t angle_fp16);

// Clear z-buffer for a frame to FP16_MAX_DEPTH
void borg_clear_zbuffer(int frame, rgb16_t clear_color);

// Set texture for subsequent draw calls (dimensions only; address is fixed)
void borg_set_texture(int tex_width, int tex_height);

// Disable texturing for subsequent draw calls
void borg_clear_texture(void);

// Upload a row-major RGB-FP16 texture (dim×dim, 6 bytes/texel) into the GPU
// texture region, Morton-encoded into the hardware's 2-word layout.  Needed on
// targets with no host to preload SDRAM (e.g. ULX3S); mirrors the simulator's
// load_texture_to_psram().
void borg_upload_texture(const uint8_t *rgb_fp16, int dim);

// Render a triangle: vertex shade → rasterize → z-test → fragment shade → framebuffer.
// Mirrors vkCmdDraw().
void borgCmdDraw(const borg_draw_data_t *d, const borg_vertex_t vertices[3], int frame);
// Vertex-dedup fast path: transform unique positions once, draw by index.
void borgTransformVerts(const borg_draw_data_t *d, const fp16_t *positions, int count);
void borgCmdDrawIndexed(const int idx[3], const borg_vertex_t vertices[3], int frame);

// Write DONE marker for a frame to PSRAM
void borg_present(int frame);

// Blocking UART byte transmit (defined in borg_driver.c).  Polls the SoC
// debug-UART busy bit, then writes the byte to UART_TX.
void putc_uart(int c);
void puts_uart(const char *s);
int  uart_rx_ready(void);
int  getc_uart(void);

// Read the RISC-V free-running cycle counter (Hutt `cycle` CSR, 0xC00).
static inline unsigned int rdcycle(void) {
  unsigned int c;
  __asm__ volatile("csrr %0, cycle" : "=r"(c));
  return c;
}
