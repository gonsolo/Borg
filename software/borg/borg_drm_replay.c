// SPDX-License-Identifier: GPL-3.0-or-later
// Renders the captured borgvk frame from Linux userspace through the Borg DRM render node:
// borg_core.c (host build) with its register and memory hooks turned into ioctls, the frame
// printed as hex between markers (scripts/uart_ppm.py turns it into a picture).
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <drm/borg_drm.h>
#include "borg_core.h"
#include "borg_layout.h"
#include "replay_capture.h"

#define WIDTH 128
#define HEIGHT 128

static int fd = -1;

// Writes are batched; the batch is flushed when the kind changes, a read needs the device,
// or a memory run stops being contiguous. Ordering between registers and memory is kept.
#define MAX_REGS 1024
#define MAX_MEM 4096
static uint32_t regs[MAX_REGS * 2];
static unsigned nregs;
static uint32_t mem[MAX_MEM];
static unsigned mem_off, nmem;

static void die(const char *what) { perror(what); _exit(1); }

static void flush_regs(void) {
  if (!nregs) return;
  struct drm_borg_reg_writes w = {.writes = (uintptr_t)regs, .count = nregs};
  if (ioctl(fd, DRM_IOCTL_BORG_REG_WRITES, &w)) die("reg writes");
  nregs = 0;
}

static void flush_mem(void) {
  if (!nmem) return;
  struct drm_borg_mem m = {.data = (uintptr_t)mem, .offset = mem_off, .length = nmem * 4};
  if (ioctl(fd, DRM_IOCTL_BORG_MEM_WRITE, &m)) die("mem write");
  nmem = 0;
}

void bh_reg_write(uint32_t off, uint32_t v) {
  flush_mem();
  if (nregs == MAX_REGS) flush_regs();
  regs[nregs * 2] = off;
  regs[nregs * 2 + 1] = v;
  nregs++;
}

uint32_t bh_reg_read(uint32_t off) {
  flush_mem();
  flush_regs();
  struct drm_borg_reg_read r = {.offset = off};
  if (ioctl(fd, DRM_IOCTL_BORG_REG_READ, &r)) die("reg read");
  return r.value;
}

void bh_dram_write(uint32_t a, uint32_t v) {
  flush_regs();
  if (nmem && (a != mem_off + nmem * 4 || nmem == MAX_MEM)) flush_mem();
  if (!nmem) mem_off = a;
  mem[nmem++] = v;
}

uint32_t bh_dram_read(uint32_t a) {
  flush_regs();
  flush_mem();
  uint32_t v;
  struct drm_borg_mem m = {.data = (uintptr_t)&v, .offset = a, .length = 4};
  if (ioctl(fd, DRM_IOCTL_BORG_MEM_READ, &m)) die("mem read");
  return v;
}

int main(void) {
  for (int i = 0; i < 100 && fd < 0; i++) {
    fd = open("/dev/dri/renderD128", O_RDWR);
    if (fd < 0) usleep(100000);
  }
  if (fd < 0) die("open renderD128");
  struct drm_borg_info info;
  if (ioctl(fd, DRM_IOCTL_BORG_INFO, &info)) die("info");
  printf("borg_drm_replay: reg_size=%u mem_size=%u abi=%u\n", info.reg_size, info.mem_size, info.abi);

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
  flush_mem();
  flush_regs();
  printf("replay: rendered\n");

  int words = WIDTH * HEIGHT * (borg_core_flush_format() ? 4 : 2) / 4;
  static uint32_t frame[WIDTH * HEIGHT];
  struct drm_borg_mem m = {.data = (uintptr_t)frame, .offset = DRAM_OUT_BASE_SPI, .length = words * 4};
  if (ioctl(fd, DRM_IOCTL_BORG_MEM_READ, &m)) die("frame read");
  printf("FRAME-BEGIN %08x %08x %08x\r\n", WIDTH, HEIGHT, (unsigned)borg_core_flush_format());
  for (int i = 0; i < words; i++) printf("%08x%s", frame[i], i % 8 == 7 ? "\n" : "");
  printf("\r\nFRAME-END\r\n");
  return 0;
}
