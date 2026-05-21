// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Simple Vulkan-like triangle application.
// Renders a single frame of a colored triangle.
// 100% self-contained: shader blobs embedded in firmware binary.

#include "borg_driver.h"
#include "borg_math.h"
#include "borg_sys.h"
#include "borg_isa.h"
#include "compiler/shader_blobs.h"

// FP16 vertex position constants (can't use fp16_from_float in const initializers)
#define FP16_POS_055  0x38CD  //  0.55
#define FP16_NEG_055  0xB8CD  // -0.55
#define FP16_POS_120  0x3CCD  //  1.2
#define FP16_NEG_120  0xBCCD  // -1.2
#define FP16_Z_NEAR   0x3266  //  0.2
#define FP16_Z_FAR    0x3A66  //  0.8

// @doc:triangle-app
// Front triangle: small, colored (RGB per-vertex), at Z=0.2
const borg_vertex_t front_tri[3] = {
    { .pos = { FP16_ZERO,    FP16_NEG_055, FP16_Z_NEAR }, .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO },
      .uv = { FP16_ZERO, FP16_ZERO } },
    { .pos = { FP16_NEG_055, FP16_POS_055, FP16_Z_NEAR }, .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO },
      .uv = { FP16_ZERO, FP16_ZERO } },
    { .pos = { FP16_POS_055, FP16_POS_055, FP16_Z_NEAR }, .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  },
      .uv = { FP16_ZERO, FP16_ZERO } },
};

// Back triangle: larger, textured, at Z=0.8
const borg_vertex_t back_tri[3] = {
    { .pos = { FP16_ZERO,    FP16_NEG_120, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ONE, FP16_ONE },
      .uv = { FP16_HALF, FP16_ZERO } },
    { .pos = { FP16_NEG_120, FP16_POS_120, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ONE, FP16_ONE },
      .uv = { FP16_ZERO, FP16_ONE } },
    { .pos = { FP16_POS_120, FP16_POS_120, FP16_Z_FAR }, .color = { FP16_ONE, FP16_ONE, FP16_ONE },
      .uv = { FP16_ONE, FP16_ONE } },
};

#define TEX_WIDTH  32
#define TEX_HEIGHT 32

// Stream the rendered 32×32 framebuffer over UART so the workstation can
// recover the pixels independently of HDMI.  Sync framing: 4-byte magic
// "FBFB" (0x46 0x42 0x46 0x42) + 2-byte width LE + 2-byte height LE +
// W*H*8 bytes of FP16 R/G/B/Z per pixel in tile-major order (matches
// HdmiScanoutFp16's address arithmetic).  Trailer: 4-byte magic "ENDB".
static void dump_fb_uart(int w, int h) {
    // Read the FB at the CPU-mapped PSRAM/VRAM address (PSRAM_OUT_RAW), which
    // carries bit 24 — the region bit the GPU flusher's gpuMem writes go through
    // (VRAM_REGION_BIT).  Reading the raw SPI address instead lands in a
    // different SDRAM region (16 MB away) and shows garbage, not the FB.
    volatile uint8_t *fb =
        (volatile uint8_t *)(uintptr_t)(PSRAM_BASE - PSRAM_SPI_BASE + PSRAM_OUT_SPI(0));
    int n = w * h * 8;   // bytes per frame (FP16 R+G+B+Z = 8 B/pixel)

    putc_uart('F'); putc_uart('B'); putc_uart('F'); putc_uart('B');
    putc_uart(w & 0xFF); putc_uart((w >> 8) & 0xFF);
    putc_uart(h & 0xFF); putc_uart((h >> 8) & 0xFF);
    for (int i = 0; i < n; i++)
        putc_uart(fb[i]);
    putc_uart('E'); putc_uart('N'); putc_uart('D'); putc_uart('B');
}

int main() {
    PSRAM_OUT(0) = 0x1234;
    borgCreateDevice();

    BorgShaderModule vert, rast, frag;
    borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
    borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
    borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
    borgCreateGraphicsPipeline(&vert, &rast, &frag);

    borg_draw_data_t draw;
    borg_set_angle(&draw, fp16_from_float(0.6283f));  // 36 degrees

    borg_clear_zbuffer(0, (rgb16_t){FP16_ZERO, FP16_ZERO, FP16_ZERO});
    borg_set_texture(TEX_WIDTH, TEX_HEIGHT);
    borgCmdDraw(&draw, back_tri, 0);   // draw back (textured)
    borg_clear_texture();
    borgCmdDraw(&draw, front_tri, 0);  // draw front (RGB vertex colors, no texture)
    borg_present(0);

    // borg_present() polls STATUS_REG_T__IDLE so the GPU has finished
    // flushing all tiles to SDRAM before we read them back here.
    // Re-dump in a loop so the workstation can connect at any time without
    // missing the (single) post-render frame.
    while (1) {
        dump_fb_uart(borg_fb_width, borg_fb_height);
        for (volatile int i = 0; i < 2000000; i++)  // ~0.5 s pause @ 25 MHz
            ;
    }
    return 0;
}
// @doc:end
