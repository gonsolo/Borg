// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borgvk's packets and their limits, as its list builder (mesa src/borg/vulkan/rust/list.rs)
// reads them.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include "borg_fpu.h"

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
#define BC_PKT_LEN_DRAW    (1 + 3 + 8 * 4 + 1)             // 0xBA (host only): draw parameters, runs the draw
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
