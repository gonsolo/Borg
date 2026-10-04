// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_core -- the hardware-independent half of the Borg driver.
//
// Everything that turns borgvk's wire packets into Borg register and DRAM writes
// lives here: shader staging, texture descriptors and texels, blend / stencil /
// depth / cull state, push constants, the draw's uniform data and the SEQ_TRIGGER
// sequence.  It is compiled twice from this one source:
//   * into the on-board firmware (borg_kernel.c / borg_driver.c), where register
//     and DRAM access are plain volatile stores, and
//   * into the direct simulator (simulation/direct), where they are calls onto
//     the simulated register bus and DRAM model (-DBORG_HOST).
// so what the CTS exercises is the code that runs on the board.
//
// Platform contract (see borg_core.c): BREG_W(field, v), BREG_R(field) and
// BDRAM_W(spi_addr, v).  The host build supplies bh_reg_write / bh_reg_read /
// bh_dram_write.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include "borg_fpu.h"

#ifdef __cplusplus
extern "C" {
#endif

// --- Wire packets (borgvk -> device) ---
#define BC_GEOM_MAX_VERTS 16
#define BC_GEOM_MAX_TRIS  12
#define BC_TEX_DIM        64
#define BC_SHADER_MAX     512
#define BC_TEXG_DATA      256
#define BC_PUSH_MAX_WORDS 32

#define BC_PKT_LEN_MVP     66
#define BC_PKT_LEN_GEOM    (1 + 2 + BC_GEOM_MAX_VERTS * 12 + BC_GEOM_MAX_TRIS * 3 + BC_GEOM_MAX_TRIS * 24 + 1)
#define BC_PKT_LEN_TEXROW  (1 + 1 + 16 + BC_TEX_DIM * 4 + 1)
#define BC_PKT_LEN_SHADER  (1 + 1 + 2 + BC_SHADER_MAX + 1)
#define BC_PKT_LEN_PUSH    (1 + 1 + 1 + BC_PUSH_MAX_WORDS * 4 + 1)
#define BC_PKT_LEN_BLEND   (1 + 4 + 4 + 1)
#define BC_PKT_LEN_STATE   (1 + 5 * 4 + 1)
#define BC_PKT_LEN_TEXG    (1 + 4 + 2 + 12 + 16 + BC_TEXG_DATA + 1)
#define BC_PKT_LEN_TARGET  (1 + 1 + 16 + 1)   // 0xB6: flush format, clear colour (4 x float32)
#define BC_PKT_LEN_ATTR4   (1 + 1 + BC_GEOM_MAX_TRIS * 3 * 16 + 1)   // 0xB7 (host only): vec4 attribute 1 per corner
#define BC_MEM_DATA        256
#define BC_PKT_LEN_MEM     (1 + 4 + 2 + BC_MEM_DATA + 1)   // 0xB8 (host only): heap write
#define BC_PKT_LEN_VATTR   (1 + 1 + 1 + 4 + 4 + 4 + 4 + 1) // 0xB9 (host only): vertex attribute descriptor
#define BC_PKT_LEN_DRAW    (1 + 3 + 6 * 4 + 1)             // 0xBA (host only): draw parameters, runs the draw
#define BC_PKT_LEN_PASS    (1 + 1 + 1 + 1)                 // 0xBB (host only): render pass attachments
#define BC_PKT_LEN_MAX     BC_PKT_LEN_MEM

// Fixed length of the packet starting with `marker`; 0 for a marker that is not
// a draw packet (0xB1 is the serial-reload trigger, handled by the platform).
int borg_core_pkt_len(uint8_t marker);

// What a packet turned out to be.
enum {
  BC_BAD = 0,        // bad checksum / field out of range / unknown marker
  BC_MVP,            // closes a draw
  BC_GEOM,
  BC_TEXROW,         // legacy 64-wide texture row
  BC_SHADER_VERT,
  BC_SHADER_FRAG,
  BC_STATE,          // blend, stencil/depth/cull or push constants (the draw's MVP follows)
  BC_TEXG,           // generic texture chunk
  BC_TARGET,         // colour attachment format + clear colour (0xB6)
  BC_MEM,            // heap write (0xB8)
  BC_VATTR,          // vertex attribute descriptor (0xB9)
  BC_PASS,           // attachments of the pass: colour format, depth, stencil (0xBB)
  BC_DRAW,           // draw parameters; the draw runs (0xBA)
};

// Validate and act on one complete packet (p[0] = marker, borg_core_pkt_len bytes).
int borg_core_packet(const uint8_t *p);

// --- Frame ---
// Framebuffer size: a power of two in 4..256 each.  Sets up the flusher, the TBR
// regions and the descriptor-0 texel store (filled white).
void borg_core_init(int width, int height);
// Colour attachment format the flusher writes: 0 = R5G6B5 (the board and the goldens),
// 1 = R8G8B8A8_UNORM, 2 = B8G8R8A8_UNORM, both tiled (4 bytes/pixel). Call before borg_core_init;
// a 0xB6 packet changes it afterwards.
void borg_core_set_flush_format(int fmt);
int borg_core_flush_format(void);   // 0 = R5G6B5, 1 = R8G8B8A8, 2 = B8G8R8A8

// A draw is ready once geometry and an MVP have both arrived.
int borg_core_ready(void);
const borg_float_t *borg_core_mvp(void);
void borg_core_set_geom(const borg_float_t *pos, const uint8_t *idx, const borg_float_t *uv,
                        int nverts, int ntris);
void borg_core_set_clear(uint16_t r, uint16_t g, uint16_t b);   // FP16 tile clear colour

// Stage the pending geometry and `mvp` (NULL: the one received over the wire) into the
// vertex shader's UBO, and bind the default texture if the host sent none.
void borg_core_stage(const borg_float_t *mvp);
// Program the sequencer for frame buffer `frame` and run the draw (returns once the
// sequencer is no longer busy); borg_core_wait_idle then waits for the last flush.
void borg_core_render(int frame);
void borg_core_wait_idle(void);
// stage + render + wait_idle, for hosts with nothing to time in between.
void borg_core_draw(const borg_float_t *mvp, int frame);

// --- Pieces the firmware also calls directly ---
extern int borg_fb_width, borg_fb_height;
extern uint32_t borg_cull_cfg;
void borg_stage_shader(uint8_t stage, const uint8_t *blob);
void borg_set_sampler(const uint32_t desc[4]);
void borg_set_texture(int tex_width, int tex_height);
void borg_set_texture_desc(const uint32_t w[3], const uint32_t samp[4]);
void borg_write_texels(uint32_t off, const uint8_t *data, uint32_t n);
void borg_set_push_constants(const uint32_t *words, uint32_t off_words, uint32_t nwords);
void borg_upload_texture_row(const uint8_t *row, int y, int dim);

#ifdef __cplusplus
}
#endif
