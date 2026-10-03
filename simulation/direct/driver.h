// Host driver for the direct simulator: what software/borg/borg_driver.c does
// per draw, but writing Borg's registers over the simulated bus instead of
// through MMIO pointers.  Mirrors that file; keep the two in step.
#pragma once
#include "direct_sim.h"
#include <cstdint>
#include <cstddef>
#include <vector>

extern "C" {
#include "borg_spirb.h"
}

class Driver {
public:
  explicit Driver(DirectSim &sim) : s(sim) {}

  // Framebuffer size is a host decision here (power of two, 4..256).
  void init(int width, int height);

  // Wire-protocol packets (borgvk's 0xAD..0xB5); returns bytes consumed or 0 if
  // the stream is malformed.  A draw is rendered when an MVP packet arrives.
  size_t packet(const uint8_t *p, size_t n);

  // Run the whole stream; returns number of draws rendered.
  int run_stream(const std::vector<uint8_t> &bytes);

  // RGB888 of the last rendered frame (width*height*3).
  std::vector<uint8_t> framebuffer_rgb() const;

  int draws = 0;
  uint16_t clear_rgb16 = 0x3266;   // FP16 0.2, as the firmware's default clear

private:
  DirectSim &s;
  int W = 128, H = 128;
  uint32_t half_w = 0, half_h = 0;
  uint32_t tbr_bin_base = 0, tbr_setup_base = 0;
  uint32_t cull_cfg = 0;

  spirb_shader_t vert{}, frag{};
  bool vert_ok = false;

  // Pending draw data (the 0xAE packet).
  static constexpr int MAX_V = 16, MAX_T = 12;
  uint32_t pos[MAX_V * 3]{};
  uint8_t idx[MAX_T * 3]{};
  uint32_t uv[MAX_T * 6]{};
  int nverts = 0, ntris = 0;
  bool have_geom = false;
  uint32_t sampler_desc[4]{};

  void reg_w(uint32_t off, uint32_t v) { s.mmio_write(off, v); }
  uint32_t reg_r(uint32_t off) { return s.mmio_read(off); }
  void stage_shader(uint8_t stage, const uint8_t *blob, size_t len);
  void set_texture_desc(const uint32_t w[3], const uint32_t samp[4]);
  void write_texels(uint32_t off, const uint8_t *d, uint32_t n);
  void set_push_constants(const uint32_t *w, uint32_t off, uint32_t n);
  void submit_geom(const uint32_t mvp[16]);
  void render();
};
