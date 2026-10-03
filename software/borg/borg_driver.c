// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg GPU driver — pipeline orchestration, hardware init, draw commands.

#include "borg_driver.h"
#include "borg_core.h"
#include "borg_fpu.h"
#include "borg_math.h"
#include "borg_spirb.h"
#include <stdbool.h>
#include <stdint.h>

// @doc:mmio-map
#include "borg_isa.h" // IWYU pragma: keep — used by @doc extractor + ISA macros
#include "borg_sys.h"
// @doc:end

// --- DRAM frame layout (tiled: 2 words per pixel) ---
// RGB565 tiled framebuffer: the flusher writes one 16-bit RGB565 halfword per
// pixel (R[15:11] G[10:5] B[4:0]); depth lives only in the on-chip tile buffer.
// FRAME_FB_SIZE = W*H/2 32-bit words (2 bytes/pixel, 2 pixels per 32-bit word).
#define FRAME_FB_SIZE (BORG_FB_WIDTH * BORG_FB_HEIGHT / 2)
#define FRAME_ZB_SIZE 0
#define FRAME_STRIDE (FRAME_FB_SIZE + 1) // FB + DONE marker

// Double-buffering: back_buf is the buffer the GPU renders to next.
// Starts at 1 so the first render goes to buffer 1 while the scanout
// shows buffer 0 (black), giving a clean first frame.
static int back_buf = 1;

// DRAM_OUT() word offset of the DONE_MARKER written by the most recent
// borg_present() call — see borg_last_present_marker_offset().
static int last_present_marker_offset = 0;

typedef struct {
  int w, h;
} dim2_t;

typedef struct {
  int dram_offset;        // -1 = no texture
  dim2_t size;             // integer dimensions
} texture_t;

// Runtime framebuffer dimensions and derived values

// (draw/texture/sampler state lives in borg_core.c)

// Global timing vars
unsigned int t_init_cycles = 0;
unsigned int t_clear_cycles = 0;
unsigned int t_draw_cycles = 0;

// --- UART ---
// NOTE: The full SoC's read at UART_STATUS (0x0800001C) does not currently
// ack on the CPU's Decoupled data bus — polling it hangs the CPU forever
// (the write to 0x08000018 worked, but the symmetric read-side decode is
// still broken).  Until that is fixed in Project.scala, use a blind-write
// + fixed cycle delay matching uart_hello.s.  The peripheral has no TX FIFO,
// so a delay shorter than one byte's real shift-out time lets the NEXT
// putc_uart() overwrite UART_TX mid-transmission, silently dropping bytes.
// Derived from CLOCK_MHZ (already known at compile time — see Makefile)
// rather than a fixed constant: a value tuned for one clock (e.g. the 4 MHz
// ASIC target) silently under-delays at another (25 MHz sim/ULX3S needs
// ~2170 cycles/byte vs ASIC's ~347) — this was the actual bug.
#define UART_BAUD_RATE       BORG_UART_BAUD  // distinct from UART_BAUD (the MMIO register)
#define UART_CYCLES_PER_BIT  ((CLOCK_MHZ * 1000000) / UART_BAUD_RATE)
#define UART_CYCLES_PER_BYTE (UART_CYCLES_PER_BIT * 10)  // start + 8 data + stop
// The busy-wait loop costs ~2 cycles/iteration (addi+bnez) on the
// multi-cycle Hutt core (empirical, see uart_hello.s); scale with margin.
#define UART_TX_DELAY_ITERS  (UART_CYCLES_PER_BYTE * 3 / 4)
void putc_uart(int c) {
  UART_TX = c;
  for (volatile int i = 0; i < UART_TX_DELAY_ITERS; i++)
    ;
}

void puts_uart(const char *s) {
  while (*s)
    putc_uart(*s++);
}

// Returns non-zero if a received byte is waiting in the UART RX buffer.
int uart_rx_ready(void) {
#if defined(TARGET_ULX3S)
  return (int)USER_UART_RX_READY;
#else
  return (int)((UART_STATUS >> 1) & 1u);
#endif
}

// Read one received byte (caller must verify uart_rx_ready() first).
int getc_uart(void) {
#if defined(TARGET_ULX3S)
  return (int)(USER_UART_RX_DATA & 0xFFu);
#else
  return (int)(UART_TX & 0xFFu);
#endif
}

// Blocking UART read with a cycle timeout.  Returns the byte (0..255) or -1 if no
// byte arrived within `timeout` free-running cycles.
static int reload_getc(unsigned timeout) {
  unsigned start = rdcycle();
  while (!uart_rx_ready()) {
    if ((unsigned)(rdcycle() - start) > timeout) return -1;
  }
  return getc_uart();
}

// Serial firmware reload (0xB1).  Streams a fresh firmware image from the host
// into the SDRAM scratch region, then pulses a warm reset so the bootloader
// re-copies it to address 0 and the CPU reboots — all without resetting the
// video clock domain, so the HDMI monitor never loses sync.
//
// Wire format after the 0xB1 marker:
//   size (4 B LE) | image[size] | xor8(image)
//
// On any framing/timeout/checksum error this returns WITHOUT resetting, so a
// botched transfer can't brick the running firmware — the caller resumes its
// normal render loop and the host can retry.
void borg_serial_reload(void) {
  // ~80 ms/byte at 25 MHz — generous for host scheduling hiccups mid-stream.
  const unsigned TO = 2000000u;

  uint32_t sz = 0;
  for (int b = 0; b < 4; b++) {
    int c = reload_getc(TO);
    if (c < 0) { puts_uart("B1: size timeout\r\n"); return; }
    sz |= (uint32_t)(c & 0xFF) << (b * 8);
  }
  if (sz == 0 || sz > BORG_RELOAD_MAX) { puts_uart("B1: bad size\r\n"); return; }

  // Image bytes → scratch+4 as word stores (avoids per-byte read-modify-write).
  volatile uint32_t *dst = (volatile uint32_t *)(uintptr_t)(BORG_RELOAD_SCRATCH + 4);
  uint8_t xsum = 0;
  uint32_t i = 0, w = 0;
  while (i < sz) {
    uint32_t word = 0;
    for (int b = 0; b < 4 && i < sz; b++, i++) {
      int c = reload_getc(TO);
      if (c < 0) { puts_uart("B1: data timeout\r\n"); return; }
      xsum ^= (uint8_t)c;
      word |= (uint32_t)(c & 0xFF) << (b * 8);
    }
    dst[w++] = word;   // last word is zero-padded if sz % 4 != 0
  }

  int ck = reload_getc(TO);
  if (ck < 0 || (uint8_t)ck != xsum) { puts_uart("B1: csum fail\r\n"); return; }

  // Commit the size header the bootloader reads first, announce, then warm-reset.
  *(volatile uint32_t *)(uintptr_t)BORG_RELOAD_SCRATCH = sz;
  puts_uart("FW: reload OK, warm reset\r\n");
  for (volatile int d = 0; d < 200000; d++) { }   // let the UART TX drain first
  SOC_WARM_RESET = SOC_WARM_RESET_MAGIC;            // CPU + bootloader reboot
  for (;;) { }                                      // wait for the reset to hit
}

// --- Timing and debug printing ---
static inline unsigned int get_cycles(void) {
  unsigned int c;
  __asm__ volatile("csrr %0, cycle" : "=r"(c));  // Hutt free-running cycle CSR (0xC00)
  return c;
}

// --- Shader globals ---

// --- Public API ---

void borgCreateDevice(void) {
  STARTUP_DELAY();
  UART_BAUD = UART_BAUD_DEFAULT;
  puts_uart("Borg pipeline\r\n");
  borg_check_float_width();
  unsigned int t_init = get_cycles();

  // Read framebuffer dimensions from DRAM (written by host on sim).
  // On ULX3S there is no host -- DRAM_IN lives in firmware .text and returns
  // garbage.  Validate: must be a non-zero power-of-2 in [4..256]; else fall
  // back to 128x128.  256 is the headless-CTS max (4096 tiles, see SEQ_MAX_TILES).
  int fb_w = (int)DRAM_IN(0), fb_h = (int)DRAM_IN(1);
  if (fb_w < 4 || fb_w > 256 || (fb_w & (fb_w - 1)) != 0) fb_w = 128;
  if (fb_h < 4 || fb_h > 256 || (fb_h & (fb_h - 1)) != 0) fb_h = 128;

  // Program the HDMI scanout's framebuffer bases from the SAME layout constants
  // that drive the GPU flush/render base, so the display engine and the GPU can
  // never drift apart (a 0x80 drift here was the blinking green corner pixel).
  //   PERI_SCANOUT_FB0 @ 0x08000010, PERI_SCANOUT_FB1 @ 0x08000028.
  // Unconditional: these SoC registers exist on every target (Project.scala);
  // on targets with no scanout the writes are harmless no-ops.
  borg_core_init(fb_w, fb_h);   // flusher, TBR regions, descriptor-0 texels
  *(volatile uint32_t *)0x08000010u = DRAM_OUT_SPI(0 * FRAME_STRIDE);
  *(volatile uint32_t *)0x08000028u = DRAM_OUT_SPI(1 * FRAME_STRIDE);

  t_init_cycles = get_cycles() - t_init;
}

void borgCreateGraphicsPipeline(const BorgShaderModule *vert,
                                const BorgShaderModule *rast,
                                const BorgShaderModule *frag) {
  (void)rast;  // the draw raster program is a hardware ROM (BorgRasterRom)
  // The baked blobs (compiler/shader_blobs.h) are borgc's draw-mode compiles
  // of cube.vert and cube.frag, the same shaders borgvk uploads; staging them
  // here gives the standalone firmware and the headless sims a working
  // pipeline, and borgvk's 0xB0 packets later restage both slots.
  borg_stage_shader(0, vert->code);
  borg_stage_shader(1, frag->code);
}

// Record the frame's tile clear colour (written to every tile in Pass 2).
void borgFastFrameBegin(rgb16_t clear_color) {
  borg_core_set_clear(clear_color.r, clear_color.g, clear_color.b);
}

#ifdef BORG_PIXEL_PROBE
// Bring-up aid: print a few framebuffer pixels (RGB565, hex) every 32nd frame
// so a feature can be checked over UART without watching the monitor.
static void probe_pixels(int buf) {
  static unsigned frame_no;
  if ((frame_no++ & 31u) != 0) return;
  static const int pts[4][2] = {{2, 2}, {64, 64}, {40, 70}, {90, 50}};
  puts_uart("PX");
  for (int i = 0; i < 4; i++) {
    unsigned p = (unsigned)pts[i][1] * BORG_FB_WIDTH + (unsigned)pts[i][0];
    uint32_t w = DRAM_OUT((unsigned)buf * FRAME_STRIDE + p / 2);
    unsigned v = (p & 1u) ? (w >> 16) : (w & 0xFFFFu);
    putc_uart(' ');
    for (int sh = 12; sh >= 0; sh -= 4) putc_uart("0123456789abcdef"[(v >> sh) & 0xF]);
  }
  puts_uart("\r\n");
  uint32_t perf[7] = {BORG_GPU->perf_total, BORG_GPU->perf_frag, BORG_GPU->perf_flush,
                      BORG_GPU->perf_stall, BORG_GPU->perf_dma, t_draw_cycles, frame_no};
  puts_uart("PF");
  for (int i = 0; i < 7; i++) {
    putc_uart(' ');
    for (int sh = 28; sh >= 0; sh -= 4) putc_uart("0123456789abcdef"[(perf[i] >> sh) & 0xF]);
  }
  puts_uart("\r\n");
}
#endif

void borg_present(int frame) {
  (void)frame;
  unsigned int t_wait = get_cycles();

  // Two-pass TBR rendering: Pass 1 runs the vertex shader, triangle setup
  // and binning for every triangle; Pass 2 rasterizes, shades and flushes
  // every tile.
  borg_core_render(back_buf);

  // Wait for the GPU to finish the last tile flush.
  borg_core_wait_idle();
  t_draw_cycles += get_cycles() - t_wait;
#ifdef BORG_PIXEL_PROBE
  probe_pixels(back_buf);
#endif

  // Hardware perf-counter snapshot at a safe DRAM offset (300020+, clear
  // of the TBR bin/setup regions) for sim tooling to read back.
  DRAM_OUT(300020) = BORG_GPU->perf_total;
  DRAM_OUT(300021) = BORG_GPU->perf_frag;
  DRAM_OUT(300022) = BORG_GPU->perf_flush;
  DRAM_OUT(300023) = BORG_GPU->perf_stall;
  DRAM_OUT(300024) = BORG_GPU->perf_dma;

#ifndef TARGET_ULX3S
  // DONE_MARKER + timing for the sim/host viewer.  Skipped on ULX3S: the marker
  // is never read back (the present-wait below is hardware-side), and the
  // timing words at FRAME_FB_SIZE+1.. would land in the *other* buffer's first
  // pixels (FRAME_STRIDE = FRAME_FB_SIZE+1), corrupting the displayed frame.
  int base = back_buf * FRAME_STRIDE + FRAME_FB_SIZE;
  last_present_marker_offset = base;
  DRAM_OUT(base) = DONE_MARKER;
  DRAM_OUT(base + 1) = t_init_cycles & 0xFFFF;
  DRAM_OUT(base + 2) = (t_init_cycles >> 16) & 0xFFFF;
  DRAM_OUT(base + 3) = t_clear_cycles & 0xFFFF;
  DRAM_OUT(base + 4) = (t_clear_cycles >> 16) & 0xFFFF;
  DRAM_OUT(base + 5) = t_draw_cycles & 0xFFFF;
  DRAM_OUT(base + 6) = (t_draw_cycles >> 16) & 0xFFFF;
#endif

  // Double-buffer swap with scanout synchronization.
  //   PERI_FB_SELECT write (0x08000024) = which buffer the scanout displays.
  //   PERI_FB_SELECT read  (0x08000024) = which buffer the scanout is actually
  //     reading right now (it switches at its fill-loop wrap, ~one frame).
  // After presenting the just-rendered buffer we flip back_buf to the OLD front
  // buffer, then block until the scanout has switched to the new front buffer.
  // That guarantees the scanout has finished reading (released) the buffer the
  // GPU is about to render into — without this the free-running GPU laps the
  // ~33 ms scanout and overwrites the frame mid-display (tearing, partial tris).
  int front = back_buf;
  *(volatile uint32_t *)0x08000024u = (uint32_t)front;
  back_buf ^= 1;
  // Wait until the scanout has actually switched to displaying the new front
  // buffer (it flips at its fill-loop wrap, i.e. the next frame boundary). This
  // both eliminates tearing and guarantees the scanout has released the buffer
  // the GPU is about to render into, so the free-running GPU never overwrites a
  // frame mid-display. The cube renders slower than the scanout refresh, so this
  // wait is essentially free (it is not the frame-rate bottleneck — that is
  // CPU/GPU compute).
  while ((*(volatile uint32_t *)0x08000024u & 1u) != (uint32_t)front)
    ;
}

// DRAM_OUT() word offset of the DONE_MARKER from the most recent borg_present()
// call.  The sim/host viewer polls this address (via DRAM_OUT) to detect frame
// completion and to know when to clear the marker for the next frame — the
// double-buffer slot alternates every present, so callers must not hardcode it.
int borg_last_present_marker_offset(void) {
  return last_present_marker_offset;
}
