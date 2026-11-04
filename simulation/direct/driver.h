// Host side of the direct simulator: the bus glue around software/borg/borg_core.c,
// the same driver code the board firmware runs.  Only the three hardware
// primitives (register write/read, DRAM write) and the framebuffer readback are
// defined here.
#pragma once
#include "direct_sim.h"
#include <cstdint>
#include <vector>

class Driver {
public:
  explicit Driver(DirectSim &sim);

  // Framebuffer size is a host decision (power of two, 4..256 each).
  // rgba8: flush R8G8B8A8_UNORM (4 B/px) instead of R5G6B5.
  void init(int width, int height, bool rgba8 = false);

  // Feed borgvk's wire stream; a draw runs when its MVP packet arrives.
  // Returns the number of draws rendered.
  int run_stream(const std::vector<uint8_t> &bytes);

  // RGB888 of the last rendered frame (width*height*3).
  std::vector<uint8_t> framebuffer_rgb() const;
  // 32-bit words of a RAW32 attachment (width*height*4).
  std::vector<uint8_t> framebuffer_raw32() const;

  int draws = 0;
  uint16_t clear_rgb16 = 0x3266;   // FP16 0.2, the firmware's default clear

private:
  int W = 128, H = 128;
  bool rgba8 = false;
};
