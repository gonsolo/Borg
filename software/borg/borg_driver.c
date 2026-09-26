// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Borg GPU driver — pipeline orchestration, hardware init, draw commands.

#include "borg_driver.h"
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
int borg_fb_width;
int borg_fb_height;
static borg_float_t half_width_f;
static borg_float_t half_height_f;  // used by the draw front end's viewport registers

// Draw front end (docs/B1_geometry_front_end.md), the only geometry path.
static int g_draw_vertex_count = 0;
// The staged vertex shader's blob: its varying count sizes the records, its
// draw extension fills the DRAW_VS_CONST window.
static spirb_shader_t g_draw_vert;
static int g_draw_vert_ok = 0;

// Sampler descriptor 0 (docs/B2_texture_unit.md) until borgvk sends the app's:
// nearest filtering, CLAMP_TO_EDGE (VkSamplerAddressMode 2) on U, V and W, no
// LOD bias, LOD clamped to [0, 0].
static uint32_t g_sampler_desc[4] = {(2u << 3) | (2u << 6) | (2u << 9), 0, 0, 0};

// Step 32.0: TBR DRAM geometry region base addresses.
// Computed in borgCreateDevice() after framebuffer size is known.
// Layout (SPI byte addresses, sequential after the output framebuffer):
//   tbr_bin_base:   per-tile bin lists  (num_tiles × TBR_BIN_ROW_BYTES)
//   tbr_setup_base: per-triangle store  (SEQ_MAX_TRI × TBR_SETUP_ENTRY_BYTES)
uint32_t tbr_bin_base   = 0;  // set in borgCreateDevice
uint32_t tbr_setup_base = 0;  // set in borgCreateDevice

// The tile clear colour, recorded per frame and written by borg_present.
static rgb16_t last_clear_color;

// Global timing vars
unsigned int t_init_cycles = 0;
unsigned int t_clear_cycles = 0;
unsigned int t_draw_cycles = 0;

// --- Texture state ---
static texture_t tex = {.dram_offset = -1};

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
// The fragment stage's parsed blob: its draw extension fills the
// DRAW_FS_CONST window before every render.
static spirb_shader_t frag_shader;

// --- Public API ---

void borgCreateDevice(void) {
  STARTUP_DELAY();
  UART_BAUD = UART_BAUD_DEFAULT;
  puts_uart("Borg pipeline\r\n");
  borg_check_float_width();
  unsigned int t_init = get_cycles();

  // Read framebuffer dimensions from DRAM (written by host on sim).
  // On ULX3S there is no host — DRAM_IN lives in firmware .text and returns
  // garbage.  Validate: must be a non-zero power-of-2 in [4..256]; else fall
  // back to 128×128.  256 is the headless-CTS max (4096 tiles, see SEQ_MAX_TILES).
  borg_fb_width  = DRAM_IN(0);
  borg_fb_height = DRAM_IN(1);
  if (borg_fb_width < 4 || borg_fb_width > 256 ||
      (borg_fb_width & (borg_fb_width - 1)) != 0)
    borg_fb_width = 128;
  if (borg_fb_height < 4 || borg_fb_height > 256 ||
      (borg_fb_height & (borg_fb_height - 1)) != 0)
    borg_fb_height = 128;

  // Half the framebuffer width, for the viewport transform baked into the MVP.
  half_width_f = borg_float_from_uint((uint32_t)borg_fb_width / 2);
  // Half the framebuffer height, for the draw front end's VIEWPORT_SY/OY
  // (the legacy path's viewport bake is width-only, square-framebuffer only).
  half_height_f = borg_float_from_uint((uint32_t)borg_fb_height / 2);

  // Step 25.4.1: Configure hardware tile flusher base address.
  // Actual per-tile base is set dynamically in borgBinRender.
  BORG_GPU->flush_fb_base = DRAM_OUT_SPI(0 * FRAME_STRIDE);

  // Program the HDMI scanout's framebuffer bases from the SAME layout constants
  // that drive the GPU flush/render base, so the display engine and the GPU can
  // never drift apart (a 0x80 drift here was the blinking green corner pixel).
  //   PERI_SCANOUT_FB0 @ 0x08000010, PERI_SCANOUT_FB1 @ 0x08000028.
  // Unconditional: these SoC registers exist on every target (Project.scala);
  // on targets with no scanout the writes are harmless no-ops.  This keeps the
  // firmware self-consistent across ULX3S and the BorgHdmiSimTop verilator sim.
  *(volatile uint32_t *)0x08000010u = DRAM_OUT_SPI(0 * FRAME_STRIDE);
  *(volatile uint32_t *)0x08000028u = DRAM_OUT_SPI(1 * FRAME_STRIDE);
  // log2(fbWidth) — fbWidth is always a power of 2
  unsigned int log2_w = 0;
  unsigned int w = (unsigned int)borg_fb_width;
  while (w > 1) {
    w >>= 1;
    log2_w++;
  }
  BORG_GPU->flush_width = log2_w;

  t_init_cycles = get_cycles() - t_init;

  // Step 32.0: Compute TBR DRAM geometry region base addresses.
  // These regions live after the output framebuffer (DRAM_OUT_OFFSET covers
  // one frame of borg_fb_width × borg_fb_height pixels × 2 words × 4 bytes).
  {
    int num_tiles = (borg_fb_width >> 2) * (borg_fb_height >> 2);
    // Two framebuffers (double-buffering): TBR starts after both.
    uint32_t fb_end_spi = (uint32_t)DRAM_SPI_BASE + (uint32_t)DRAM_OUT_OFFSET
                        + 2u * (uint32_t)FRAME_STRIDE * 4u;
    // Bin list: num_tiles rows, each TBR_BIN_ROW_BYTES wide.
    tbr_bin_base   = fb_end_spi;
    uint32_t bin_region_bytes = (uint32_t)num_tiles * TBR_BIN_ROW_BYTES;
    // Setup store: SEQ_MAX_TRI entries, each TBR_SETUP_ENTRY_BYTES wide.
    tbr_setup_base = tbr_bin_base + bin_region_bytes;
  }
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

void borg_stage_shader(uint8_t stage, const uint8_t *blob) {
  // .borg blob layout (spirb_parse / emit_blob): byte 0 = num_instrs, then a
  // 6-byte header, then num_instrs little-endian u32 instruction words.  Only the
  // instruction words need staging — the sequencer re-DMAs them into IMEM each
  // render.  The fragment's const pool is parsed here and written to its GPRs
  // in the render path.  borgc HALT-terminates the blob, so seq_*_len =
  // num_instrs (no +1).
  uint32_t n = blob[0];
  // Slot bounds: the vertex shader has its own DRAW_VERT_SHADER_SPI slot; the
  // fragment occupies IMEM[BORG_IMEM_FRAG_OFFSET..BORG_IMEM_DEPTH-1]
  // (BORG_IMEM_FRAG_LEN words). Reject oversized blobs.
  if (stage == 0 && n > DRAW_VERT_SHADER_MAX_WORDS) return;
  if (stage == 1 && n > BORG_IMEM_FRAG_LEN) return;
  // A blob must carry its draw extension, and every window word must land
  // inside its stage's window (u25-u31 vertex, u20-u31 fragment): the
  // firmware writes it to the window base + 4*(u - first index).
  static spirb_shader_t parsed;
  uint8_t u0 = (stage == 0) ? DRAW_VS_CONST_U0 : DRAW_FS_CONST_U0;
  uint8_t words = (stage == 0) ? DRAW_VS_CONST_MAX_WORDS : DRAW_FS_CONST_WORDS;
  if (spirb_parse(blob, &parsed) < 0 || !parsed.has_draw_ext) return;
  for (int i = 0; i < parsed.num_window; i++)
    if (parsed.window_regs[i] < u0 || parsed.window_regs[i] >= u0 + words) return;

  const uint8_t *w = blob + 6;
  uint32_t addr = (stage == 0) ? DRAW_VERT_SHADER_SPI : SEQ_FRAG_SHADER_ADDR;
  for (uint32_t i = 0; i < n; i++) {
    uint32_t word = (uint32_t)w[i * 4]            | ((uint32_t)w[i * 4 + 1] << 8) |
                    ((uint32_t)w[i * 4 + 2] << 16) | ((uint32_t)w[i * 4 + 3] << 24);
    DRAM_OUT_RAW(addr + i * 4) = word;
  }
  // NOTE: no UART print here.  The host streams the vert and frag 0xB0 packets
  // back-to-back (USB-CDC buffering absorbs the inter-packet usleep into vert's
  // own ~45 ms wire time, so frag immediately follows vert with no gap).  A
  // blocking puts_uart between the two stages would drop frag's leading bytes
  // from the shallow UART FIFO.  The drain loop prints a deferred confirmation
  // after the whole burst is absorbed instead.
  if (stage == 0) {
    BORG_GPU->seq_vert_addr = DRAW_VERT_SHADER_SPI;
    BORG_GPU->seq_vert_len  = n;
    g_draw_vert = parsed;
    g_draw_vert_ok = 1;
  } else {
    BORG_GPU->seq_frag_addr = SEQ_FRAG_SHADER_ADDR;
    BORG_GPU->seq_frag_len  = n;
    frag_shader = parsed;
  }
}

// Record the frame's tile clear colour (written to every tile in Pass 2).
void borgFastFrameBegin(rgb16_t clear_color) {
  last_clear_color = clear_color;
}

void borg_set_sampler(const uint32_t desc[4]) {
  for (int i = 0; i < 4; i++)
    g_sampler_desc[i] = desc[i];
}

void borg_set_texture(int tex_width, int tex_height) {
  tex = (texture_t){.dram_offset = 0, // unused, texture at fixed DRAM addr
                    .size = {tex_width, tex_height}};
  // Texture descriptor 0 (docs/B2_texture_unit.md): a 2D, one-level,
  // one-layer RGBA8 image, linear, identity swizzle.
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 0) = TEX_TEXEL_ADDR;
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 4) =
      (uint32_t)(tex_width - 1) | ((uint32_t)(tex_height - 1) << 16) |
      (1u << 28) |  // type 2D
      (1u << 30);   // linear layout
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 8) =
      (uint32_t)BORG_TEX_FORMAT_R8G8B8A8_UNORM << 14;
  DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + 12) = (uint32_t)tex_width * 4; // bytes per row
  for (int w = 4; w < 16; w++)
    DRAM_OUT_RAW(TEX_DESC_TABLE_ADDR + (uint32_t)w * 4) = 0;
  for (int w = 0; w < 4; w++)
    DRAM_OUT_RAW(SAMPLER_DESC_TABLE_ADDR + (uint32_t)w * 4) = g_sampler_desc[w];
  // Written after the tables: a write to either register also drops the one
  // texture and one sampler descriptor the unit caches.
  BORG_GPU->tex_desc_base = TEX_DESC_TABLE_ADDR;
  BORG_GPU->sampler_desc_base = SAMPLER_DESC_TABLE_ADDR;
}

void borg_clear_texture(void) {
  tex.dram_offset = -1;
}

// Step 50 item 13: stage a push-constant range and point LS_BASE at it.
//
// No new hardware: LOAD/STORE and the LS_BASE register already exist
// (BorgConfig.hasMemoryOps, ls_base_reg_t in the RDL), and borgc already
// lowers load_push_constant to `LOAD rd, rs1` with rs1 pinned to the field's
// word index.  The only thing that was missing is this -- putting the bytes
// where those loads look.
//
// LS_BASE is written on every call rather than once at init because it is
// also the base for ordinary SSBO-style LOAD/STORE: whoever points it
// somewhere else must not silently break the next draw's push constants, and
// re-asserting it here is one register write against a UART packet's cost.
void borg_set_push_constants(const uint32_t *words, uint32_t off_words,
                             uint32_t nwords) {
  if (!words || nwords == 0) return;
  // Clamp rather than trust: the range arrives over a serial link, and a
  // corrupted length that survived the checksum would otherwise scribble
  // across the CTS mailbox below or the firmware stack above.
  if (off_words >= BORG_PUSH_CONST_MAX_WORDS) return;
  if (nwords > BORG_PUSH_CONST_MAX_WORDS - off_words)
    nwords = BORG_PUSH_CONST_MAX_WORDS - off_words;

  for (uint32_t i = 0; i < nwords; i++)
    DRAM_OUT_RAW(BORG_PUSH_CONST_SPI + (off_words + i) * 4) = words[i];

  BORG_GPU->ls_base = BORG_PUSH_CONST_SPI & LS_BASE_REG_T__BASE_ADDR_bm;
}

// Upload a single texture row: the host (borgvk) streams the app's texture one
// row at a time so no large assembly buffer is needed. `row` is `dim` RGBA8
// texels for the given y, stored linear (row-major) at TEX_TEXEL_ADDR -- what
// texture descriptor 0 describes. One 32-bit write per texel, word-aligned, so
// no byte-write RMW occurs.
void borg_upload_texture_row(const uint8_t *row, int y, int dim) {
  uint32_t base = TEX_TEXEL_ADDR + (uint32_t)y * (uint32_t)dim * 4;
  for (int x = 0; x < dim; x++) {
    const uint8_t *p = &row[x * 4];
    DRAM_OUT_RAW(base + (uint32_t)x * 4) =
        (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
        ((uint32_t)p[3] << 24);
  }
}

// --- Draw front end (docs/B1_geometry_front_end.md) ---
//
// Records: 48 + 3*N words for N varying components (the Records table),
// in the smallest power-of-two stride that holds them, 256 B (record_shift
// 8, up to five components) to 1 KB (10, up to 64). Too small a stride lets
// each record's varyings spill into the next record, which the next triangle
// then overwrites. Returns -1 when N does not fit.
#define DRAW_RECORD_SHIFT_MIN 8
#define DRAW_RECORD_SHIFT_MAX 10
_Static_assert(((DRAW_UBO_MAX_VERTS / 3) << DRAW_RECORD_SHIFT_MAX) <= SEQ_MAX_TRI * TBR_SETUP_ENTRY_BYTES,
               "draw-mode records overflow the TBR setup region");
static int draw_record_shift(int num_varyings) {
  uint32_t bytes = (48u + 3u * (uint32_t)num_varyings) * 4u;
  for (int shift = DRAW_RECORD_SHIFT_MIN; shift <= DRAW_RECORD_SHIFT_MAX; shift++)
    if (bytes <= (1u << shift)) return shift;
  return -1;
}

// Stage one frame's geometry into cube.vert's UBO. VertexIndex for a
// non-indexed "list" draw is 3*triangle + corner, so this expands the
// deduplicated positions per corner.
void borgDrawSubmitGeom(const borg_draw_data_t *d, const borg_float_t *positions,
                        int nverts, const uint8_t *idx, const borg_float_t *uv,
                        int ntris) {
  (void)nverts;
  for (int i = 0; i < 16; i++)
    DRAM_OUT_RAW(DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_MVP_WORD + i) * 4) = d->uniforms[i];
  for (int t = 0; t < ntris; t++) {
    for (int v = 0; v < 3; v++) {
      int i = t * 3 + v;   // gl_VertexIndex
      int vi = idx[i];
      uint32_t pbase = DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_POS_WORD  + 4 * i) * 4;
      uint32_t abase = DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_ATTR_WORD + 4 * i) * 4;
      DRAM_OUT_RAW(pbase + 0)  = positions[vi * 3 + 0];
      DRAM_OUT_RAW(pbase + 4)  = positions[vi * 3 + 1];
      DRAM_OUT_RAW(pbase + 8)  = positions[vi * 3 + 2];
      DRAM_OUT_RAW(pbase + 12) = BORG_FLOAT_ONE;
      DRAM_OUT_RAW(abase + 0)  = uv[i * 2 + 0];
      DRAM_OUT_RAW(abase + 4)  = uv[i * 2 + 1];
      DRAM_OUT_RAW(abase + 8)  = BORG_FLOAT_ZERO;
      DRAM_OUT_RAW(abase + 12) = BORG_FLOAT_ZERO;
    }
  }
  g_draw_vertex_count = ntris * 3;
}

// Render one frame through the hardware draw front end
// (docs/B1_geometry_front_end.md's register table). Geometry and the MVP
// must already be staged via borgDrawSubmitGeom.
static void borgDrawRenderAutonomous(int frame) {
  // Nothing to draw before a draw-mode vertex shader arrives, or when its
  // varyings do not fit the largest record.
  int record_shift = draw_record_shift(g_draw_vert.num_varyings);
  if (!g_draw_vert_ok || record_shift < 0) return;

  int fb_offset = frame * FRAME_STRIDE;
  uint32_t cc_lo = ((uint32_t)last_clear_color.b << 16) | FP16_MAX_DEPTH;
  uint32_t cc_hi = ((uint32_t)last_clear_color.r << 16) | last_clear_color.g;

  BORG_GPU->seq_fb_base       = DRAM_OUT_SPI(fb_offset);
  BORG_GPU->seq_tiles_per_row = borg_fb_width >> 2;
  BORG_GPU->seq_clear_lo      = cc_lo;
  BORG_GPU->seq_clear_hi      = cc_hi;
  BORG_GPU->seq_bin_base      = tbr_bin_base;
  BORG_GPU->seq_bin_row_bytes = TBR_BIN_ROW_BYTES;
  BORG_GPU->seq_setup_base    = tbr_setup_base;
  BORG_GPU->tile_bz           = cc_lo;
  // Chains the dispatcher to the fragment shader. Without it fragPcReg stays
  // 0, the dispatcher shades nothing, and every pixel keeps the clear colour.
  BORG_GPU->frag_pc = BORG_IMEM_FRAG_OFFSET;

  BORG_GPU->viewport_sx = half_width_f;  BORG_GPU->viewport_sy = half_height_f;
  BORG_GPU->viewport_ox = half_width_f;  BORG_GPU->viewport_oy = half_height_f;
  BORG_GPU->depth_scale = BORG_FLOAT_ONE; BORG_GPU->depth_offset = BORG_FLOAT_ZERO;

  BORG_GPU->ls_base = DRAW_UBO_SPI & LS_BASE_REG_T__BASE_ADDR_bm;
  // The shaders' constant windows, from their blobs' draw extensions
  // (indices range-checked in borg_stage_shader).
  for (int i = 0; i < g_draw_vert.num_window; i++)
    DRAM_OUT_RAW(DRAW_VS_CONST_SPI + (uint32_t)(g_draw_vert.window_regs[i] - DRAW_VS_CONST_U0) * 4) =
        g_draw_vert.window_vals[i];
  for (int i = 0; i < frag_shader.num_window; i++)
    DRAM_OUT_RAW(DRAW_FS_CONST_SPI + (uint32_t)(frag_shader.window_regs[i] - DRAW_FS_CONST_U0) * 4) =
        frag_shader.window_vals[i];

  BORG_GPU->draw_vs_const = DRAW_VS_CONST_SPI;
  BORG_GPU->draw_fs_const = DRAW_FS_CONST_SPI;
  BORG_GPU->tex_desc_base     = TEX_DESC_TABLE_ADDR;
  BORG_GPU->sampler_desc_base = SAMPLER_DESC_TABLE_ADDR;
  // cube.c: rasterizationSamples = VK_SAMPLE_COUNT_1_BIT (bit 6), all mask
  // bits set. At one sample the raster ROM also skips the per-sample depths.
  BORG_GPU->sample_mask_cfg   = 0xF | (1u << 6);
  // cube.c: VK_CULL_MODE_BACK_BIT, VK_FRONT_FACE_COUNTER_CLOCKWISE, which is
  // the walker's front face with front_face_invert clear.
  BORG_GPU->cull_cfg = 2u << CULL_CFG_REG_T__CULL_MODE_bp;

  BORG_GPU->draw_cfg = 1u | ((uint32_t)record_shift << 6);  // mode=1, list, no indices, no restart
  BORG_GPU->draw_vertex_count   = g_draw_vertex_count;
  BORG_GPU->draw_instance_count = 1;
  BORG_GPU->draw_first_vertex   = 0; BORG_GPU->draw_first_instance = 0;
  BORG_GPU->draw_vertex_offset  = 0; BORG_GPU->draw_index_base     = 0;

  if (g_draw_vertex_count > 0) {
    BORG_GPU->seq_trigger = 1;
    while (BORG_GPU->status & STATUS_REG_T__SEQ_BUSY_bm)
      ;
  }
}

void borg_present(int frame) {
  (void)frame;
  unsigned int t_wait = get_cycles();

  // Two-pass TBR rendering: Pass 1 runs the vertex shader, triangle setup
  // and binning for every triangle; Pass 2 rasterizes, shades and flushes
  // every tile.
  borgDrawRenderAutonomous(back_buf);

  // Wait for the GPU to finish the last tile flush.
  while (!(BORG_GPU->status & STATUS_REG_T__IDLE_bm))
    ;
  t_draw_cycles += get_cycles() - t_wait;

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
