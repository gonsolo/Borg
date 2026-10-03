#include "driver.h"
#include "borg_layout.h"
#include <cstdio>

extern "C" {
#include "borg_core.h"
}

// The core's hardware primitives, bound to the one simulator instance.
static DirectSim *g_sim = nullptr;
extern "C" {
void bh_reg_write(uint32_t off, uint32_t v) { g_sim->mmio_write(off, v); }
uint32_t bh_reg_read(uint32_t off) { return g_sim->mmio_read(off); }
void bh_dram_write(uint32_t a, uint32_t v) { g_sim->w32(a, v); }
}

Driver::Driver(DirectSim &sim) { g_sim = &sim; }

void Driver::init(int width, int height, bool rgba) {
  W = width; H = height; rgba8 = rgba;
  borg_core_set_flush_format(rgba8 ? 1 : 0);
  borg_core_init(W, H);
}

int Driver::run_stream(const std::vector<uint8_t> &b) {
  size_t i = 0;
  while (i < b.size()) {
    if (b[i] == 0xB1) { i++; continue; }   // serial-reload trigger: not a draw packet
    int len = borg_core_pkt_len(b[i]);
    if (!len || i + (size_t)len > b.size()) {
      fprintf(stderr, "[direct] bad packet 0x%02x at byte %zu\n", b[i], i);
      break;
    }
    int kind = borg_core_packet(&b[i]);
    if (kind == BC_BAD) {
      fprintf(stderr, "[direct] rejected packet 0x%02x at byte %zu\n", b[i], i);
      break;
    }
    if (kind == BC_MVP && borg_core_ready()) {
      borg_core_set_clear(clear_rgb16, clear_rgb16, clear_rgb16);
      borg_core_draw(nullptr, 0);
      draws++;
    }
    i += (size_t)len;
  }
  return draws;
}

std::vector<uint8_t> Driver::framebuffer_rgb() const {
  std::vector<uint8_t> rgb((size_t)W * H * 3);
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++) {
      uint32_t tiles_per_row = W >> 2, tile = (y >> 2) * tiles_per_row + (x >> 2);
      uint32_t ti = (x & 3) | ((y & 3) << 2);
      uint8_t r, g, bl;
      if (rgba8) {   // tile = 16 pixels x 4 B: R, G, B, A
        uint32_t px = g_sim->r32(DRAM_OUT_BASE_SPI + tile * 64 + ti * 4);
        r = px & 0xFF; g = (px >> 8) & 0xFF; bl = (px >> 16) & 0xFF;
      } else {
        uint32_t word = g_sim->r32(DRAM_OUT_BASE_SPI + (tile * 8 + (ti >> 1)) * 4);
        uint16_t px = (ti & 1) ? (uint16_t)(word >> 16) : (uint16_t)word;
        r = ((px >> 11) & 0x1F) << 3; g = ((px >> 5) & 0x3F) << 2; bl = (px & 0x1F) << 3;
        r |= r >> 5; g |= g >> 6; bl |= bl >> 5;
      }
      uint8_t *o = &rgb[((size_t)y * W + x) * 3];
      o[0] = r; o[1] = g; o[2] = bl;
    }
  return rgb;
}
