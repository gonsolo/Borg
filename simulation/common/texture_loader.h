// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// texture_loader.h — single source of truth for Morton-encoded texture upload
// into the simulator's PSRAM byte array.
//
// Format: each texel is stored as 2 little-endian 32-bit words at Morton index:
//   Word 0: { G[15:0], R[15:0] }  (bytes 0-3)
//   Word 1: { pad[15:0], B[15:0] }  (bytes 4-7)
// This matches the hardware sTexFetch format expected by BorgRasterizer.scala.

#pragma once
#include <cstdint>
#include <string>

// Load a raw FP16-RGB texture file into a PSRAM byte array in Morton order.
//
//   psram_mem    — pointer to the simulator's PSRAM backing store
//   path         — path to the .dat file (RGB FP16, linear row-major order)
//   tex_dim      — texture width/height in texels (must be power of 2)
//   tex_byte_base — byte offset in psram_mem where the texture is placed
//                   (typically TEX_PSRAM_BYTE_ADDR_FIXED from borg_layout.h)
void load_texture_to_psram(uint8_t* psram_mem,
                           const std::string& path,
                           uint32_t tex_dim,
                           uint32_t tex_byte_base);
