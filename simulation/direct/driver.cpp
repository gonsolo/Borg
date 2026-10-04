#include <cstring>
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
uint32_t bh_dram_read(uint32_t a) { return g_sim->r32(a); }
}

Driver::Driver(DirectSim &sim) { g_sim = &sim; }

void Driver::init(int width, int height, bool rgba) {
  W = width; H = height; rgba8 = rgba;
  borg_core_set_flush_format(rgba8 ? 1 : 0);
  borg_core_init(W, H);
}

int Driver::run_stream(const std::vector<uint8_t> &b) {
  size_t i = 0;
  bool target_seen = false;   // a 0xB6 packet carries the app's clear colour; otherwise the default
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
    if (kind == BC_TARGET) target_seen = true;
    if (kind == BC_DRAW) {
      if (!target_seen)
        borg_core_set_clear(clear_rgb16, clear_rgb16, clear_rgb16);
      borg_core_list_draw();    // queued: one render for the whole pass
      draws++;
    }
    if (kind == BC_MVP && borg_core_ready()) {
      if (!target_seen)
        borg_core_set_clear(clear_rgb16, clear_rgb16, clear_rgb16);
      borg_core_draw(nullptr, 0);
      draws++;
    }
    i += (size_t)len;
  }
  borg_core_list_flush();
  return draws;
}

// Serve mode: packets on `in`, answers on `out`, memory shared with the driver process.
//   0xBC w16 h16   set the target size; answers 16 bytes: the byte addresses of the colour,
//                  depth and stencil attachments and of the heap (u32 each, little endian)
//   0xBE k n       render strip k of n tile-row strips from now on
//   0xBD           answers one byte when every draw so far has finished
//   anything else  a wire packet for borg_core_packet(); a draw packet renders
#include <unistd.h>
static bool read_all(int fd, uint8_t *p, size_t n) {
  while (n) { ssize_t r = read(fd, p, n); if (r <= 0) return false; p += r; n -= (size_t)r; }
  return true;
}
static bool write_all(int fd, const uint8_t *p, size_t n) {
  while (n) { ssize_t r = write(fd, p, n); if (r <= 0) return false; p += r; n -= (size_t)r; }
  return true;
}
int Driver::serve(int in, int out) {
  std::vector<uint8_t> pkt(BC_PKT_LEN_MAX);
  for (;;) {
    if (!read_all(in, pkt.data(), 1)) return 0;
    if (pkt[0] == 0xBC) {
      if (!read_all(in, &pkt[1], 4)) return 0;
      borg_core_list_flush();
      init(pkt[1] | pkt[2] << 8, pkt[3] | pkt[4] << 8, false);
      const uint32_t a[4] = {DRAM_OUT_BASE_SPI, BORG_ZB_SPI, BORG_SB_SPI, BORG_HEAP_SPI};
      uint8_t r[16];
      for (int i = 0; i < 4; i++) for (int k = 0; k < 4; k++) r[4 * i + k] = a[i] >> (8 * k);
      if (!write_all(out, r, 16)) return 0;
      continue;
    }
    if (pkt[0] == 0xBE) {
      if (!read_all(in, &pkt[1], 2)) return 0;
      borg_core_list_flush();
      borg_core_set_strip(pkt[1], pkt[2]);
      continue;
    }
    if (pkt[0] == 0xBD) {
      borg_core_list_flush();
      uint8_t ok = 1;
      if (!write_all(out, &ok, 1)) return 0;
      continue;
    }
    int len = borg_core_pkt_len(pkt[0]);
    if (!len) { fprintf(stderr, "[direct] bad packet 0x%02x\n", pkt[0]); return 1; }
    if (!read_all(in, &pkt[1], (size_t)len - 1)) return 0;
    int kind = borg_core_packet(pkt.data());
    if (kind == BC_BAD) { fprintf(stderr, "[direct] rejected packet 0x%02x\n", pkt[0]); continue; }
    if (kind == BC_DRAW) {
      borg_core_list_draw();
      draws++;
    }
  }
}

// The attachment's 32-bit words (RAW32 target), pixel order, little endian.
std::vector<uint8_t> Driver::framebuffer_raw32() const {
  std::vector<uint8_t> out((size_t)W * H * 4);
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++) {
      uint32_t tiles_per_row = W >> 2, tile = (y >> 2) * tiles_per_row + (x >> 2);
      uint32_t ti = (x & 3) | ((y & 3) << 2);
      uint32_t px = g_sim->r32(DRAM_OUT_BASE_SPI + tile * 64 + ti * 4);
      memcpy(&out[((size_t)y * W + x) * 4], &px, 4);
    }
  return out;
}

std::vector<uint8_t> Driver::framebuffer_rgb() const {
  const int fmt = borg_core_flush_format();   // 0 = R5G6B5, 1 = RGBA8, 2 = BGRA8
  std::vector<uint8_t> rgb((size_t)W * H * 3);
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++) {
      uint32_t tiles_per_row = W >> 2, tile = (y >> 2) * tiles_per_row + (x >> 2);
      uint32_t ti = (x & 3) | ((y & 3) << 2);
      uint8_t r, g, bl;
      if (fmt) {   // tile = 16 pixels x 4 B: R,G,B,A (fmt 1) or B,G,R,A (fmt 2)
        uint32_t px = g_sim->r32(DRAM_OUT_BASE_SPI + tile * 64 + ti * 4);
        uint8_t b0 = px & 0xFF, b1 = (px >> 8) & 0xFF, b2 = (px >> 16) & 0xFF;
        r = fmt == 1 ? b0 : b2; g = b1; bl = fmt == 1 ? b2 : b0;
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
