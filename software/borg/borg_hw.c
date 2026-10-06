// SPDX-License-Identifier: GPL-3.0-or-later
#include "borg_hw.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <drm/borg_drm.h>

#define MAX_REGS 1024
#define MAX_MEM 4096

static int drm_fd = -1, to_dev = -1, from_dev = -1;
static uint32_t regs[MAX_REGS * 2];
static unsigned nregs;
static uint32_t mem[MAX_MEM];
static unsigned mem_off, nmem;

static void die(const char *what) { perror(what); _exit(1); }

static void put(const void *p, size_t n) {
  const uint8_t *b = p;
  while (n) { ssize_t r = write(to_dev, b, n); if (r <= 0) die("borg_hw: write"); b += r; n -= (size_t)r; }
}
static void get(void *p, size_t n) {
  uint8_t *b = p;
  while (n) { ssize_t r = read(from_dev, b, n); if (r <= 0) die("borg_hw: read"); b += r; n -= (size_t)r; }
}

static void dev_regs(const uint32_t *w, unsigned n) {
  if (drm_fd >= 0) {
    struct drm_borg_reg_writes rw = {.writes = (uintptr_t)w, .count = n};
    if (ioctl(drm_fd, DRM_IOCTL_BORG_REG_WRITES, &rw)) die("borg_hw: reg writes");
  } else {
    put("W", 1); put(&n, 4); put(w, n * 8);
  }
}

static uint32_t dev_reg_read(uint32_t off) {
  if (drm_fd >= 0) {
    struct drm_borg_reg_read rr = {.offset = off};
    if (ioctl(drm_fd, DRM_IOCTL_BORG_REG_READ, &rr)) die("borg_hw: reg read");
    return rr.value;
  }
  uint32_t v;
  put("R", 1); put(&off, 4); get(&v, 4);
  return v;
}

static void dev_mem_write(uint32_t off, const void *p, uint32_t len) {
  if (drm_fd >= 0) {
    struct drm_borg_mem m = {.data = (uintptr_t)p, .offset = off, .length = len};
    if (ioctl(drm_fd, DRM_IOCTL_BORG_MEM_WRITE, &m)) die("borg_hw: mem write");
  } else {
    put("M", 1); put(&off, 4); put(&len, 4); put(p, len);
  }
}

static void dev_mem_read(uint32_t off, void *p, uint32_t len) {
  if (drm_fd >= 0) {
    struct drm_borg_mem m = {.data = (uintptr_t)p, .offset = off, .length = len};
    if (ioctl(drm_fd, DRM_IOCTL_BORG_MEM_READ, &m)) die("borg_hw: mem read");
  } else {
    put("m", 1); put(&off, 4); put(&len, 4); get(p, len);
  }
}

int borg_hw_open(void) {
  if (drm_fd >= 0 || to_dev >= 0) return 0;
  const char *fds = getenv("BORG_HW_FDS");   /* "to,from": pipes to a raw device the launcher started */
  if (fds && sscanf(fds, "%d,%d", &to_dev, &from_dev) == 2)
    return 0;
  const char *raw = getenv("BORG_HW_RAW");
  if (raw) {
    int in[2], out[2];
    if (pipe(in) || pipe(out)) return -1;
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (!pid) {
      dup2(in[0], 0); dup2(out[1], 1);
      close(in[0]); close(in[1]); close(out[0]); close(out[1]);
      execlp(raw, raw, "--raw", (char *)NULL);
      _exit(127);
    }
    close(in[0]); close(out[1]);
    to_dev = in[1]; from_dev = out[0];
    return 0;
  }
  const char *node = getenv("BORG_HW_NODE");
  for (int i = 0; i < 100 && drm_fd < 0; i++) {
    drm_fd = open(node ? node : "/dev/dri/renderD128", O_RDWR);
    if (drm_fd < 0) usleep(100000);
  }
  return drm_fd < 0 ? -1 : 0;
}

static void flush_regs(void) {
  if (nregs) dev_regs(regs, nregs);
  nregs = 0;
}
static void flush_mem(void) {
  if (nmem) dev_mem_write(mem_off, mem, nmem * 4);
  nmem = 0;
}

void borg_hw_flush(void) {
  flush_mem();
  flush_regs();
  if (to_dev >= 0) { uint8_t ok; put("S", 1); get(&ok, 1); }
}

void borg_hw_mem_write(uint32_t off, const void *p, uint32_t len) {
  flush_regs();
  flush_mem();
  dev_mem_write(off, p, len);
}

void borg_hw_mem_read(uint32_t off, void *p, uint32_t len) {
  flush_regs();
  flush_mem();
  dev_mem_read(off, p, len);
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
  return dev_reg_read(off);
}

void bh_dram_write(uint32_t a, uint32_t v) {
  flush_regs();
  if (nmem && (a != mem_off + nmem * 4 || nmem == MAX_MEM)) flush_mem();
  if (!nmem) mem_off = a;
  mem[nmem++] = v;
}

uint32_t bh_dram_read(uint32_t a) {
  uint32_t v;
  borg_hw_mem_read(a, &v, 4);
  return v;
}
