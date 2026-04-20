// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// texture_loader.cpp — single implementation of Morton-encoded texture packing.
// Replaces the triplicated inline loops in BorgSimulator.h and arcilator/main.cpp.

#include "texture_loader.h"

#include <cstdint>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

// Morton (Z-order) interleave helpers — kept local; canonical definition is
// borg_math.h which firmware and nanobind bindings use.
static inline uint32_t morton_interleave(uint32_t x) {
    x = (x | (x << 8)) & 0x00FF00FF;
    x = (x | (x << 4)) & 0x0F0F0F0F;
    x = (x | (x << 2)) & 0x33333333;
    x = (x | (x << 1)) & 0x55555555;
    return x;
}

static inline uint32_t morton_encode(uint32_t x, uint32_t y) {
    return morton_interleave(x) | (morton_interleave(y) << 1);
}

void load_texture_to_psram(uint8_t* psram_mem,
                           const std::string& path,
                           uint32_t tex_dim,
                           uint32_t tex_byte_base) {
    std::ifstream tex_f(path, std::ios::binary);
    if (!tex_f) {
        std::cerr << "[SIM] WARNING: texture file not found: " << path << "\n";
        return;
    }

    uint32_t num_texels = tex_dim * tex_dim;
    std::vector<uint8_t> tex_data(num_texels * 6); // RGB FP16 = 6 bytes/texel
    tex_f.read(reinterpret_cast<char*>(tex_data.data()), tex_data.size());

    // Store texels in packed 2-word (8-byte) format at their Morton index:
    //   Word 0: { G[15:0], R[15:0] }  — little-endian bytes 0-3
    //   Word 1: { pad[15:0], B[15:0] } — little-endian bytes 4-7
    // This matches the hardware sTexFetch read order in BorgRasterizer.scala.
    for (uint32_t y = 0; y < tex_dim; y++) {
        for (uint32_t x = 0; x < tex_dim; x++) {
            uint32_t src_idx = y * tex_dim + x;
            uint32_t dst_idx = morton_encode(x, y);

            uint16_t r = tex_data[(src_idx * 3 + 0) * 2]
                       | (tex_data[(src_idx * 3 + 0) * 2 + 1] << 8);
            uint16_t g = tex_data[(src_idx * 3 + 1) * 2]
                       | (tex_data[(src_idx * 3 + 1) * 2 + 1] << 8);
            uint16_t b = tex_data[(src_idx * 3 + 2) * 2]
                       | (tex_data[(src_idx * 3 + 2) * 2 + 1] << 8);

            uint32_t word0 = static_cast<uint32_t>(r) | (static_cast<uint32_t>(g) << 16);
            uint32_t word1 = static_cast<uint32_t>(b);

            uint32_t byte_addr = tex_byte_base + dst_idx * 8;
            psram_mem[byte_addr + 0] = word0 & 0xFF;
            psram_mem[byte_addr + 1] = (word0 >> 8) & 0xFF;
            psram_mem[byte_addr + 2] = (word0 >> 16) & 0xFF;
            psram_mem[byte_addr + 3] = (word0 >> 24) & 0xFF;
            psram_mem[byte_addr + 4] = word1 & 0xFF;
            psram_mem[byte_addr + 5] = (word1 >> 8) & 0xFF;
            psram_mem[byte_addr + 6] = (word1 >> 16) & 0xFF;
            psram_mem[byte_addr + 7] = (word1 >> 24) & 0xFF;
        }
    }

    std::cout << "[SIM] Texture loaded: " << path
              << " at PSRAM byte 0x" << std::hex << tex_byte_base
              << " (" << std::dec << tex_dim << "x" << tex_dim << ")\n";
}
