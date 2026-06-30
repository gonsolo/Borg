// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_kernel.c — thin render kernel driven by the borgvk Mesa driver.
// Boots, drains borgvk wire packets (0xAD/0xAE/0xAF/0xB0/0xB1) from UART,
// and drives the autonomous TBR hardware.  No hardcoded geometry, shaders, or
// texture — all content is uploaded at runtime by borgvk / cube.c.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_math.h"
#include "borg_sys.h"
#include "compiler/shader_blobs.h"

// Host-uploaded geometry (0xAE packet): deduplicated model-space vertices +
// an indexed triangle list with per-triangle-vertex UVs.
#define RX_GEOM_MAX_VERTS 16
#define RX_GEOM_MAX_TRIS  12
// Payload after marker: nverts(1), ntris(1), verts(MAX_VERTS*6 B),
// idx(MAX_TRIS*3 B), uv(MAX_TRIS*12 B), xor_checksum(1).
#define RX_GEOM_PKT_LEN \
  (1 + 2 + RX_GEOM_MAX_VERTS * 6 + RX_GEOM_MAX_TRIS * 3 + RX_GEOM_MAX_TRIS * 12 + 1)
static fp16_t  rx_geom_pos[RX_GEOM_MAX_VERTS * 3];
static uint8_t rx_geom_idx[RX_GEOM_MAX_TRIS * 3];
static fp16_t  rx_geom_uv[RX_GEOM_MAX_TRIS * 3 * 2];
// Per-vertex RGB for the CTS flat-shaded path (zero when borgvk is the source).
static fp16_t  rx_geom_color[RX_GEOM_MAX_VERTS * 3];
static int     rx_have_color  = 0;
static int     rx_geom_nverts = 0;
static int     rx_geom_ntris  = 0;
static int     rx_have_geom   = 0;
static int     g_geom_recorded = 0;

// 0xAF texture-row packet: marker(1), y(1), row_pixels(TEX_DIM * 6 B), csum(1)
#define RX_TEX_DIM       64
#define RX_TEX_PKT_LEN   (1 + 1 + RX_TEX_DIM * 6 + 1)

// 0xB0 borgc shader upload: marker(1), stage(1), len(2 LE), blob(RX_SHADER_MAX), csum(1)
#define RX_SHADER_MAX     512
#define RX_SHADER_PKT_LEN (1 + 1 + 2 + RX_SHADER_MAX + 1)

#define RX_PKT_BUF_LEN \
  (RX_GEOM_PKT_LEN > RX_TEX_PKT_LEN \
     ? (RX_GEOM_PKT_LEN > RX_SHADER_PKT_LEN ? RX_GEOM_PKT_LEN : RX_SHADER_PKT_LEN) \
     : (RX_TEX_PKT_LEN  > RX_SHADER_PKT_LEN ? RX_TEX_PKT_LEN  : RX_SHADER_PKT_LEN))

// CTS host-mailbox: a transport-independent DRAM region the headless test
// harness fills with geometry + MVP so the sim needs no UART drain.
#define CTS_MB(n) DRAM_OUT_RAW(BORG_CTS_MAILBOX_SPI + (n) * 4)

static int cts_mailbox_present(void) {
  return CTS_MB(BORG_CTS_OFF_MAGIC) == BORG_CTS_MAGIC;
}

static int cts_load_mailbox(fp16_t mvp_out[16]) {
  if (!cts_mailbox_present()) return 0;
  int nv = (int)CTS_MB(BORG_CTS_OFF_NVERTS);
  int nt = (int)CTS_MB(BORG_CTS_OFF_NTRIS);
  if (nv < 1 || nv > RX_GEOM_MAX_VERTS || nt < 1 || nt > RX_GEOM_MAX_TRIS)
    return 0;
  for (int i = 0; i < 16; i++)
    mvp_out[i] = (fp16_t)(CTS_MB(BORG_CTS_OFF_MVP + i) & 0xFFFF);
  for (int i = 0; i < nv * 3; i++) {
    rx_geom_pos[i]   = (fp16_t)(CTS_MB(BORG_CTS_OFF_POS   + i) & 0xFFFF);
    rx_geom_color[i] = (fp16_t)(CTS_MB(BORG_CTS_OFF_COLOR + i) & 0xFFFF);
  }
  for (int i = 0; i < nt * 3; i++) {
    rx_geom_idx[i]        = (uint8_t)CTS_MB(BORG_CTS_OFF_IDX + i);
    rx_geom_uv[i * 2 + 0] = FP16_ZERO;
    rx_geom_uv[i * 2 + 1] = FP16_ZERO;
  }
  rx_geom_nverts = nv;
  rx_geom_ntris  = nt;
  rx_have_geom   = 1;
  rx_have_color  = 1;
  return 1;
}

static void draw_received_geom(const borg_draw_data_t *draw) {
  borgTransformVerts(draw, rx_geom_pos, rx_geom_nverts);
  for (int t = 0; t < rx_geom_ntris; t++) {
    int idx[3];
    borg_vertex_t tri[3];
    for (int v = 0; v < 3; v++) {
      int vi = rx_geom_idx[t * 3 + v];
      idx[v] = vi;
      fp16_t cr = FP16_ONE, cg = FP16_ONE, cb = FP16_ONE;
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

int main() {
  borgCreateDevice();

  // Load baked shaders so the GPU pipeline is valid before borgvk uploads its
  // own.  borgvk overrides vert+frag at runtime via 0xB0; rast stays baked.
  BorgShaderModule vert, rast, frag;
  borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
  borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
  borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
  borgCreateGraphicsPipeline(&vert, &rast, &frag);
  // Baked frag is a texel×vertex_color Gouraud shader; borgvk's borgc frag uses
  // frag_pos instead.  borg_stage_shader() resets the mode when frag is uploaded.
  borg_set_frag_vertex_color(1);

  const int cts_active = cts_mailbox_present();

  static uint8_t pkt_buf[RX_PKT_BUF_LEN];
  static float host_mvp[16];
  static int have_mvp = 0;

  while (1) {
    // Drain borgvk packets from the UART.  Gap-sync: consume bytes until the
    // line has been idle for GAP_CYCLES of real time (indicating a packet
    // boundary), then wait for the next marker and read a fixed-length payload.
    // This avoids mid-packet framing errors even if bytes are dropped during
    // the previous borg_present() call (the UART FIFO is only 1 byte deep).
    // Greedy loop: keep draining while texture or shader bursts keep arriving;
    // break on any other packet type so we render once per MVP packet.
    int staged_vert = 0, staged_frag = 0;
    int skip_gap = 0;
    for (int drain_iter = 0; drain_iter < 16; drain_iter++) {
      int got_tex_row = 0;
      int got_shader_pkt = 0;
      // At 25 MHz: inter-byte = 87 µs = 2175 cyc; smallest inter-packet gap
      // (0xAD) ≈ 6.3 ms.  300 µs = 7500 cyc sits safely between the two.
      const unsigned GAP_CYCLES   = 7500;
      const unsigned GUARD_CYCLES = 4000000;  // ~160 ms hard cap

      if (!skip_gap) {
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
      if (uart_rx_ready()) {
        int pkt_marker = (uint8_t)getc_uart();
        if (pkt_marker == 0xB1) { borg_serial_reload(); break; }
        int need = (pkt_marker == 0xAD) ? 66 :
                   (pkt_marker == 0xAE) ? RX_GEOM_PKT_LEN :
                   (pkt_marker == 0xAF) ? RX_TEX_PKT_LEN :
                   (pkt_marker == 0xB0) ? RX_SHADER_PKT_LEN : 0;
        if (need) {
          pkt_buf[0] = (uint8_t)pkt_marker;
          int pkt_pos = 1;
          int ok = 1;
          while (pkt_pos < need) {
            for (volatile int t = 4000; !uart_rx_ready() && t > 0; t--) ;
            if (!uart_rx_ready()) { ok = 0; break; }
            pkt_buf[pkt_pos++] = (uint8_t)getc_uart();
          }

          if (ok && pkt_marker == 0xAD) {
            // Full 4×4 MVP from borgvk: 16 LE float32 + 1 XOR checksum.
            uint8_t csum = 0;
            for (int i = 1; i <= 64; i++) csum ^= pkt_buf[i];
            if (csum == pkt_buf[65]) {
              union { uint32_t u; float f; } conv;
              for (int i = 0; i < 16; i++) {
                int base = 1 + i * 4;
                conv.u = (uint32_t)pkt_buf[base]           |
                         ((uint32_t)pkt_buf[base+1] << 8)  |
                         ((uint32_t)pkt_buf[base+2] << 16) |
                         ((uint32_t)pkt_buf[base+3] << 24);
                host_mvp[i] = conv.f;
              }
              have_mvp = 1;
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
              int ibase = vbase + RX_GEOM_MAX_VERTS * 6;
              int ubase = ibase + RX_GEOM_MAX_TRIS * 3;
              for (int i = 0; i < nv * 3; i++)
                rx_geom_pos[i] = (uint16_t)pkt_buf[vbase + i*2] |
                                 ((uint16_t)pkt_buf[vbase + i*2 + 1] << 8);
              for (int i = 0; i < nt * 3; i++)
                rx_geom_idx[i] = pkt_buf[ibase + i];
              for (int i = 0; i < nt * 6; i++)
                rx_geom_uv[i] = (uint16_t)pkt_buf[ubase + i*2] |
                                ((uint16_t)pkt_buf[ubase + i*2 + 1] << 8);
              rx_geom_nverts = nv;
              rx_geom_ntris  = nt;
              rx_have_geom   = 1;
              rx_have_color  = 0;
            }
          } else if (ok && pkt_marker == 0xAF) {
            // Texture row: [1]=y, then RX_TEX_DIM texels as RGB-FP16.
            uint8_t csum = 0;
            for (int i = 1; i < RX_TEX_PKT_LEN - 1; i++) csum ^= pkt_buf[i];
            int yrow = pkt_buf[1];
            if (csum == pkt_buf[RX_TEX_PKT_LEN - 1] &&
                yrow >= 0 && yrow < RX_TEX_DIM) {
              borg_upload_texture_row(&pkt_buf[2], yrow, RX_TEX_DIM);
              got_tex_row = 1;
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
              } else {
                puts_uart("B0:csum\r\n");
              }
            }
          }
        }
      }
      if (!got_tex_row && !got_shader_pkt) break;
    }
    if (staged_vert) puts_uart("FW: vert shader uploaded\r\n");
    if (staged_frag) puts_uart("FW: frag shader uploaded\r\n");

    // CTS host-mailbox: override geometry + MVP if the headless harness has
    // filled the DRAM region (transport-independent, no UART required).
    fp16_t cts_mvp[16];
    int cts_frame = cts_active && cts_load_mailbox(cts_mvp);

    // Wait for borgvk to deliver geometry and an MVP before rendering.
    if (!rx_have_geom || (!have_mvp && !cts_frame))
      continue;

    borg_draw_data_t draw;
    if (cts_frame) {
      for (int i = 0; i < 16; i++) draw.uniforms[i] = cts_mvp[i];
    } else {
      for (int i = 0; i < 16; i++)
        draw.uniforms[i] = fp16_from_float(host_mvp[i]);
    }

    rgb16_t bg = cts_active ? (rgb16_t){0,0,0} : (rgb16_t){0x3266, 0x3266, 0x3266};

    if (rx_have_geom && g_geom_recorded) {
      borgFastFrameBegin(bg);
      borgUpdateUniforms(&draw);
    } else {
      borg_clear_zbuffer(0, bg);
      borg_set_texture(RX_TEX_DIM, RX_TEX_DIM);
      if (!g_geom_recorded) borgInvalidateCommandBuffer();
      draw_received_geom(&draw);
    }
    borg_present(0);
    if (rx_have_geom && borgCommandBufferValid())
      g_geom_recorded = 1;

#ifndef TARGET_ULX3S
    // Simulation sync: poll until the viewer has consumed the framebuffer and
    // cleared the done marker, then start the next drain/render cycle.
    int done_offset = BORG_FB_WIDTH * BORG_FB_HEIGHT * 2;
    while (DRAM_OUT(done_offset) == DONE_MARKER)
      ;
#endif
  }
  return 0;
}
