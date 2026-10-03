// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_kernel.c — thin render kernel driven by the borgvk Mesa driver.
// Boots, drains borgvk wire packets (0xAD/0xAE/0xAF/0xB0/0xB1/0xB2/0xB3/0xB4/0xB5) from UART,
// and drives the autonomous TBR hardware.  No hardcoded geometry, shaders, or
// texture — all content is uploaded at runtime by borgvk / cube.c.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_sys.h"
#include "compiler/shader_blobs.h"

// Wire-packet formats and their handling live in borg_core.c (shared with the
// direct simulator); this file only frames them off the UART.
// CTS host-mailbox: a transport-independent DRAM region the headless test
// harness fills with geometry + MVP so the sim needs no UART drain.
#define CTS_MB(n) DRAM_OUT_RAW(BORG_CTS_MAILBOX_SPI + (n) * 4)


static int cts_mailbox_present(void) {
  return CTS_MB(BORG_CTS_OFF_MAGIC) == BORG_CTS_MAGIC;
}

// The mailbox also carries per-vertex colours (BORG_CTS_OFF_COLOR), which
// nothing renders: the draw path runs cube.vert, which reads positions and
// texcoords from its UBO. CTS draws need vertex-buffer input in draw mode.
static int cts_load_mailbox(borg_float_t mvp_out[16]) {
  if (!cts_mailbox_present()) return 0;
  int nv = (int)CTS_MB(BORG_CTS_OFF_NVERTS);
  int nt = (int)CTS_MB(BORG_CTS_OFF_NTRIS);
  if (nv < 1 || nv > BC_GEOM_MAX_VERTS || nt < 1 || nt > BC_GEOM_MAX_TRIS)
    return 0;
  borg_float_t pos[BC_GEOM_MAX_VERTS * 3];
  uint8_t idx[BC_GEOM_MAX_TRIS * 3];
  for (int i = 0; i < 16; i++)
    mvp_out[i] = CTS_MB(BORG_CTS_OFF_MVP + i);
  for (int i = 0; i < nv * 3; i++)
    pos[i] = CTS_MB(BORG_CTS_OFF_POS + i);
  for (int i = 0; i < nt * 3; i++)
    idx[i] = (uint8_t)CTS_MB(BORG_CTS_OFF_IDX + i);
  borg_core_set_geom(pos, idx, 0, nv, nt);   // no UVs: zero
  return 1;
}

int main() {
  borgCreateDevice();

  // Load baked shaders (borgc's draw-mode compiles of cube.vert/cube.frag) so
  // the GPU pipeline is valid before borgvk uploads its own. borgvk overrides
  // vert+frag at runtime via 0xB0; the raster program is a hardware ROM.
  BorgShaderModule vert, rast, frag;
  borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
  borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
  borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
  borgCreateGraphicsPipeline(&vert, &rast, &frag);

  const int cts_active = cts_mailbox_present();

  static uint8_t pkt_buf[BC_PKT_LEN_MAX];
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
      int got_state_pkt = 0;   // blend / push-constant state: the draw's MVP follows
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
      int need = borg_core_pkt_len((uint8_t)pkt_marker);
      if (need) {
          int ok = 1;
          while (pkt_pos < need) {
            for (volatile int t = 4000; !uart_rx_ready() && t > 0; t--) ;
            if (!uart_rx_ready()) { ok = 0; break; }
            pkt_buf[pkt_pos++] = (uint8_t)getc_uart();
          }

          int success = 0;
          if (ok) {
            // borg_core_packet checks the checksum and the fields, then acts on the packet.
            switch (borg_core_packet(pkt_buf)) {
            case BC_MVP:          success = 1; break;
            case BC_GEOM:         success = 1; got_geom_pkt = 1; skip_gap = 1; break;  // texture rows follow
            case BC_TEXROW:
            case BC_TEXG:         success = 1; got_tex_row = 1; skip_gap = 1; break;   // next row / the MVP follows
            case BC_SHADER_VERT:  success = 1; got_shader_pkt = 1; skip_gap = 1; staged_vert = 1; break;
            case BC_SHADER_FRAG:  success = 1; got_shader_pkt = 1; skip_gap = 1; staged_frag = 1; break;
            case BC_STATE:
            case BC_TARGET:       success = 1; got_state_pkt = 1; skip_gap = 1; break; // the MVP follows
            default:
              if (pkt_marker == 0xB0) puts_uart("B0:csum\r\n");
              break;
            }
          } else if (pkt_marker == 0xB0) {
            puts_uart("B0:short\r\n");
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
              if (m == 0xAD || m == 0xAE || m == 0xAF || m == 0xB0 || m == 0xB2 || m == 0xB3 || m == 0xB4 || m == 0xB5) {
                int rem = pkt_pos - q;
                for (int i = 0; i < rem; i++) pkt_buf[i] = pkt_buf[q + i];
                pending_len = rem;
                skip_gap = 1;
                break;
              }
            }
          }
      }
      if (!got_tex_row && !got_shader_pkt && !got_geom_pkt && !got_state_pkt &&
          pending_len == 0) break;
    }
    if (staged_vert) puts_uart("FW: vert shader uploaded\r\n");
    if (staged_frag) puts_uart("FW: frag shader uploaded\r\n");

    // CTS host-mailbox: override geometry + MVP if the headless harness has
    // filled the DRAM region (transport-independent, no UART required).
    borg_float_t cts_mvp[16];
    int cts_frame = cts_active && cts_load_mailbox(cts_mvp);

    // Wait for borgvk to deliver geometry and an MVP before rendering.
    if (!cts_frame && !borg_core_ready())
      continue;

    // Tile clear colour: FP16, the tile buffer's own format.
    rgb16_t bg = cts_active ? (rgb16_t){0,0,0} : (rgb16_t){0x3266, 0x3266, 0x3266};

    // Re-stage the (small, static) geometry and the fresh MVP every frame.
    borgFastFrameBegin(bg);
    borg_core_stage(cts_frame ? cts_mvp : 0);
    borg_present(0);

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
