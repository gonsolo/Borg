// direct_sim <stream.bin> <W> <H> [out.rgb [rgba8]]   -- replay a borgvk wire stream through
// the driver straight onto the arcilated Borg module; RGB888 goes to stdout (or a file).
#include "driver.h"
#include "driver.cpp"
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <iterator>

// direct_sim --compute <job.bin> <out.bin>
// job (little-endian u32): nprog gx gy gz lx ly lz nregs nbufs, then nprog program words,
// nregs x (gpr, value), nbufs x (byte address, byte size, size bytes of data).  The program is
// written to IMEM, the buffers to DRAM, one dispatch runs, and every buffer's bytes are written
// to out.bin in order.  Memory is sequentially consistent (see docs/B0_compiler_contract.md).
static int run_compute(const char *jobf, const char *outf) {
  std::ifstream f(jobf, std::ios::binary);
  if (!f) { fprintf(stderr, "cannot open %s\n", jobf); return 1; }
  std::vector<uint8_t> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
  size_t p = 0;
  auto rd = [&]() { uint32_t v = b[p] | b[p+1] << 8 | b[p+2] << 16 | (uint32_t)b[p+3] << 24; p += 4; return v; };
  uint32_t nprog = rd(), gx = rd(), gy = rd(), gz = rd(), lx = rd(), ly = rd(), lz = rd(), nregs = rd(), nbufs = rd();
  DirectSim sim;
  const uint32_t GPR = 0, IMEM = 128, CONTROL = 424, LS_BASE = 724, C_CTRL = 752, C_PC = 756, C_XY = 760, C_Z = 764, C_LOCAL = 768;
  sim.mmio(CONTROL, true, 2);
  sim.mmio(LS_BASE, true, 0);
  for (uint32_t i = 0; i < nprog; i++) sim.mmio(IMEM + 4 * i, true, rd());
  sim.mmio(IMEM + 4 * nprog, true, 0);   // HALT after the program
  for (uint32_t i = 0; i < nregs; i++) { uint32_t r = rd(), v = rd(); sim.mmio(GPR + 4 * r, true, v); }
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

int main(int argc, char **argv) {
  if (argc == 4 && std::string(argv[1]) == "--compute") return run_compute(argv[2], argv[3]);
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
