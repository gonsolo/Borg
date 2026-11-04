// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_core -- turns borgvk's wire packets into Borg register and DRAM writes: shader
// staging, texture descriptors and texels, blend / stencil / depth / cull state, push
// constants and render lists. Host build (-DBORG_HOST) for the direct simulator and borgvk's
// device path; the driver's own list builder (mesa src/borg/vulkan/rust/list.rs) replaces it.
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
#define BC_SHADER_MAX     512
#define BC_SHADER_MAX_BIG 4096   // marker 0xC1: the same packet for a blob of more than BC_SHADER_MAX bytes
#define BC_SHADER_MAX_HUGE 32768 // marker 0xC3 (host only): more than BC_SHADER_MAX_BIG; the board has no room
#define BC_TEXG_DATA      256
#define BC_PUSH_MAX_WORDS 32

#define BC_PKT_LEN_SHADER  (1 + 1 + 2 + BC_SHADER_MAX + 1)
#define BC_PKT_LEN_SHADER_BIG (1 + 1 + 2 + BC_SHADER_MAX_BIG + 1)
#define BC_PKT_LEN_SHADER_HUGE (1 + 1 + 2 + BC_SHADER_MAX_HUGE + 1)
#define BC_PKT_LEN_PUSH    (1 + 1 + 1 + BC_PUSH_MAX_WORDS * 4 + 1)
#define BC_PKT_LEN_BLEND   (1 + 4 + 4 + 1)
#define BC_PKT_LEN_STATE   (1 + 5 * 4 + 1)
#define BC_PKT_LEN_VP      (1 + 6 * 4 + 4 * 4 + 1)   // 0xC2: viewport x y w h, depth min max (float32), scissor x0 y0 x1 y1
#define BC_PKT_LEN_TEXG    (1 + 4 + 2 + 12 + 16 + BC_TEXG_DATA + 1)
#define BC_PKT_LEN_TARGET  (1 + 1 + 16 + 1)   // 0xB6: flush format, clear colour (4 x float32)
#define BC_MEM_DATA        256
#define BC_PKT_LEN_MEM     (1 + 4 + 2 + BC_MEM_DATA + 1)   // 0xB8 (host only): heap write
#define BC_PKT_LEN_VATTR   (1 + 1 + 1 + 4 + 4 + 4 + 4 + 1) // 0xB9 (host only): vertex attribute descriptor
#define BC_PKT_LEN_DRAW    (1 + 3 + 7 * 4 + 1)             // 0xBA (host only): draw parameters, runs the draw
#define BC_PKT_LEN_TEXL    (1 + 12 * 4 + 1)                // 0xC0 (host only): byte offsets of texture levels 1..12
#define BC_PKT_LEN_TEXS    (1 + 1 + 1 + 4 + 1)             // 0xC4 (host only): texture slot, sampler slot, texel base
#define BC_PKT_LEN_BIAS    (1 + 4 + 4 + 1)                 // 0xC5 (host only): depth bias of the next draw
#define BC_PKT_LEN_OCC     (1 + 1 + 4 + 1)                 // 0xC6 (host only): occlusion query begin/end, heap offset
#ifdef BORG_HOST
#define BC_TEX_SLOTS       32
#else
#define BC_TEX_SLOTS       1
#endif
#define BC_PKT_LEN_PASS    (1 + 1 + 1 + 1)                 // 0xBB (host only): render pass attachments
#define BC_PKT_LEN_ATT     (1 + 1 + 3 + 1)                 // 0xBF (host only): colour attachment count and formats 1-3
#define BC_PKT_LEN_PLACE   (1 + 7 * 4 + 1)                 // 0xC7 (host only): attachment addresses and pitch
#define BC_PKT_LEN_TEXD    (1 + 20 * 4 + 1)                // 0xC8 (host only): a texture's descriptor in device memory
#ifdef BORG_HOST
#define BC_PKT_LEN_MAX     BC_PKT_LEN_SHADER_HUGE
#else
#define BC_PKT_LEN_MAX     BC_PKT_LEN_SHADER_BIG
#endif

// Fixed length of the packet starting with `marker`; 0 for a marker that is not
// a draw packet (0xB1 is the serial-reload trigger, handled by the platform).
int borg_core_pkt_len(uint8_t marker);

// What a packet turned out to be.
enum {
  BC_BAD = 0,        // bad checksum / field out of range / unknown marker
  BC_SHADER_VERT,
  BC_SHADER_FRAG,
  BC_STATE,          // blend, stencil/depth/cull or push constants (the draw's MVP follows)
  BC_TEXG,           // generic texture chunk
  BC_TARGET,         // colour attachment format + clear colour (0xB6)
  BC_MEM,            // heap write (0xB8)
  BC_VATTR,          // vertex attribute descriptor (0xB9)
  BC_PASS,           // attachments of the pass: colour format, depth, stencil (0xBB)
  BC_ATT,            // colour attachments 1-3 of the pass (0xBF)
  BC_PLACE,          // the pass's attachments in device memory (0xC7)
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
// The last sequencer run's perf_total, perf_frag and the four perf_issue counters.
void borg_core_perf(uint32_t out[9]);
void borg_core_perf_add(void);
void borg_core_perf_report(void);

void borg_core_set_clear(uint16_t r, uint16_t g, uint16_t b);   // FP16 tile clear colour

void borg_core_wait_idle(void);
// Render lists (host build): a 0xBA draw is queued (borg_core_list_draw, called for BC_DRAW)
// and the queued draws are rendered as one render by borg_core_list_flush -- at the end of a
// pass, or earlier when a packet is about to change memory the queued draws read.
void borg_core_list_draw(void);
void borg_core_list_flush(void);
void borg_core_set_strip(int k, int n, int rows);   // strip k of n, `rows` tile rows each

// --- Pieces the hosts also call directly ---
extern int borg_fb_width, borg_fb_height;
extern uint32_t borg_cull_cfg;
void borg_stage_shader(uint8_t stage, const uint8_t *blob);
void borg_set_texture_desc(const uint32_t w[3], const uint32_t samp[4]);
void borg_write_texels(uint32_t off, const uint8_t *data, uint32_t n);
void borg_set_push_constants(const uint32_t *words, uint32_t off_words, uint32_t nwords);

#ifdef __cplusplus
}
#endif
