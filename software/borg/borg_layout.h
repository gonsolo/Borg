// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Borg PSRAM layout — pure arithmetic macros shared between firmware and
// simulator.  No MMIO, no hardware registers, no volatile pointers.
//
// All address constants are DERIVED from the base anchors below — edit the
// anchors, not the derived values, so nothing drifts out of sync.
//
// Memory map (PSRAM SPI byte addresses):
//   0x0000 .. 0x0FFF  (reserved: SPI address 0 maps to CPU PSRAM_BASE)
//   0x1000 .. 0x100F  PSRAM_IN parameters (width, height, rot_x, rot_y)
//   0x0000 .. 0x4260  Firmware .data/.bss/.uninitialized_data (linker-placed)
//   0x4800 .. 0x49FF  Sequencer shaders (vert, setup, rast, frag — 4×128B)
//   SEQ_DESC_BASE_ADDR .. SEQ_DESC_END  Sequencer descriptors (SEQ_MAX_DRAWS × SEQ_DESC_STRIDE)
//   TEX_PSRAM_BYTE_ADDR_FIXED .. +TEX_REGION_BYTES  Texture (Morton-packed FP16)
//   PSRAM_OUT_BASE_SPI ..         Framebuffer, Z-buffer, DONE marker (PSRAM_OUT)

#pragma once

// -------------------------------------------------------------------------
// Anchor constants — change these when the layout shifts.
// -------------------------------------------------------------------------

#define PSRAM_SPI_BASE        0x001000  // 24-bit SPI byte address of PSRAM word 0

// Maximum triangles buffered per frame.  This drives the descriptor window size
// and therefore the texture start address — change it and everything else
// (TEX_PSRAM_BYTE_ADDR_FIXED, PSRAM_OUT_OFFSET) adjusts automatically.
#define SEQ_MAX_DRAWS         12        // max draw calls / triangles per frame

// -------------------------------------------------------------------------
// Sequencer PSRAM layout (Step 29.5)
// -------------------------------------------------------------------------

#define SEQ_VERT_SHADER_ADDR  0x4800   // SPI byte addr for vertex shader  (max 128B)
#define SEQ_SETUP_SHADER_ADDR 0x4880   // SPI byte addr for setup shader   (max 128B)
#define SEQ_RAST_SHADER_ADDR  0x4900   // SPI byte addr for rast shader    (max 128B)
#define SEQ_FRAG_SHADER_ADDR  0x4980   // SPI byte addr for frag shader    (max 128B)
#define SEQ_DESC_BASE_ADDR    0x4A00   // SPI byte addr for descriptor 0

// Descriptor layout: 3 verts × 32B + 64B MVP + 32B metadata = 256B each.
#define SEQ_DESC_STRIDE       256
#define SEQ_MVP_OFFSET        96       // byte offset to 16 MVP FP16 words (64B)
#define SEQ_META_OFFSET       160      // byte offset to bbox + flags (32B)

// End of descriptor region (exclusive) — derived, do not edit.
#define SEQ_DESC_END          (SEQ_DESC_BASE_ADDR + SEQ_MAX_DRAWS * SEQ_DESC_STRIDE)

// -------------------------------------------------------------------------
// Texture region — starts immediately after descriptors.
// -------------------------------------------------------------------------

// TEX_PSRAM_BYTE_ADDR_FIXED is DERIVED from SEQ_DESC_END.
// Currently: 0x4A00 + 12 × 256 = 0x4A00 + 0xC00 = 0x5600.
#define TEX_PSRAM_BYTE_ADDR_FIXED  SEQ_DESC_END

// Maximum texture size (256×256 texels, 8 bytes each = 2×FP16 words/texel).
#define TEX_REGION_BYTES      (256 * 256 * 8)   // 0x80000 = 512 KB

// -------------------------------------------------------------------------
// Framebuffer region — starts immediately after texture.
// -------------------------------------------------------------------------

// PSRAM_OUT_BASE_SPI = TEX_PSRAM_BYTE_ADDR_FIXED + TEX_REGION_BYTES.
// Currently: 0x5600 + 0x80000 = 0x85600.
#define PSRAM_OUT_BASE_SPI    (TEX_PSRAM_BYTE_ADDR_FIXED + TEX_REGION_BYTES)

// PSRAM_OUT(n) / PSRAM_OUT_SPI(n) use this byte offset from PSRAM_SPI_BASE.
// Currently: 0x85600 - 0x1000 = 0x84600.
#define PSRAM_OUT_OFFSET      (PSRAM_OUT_BASE_SPI - PSRAM_SPI_BASE)

// -------------------------------------------------------------------------
// TBR geometry data (Step 32.0) — placed AFTER the framebuffer at runtime.
// -------------------------------------------------------------------------

// Triangle indices are uint16_t so the bin-list can address up to 65535 triangles.
#define SEQ_MAX_TRI           1024  // compile-time triangle capacity
#define SEQ_MAX_TILES         1024  // max tiles per frame (matches BORG_MAX_TILES)

// Per-tile bin list: one row of SEQ_MAX_TRI uint16_t entries per tile.
#define TBR_BIN_ENTRY_SIZE    2                              // sizeof(uint16_t)
#define TBR_BIN_ROW_BYTES     (SEQ_MAX_TRI * TBR_BIN_ENTRY_SIZE)

// Per-triangle setup store: 128 bytes per triangle (31 uniforms × 4B, rounded up).
// Hardware sStoreSetup: addr = setupBase + (triIdx << 7).
#define TBR_SETUP_ENTRY_BYTES 128

// TBR_BIN_BASE and TBR_SETUP_BASE are computed at runtime (depend on fb size).
