// direct_sim <stream.bin> <W> <H> [out.rgb [rgba8]]   -- replay a borgvk wire stream through
// the driver straight onto the arcilated Borg module; RGB888 goes to stdout (or a file).
#include "driver.h"
#include "driver.cpp"
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <iterator>

int main(int argc, char **argv) {
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
  auto rgb = drv.framebuffer_rgb();
  FILE *o = argc > 4 ? fopen(argv[4], "wb") : stdout;
  fwrite(rgb.data(), 1, rgb.size(), o);
  if (o != stdout) fclose(o);
  return 0;
}
