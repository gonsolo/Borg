// SPDX-License-Identifier: GPL-3.0-or-later
// Renders the captured borgvk frame from Linux userspace through the Borg DRM render node:
// borg_core.c (host build) with its register and memory hooks turned into ioctls, the frame
// printed as hex between markers (scripts/uart_ppm.py turns it into a picture).
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "borg_hw.h"
#include "borg_core.h"
#include "borg_layout.h"
#include "replay_capture.h"

#define WIDTH 128
#define HEIGHT 128

int main(void) {
  if (borg_hw_open()) { perror("open Borg device"); return 1; }

  borg_core_set_flush_format(0);
  borg_core_init(WIDTH, HEIGHT);
  const uint8_t *p = replay_capture, *end = replay_capture + replay_capture_len;
  int target_seen = 0;
  while (p < end) {
    if (*p == 0xB1) { p++; continue; }
    int n = borg_core_pkt_len(*p);
    if (!n || p + n > end) { printf("replay: bad packet %02x\n", *p); break; }
    int kind = borg_core_packet((uint8_t *)p);
    if (kind == BC_BAD) { printf("replay: rejected packet %02x\n", *p); break; }
    if (kind == BC_TARGET) target_seen = 1;
    if (kind == BC_DRAW || (kind == BC_MVP && borg_core_ready())) {
      if (!target_seen) borg_core_set_clear(0x3266, 0x3266, 0x3266);
      if (kind == BC_DRAW) borg_core_list_draw(); else borg_core_draw(NULL, 0);
    }
    p += n;
  }
  borg_core_list_flush();
  borg_hw_flush();
  printf("replay: rendered\n");

  int words = WIDTH * HEIGHT * (borg_core_flush_format() ? 4 : 2) / 4;
  static uint32_t frame[WIDTH * HEIGHT];
  borg_hw_mem_read(DRAM_OUT_BASE_SPI, frame, words * 4);
  printf("FRAME-BEGIN %08x %08x %08x\r\n", WIDTH, HEIGHT, (unsigned)borg_core_flush_format());
  for (int i = 0; i < words; i++) printf("%08x%s", frame[i], i % 8 == 7 ? "\n" : "");
  printf("\r\nFRAME-END\r\n");
  return 0;
}
