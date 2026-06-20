// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Borg DRAM layout — pure arithmetic macros shared between firmware and
// simulator.  No MMIO, no hardware registers, no volatile pointers.
//
// All address constants are DERIVED from the base anchors below — edit the
// anchors, not the derived values, so nothing drifts out of sync.
//
// Memory map (DRAM SPI byte addresses):
//   0x0000 .. 0x0FFF  (reserved: SPI address 0 maps to CPU DRAM_BASE)
//   0x1000 .. 0x100F  DRAM_IN parameters (width, height, rot_x, rot_y)
//   0x0000 .. 0x4260  Firmware .data/.bss/.uninitialized_data (linker-placed)
//   0x4800 .. 0x49FF  Sequencer shaders (vert, setup, rast, frag — 4×128B)
//   SEQ_DESC_BASE_ADDR .. SEQ_DESC_END  Sequencer descriptors (SEQ_MAX_DRAWS × SEQ_DESC_STRIDE)
//   TEX_DRAM_BYTE_ADDR_FIXED .. +TEX_REGION_BYTES  Texture (Morton-packed FP16)
//   DRAM_OUT_BASE_SPI ..         Framebuffer, Z-buffer, DONE marker (DRAM_OUT)

#pragma once

// -------------------------------------------------------------------------
// Anchor constants — change these when the layout shifts.
// -------------------------------------------------------------------------

#define DRAM_SPI_BASE        0x001000  // 24-bit SPI byte address of DRAM word 0

// Maximum triangles buffered per frame.  This drives the descriptor window size
// and therefore the texture start address — change it and everything else
// (TEX_DRAM_BYTE_ADDR_FIXED, DRAM_OUT_OFFSET) adjusts automatically.
#define SEQ_MAX_DRAWS         12        // max draw calls / triangles per frame

// -------------------------------------------------------------------------
// Sequencer DRAM layout (Step 29.5)
// -------------------------------------------------------------------------

#define SEQ_VERT_SHADER_ADDR  0x4800   // SPI byte addr for vertex shader  (max 128B)
#define SEQ_SETUP_SHADER_ADDR 0x4880   // SPI byte addr for setup shader   (max 128B)
#define SEQ_RAST_SHADER_ADDR  0x4900   // SPI byte addr for rast shader    (max 128B)
#define SEQ_FRAG_SHADER_ADDR  0x4980   // SPI byte addr for frag shader    (max 256B = 64 words)
#define SEQ_DESC_BASE_ADDR    0x4A80   // SPI byte addr for descriptor 0 (moved +0x80 for the
                                       // borgc 56-word frag; TEX/DRAM_OUT derive from here)

// Descriptor layout: 3 verts × 32B + 64B MVP + 32B metadata = 256B each.
#define SEQ_DESC_STRIDE       256
#define SEQ_MVP_OFFSET        96       // byte offset to 16 MVP FP16 words (64B)
#define SEQ_META_OFFSET       160      // byte offset to bbox + flags (32B)

// End of descriptor region (exclusive) — derived, do not edit.
#define SEQ_DESC_END          (SEQ_DESC_BASE_ADDR + SEQ_MAX_DRAWS * SEQ_DESC_STRIDE)

// -------------------------------------------------------------------------
// Texture region — starts immediately after descriptors.
// -------------------------------------------------------------------------

// TEX_DRAM_BYTE_ADDR_FIXED is DERIVED from SEQ_DESC_END.
// Currently: 0x4A00 + 12 × 256 = 0x4A00 + 0xC00 = 0x5600.
#define TEX_DRAM_BYTE_ADDR_FIXED  SEQ_DESC_END

// Maximum texture size (256×256 texels, 8 bytes each = 2×FP16 words/texel).
#define TEX_REGION_BYTES      (256 * 256 * 8)   // 0x80000 = 512 KB

// -------------------------------------------------------------------------
// Framebuffer region — starts immediately after texture.
// -------------------------------------------------------------------------

// DRAM_OUT_BASE_SPI = TEX_DRAM_BYTE_ADDR_FIXED + TEX_REGION_BYTES.
// Currently: 0x5600 + 0x80000 = 0x85600.
#define DRAM_OUT_BASE_SPI    (TEX_DRAM_BYTE_ADDR_FIXED + TEX_REGION_BYTES)

// DRAM_OUT(n) / DRAM_OUT_SPI(n) use this byte offset from DRAM_SPI_BASE.
// Currently: 0x85600 - 0x1000 = 0x84600.
#define DRAM_OUT_OFFSET      (DRAM_OUT_BASE_SPI - DRAM_SPI_BASE)

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

// -------------------------------------------------------------------------
// CTS / host draw mailbox — host-provided geometry for headless draw tests.
// -------------------------------------------------------------------------
//
// A transport-independent way to hand the firmware one frame's geometry
// without the UART drain loop: the host (arcilator harness, or later the DRM
// shim) writes a draw command into a fixed DRAM region; the firmware reads it
// at the top of the render loop.  Placed at the 4 MB SPI mark — well above the
// framebuffer + TBR bin/setup data (~2.8 MB worst case at 128²) and below the
// firmware stack (top of the 8 MB ram_a).  Values are stored one-per-32-bit
// word (fp16 in the low half) so DRAM_OUT_RAW's word access is alignment-safe.
#define BORG_CTS_MAILBOX_SPI  0x400000      // SPI byte address of the mailbox
#define BORG_CTS_MAGIC        0x0C75DA7Au   // "CTS DATA" presence sentinel
#define BORG_CTS_MAX_VERTS    16
#define BORG_CTS_MAX_TRIS     16

// Word offsets within the mailbox (multiply by 4 for the byte address).
#define BORG_CTS_OFF_MAGIC    0
#define BORG_CTS_OFF_NVERTS   1
#define BORG_CTS_OFF_NTRIS    2
#define BORG_CTS_OFF_MVP      16                                       // 16 fp16 words
#define BORG_CTS_OFF_POS      32                                       // nverts*3 fp16
#define BORG_CTS_OFF_COLOR    (BORG_CTS_OFF_POS   + BORG_CTS_MAX_VERTS * 3)  // 80
#define BORG_CTS_OFF_IDX      (BORG_CTS_OFF_COLOR + BORG_CTS_MAX_VERTS * 3)  // 128
#define BORG_CTS_WORDS        (BORG_CTS_OFF_IDX   + BORG_CTS_MAX_TRIS  * 3)  // 176
