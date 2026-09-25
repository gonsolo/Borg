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
//   TEX_DRAM_BYTE_ADDR_FIXED .. +TEX_REGION_BYTES  Texture descriptor tables, then texels
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
// Setup shader: 256B (64 instructions), spanning 0x4880-0x4980.
//
// It absorbed the old SEQ_RAST_SHADER_ADDR region at 0x4900, which is dead
// space: the rasterizer edge-test shader is a permanent hardware ROM
// (BorgRasterRom, fetched directly by BorgCore), BorgSequencer's
// handleLoadRastShader() is a no-op pass-through, and the firmware never
// staged anything there. Growing into it shifts NO other address -- frag,
// descriptors, texture and framebuffer bases are all unmoved.
//
// The extra room is needed by Step 50.2b: the setup shader gained 12
// instructions computing the per-edge MSAA sample deltas, taking it from 31 to
// 43 -- past the old 32-instruction (128B) cap, which would have silently
// overflowed into 0x4900 and corrupted whatever lived there.
#define SEQ_SETUP_SHADER_ADDR 0x4880   // SPI byte addr for setup shader   (max 256B)
#define SEQ_FRAG_SHADER_ADDR  0x4980   // SPI byte addr for frag shader    (max 256B = 64 words)
#define SEQ_DESC_BASE_ADDR    0x4A80   // SPI byte addr for descriptor 0 (moved +0x80 for the
                                       // borgc 56-word frag; TEX/DRAM_OUT derive from here)

// Descriptor layout: 3 verts × 32B + 64B MVP + 32B metadata = 256B each.
#define SEQ_DESC_STRIDE       256
#define SEQ_MVP_OFFSET        96       // byte offset to 16 MVP datapath-float words (64B)
#define SEQ_META_OFFSET       160      // byte offset to bbox + flags (32B)

// End of descriptor region (exclusive) — derived, do not edit.
#define SEQ_DESC_END          (SEQ_DESC_BASE_ADDR + SEQ_MAX_DRAWS * SEQ_DESC_STRIDE)

// -------------------------------------------------------------------------
// Texture region — starts immediately after descriptors.
// -------------------------------------------------------------------------

// TEX_DRAM_BYTE_ADDR_FIXED is DERIVED from SEQ_DESC_END.
// Currently: 0x4A00 + 12 × 256 = 0x4A00 + 0xC00 = 0x5600.
#define TEX_DRAM_BYTE_ADDR_FIXED  SEQ_DESC_END

// Region size, kept from the legacy 256×256 × 8-byte layout so nothing after
// it moves: room for the descriptor tables and any texture up to 256×256 at
// 8 bytes per texel.
#define TEX_REGION_BYTES      (256 * 256 * 8)   // 0x80000 = 512 KB

// The texture unit's descriptor tables (docs/B2_texture_unit.md), at the start
// of the region, texels after them. One texture (16 words) and one sampler
// (4 words) today; TEX_DESC_BASE and SAMPLER_DESC_BASE point here.
#define TEX_DESC_TABLE_ADDR     TEX_DRAM_BYTE_ADDR_FIXED
#define SAMPLER_DESC_TABLE_ADDR (TEX_DRAM_BYTE_ADDR_FIXED + 64)
#define TEX_TEXEL_ADDR          (TEX_DRAM_BYTE_ADDR_FIXED + 256)

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
// Per-tile bin-list capacity (triangles indexable per tile).  Reduced from 1024
// so the bin region (num_tiles × SEQ_MAX_TRI × 2 B) fits PSRAM at 256×256
// (4096 tiles × 256 × 2 = 2 MB).  Ample for the cube (12) and CTS (≤45 tris).
#define SEQ_MAX_TRI           256
#define SEQ_MAX_TILES         4096  // max tiles per frame (64×64 @ 4×4 = 256×256)

// Per-tile bin list: one row of SEQ_MAX_TRI uint16_t entries per tile.
#define TBR_BIN_ENTRY_SIZE    2                              // sizeof(uint16_t)
#define TBR_BIN_ROW_BYTES     (SEQ_MAX_TRI * TBR_BIN_ENTRY_SIZE)

// Per-triangle setup store. At samples > 1 (every current BorgConfig: 4x MSAA)
// the hardware stores 38 words per triangle (uniforms + has_uvs + covDelta) at
// a 256-byte stride -- sStoreSetup: addr = setupBase + (triIdx << 8), see
// BorgGeometrySequencer.setupStrideShift. 128 is the samples == 1 stride.
#define TBR_SETUP_ENTRY_BYTES 256

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
// word (one datapath float, or an integer) so DRAM_OUT_RAW's word access is
// alignment-safe.
#define BORG_CTS_MAILBOX_SPI  0x400000      // SPI byte address of the mailbox
#define BORG_CTS_MAGIC        0x0C75DA7Au   // "CTS DATA" presence sentinel
#define BORG_CTS_MAX_VERTS    16
#define BORG_CTS_MAX_TRIS     16

// Word offsets within the mailbox (multiply by 4 for the byte address).
#define BORG_CTS_OFF_MAGIC    0
#define BORG_CTS_OFF_NVERTS   1
#define BORG_CTS_OFF_NTRIS    2
#define BORG_CTS_OFF_FLAGS    3   // render flags (see BORG_CTS_FLAG_*)
#define BORG_CTS_OFF_MVP      16                                       // 16 float words
#define BORG_CTS_OFF_POS      32                                       // nverts*3 float words
#define BORG_CTS_OFF_COLOR    (BORG_CTS_OFF_POS   + BORG_CTS_MAX_VERTS * 3)  // 80
#define BORG_CTS_OFF_IDX      (BORG_CTS_OFF_COLOR + BORG_CTS_MAX_VERTS * 3)  // 128
#define BORG_CTS_WORDS        (BORG_CTS_OFF_IDX   + BORG_CTS_MAX_TRIS  * 3)  // 176

// Render flags for BORG_CTS_OFF_FLAGS.
// NO_CULL: the firmware submits each triangle twice (normal + reversed winding)
// so the hardware culler lets both front- and back-facing triangles through.
#define BORG_CTS_FLAG_NO_CULL  (1u << 0)

// -------------------------------------------------------------------------
// Push-constant staging block (Step 50 item 13).
// -------------------------------------------------------------------------
//
// LS_BASE points at this block, and the shader's `LOAD rd, rs1` forms
// LS_BASE + (rs1 << 2) -- so word i here IS byte offset 4*i of the Vulkan
// push-constant range.  That is exactly the mapping borgc already emits: it
// pins each static field's byte_offset/4 into a const GPR as a RAW word index
// (see lib.rs's load_push_constant lowering), so nothing on either side needs
// to repack.  Push-constant data is word-granular by construction, since
// Vulkan requires offset and size to be multiples of 4.
//
// 128 bytes = Vulkan 1.0's minimum maxPushConstantsSize, which is what
// borgvk advertises; a larger range is a driver-side limit change, not a
// layout change, as long as this block grows with it.
//
// Placed above the CTS mailbox and DERIVED from its anchor, per this file's
// rule.  0x400 of headroom over the mailbox's own BORG_CTS_WORDS*4 = 704 B
// leaves room for the mailbox to grow without silently overlapping this.
#define BORG_PUSH_CONST_SPI        (BORG_CTS_MAILBOX_SPI + 0x400)
#define BORG_PUSH_CONST_MAX_WORDS  32
#define BORG_PUSH_CONST_MAX_BYTES  (BORG_PUSH_CONST_MAX_WORDS * 4)

// -------------------------------------------------------------------------
// Draw front end (docs/B1_geometry_front_end.md) vertex-pulling UBO and
// fragment constant window.
// -------------------------------------------------------------------------
//
// cube.vert's own UBO (`layout(std140, binding=0) uniform buf { mat4 MVP;
// vec4 position[36]; vec4 attr[36]; }`, Vulkan-Tools/cube/cube.vert)
// reproduced exactly so its borgc-compiled, draw-mode LOADs (LS_BASE + a
// pinned-GPR base word + gl_VertexIndex*stride) read it directly: MVP at
// word 0, position[] at word 16, attr[] at word 160 -- see
// hardware/borg/test/src/BorgDrawFrontEndCompilerTests.scala, whose address
// scheme this matches bit for bit. Placed above the push-constant block,
// DERIVED from its anchor per this file's rule.
#define DRAW_UBO_SPI          (BORG_PUSH_CONST_SPI + BORG_PUSH_CONST_MAX_BYTES)
#define DRAW_UBO_MVP_WORD     0
#define DRAW_UBO_POS_WORD     16
#define DRAW_UBO_ATTR_WORD    160
#define DRAW_UBO_MAX_VERTS    36   // Vulkan-Tools cube.c: 12 triangles, vkCmdDraw(36,...), no index buffer
#define DRAW_UBO_WORDS        (DRAW_UBO_ATTR_WORD + DRAW_UBO_MAX_VERTS * 4)   // 304

// The fragment shader's DRAW_FS_CONST window (12 words) -- cube.frag's
// lightDir lives at u20-u22 (draw_fs_const_offset + 4*0..2), see
// mesa/src/borg/compiler/lib.rs's load_const draw-mode arm.
#define DRAW_FS_CONST_SPI     (DRAW_UBO_SPI + DRAW_UBO_WORDS * 4)
#define DRAW_FS_CONST_WORDS   12
#define DRAW_FS_CONST_U0      20   // the window is u20-u31

// The vertex shader's DRAW_VS_CONST window (u25-u31, 7 words): borgc puts
// a draw-mode vertex shader's integer constants here, not in GPRs.
#define DRAW_VS_CONST_SPI     (DRAW_FS_CONST_SPI + DRAW_FS_CONST_WORDS * 4)
#define DRAW_VS_CONST_MAX_WORDS 7
#define DRAW_VS_CONST_U0      25   // the window is u25-u31

// Draw-mode vertex shader code.  A draw-mode vertex shader pulls its own
// vertices and transforms them, so it is far longer than the legacy 32-word
// SEQ_VERT_SHADER_ADDR slot allows (borgc's cube.vert is 74 instructions).
// The instruction cache fetches it from DRAM, so only this slot bounds its
// length; 128 words covers any blob a 0xB0 packet (RX_SHADER_MAX = 512 B)
// can carry.
#define DRAW_VERT_SHADER_SPI        (DRAW_VS_CONST_SPI + DRAW_VS_CONST_MAX_WORDS * 4)
#define DRAW_VERT_SHADER_MAX_WORDS  128
