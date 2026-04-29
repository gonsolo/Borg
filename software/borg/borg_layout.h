// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Borg PSRAM layout — pure arithmetic macros shared between firmware and
// simulator.  No MMIO, no hardware registers, no volatile pointers.
//
// Memory map (PSRAM SPI byte addresses):
//   0x0000 .. 0x0FFF  (reserved: SPI address 0 maps to CPU PSRAM_BASE)
//   0x1000 .. 0x100F  PSRAM_IN parameters (width, height, rot_x, rot_y)
//   0x0000 .. 0x4260  Firmware .data/.bss/.uninitialized_data (linker-placed)
//   0x5000 .. 0x85000 Texture data (256×256 Morton-packed, 512 KB)
//   0x85000 .. end    Framebuffer, Z-buffer, DONE marker  (PSRAM_OUT)
//
// The texture is placed AFTER the firmware BSS section (ends ~0x4260) so
// the C runtime's BSS zeroing doesn't overwrite texture data.

#pragma once

// --- PSRAM address constants ---
#define PSRAM_SPI_BASE    0x001000    // 24-bit SPI/QSPI address

// Texture starts at SPI 0x5000, safely past the firmware BSS end (~0x4260).
// Texture occupies up to 256×256 × 8 bytes = 524288 bytes.
#define TEX_PSRAM_BYTE_ADDR_FIXED  0x5000

// Framebuffer starts after the texture region.
// 0x5000 + 524288 (0x80000) = 0x85000.
#define PSRAM_OUT_OFFSET  0x84000   // Byte offset from PSRAM_SPI_BASE: 0x85000 - 0x1000
