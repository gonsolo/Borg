// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_replay.c -- renders one captured borgvk frame with no host: the wire
// packets are linked in, fed to borg_core_packet(), and the frame goes out of
// the UART as hex between markers (scripts/uart_ppm.py turns it into a picture).

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_sys.h"
#include "compiler/shader_blobs.h"
#include "replay_capture.h"

// The blind UART needs one byte time between writes; putc_uart's loop is far slower than that here.
static void fast_putc(int c) {
  UART_TX = c;
  unsigned t = rdcycle();
  while ((unsigned)(rdcycle() - t) < (CLOCK_MHZ * 1000000u / BORG_UART_BAUD) * 10 + 20) ;
}

static void put_hex32(uint32_t v) {
  for (int s = 28; s >= 0; s -= 4) fast_putc("0123456789abcdef"[(v >> s) & 15]);
}

int main() {
#ifdef REPLAY_HEARTBEAT
  for (;;) fast_putc('A');   // UART/boot check only; the Borg is never touched
#endif
  borgCreateDevice();
  BorgShaderModule vert, rast, frag;
  borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
  borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
  borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
  borgCreateGraphicsPipeline(&vert, &rast, &frag);

  for (const uint8_t *p = replay_capture; p < replay_capture + replay_capture_len;) {
    int n = borg_core_pkt_len(*p);
    if (!n) { puts_uart("replay: bad packet\r\n"); break; }
    if (borg_core_packet((uint8_t *)p) == BC_BAD) puts_uart("replay: rejected packet\r\n");
    p += n;
  }
  puts_uart("replay: sent\r\n");

  borgFastFrameBegin((rgb16_t){0x3266, 0x3266, 0x3266});
  borg_core_stage(0);
  borg_present(0);
  puts_uart("replay: rendered\r\n");

  // The first present renders into buffer 1, one frame stride (frame words plus the marker word) in.
  int words = borg_fb_width * borg_fb_height * (borg_core_flush_format() ? 4 : 2) / 4;
  int base = words + 1;
  puts_uart("FRAME-BEGIN ");
  put_hex32((uint32_t)borg_fb_width);  fast_putc(' ');
  put_hex32((uint32_t)borg_fb_height); fast_putc(' ');
  put_hex32((uint32_t)borg_core_flush_format()); puts_uart("\r\n");
  for (int i = 0; i < words; i++) {
    put_hex32(DRAM_OUT(base + i));
    if (i % 8 == 7) fast_putc('\n');
  }
  puts_uart("\r\nFRAME-END\r\n");
  for (;;) ;
}
