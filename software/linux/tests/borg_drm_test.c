// SPDX-License-Identifier: GPL-2.0
// Exercises the Borg render node: info, register write/read, memory write/read and the error paths.
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <drm/borg_drm.h>

static int fails;
#define CHECK(c, msg) do { if (!(c)) { printf("FAIL: %s (errno %d)\n", msg, errno); fails++; } } while (0)

int main(void) {
  int fd = -1;
  for (int i = 0; i < 100 && fd < 0; i++) {
    fd = open("/dev/dri/renderD128", O_RDWR);
    if (fd < 0) usleep(100000);
  }
  if (fd < 0) { perror("open renderD128"); return 2; }

  struct drm_borg_info info;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_INFO, &info) == 0, "info");
  printf("borg: reg_size=%u mem_size=%u abi=%u\n", info.reg_size, info.mem_size, info.abi);

  uint32_t w[4] = {0x10, 0xdeadbeef, 0x14, 0x12345678};
  struct drm_borg_reg_writes rw = {.writes = (uintptr_t)w, .count = 2};
  CHECK(ioctl(fd, DRM_IOCTL_BORG_REG_WRITES, &rw) == 0, "reg writes");
  struct drm_borg_reg_read rr = {.offset = 0x14};
  CHECK(ioctl(fd, DRM_IOCTL_BORG_REG_READ, &rr) == 0 && rr.value == 0x12345678, "reg read back");

  uint8_t out[64], in[64];
  for (int i = 0; i < 64; i++) out[i] = i * 7 + 1;
  struct drm_borg_mem m = {.data = (uintptr_t)out, .offset = 0x100, .length = 64};
  CHECK(ioctl(fd, DRM_IOCTL_BORG_MEM_WRITE, &m) == 0, "mem write");
  m.data = (uintptr_t)in;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_MEM_READ, &m) == 0 && !memcmp(in, out, 64), "mem read back");

  rr.offset = info.reg_size;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_REG_READ, &rr) != 0, "register read past the block must fail");
  rr.offset = 2;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_REG_READ, &rr) != 0 && errno == EINVAL, "unaligned register read must fail");
  m.offset = info.mem_size - 32;
  m.length = 64;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_MEM_WRITE, &m) != 0, "memory write past the end must fail");

  puts(fails ? "BORG_DRM_TEST FAIL" : "BORG_DRM_TEST PASS");
  return fails != 0;
}
