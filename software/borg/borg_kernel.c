// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_kernel.c — thin render kernel driven by the borgvk Mesa driver.
// Boots, drains borgvk wire packets (0xAD/0xAE/0xAF/0xB0/0xB1/0xB2) from UART,
// and drives the autonomous TBR hardware.  No hardcoded geometry, shaders, or
// texture — all content is uploaded at runtime by borgvk / cube.c.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_sys.h"
#include "compiler/shader_blobs.h"

// Host-uploaded geometry (0xAE packet): deduplicated model-space vertices +
// an indexed triangle list with per-triangle-vertex UVs.
#define RX_GEOM_MAX_VERTS 16
#define RX_GEOM_MAX_TRIS  12
// Payload after marker: nverts(1), ntris(1), verts(MAX_VERTS*3 float32 = 12 B
// each), idx(MAX_TRIS*3 B), uv(MAX_TRIS*3*2 float32 = 24 B per tri),
// xor_checksum(1). Positions and UVs are datapath values, sent as the host's
// float32 bits.
#define RX_GEOM_PKT_LEN \
  (1 + 2 + RX_GEOM_MAX_VERTS * 12 + RX_GEOM_MAX_TRIS * 3 + RX_GEOM_MAX_TRIS * 24 + 1)
static borg_float_t rx_geom_pos[RX_GEOM_MAX_VERTS * 3];
static uint8_t      rx_geom_idx[RX_GEOM_MAX_TRIS * 3];
static borg_float_t rx_geom_uv[RX_GEOM_MAX_TRIS * 3 * 2];
// Per-vertex RGB for the CTS flat-shaded path (zero when borgvk is the source).
static borg_float_t rx_geom_color[RX_GEOM_MAX_VERTS * 3];
static int     rx_have_color  = 0;
static int     rx_geom_nverts = 0;
static int     rx_geom_ntris  = 0;
static int     rx_have_geom   = 0;
static int     g_geom_recorded = 0;

// 0xAF texture-row packet: marker(1), y(1), sampler descriptor(4 words LE),
// row_pixels(TEX_DIM * 4 B RGBA8), csum(1). Every row carries the sampler, so
// any row that arrives intact delivers it.
#define RX_TEX_DIM       64
#define RX_TEX_SAMP_LEN  16
#define RX_TEX_PKT_LEN   (1 + 1 + RX_TEX_SAMP_LEN + RX_TEX_DIM * 4 + 1)

// 0xB0 borgc shader upload: marker(1), stage(1), len(2 LE), blob(RX_SHADER_MAX), csum(1)
#define RX_SHADER_MAX     512
#define RX_SHADER_PKT_LEN (1 + 1 + 2 + RX_SHADER_MAX + 1)

// 0xB2 push constants: marker(1), off_words(1), n_words(1), data(128 B), csum(1)
//
// 0xB1 is NOT free -- it is the serial-reload trigger handled before the
// length table below -- hence 0xB2.  Fixed length, padded to the full 32-word
// range, for the same reason 0xAE/0xAF/0xB0 are: the drain loop reads a
// constant byte count per marker and `n_words` says how much is valid.
// 132 B, comfortably inside RX_PKT_BUF_LEN (the largest packet: 0xAE at 520 B),
// so the shared buffer below does not need to grow -- the max() there is what
// guarantees that.
#define RX_PUSH_MAX_WORDS 32   // = BORG_PUSH_CONST_MAX_WORDS (128 B, Vulkan min)
#define RX_PUSH_PKT_LEN   (1 + 1 + 1 + RX_PUSH_MAX_WORDS * 4 + 1)
// The wire packet and the DRAM staging block must hold the same number of
// words, or a host pushing the full 128 B range would have its tail silently
// clamped away by borg_set_push_constants().  Tie them together here rather
// than trusting two 32s to stay equal.
_Static_assert(RX_PUSH_MAX_WORDS == BORG_PUSH_CONST_MAX_WORDS,
               "push-constant wire packet and DRAM staging block disagree");

#define RX_PKT_BUF_LEN \
  (RX_GEOM_PKT_LEN > RX_TEX_PKT_LEN \
     ? (RX_GEOM_PKT_LEN > RX_SHADER_PKT_LEN ? RX_GEOM_PKT_LEN : RX_SHADER_PKT_LEN) \
     : (RX_TEX_PKT_LEN  > RX_SHADER_PKT_LEN ? RX_TEX_PKT_LEN  : RX_SHADER_PKT_LEN))

// CTS host-mailbox: a transport-independent DRAM region the headless test
// harness fills with geometry + MVP so the sim needs no UART drain.
#define CTS_MB(n) DRAM_OUT_RAW(BORG_CTS_MAILBOX_SPI + (n) * 4)

static inline uint32_t rx_le32(const uint8_t *b) {
  return (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
         ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

static int cts_mailbox_present(void) {
  return CTS_MB(BORG_CTS_OFF_MAGIC) == BORG_CTS_MAGIC;
}

static int cts_load_mailbox(borg_float_t mvp_out[16]) {
  if (!cts_mailbox_present()) return 0;
  int nv = (int)CTS_MB(BORG_CTS_OFF_NVERTS);
  int nt = (int)CTS_MB(BORG_CTS_OFF_NTRIS);
  if (nv < 1 || nv > RX_GEOM_MAX_VERTS || nt < 1 || nt > RX_GEOM_MAX_TRIS)
    return 0;
  for (int i = 0; i < 16; i++)
    mvp_out[i] = CTS_MB(BORG_CTS_OFF_MVP + i);
  for (int i = 0; i < nv * 3; i++) {
    rx_geom_pos[i]   = CTS_MB(BORG_CTS_OFF_POS   + i);
    rx_geom_color[i] = CTS_MB(BORG_CTS_OFF_COLOR + i);
  }
  for (int i = 0; i < nt * 3; i++) {
    rx_geom_idx[i]        = (uint8_t)CTS_MB(BORG_CTS_OFF_IDX + i);
    rx_geom_uv[i * 2 + 0] = BORG_FLOAT_ZERO;
    rx_geom_uv[i * 2 + 1] = BORG_FLOAT_ZERO;
  }
  rx_geom_nverts = nv;
  rx_geom_ntris  = nt;
  rx_have_geom   = 1;
  rx_have_color  = 1;
  return 1;
}

#ifndef BORG_DRAW_MODE_CUBE
static void draw_received_geom(const borg_draw_data_t *draw) {
  borgTransformVerts(draw, rx_geom_pos, rx_geom_nverts);
  for (int t = 0; t < rx_geom_ntris; t++) {
    int idx[3];
    borg_vertex_t tri[3];
    for (int v = 0; v < 3; v++) {
      int vi = rx_geom_idx[t * 3 + v];
      idx[v] = vi;
      borg_float_t cr = BORG_FLOAT_ONE, cg = BORG_FLOAT_ONE, cb = BORG_FLOAT_ONE;
      if (rx_have_color) {
        cr = rx_geom_color[vi * 3 + 0];
        cg = rx_geom_color[vi * 3 + 1];
        cb = rx_geom_color[vi * 3 + 2];
      }
      tri[v] = (borg_vertex_t){
          .color = {cr, cg, cb},
          .uv    = {rx_geom_uv[(t * 3 + v) * 2 + 0], rx_geom_uv[(t * 3 + v) * 2 + 1]},
      };
    }
    borgCmdDrawIndexed(idx, tri, 0);
  }
}
#endif // !BORG_DRAW_MODE_CUBE

int main() {
  borgCreateDevice();

  // Load baked shaders so the GPU pipeline is valid before borgvk uploads its
  // own.  borgvk overrides vert+frag at runtime via 0xB0; rast stays baked.
  BorgShaderModule vert, rast, frag;
  borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
  borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
  borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
  borgCreateGraphicsPipeline(&vert, &rast, &frag);
  // The baked frag is now borgc's compilation of cube.frag -- the same shader
  // borgvk uploads -- so it wants the same staging mode borgvk's does:
  // frag_pos, not vertex colour. It used to be a texel x vertex_color Gouraud
  // shader from a since-deleted pipeline, which is why this was 1.
  //
  // Getting this wrong does not fail loudly, it renders flat/yellow -- the
  // staging mode decides which uniforms the sequencer writes, so a mismatch
  // feeds the shader the wrong inputs rather than crashing.
  borg_set_frag_vertex_color(0);

#ifdef BORG_DRAW_MODE_CUBE
  // Draw front end (docs/B1_geometry_front_end.md): render borgvk's real
  // cube.vert/cube.frag draw-mode compile through the full-hardware draw
  // path instead of the legacy per-triangle descriptors. Opt-in build flag
  // -- the legacy path stays the default until borgc/borgvk drive draw mode
  // for every shader, not just this one hand-verified pair.
  borg_set_draw_mode(1);
#endif

  // Pre-fill the texture region with white before any borgvk upload arrives.
  // The RX drain loop below recovers from a dropped/corrupted 0xAF texture-row
  // packet by discarding just that row (see the resync comment below) — the
  // affected texel then keeps whatever was in DRAM before, which without this
  // fill is uninitialized SDRAM (visible as stray colored pixels, moving with
  // the textured geometry since it's fixed in UV space). White keeps a missed
  // row visually unobtrusive instead.
  {
    static uint8_t white_row[RX_TEX_DIM * 4];   // RGBA8
    for (int i = 0; i < RX_TEX_DIM * 4; i++)
      white_row[i] = 0xFF;
    for (int y = 0; y < RX_TEX_DIM; y++)
      borg_upload_texture_row(white_row, y, RX_TEX_DIM);
  }

  const int cts_active = cts_mailbox_present();

  static uint8_t pkt_buf[RX_PKT_BUF_LEN];
  static borg_float_t host_mvp[16];
  static int have_mvp = 0;
  // Persists ACROSS while(1) iterations (not just within one drain-loop call):
  // a burst's packets stream back-to-back with no idle gap, so when a call
  // ends because a non-shader/non-texture-row packet succeeded (e.g. geometry,
  // which intentionally ends the batch to let rendering/other work happen),
  // the NEXT call must still skip the gap-sync for the packet immediately
  // following on the wire — otherwise the still-arriving bytes get silently
  // eaten as "idle padding" by the gap-sync loop below and the rest of the
  // burst is lost with no error printed at all.
  int skip_gap = 0;

  while (1) {
    // Drain borgvk packets from the UART.  Gap-sync: consume bytes until the
    // line has been idle for GAP_CYCLES of real time (indicating a packet
    // boundary), then wait for the next marker and read a fixed-length payload.
    // This avoids mid-packet framing errors even if bytes are dropped during
    // the previous borg_present() call (the UART FIFO is only 1 byte deep).
    // Greedy loop: keep draining while texture or shader bursts keep arriving;
    // break on any other packet type so we render once per MVP packet.
    int staged_vert = 0, staged_frag = 0;
    int pending_len = 0;  // bytes of a resync-recovered marker+payload already in pkt_buf
    // Bound must cover a full burst (2 shaders + geometry + 64 texture rows +
    // MVP ≈ 68): a smaller bound forces a round-trip through the outer
    // while(1) (cts_mailbox_present() + condition checks) between finishing
    // one packet and polling for the next marker byte — the same risk this
    // loop's geometry-packet handling above was just fixed for, but recurring
    // every time the bound is hit mid-burst instead of only once.
    for (int drain_iter = 0; drain_iter < 80; drain_iter++) {
      int got_tex_row = 0;
      int got_shader_pkt = 0;
      // Geometry (0xAE) used to fall through to the generic break below like
      // MVP does, forcing a round-trip through the outer while(1) (a
      // cts_mailbox_present() call + condition checks) before the firmware
      // resumes polling for the next byte — right at the one point where the
      // host is already streaming continuously into the first texture-row
      // packet. If that marker byte is lost in the gap, the receiver loses
      // framing sync and has to resync somewhere later in the texture data,
      // corrupting a whole contiguous run of rows — confirmed on real
      // hardware. Track it like a tex-row/shader packet so the loop keeps
      // draining without leaving this tight loop.
      int got_geom_pkt = 0;
      int pkt_marker, pkt_pos;

      if (pending_len > 0) {
        // Recovered below from a prior packet's checksum failure: since the
        // sender streams every packet in a burst back-to-back with no idle
        // gap, the tail of a corrupted read can already hold the START of the
        // next real packet — resume from there instead of waiting on the wire.
        pkt_marker = pkt_buf[0];
        pkt_pos = pending_len;
        pending_len = 0;
      } else {
        // Gap-sync: consume bytes until the line has been idle for GAP_CYCLES
        // (a packet boundary), avoiding mid-packet false framing from UART drops
        // during borg_present() (the FIFO is only 1 byte deep).  The sender
        // (borgvk, on both the real serial port and the sim socket transport)
        // paces packets with a real idle gap for exactly this reason.
        // At 25 MHz: inter-byte = 87 µs = 2175 cyc; 0xAD gap ≈ 6.3 ms.
        // 300 µs = 7500 cyc sits safely between the two.
        if (!skip_gap) {
          const unsigned GAP_CYCLES   = 7500;
          const unsigned GUARD_CYCLES = 4000000;  // ~160 ms hard cap
          unsigned t0 = rdcycle();
          unsigned tg = t0;
          while ((unsigned)(rdcycle() - t0) < GAP_CYCLES) {
            if (uart_rx_ready()) { (void)getc_uart(); t0 = rdcycle(); }
            if ((unsigned)(rdcycle() - tg) >= GUARD_CYCLES) break;
          }
        }
        skip_gap = 0;

        // Wait up to ~15 ms for the next packet's marker byte.
        for (volatile int t = 375000; !uart_rx_ready() && t > 0; t--) ;
        if (!uart_rx_ready()) break;
        pkt_marker = (uint8_t)getc_uart();
        pkt_buf[0] = (uint8_t)pkt_marker;
        pkt_pos = 1;
      }

      if (pkt_marker == 0xB1) { borg_serial_reload(); break; }
      int need = (pkt_marker == 0xAD) ? 66 :
                 (pkt_marker == 0xAE) ? RX_GEOM_PKT_LEN :
                 (pkt_marker == 0xAF) ? RX_TEX_PKT_LEN :
                 (pkt_marker == 0xB0) ? RX_SHADER_PKT_LEN :
                 (pkt_marker == 0xB2) ? RX_PUSH_PKT_LEN : 0;
      if (need) {
          int ok = 1;
          while (pkt_pos < need) {
            for (volatile int t = 4000; !uart_rx_ready() && t > 0; t--) ;
            if (!uart_rx_ready()) { ok = 0; break; }
            pkt_buf[pkt_pos++] = (uint8_t)getc_uart();
          }

          int success = 0;
          if (ok && pkt_marker == 0xAD) {
            // Full 4×4 MVP from borgvk: 16 LE float32 + 1 XOR checksum.
            uint8_t csum = 0;
            for (int i = 1; i <= 64; i++) csum ^= pkt_buf[i];
            if (csum == pkt_buf[65]) {
              for (int i = 0; i < 16; i++) {
                int base = 1 + i * 4;
                host_mvp[i] = (uint32_t)pkt_buf[base]           |
                              ((uint32_t)pkt_buf[base+1] << 8)  |
                              ((uint32_t)pkt_buf[base+2] << 16) |
                              ((uint32_t)pkt_buf[base+3] << 24);
              }
              have_mvp = 1;
              success = 1;
            }
          } else if (ok && pkt_marker == 0xAE) {
            // Host geometry: fixed-offset regions padded to max size.
            uint8_t csum = 0;
            for (int i = 1; i < RX_GEOM_PKT_LEN - 1; i++) csum ^= pkt_buf[i];
            int nv = pkt_buf[1], nt = pkt_buf[2];
            if (csum == pkt_buf[RX_GEOM_PKT_LEN - 1] &&
                nv >= 1 && nv <= RX_GEOM_MAX_VERTS &&
                nt >= 1 && nt <= RX_GEOM_MAX_TRIS) {
              int vbase = 3;
              int ibase = vbase + RX_GEOM_MAX_VERTS * 12;
              int ubase = ibase + RX_GEOM_MAX_TRIS * 3;
              for (int i = 0; i < nv * 3; i++)
                rx_geom_pos[i] = rx_le32(&pkt_buf[vbase + i * 4]);
              for (int i = 0; i < nt * 3; i++)
                rx_geom_idx[i] = pkt_buf[ibase + i];
              for (int i = 0; i < nt * 6; i++)
                rx_geom_uv[i] = rx_le32(&pkt_buf[ubase + i * 4]);
              rx_geom_nverts = nv;
              rx_geom_ntris  = nt;
              rx_have_geom   = 1;
              rx_have_color  = 0;
              success = 1;
              got_geom_pkt = 1;
              skip_gap = 1;  // texture rows immediately follow geometry on the wire
            }
          } else if (ok && pkt_marker == 0xAF) {
            // Texture row: [1]=y, the sampler descriptor, then RX_TEX_DIM
            // RGBA8 texels.
            uint8_t csum = 0;
            for (int i = 1; i < RX_TEX_PKT_LEN - 1; i++) csum ^= pkt_buf[i];
            int yrow = pkt_buf[1];
            if (csum == pkt_buf[RX_TEX_PKT_LEN - 1] &&
                yrow >= 0 && yrow < RX_TEX_DIM) {
              uint32_t samp[4];
              for (int w = 0; w < 4; w++) {
                const uint8_t *b = &pkt_buf[2 + w * 4];
                samp[w] = (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
                          ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
              }
              borg_set_sampler(samp);
              borg_upload_texture_row(&pkt_buf[2 + RX_TEX_SAMP_LEN], yrow, RX_TEX_DIM);
              got_tex_row = 1;
              success = 1;
              skip_gap = 1;  // next texture row (or the closing MVP) immediately follows
            }
          } else if (pkt_marker == 0xB0) {
            if (!ok) {
              puts_uart("B0:short\r\n");
            } else {
              uint8_t csum = 0;
              for (int i = 1; i < RX_SHADER_PKT_LEN - 1; i++) csum ^= pkt_buf[i];
              uint8_t stage = pkt_buf[1];
              uint32_t blen = (uint32_t)pkt_buf[2] | ((uint32_t)pkt_buf[3] << 8);
              if (csum == pkt_buf[RX_SHADER_PKT_LEN - 1] && stage <= 1 &&
                  blen >= 6 && blen <= RX_SHADER_MAX) {
                borg_stage_shader(stage, &pkt_buf[4]);
                got_shader_pkt = 1;
                skip_gap = 1;  // frag immediately follows vert on the wire
                if (stage == 0) staged_vert = 1; else staged_frag = 1;
                success = 1;
              } else {
                puts_uart("B0:csum\r\n");
              }
            }
          } else if (ok && pkt_marker == 0xB2) {
            // Push constants: [1]=off_words, [2]=n_words, [3..]=LE u32 words.
            uint8_t csum = 0;
            for (int i = 1; i < RX_PUSH_PKT_LEN - 1; i++) csum ^= pkt_buf[i];
            uint32_t off_w = pkt_buf[1];
            uint32_t n_w   = pkt_buf[2];
            if (csum == pkt_buf[RX_PUSH_PKT_LEN - 1] &&
                n_w >= 1 && n_w <= RX_PUSH_MAX_WORDS &&
                off_w < RX_PUSH_MAX_WORDS &&
                n_w <= RX_PUSH_MAX_WORDS - off_w) {
              // Rebuild words from LE bytes rather than aliasing pkt_buf to
              // uint32_t*: pkt_buf[3] is not 4-byte aligned, and this core
              // does not do unaligned loads.
              uint32_t w[RX_PUSH_MAX_WORDS];
              for (uint32_t i = 0; i < n_w; i++) {
                int b = 3 + (int)i * 4;
                w[i] = (uint32_t)pkt_buf[b]            |
                       ((uint32_t)pkt_buf[b+1] << 8)   |
                       ((uint32_t)pkt_buf[b+2] << 16)  |
                       ((uint32_t)pkt_buf[b+3] << 24);
              }
              borg_set_push_constants(w, off_w, n_w);
              success = 1;
              skip_gap = 1;  // push constants precede the draw's MVP on the wire
            }
          }

          // Resync: a checksum failure (or short read) means the framing
          // slipped — most likely the CPU missed a poll window mid-burst and
          // the RX FIFO (only 1 byte deep) silently dropped the intervening
          // bytes.  Because every packet in a burst streams back-to-back with
          // no idle gap, the true start of the NEXT packet is often still
          // sitting in the tail of what we just (mis-)read.  Scan for it
          // instead of treating one bad packet as fatal for the whole burst.
          if (!success && pkt_marker != 0xB1) {
            for (int q = 1; q < pkt_pos; q++) {
              uint8_t m = pkt_buf[q];
              if (m == 0xAD || m == 0xAE || m == 0xAF || m == 0xB0 || m == 0xB2) {
                int rem = pkt_pos - q;
                for (int i = 0; i < rem; i++) pkt_buf[i] = pkt_buf[q + i];
                pending_len = rem;
                skip_gap = 1;
                break;
              }
            }
          }
      }
      if (!got_tex_row && !got_shader_pkt && !got_geom_pkt && pending_len == 0) break;
    }
    if (staged_vert) puts_uart("FW: vert shader uploaded\r\n");
    if (staged_frag) puts_uart("FW: frag shader uploaded\r\n");

    // CTS host-mailbox: override geometry + MVP if the headless harness has
    // filled the DRAM region (transport-independent, no UART required).
    borg_float_t cts_mvp[16];
    int cts_frame = cts_active && cts_load_mailbox(cts_mvp);

    // Wait for borgvk to deliver geometry and an MVP before rendering.
    if (!rx_have_geom || (!have_mvp && !cts_frame))
      continue;

    borg_draw_data_t draw;
    if (cts_frame) {
      for (int i = 0; i < 16; i++) draw.uniforms[i] = cts_mvp[i];
    } else {
      for (int i = 0; i < 16; i++)
        draw.uniforms[i] = host_mvp[i];  // float32 on the wire = datapath float
    }

    // Tile clear colour: FP16, the tile buffer's own format.
    rgb16_t bg = cts_active ? (rgb16_t){0,0,0} : (rgb16_t){0x3266, 0x3266, 0x3266};

#ifdef BORG_DRAW_MODE_CUBE
    // The draw front end has no per-triangle descriptor cache to keep valid
    // across frames -- re-stage the (small, static) geometry and the fresh
    // MVP every frame; borg_set_texture still only needs doing once.
    // borgFastFrameBegin only records the clear colour (borgBinRenderAutonomous's
    // TBR-specific state it also resets is unused on this path).
    borgFastFrameBegin(bg);
    if (!g_geom_recorded) {
      borg_set_texture(RX_TEX_DIM, RX_TEX_DIM);
      g_geom_recorded = 1;
    }
    borgDrawSubmitGeom(&draw, rx_geom_pos, rx_geom_nverts, rx_geom_idx, rx_geom_uv,
                      rx_geom_ntris);
#else
    if (rx_have_geom && g_geom_recorded) {
      borgFastFrameBegin(bg);
      borgUpdateUniforms(&draw);
    } else {
      borg_clear_zbuffer(0, bg);
      borg_set_texture(RX_TEX_DIM, RX_TEX_DIM);
      if (!g_geom_recorded) borgInvalidateCommandBuffer();
      draw_received_geom(&draw);
    }
#endif
    borg_present(0);
    // No-op under BORG_DRAW_MODE_CUBE: g_geom_recorded is already set above,
    // and borgCommandBufferValid() (legacy's own command-buffer cache) is
    // never set true on the draw front end's path.
    if (rx_have_geom && borgCommandBufferValid())
      g_geom_recorded = 1;

#ifndef TARGET_ULX3S
    // Simulation sync: poll until the viewer has consumed the framebuffer and
    // cleared the done marker, then start the next drain/render cycle.  The
    // marker's double-buffer slot alternates every present, so ask the driver
    // for the address rather than recomputing it (it doesn't track back_buf).
    int done_offset = borg_last_present_marker_offset();
    while (DRAM_OUT(done_offset) == DONE_MARKER)
      ;
#endif
  }
  return 0;
}
