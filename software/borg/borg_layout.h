// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Borg PSRAM layout — pure arithmetic macros shared between firmware and
// simulator.  No MMIO, no hardware registers, no volatile pointers.
//
// Memory map (PSRAM byte addresses):
//   0x1000 .. 0x100F  PSRAM_IN parameters (width, height, rot_x, rot_y)
//   0x1080 .. 0x3080  Texture data (32×32 Morton-packed, 8 KB)
//   0x3080 .. end     Framebuffer, Z-buffer, DONE marker  (PSRAM_OUT)
//
// The texture is placed BEFORE the framebuffer so its byte address
// always fits in the 16-bit TEX_CONFIG.base_addr hardware register,
// regardless of framebuffer resolution.

#pragma once

// --- PSRAM address constants ---
#define PSRAM_SPI_BASE    0x001000    // 24-bit SPI/QSPI address

// Texture occupies 32×32 × 8 bytes = 8192 bytes starting right after
// the PSRAM_IN parameter area (128 bytes reserved for parameters).
#define TEX_PSRAM_BYTE_ADDR_FIXED  (PSRAM_SPI_BASE + 128)  // = 0x1080

// Framebuffer starts after the texture region.
// 128 (params) + 8192 (texture) = 8320 bytes from PSRAM_SPI_BASE.
#define PSRAM_OUT_OFFSET  8320       // Byte offset: PSRAM_OUT base = PSRAM_BASE + 8320
