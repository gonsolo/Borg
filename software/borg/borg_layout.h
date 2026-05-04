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

// --- Sequencer PSRAM layout (Step 29.5) ---
// Placed between BSS end (~0x4260) and texture start (0x5000).
// The sequencer needs: vert shader, setup shader, and per-draw-call vertex
// descriptors (3 vertices × 8 FP16 words × 4 bytes = 96 bytes each).
#define SEQ_VERT_SHADER_ADDR  0x4800  // SPI byte addr for vertex shader (max 224B)
#define SEQ_SETUP_SHADER_ADDR 0x4880  // SPI byte addr for setup shader (max 224B)
#define SEQ_RAST_SHADER_ADDR  0x4900  // SPI byte addr for rast shader (Step 31, max 224B)
#define SEQ_FRAG_SHADER_ADDR  0x4980  // SPI byte addr for frag shader (Step 31, max 224B)
#define SEQ_DESC_BASE_ADDR    0x4A00  // SPI byte addr for vertex descriptors
#define SEQ_DESC_STRIDE       128     // 3 vertices × 8 words × 4 bytes + 32B metadata (Step 31)

// --- TBR geometry data (Step 32.0) ---
// Placed after the framebuffer (PSRAM_OUT_OFFSET + framebuffer region).
//
// Triangle indices are uint16_t (2 bytes each) — supports up to 65 535 triangles.
// (A uint8_t cap of 255 would not be future-proof for thousands of triangles.)
//
// SEQ_MAX_TRI is the compile-time triangle capacity.  All size macros are derived
// from it so there is a single source of truth.
#define SEQ_MAX_TRI             1024  // max triangles per frame (uint16-safe: 0..65535)
#define SEQ_MAX_TILES           1024  // max tiles per frame (matches BORG_MAX_TILES)

// Per-tile bin list: one row of SEQ_MAX_TRI uint16_t entries per tile.
//   Total = SEQ_MAX_TRI * SEQ_MAX_TILES * 2 bytes = 2 MB at 1024×1024.
//   Practical configs are much smaller (e.g. 12 tri × 80 tiles = 1.9 KB).
//   The hardware reads only bin_count[tile] entries; the rest are never accessed.
#define TBR_BIN_ENTRY_SIZE      2     // sizeof(uint16_t) — triangle index
#define TBR_BIN_ROW_BYTES       (SEQ_MAX_TRI * TBR_BIN_ENTRY_SIZE)  // bytes per tile
// Base address written at runtime in borgCreateDevice() after the framebuffer.
// #define TBR_BIN_BASE computed at runtime (depends on framebuffer size)

// Per-triangle setup store: 64 bytes per triangle (u0–u11 = 12×FP16 = 24 B,
// remaining 40 B reserved for future fields: UV interpolators, flags, etc.).
// Stride is a power of 2 so address = TBR_SETUP_BASE + (tri << 6).
#define TBR_SETUP_ENTRY_BYTES   64    // bytes per triangle
// #define TBR_SETUP_BASE computed at runtime (after TBR_BIN region)
