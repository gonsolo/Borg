// direct_sim <stream.bin> <W> <H> [out.rgb [rgba8]]   -- replay a borgvk wire stream through
// the driver straight onto the arcilated Borg module; RGB888 goes to stdout (or a file).
#include "driver.h"
#include "driver.cpp"
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <string>
#include <iterator>

// direct_sim --compute <job.bin> <out.bin>
// job (little-endian u32): nprog gx gy gz lx ly lz nregs nbufs bx by bz, then nprog program words,
// nregs x (gpr, value), nbufs x (byte address, byte size, size bytes of data).  The program is
// written to IMEM, the buffers to DRAM, one dispatch runs, and every buffer's bytes are written
// to out.bin in order.  Memory is sequentially consistent (see docs/B0_compiler_contract.md).
static int run_compute(const char *jobf, const char *outf) {
  std::ifstream f(jobf, std::ios::binary);
  if (!f) { fprintf(stderr, "cannot open %s\n", jobf); return 1; }
  std::vector<uint8_t> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
  size_t p = 0;
  auto rd = [&]() { uint32_t v = b[p] | b[p+1] << 8 | b[p+2] << 16 | (uint32_t)b[p+3] << 24; p += 4; return v; };
  uint32_t nprog = rd(), gx = rd(), gy = rd(), gz = rd(), lx = rd(), ly = rd(), lz = rd(), nregs = rd(), nbufs = rd(), bx = rd(), by = rd(), bz = rd();
  DirectSim sim;
  const uint32_t GPR = 0, IMEM = 128, CONTROL = 424, LS_BASE = 724, C_CTRL = 752, C_PC = 756, C_XY = 760, C_Z = 764, C_LOCAL = 768;
  sim.mmio(CONTROL, true, 2);
  sim.mmio(LS_BASE, true, 0);
  // IMEM holds 72 words; a longer program lives in DRAM (docs/B0_compiler_contract.md, "All
  // shaders: program length and the instruction cache"): CODE_BASE is written before the IMEM
  // words, because that write flushes the cache and the IMEM writes that follow are the prefill.
  const uint32_t IMEM_WORDS = 72, CODE_BASE = 0x318, CODE_ADDR = 0xF0000;
  std::vector<uint32_t> prog(nprog);
  for (uint32_t i = 0; i < nprog; i++) prog[i] = rd();
  if (nprog > IMEM_WORDS) {
    for (uint32_t i = 0; i <= nprog; i++) {
      uint32_t w = i < nprog ? prog[i] : 0;   // HALT after the program
      for (int k = 0; k < 4; k++) sim.mem[(CODE_ADDR + 4 * i + k) & (DirectSim::MEM_BYTES - 1)] = (w >> (8 * k)) & 0xff;
    }
    sim.mmio(CODE_BASE, true, CODE_ADDR);
  }
  for (uint32_t i = 0; i < nprog && i < IMEM_WORDS; i++) sim.mmio(IMEM + 4 * i, true, prog[i]);
  if (nprog < IMEM_WORDS) sim.mmio(IMEM + 4 * nprog, true, 0);   // HALT after the program
  for (uint32_t i = 0; i < nregs; i++) { uint32_t r = rd(), v = rd(); sim.mmio(GPR + 4 * r, true, v); }
  // The grid origin (a split dispatch): r12-r14 are reserved for it by the compute compiler.
  if (bx | by | bz) { sim.mmio(GPR + 4 * 12, true, bx); sim.mmio(GPR + 4 * 13, true, by); sim.mmio(GPR + 4 * 14, true, bz); }
  struct Buf { uint32_t addr, size; };
  std::vector<Buf> bufs;
  for (uint32_t i = 0; i < nbufs; i++) {
    Buf bf{rd(), rd()};
    for (uint32_t k = 0; k < bf.size; k++) sim.mem[(bf.addr + k) & (DirectSim::MEM_BYTES - 1)] = b[p + k];
    p += (bf.size + 3) & ~3u;
    bufs.push_back(bf);
  }
  sim.mmio(C_PC, true, 0);
  sim.mmio(C_XY, true, gx | (gy << 16));
  sim.mmio(C_Z, true, gz);
  sim.mmio(C_LOCAL, true, lx | (ly << 8) | ((lx * ly * lz) << 16));
  sim.mmio(C_CTRL, true, 1);
  uint32_t st = 0;
  for (int n = 0; n < 4000000 && !(st & 1); n++) {
    for (int k = 0; k < 50; k++) sim.tick();
    st = sim.mmio(C_CTRL, false, 0);
  }
  if (getenv("DIRECT_DBG")) fprintf(stderr, "[direct] compute status 0x%x, %llu cycles\n", st, (unsigned long long)sim.cycles);
  if (!(st & 1)) return 3;
  if (st & 8) { fprintf(stderr, "[direct] compute barrier fault\n"); return 4; }
  FILE *o = fopen(outf, "wb");
  for (auto &bf : bufs) for (uint32_t k = 0; k < bf.size; k++) fputc(sim.mem[(bf.addr + k) & (DirectSim::MEM_BYTES - 1)], o);
  fclose(o);
  return 0;
}

// direct_sim --raw: the bare device on stdin/stdout, standing in for the DRM render node.
//   'W' n32 (off32 val32)*n   register writes        'R' off32  -> val32
//   'M' off32 len32 data      memory write           'm' off32 len32 -> data
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
static int run_raw() {
  DirectSim sim;
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
    } else if (c == 'S') {
      uint8_t ok = 1;
      wr(&ok, 1);
    } else return 1;
  }
  return 0;
}

int main(int argc, char **argv) {
  if (argc == 2 && std::string(argv[1]) == "--raw") return run_raw();
  if (argc == 4 && std::string(argv[1]) == "--compute") return run_compute(argv[2], argv[3]);
  if (argc == 3 && std::string(argv[1]) == "--serve") {   // direct_sim --serve <memory fd>
    DirectSim sim(atoi(argv[2]));
    Driver drv(sim);
    return drv.serve(0, 1);
  }
  if (argc < 4) { fprintf(stderr, "usage: %s <stream.bin> <W> <H> [out.rgb]\n", argv[0]); return 1; }
  std::ifstream f(argv[1], std::ios::binary);
  if (!f) { fprintf(stderr, "cannot open %s\n", argv[1]); return 1; }
  std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
  int W = atoi(argv[2]), H = atoi(argv[3]);

  DirectSim sim;
  Driver drv(sim);
  bool rgba8 = argc > 5 && std::string(argv[5]) == "rgba8";
  drv.init(W, H, rgba8);
  int draws = drv.run_stream(bytes);
  if (getenv("DIRECT_DBG")) fprintf(stderr, "[direct] %d draw(s), %llu cycles\n", draws, (unsigned long long)sim.cycles);
  if (!draws) return 2;
  auto rgb = borg_core_flush_format() == 3 ? drv.framebuffer_raw32() : drv.framebuffer_rgb();
  FILE *o = argc > 4 ? fopen(argv[4], "wb") : stdout;
  fwrite(rgb.data(), 1, rgb.size(), o);
  if (o != stdout) fclose(o);
  return 0;
}
