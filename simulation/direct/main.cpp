// direct_sim: the Borg module alone (arcilated, or Verilated as direct_sim_v) as a device for the
// driver (--raw) or QEMU's MMIO (--bridge).
#include "direct_sim.h"
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <cstddef>
#define _Static_assert static_assert   // the generated header is C
#include "borg_regs.h"
#undef _Static_assert

// The core's sticky faults: a branch whose condition differed between the quad's lanes, or an
// exec-mask stack over- or underflow. Either means a wrong result, so say so.
static void report_faults(DirectSim &sim) {
  uint32_t st = sim.mmio_read(offsetof(borg_gpu_t, status));
  if (st & STATUS_REG_T__BRANCH_DIVERGENT_bm) fprintf(stderr, "[direct] FAULT: divergent branch\n");
  if (st & STATUS_REG_T__EXEC_FAULT_bm) fprintf(stderr, "[direct] FAULT: exec mask stack over- or underflow\n");
}

// direct_sim --raw: the bare device on stdin/stdout, standing in for the DRM render node.
//   'W' n32 (off32 val32)*n   register writes        'R' off32  -> val32
//   'M' off32 len32 data      memory write           'm' off32 len32 -> data
//   'P' off32 mask32 val32    -> val32 once (reg & mask) == val, or after 200M cycles
//   'S'                       -> one byte once everything before it is done
static bool rd(void *p, size_t n) {
  uint8_t *b = (uint8_t *)p;
  while (n) { ssize_t r = read(0, b, n); if (r <= 0) return false; b += r; n -= (size_t)r; }
  return true;
}
static void wr(const void *p, size_t n) {
  const uint8_t *b = (const uint8_t *)p;
  while (n) { ssize_t r = write(1, b, n); if (r <= 0) exit(0); b += r; n -= (size_t)r; }
}
// The memories may be the driver's: the private one and the device heap (memfds, -1: the process's own).
static int raw_loop(DirectSim &sim) {
  for (uint8_t c; rd(&c, 1);) {
    uint32_t a[2];
    if (c == 'W') {
      uint32_t n;
      if (!rd(&n, 4)) return 0;
      for (uint32_t i = 0; i < n; i++) { if (!rd(a, 8)) return 0; sim.mmio_write(a[0], a[1]); }
    } else if (c == 'R') {
      if (!rd(a, 4)) return 0;
      uint32_t v = sim.mmio_read(a[0]);
      wr(&v, 4);
    } else if (c == 'M') {
      if (!rd(a, 8)) return 0;
      std::vector<uint8_t> d(a[1]);
      if (!rd(d.data(), a[1])) return 0;
      for (uint32_t i = 0; i < a[1]; i += 4) { uint32_t w; memcpy(&w, &d[i], 4); sim.w32(a[0] + i, w); }
    } else if (c == 'm') {
      if (!rd(a, 8)) return 0;
      std::vector<uint8_t> d(a[1]);
      for (uint32_t i = 0; i < a[1]; i += 4) { uint32_t w = sim.r32(a[0] + i); memcpy(&d[i], &w, 4); }
      wr(d.data(), d.size());
    } else if (c == 'P') {   // poll: offset, mask, value; answers once (reg & mask) == value, or after 200M cycles
      uint32_t q[3];
      if (!rd(q, 12)) return 0;
      uint32_t v;
      const auto until = sim.cycles + 200000000ull;
      while (((v = sim.mmio_read(q[0])) & q[1]) != q[2] && sim.cycles < until)
        ;
      wr(&v, 4);
    } else if (c == 'S') {
      uint8_t ok = 1;
      wr(&ok, 1);
    } else return 1;
  }
  return 0;
}
static int run_raw(int mem_fd, int heap_fd) {
  DirectSim sim(mem_fd, heap_fd);
  int r = raw_loop(sim);
  report_faults(sim);
  if (sim.progress) fprintf(stderr, "[direct %d] total %llu cycles\n", (int)getpid(), (unsigned long long)sim.cycles);
  return r;
}

// direct_sim --bridge <memory file> <socket>: the Borg behind QEMU's "borg" device.  The device
// sends {write, addr, data} (u32 each) per guest register access and gets the read data back;
// GPU memory is the file QEMU also maps.  The RTL runs a few cycles after every access.
static int run_bridge(const char *memf, const char *sockf) {
  int mfd = open(memf, O_RDWR);
  if (mfd < 0) { perror("[bridge] memory file"); return 1; }
  DirectSim sim(mfd);
  int ls = socket(AF_UNIX, SOCK_STREAM, 0);
  sockaddr_un a{}; a.sun_family = AF_UNIX; strncpy(a.sun_path, sockf, sizeof(a.sun_path) - 1);
  unlink(sockf);
  if (bind(ls, (sockaddr *)&a, sizeof a) < 0 || listen(ls, 1) < 0) { perror("[bridge] socket"); return 1; }
  const char *t = getenv("BRIDGE_TICKS");
  const int ticks = t ? atoi(t) : 64;
  const char *tr_ = getenv("BRIDGE_READ_TICKS");
  const int rticks = tr_ ? atoi(tr_) : 4096;   // a read is a poll: let the Borg get on with it
  const bool trace = getenv("BRIDGE_TRACE") != nullptr;
  fprintf(stderr, "[bridge] listening on %s\n", sockf);
  int c = accept(ls, nullptr, nullptr);
  uint32_t req[3];
  while (read(c, req, sizeof req) == (ssize_t)sizeof req) {
    uint32_t d = sim.mmio(req[1], req[0] != 0, req[2]);
    for (int i = 0, n = req[0] ? ticks : rticks; i < n; i++) sim.tick();
    if (trace) fprintf(stderr, "%c %03x %08x -> %08x\n", req[0] ? 'W' : 'R', req[1], req[2], d);
    if (write(c, &d, 4) != 4) break;
  }
  return 0;
}

int main(int argc, char **argv) {
  if (argc >= 2 && argc <= 4 && std::string(argv[1]) == "--raw")   // [<memory fd> [<device heap fd>]]
    return run_raw(argc > 2 ? atoi(argv[2]) : -1, argc > 3 ? atoi(argv[3]) : -1);
  if (argc == 4 && std::string(argv[1]) == "--bridge") return run_bridge(argv[2], argv[3]);
  fprintf(stderr, "usage: %s --raw [<memory fd> [<heap fd>]] | --bridge <memory file> <socket>\n", argv[0]);
  return 1;
}
