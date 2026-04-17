// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Borg PSRAM layout — pure arithmetic macros shared between firmware and
// simulator.  No MMIO, no hardware registers, no volatile pointers.
//
// Memory map (PSRAM byte addresses):
//   0x1000 .. 0x100F  PSRAM_IN parameters (width, height, rot_x, rot_y)
//   0x1080 .. 0x81080 Texture data (256×256 Morton-packed, 512 KB)
//   0x81080 .. end    Framebuffer, Z-buffer, DONE marker  (PSRAM_OUT)
//
// The texture is placed BEFORE the framebuffer so its byte address
// always fits in the 16-bit TEX_CONFIG.base_addr hardware register,
// regardless of framebuffer resolution.

#pragma once

// --- PSRAM address constants ---
#define PSRAM_SPI_BASE    0x001000    // 24-bit SPI/QSPI address

// Texture occupies 256×256 × 8 bytes = 524288 bytes starting right after
// the PSRAM_IN parameter area (128 bytes reserved for parameters).
#define TEX_PSRAM_BYTE_ADDR_FIXED  (PSRAM_SPI_BASE + 128)  // = 0x1080

// Framebuffer starts after the texture region.
// 128 (params) + 524288 (texture) = 524416 bytes from PSRAM_SPI_BASE.
#define PSRAM_OUT_OFFSET  524416      // Byte offset: PSRAM_OUT base = PSRAM_BASE + 524416
