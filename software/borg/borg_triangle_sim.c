// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_triangle_demo.c — Diagnostic: drive the real Borg GPU hardware to
// render ONE hardcoded triangle through the draw front end (SEQ_TRIGGER,
// vertex pulling, SOUT/FATTR varyings, hardware rasterization), with no
// borgvk, no host, no wire protocol, and no texturing -- this firmware is
// for the BorgConfig.UlxTriangle bitstream (borg-triangle.bit), which has
// hasSampler=false. No hardcoded-content rule from borg_kernel.c applies
// here: that rule is about the *production* wire-protocol path, not a
// one-off hardware validation build.
//
// Deliberately self-contained: does NOT link borg_driver.c/borg_fpu.c/
// borg_math.c/borg_spirb.c (the "kernel" firmware's shared sources) at all,
// to sidestep their dependency chain (SPIR-B parsing, the borgc shader
// compiler, float LUTs) entirely. Everything needed -- the borg_gpu_t
// register struct, the BORG_INSTR_* instruction-encoding macros, and the
// system/UART constants -- comes from the RDL-generated headers plus
// borg_sys.h, all header-only.
//
// Register sequence and shader machine code are a direct translation of
// hardware/borg/test/src/BorgDrawTests.scala's DrawRig, which
// docs/B1_geometry_front_end.md's "Using it: a minimal draw" section calls
// out as the reference recipe ("renders correctly on every configuration").
// DrawRig's own arg order for FMA/MUL/IADD/LOAD/SOUT/FATTR is preserved
// exactly (see comments on each instruction below) -- cross-checked against
// Instructions.scala's encoders and the equivalent BORG_INSTR_* macros in
// borg_isa.h, which are generated from the same source and confirmed to
// produce bit-identical encodings.
//
// Scene: one triangle covering the screen's upper-left half (the top-left,
// top-right and bottom-left corners), red at the top-left corner shading to
// green at the top-right, black at the bottom-left -- the same scene shape
// BorgDrawTests.perspective renders, scaled from its 8x8 test framebuffer to
// this build's 32x32 one. Large enough to be unambiguous on a real monitor,
// and its two-varying gradient is a real correctness check (a uniform colour
// wouldn't distinguish "the GPU rendered nothing and this is stale/garbage
// memory" from "it worked").
//
// Flush format is RGB565 (FlushFormat.RGB565 = 0), NOT the RGBA8 DrawRig's
// simulation uses -- that only matters for DrawRig's own sim-side pixel
// accessor. HdmiScanoutFp16 (the real HDMI scanout hardware) reads a tiled
// RGB565 framebuffer (2 bytes/pixel, 32 bytes/tile -- see its own header
// comment), so this firmware must flush in that same format for the display
// to show anything but noise.

#include <stdint.h>
#include "borg_regs.h"
#include "borg_isa.h"
#include "borg_sys.h"    // BORG_BASE, UART_TX, FPGA_CLOCK_HZ, BORG_UART_BAUD, DRAM_OUT_RAW, STARTUP_DELAY

#define BORG_GPU ((volatile borg_gpu_t *)(uintptr_t)BORG_BASE)

// FlushFormat.RGB565 (BorgTileFlusher.scala) -- not in borg_isa.h (that
// header is instruction encodings only), so named here directly.
#define FLUSH_FORMAT_RGB565 0

// --- Minimal, self-contained UART TX (same formula as borg_driver.c's
// putc_uart -- delay-based, not status-polled: this session independently
// found that polling PERI_DEBUG_UART_STATUS on ULX3S produces garbage
// output, while a fixed delay is reliable) ----------------------------------
#define UART_CYCLES_PER_BIT  (FPGA_CLOCK_HZ / BORG_UART_BAUD)
#define UART_CYCLES_PER_BYTE (UART_CYCLES_PER_BIT * 10)  // start + 8 data + stop
#define UART_TX_DELAY_ITERS  (UART_CYCLES_PER_BYTE * 3 / 4)

static void putc_uart(int c) {
  UART_TX = c;
  for (volatile int i = 0; i < UART_TX_DELAY_ITERS; i++) ;
}

static void puts_uart(const char *s) {
  while (*s) putc_uart(*s++);
}

// float -> raw FP32 bit pattern, for the vertex buffer / viewport / depth
// registers, which all take the datapath's native FP32 bits directly.
static uint32_t f2b(float f) {
  union { float f; uint32_t u; } c;
  c.f = f;
  return c.u;
}

// --- Scene: one triangle, screen-space corners, matching
// BorgDrawTests.at()'s screen-to-clip-space convention: x = (sx/(Size/2)-1)*w,
// y = (sy/(Size/2)-1)*w, z = z_ndc*w, at w=1 and z_ndc=0.5 for all three
// corners (so the whole triangle sits mid-depth). Size = 32 (this build's
// framebuffer). Six floats per vertex: X, Y, Z, W, r, g (matching the vertex
// shader below, which pulls exactly this layout).
static const float kVerts[3][6] = {
  // X       Y       Z     W     r     g
  { -1.0f,  -1.0f,  0.5f, 1.0f, 1.0f, 0.0f },  // top-left: red
  {  1.0f,  -1.0f,  0.5f, 1.0f, 0.0f, 1.0f },  // top-right: green
  { -1.0f,   1.0f,  0.5f, 1.0f, 0.0f, 0.0f },  // bottom-left: black
};

// --- DRAM layout for this test: all addresses are GPU (SPI) byte addresses,
// well clear of firmware code (which is small and loads at 0) and of every
// other region borg_layout.h defines (the CTS mailbox at 0x400000 etc.) --
// this firmware doesn't touch any of those, so picking a clean, unused block
// starting at 256 KB keeps this fully independent of that layout. ----------
#define VS_ADDR     0x40000u   // vertex shader
#define FS_ADDR     0x41000u   // fragment shader
#define BIN_BASE    0x42000u   // bin table (hardware-written during binning)
#define SETUP_BASE  0x43000u   // per-triangle records (hardware-written)
#define VB_ADDR     0x44000u   // vertex buffer
#define IB_ADDR     0x44100u   // index buffer (unused -- non-indexed draw)
#define VS_CONST    0x45000u   // vertex shader's constant window
#define FS_CONST    0x45100u   // fragment shader's constant window
#define FB_BASE     DRAM_OUT_SPI(0)   // sim-only: matches the verilator harness's save_ppm() offset

#define FB_SIZE        32      // pixels, both dimensions
#define FB_TILES_PER_ROW (FB_SIZE / 4)   // 8
#define FB_TILE_ROWS     (FB_SIZE / 4)   // 8
#define BIN_ROW_BYTES  32      // matches BorgDrawTests.DrawRig's default

int main(void) {
  STARTUP_DELAY();
  UART_BAUD = UART_BAUD_DEFAULT;
  puts_uart("triangle demo\r\n");

  volatile borg_gpu_t *g = BORG_GPU;

  // --- Vertex shader: vertex pulling (docs/B1_geometry_front_end.md).
  // VertexIndex (r30) * stride (u26) + vertex-buffer word address (u25) ->
  // r9, then six LOADs walk r9 forward by u27 (=1 word) between each,
  // landing X,Y,Z,W in r0-r3 and r,g in r10-r11; SOUT writes varyings 0/1;
  // HALT. Exact translation of DrawRig's `vs` (BorgDrawTests.scala) --
  // IMUL/IADD/LOAD/SOUT arg order matches Instructions.scala's Scala-side
  // helpers 1:1 with borg_isa.h's BORG_INSTR_* macros (rd first in the C
  // macros vs last in the Scala helpers -- everything else is the same).
  uint32_t vs[] = {
    BORG_INSTR_IMUL(9, 30, 26, 2),         // r9 = VertexIndex * stride
    BORG_INSTR_IADD(9, 9, 25, 2),          // r9 += vertex buffer word addr
    BORG_INSTR_LOAD(0, 9, 0),  BORG_INSTR_IADD(9, 9, 27, 2),   // X, r9 += 1
    BORG_INSTR_LOAD(1, 9, 0),  BORG_INSTR_IADD(9, 9, 27, 2),   // Y, r9 += 1
    BORG_INSTR_LOAD(2, 9, 0),  BORG_INSTR_IADD(9, 9, 27, 2),   // Z, r9 += 1
    BORG_INSTR_LOAD(3, 9, 0),  BORG_INSTR_IADD(9, 9, 27, 2),   // W, r9 += 1
    BORG_INSTR_LOAD(10, 9, 0), BORG_INSTR_IADD(9, 9, 27, 2),   // r, r9 += 1
    BORG_INSTR_LOAD(11, 9, 0),                                 // g
    BORG_INSTR_SOUT(10, 0, 0),             // varying 0 = r
    BORG_INSTR_SOUT(11, 1, 0),             // varying 1 = g
    BORG_INSTR_HALT,
  };

  // --- Fragment shader: v = l0*a0 + l1*a1 + l2*a2 for r and g (FATTR
  // r10 populates r10..r12 with the three corners' values for one varying);
  // blue is the constant window's first word (0.0). Exact translation of
  // DrawRig's `fs`. Note MUL's funct3=2 on the last FMUL -- copied exactly
  // from DrawRig, not a typo.
  uint32_t fs[] = {
    BORG_INSTR_FATTR(10, 0),                        // r10..r12 = varying 0 (r) at the 3 corners
    BORG_INSTR_FMUL(26, 5, 10, 0),                   // r26 = l0*a0
    BORG_INSTR_FMADD(26, 6, 11, 26, 0),              // r26 += l1*a1
    BORG_INSTR_FMADD(26, 7, 12, 26, 0),               // r26 += l2*a2   (= red)
    BORG_INSTR_FATTR(10, 1),                          // r10..r12 = varying 1 (g)
    BORG_INSTR_FMUL(27, 5, 10, 0),
    BORG_INSTR_FMADD(27, 6, 11, 27, 0),
    BORG_INSTR_FMADD(27, 7, 12, 27, 0),               // (= green)
    BORG_INSTR_FMUL(28, 5, 20, 2),                    // blue = 0.0 (u20, funct3=2 as in DrawRig)
    BORG_INSTR_HALT,
  };

  for (unsigned i = 0; i < sizeof(vs) / sizeof(vs[0]); i++)
    DRAM_OUT_RAW(VS_ADDR + 4 * i) = vs[i];
  for (unsigned i = 0; i < sizeof(fs) / sizeof(fs[0]); i++)
    DRAM_OUT_RAW(FS_ADDR + 4 * i) = fs[i];

  // Vertex shader's constant window: u25 = vertex-buffer WORD address
  // (VB_ADDR/4), u26 = stride in words (6), u27 = 1.
  DRAM_OUT_RAW(VS_CONST + 0) = VB_ADDR / 4;
  DRAM_OUT_RAW(VS_CONST + 4) = 6;
  DRAM_OUT_RAW(VS_CONST + 8) = 1;
  // Fragment shader's constant window: u20 = 0.0 (blue).
  DRAM_OUT_RAW(FS_CONST + 0) = f2b(0.0f);

  // Vertex buffer: 3 vertices x 6 floats.
  for (int v = 0; v < 3; v++)
    for (int c = 0; c < 6; c++)
      DRAM_OUT_RAW(VB_ADDR + 4 * (6 * v + c)) = f2b(kVerts[v][c]);

  // --- Registers, then SEQ_TRIGGER (docs/B1_geometry_front_end.md,
  // "Using it: a minimal draw"; values match DrawRig's draw() defaults
  // scaled to this build's 32x32 / 8x8-tile framebuffer). ------------------
  g->control = 2;   // reset pipeline before configuring (DrawRig's own reset step)

  g->seq_vert_addr = VS_ADDR;
  g->seq_vert_len  = sizeof(vs) / sizeof(vs[0]);
  g->seq_frag_addr = FS_ADDR;
  g->seq_frag_len  = sizeof(fs) / sizeof(fs[0]);
  g->seq_bin_base       = BIN_BASE;
  g->seq_bin_row_bytes  = BIN_ROW_BYTES;
  g->seq_setup_base     = SETUP_BASE;
  g->seq_fb_base        = FB_BASE;
  g->seq_tiles_per_row  = FB_TILES_PER_ROW;
  g->seq_tile_rows      = FB_TILE_ROWS;
  g->fb_pitch           = FB_TILES_PER_ROW;
  g->fb_origin          = 0;                 // origin (0,0): the whole framebuffer, no sub-window
  g->seq_clear_lo       = 0x7BFF;             // DrawRig's exact clear value (tile-buffer native FP16)
  g->seq_clear_hi       = 0;
  g->frag_pc            = 1;
  g->flush_format       = FLUSH_FORMAT_RGB565;
  g->depth_cfg          = 7 | (1 << 3);       // DrawRig default: ALWAYS test, writes on
  g->occ_tri_range      = 0 | (0xFFFFu << 16);
  g->cull_cfg           = 0;                  // cull nothing
  g->occ_ctrl           = 3;
  // draw_cfg: mode(1) | topology(0=list)<<1 | indexType(0=none)<<3 |
  // restart(0)<<5 | record_shift(8)<<6 -- record_shift 8 holds up to five
  // varying components; this shader only uses two.
  g->draw_cfg              = 1 | (0 << 1) | (0 << 3) | (0 << 5) | (8 << 6);
  g->draw_vertex_count     = 3;
  g->draw_instance_count   = 1;
  g->draw_first_vertex     = 0;
  g->draw_first_instance   = 0;
  g->draw_vertex_offset    = 0;
  g->draw_index_base       = IB_ADDR;         // unused: indexType = 0
  g->viewport_sx = f2b((float)FB_SIZE / 2.0f); g->viewport_sy = f2b((float)FB_SIZE / 2.0f);
  g->viewport_ox = f2b((float)FB_SIZE / 2.0f); g->viewport_oy = f2b((float)FB_SIZE / 2.0f);
  g->depth_scale = f2b(1.0f); g->depth_offset = f2b(0.0f);
  g->draw_vs_const = VS_CONST;
  g->draw_fs_const = FS_CONST;
  g->tex_desc_base     = 0;   // unused: this build has hasSampler=false, and the shader never TEXs
  g->sampler_desc_base = 0;
  g->sample_mask_cfg   = 0xF;

  // Program the HDMI scanout's framebuffer base from the SAME address as
  // seq_fb_base, so the GPU flush and the display engine can't drift apart
  // (the documented cause of the historical blinking-green-corner-pixel
  // bug). Single buffer: both PERI_SCANOUT_FB0 and FB1 point at FB_BASE, so
  // front/back buffer selection is a don't-care. PERI_SCANOUT_FB0 @
  // 0x08000010, PERI_SCANOUT_FB1 @ 0x08000028 (Project.scala).
  *(volatile uint32_t *)0x08000010u = FB_BASE;
  *(volatile uint32_t *)0x08000028u = FB_BASE;

  g->seq_trigger = 1;

  puts_uart("draw triggered\r\n");

  // Poll STATUS bit 5 (sequencer busy) until it's been seen high then goes
  // low again -- bounded, not truly infinite, so a real hardware/config
  // problem hangs here for a while rather than forever.
  int seen = 0;
  long i;
  for (i = 0; i < 50000000L; i++) {
    uint32_t st = g->status;
    if ((st >> 5) & 1) seen = 1;
    if (seen && !((st >> 5) & 1)) break;
  }
  puts_uart(seen ? "draw done\r\n" : "draw TIMED OUT (never saw busy)\r\n");

  // Hold: the frame stays in the framebuffer regardless (nothing re-renders
  // it), just spam 'R' so a UART capture confirms the CPU is still alive.
  for (;;) {
    putc_uart('R');
    for (volatile long d = 0; d < 2000000L; d++) ;
  }
}
