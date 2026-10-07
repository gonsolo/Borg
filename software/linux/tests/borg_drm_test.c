// SPDX-License-Identifier: GPL-2.0
// Exercises the Borg render node: info, register write/read, memory write/read and the error paths.
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <drm/borg_drm.h>

static int fails;
static uint8_t big[32768];
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
  uint32_t bad[2] = {0x3fc, 1};
  rw.writes = (uintptr_t)bad; rw.count = 1;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_REG_WRITES, &rw) != 0 && errno == EINVAL, "write to an undefined register must fail");
  bad[0] = 0x1ac;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_REG_WRITES, &rw) != 0 && errno == EINVAL, "write to the read-only status must fail");
  m.offset = info.mem_size - 32;
  m.length = 64;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_MEM_WRITE, &m) != 0, "memory write past the end must fail");

  m.data = (uintptr_t)big; m.offset = 0x1000; m.length = sizeof(big);
  CHECK(ioctl(fd, DRM_IOCTL_BORG_MEM_READ, &m) == 0, "32 KB memory read into a static buffer");

  /* GEM: create, map, write and read through the mapping, close. */
  struct drm_borg_gem_create gc = {.size = 100};
  CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_CREATE, &gc) == 0 && gc.handle != 0, "gem create");
  struct drm_borg_gem_mmap gm = {.handle = gc.handle};
  CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_MMAP, &gm) == 0, "gem mmap offset");
  uint8_t *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, gm.offset);
  CHECK(p != MAP_FAILED, "mmap the buffer");
  if (p != MAP_FAILED) {
    memset(p, 0xa5, 4096);
    CHECK(p[0] == 0xa5 && p[4095] == 0xa5, "write and read through the mapping");
    munmap(p, 4096);
  }
  struct drm_gem_close cl = {.handle = gc.handle};
  CHECK(ioctl(fd, DRM_IOCTL_GEM_CLOSE, &cl) == 0, "gem close");
  CHECK(ioctl(fd, DRM_IOCTL_GEM_CLOSE, &cl) != 0, "second close must fail");
  CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_MMAP, &gm) != 0, "mmap offset of a closed handle must fail");
  struct drm_borg_gem_create bad_gc = {.size = 0};
  CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_CREATE, &bad_gc) != 0 && errno == EINVAL, "size 0 must fail");
  bad_gc.size = 1ull << 40;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_CREATE, &bad_gc) != 0 && errno == EINVAL, "huge size must fail");
  struct drm_borg_gem_create a = {.size = 8192}, b = {.size = 8192};
  struct drm_borg_gem_mmap am, bm;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_CREATE, &a) == 0 && ioctl(fd, DRM_IOCTL_BORG_GEM_CREATE, &b) == 0, "two buffers");
  am.handle = a.handle; bm.handle = b.handle;
  CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_MMAP, &am) == 0 && ioctl(fd, DRM_IOCTL_BORG_GEM_MMAP, &bm) == 0, "two offsets");
  uint8_t *pa = mmap(NULL, 8192, PROT_READ | PROT_WRITE, MAP_SHARED, fd, am.offset);
  uint8_t *pb = mmap(NULL, 8192, PROT_READ | PROT_WRITE, MAP_SHARED, fd, bm.offset);
  if (pa != MAP_FAILED && pb != MAP_FAILED) {
    memset(pa, 1, 8192); memset(pb, 2, 8192);
    CHECK(pa[8191] == 1 && pb[0] == 2, "buffers do not alias");
  } else CHECK(0, "map two buffers");
  for (int i = 0; i < 300; i++) {   /* create and close many, 4 MB each: the memory is released on close */
    struct drm_borg_gem_create g = {.size = 4u << 20};
    if (ioctl(fd, DRM_IOCTL_BORG_GEM_CREATE, &g) != 0) { CHECK(0, "stress create"); break; }
    struct drm_borg_gem_mmap gmm = {.handle = g.handle};
    CHECK(ioctl(fd, DRM_IOCTL_BORG_GEM_MMAP, &gmm) == 0, "stress mmap offset");
    struct drm_gem_close c2 = {.handle = g.handle};
    CHECK(ioctl(fd, DRM_IOCTL_GEM_CLOSE, &c2) == 0, "stress close");
  }

  puts(fails ? "BORG_DRM_TEST FAIL" : "BORG_DRM_TEST PASS");
  return fails != 0;
}
