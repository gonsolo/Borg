#include "driver.h"
#include "borg_layout.h"
#include "borg_sys.h"      // DRAM_* layout, BORG_BASE (register-bus offsets are relative to it)
#include "borg_regs.h"
#include "borg_fpu.h"
#include "borg_isa.h"
#include <cstring>
#include <cstdio>

// Register write/read by field name: offset inside borg_gpu_t on the 10-bit bus.
#define OFF(field) ((uint32_t)offsetof(borg_gpu_t, field))
#define WR(field, v) reg_w(OFF(field), (uint32_t)(v))
#define RD(field) reg_r(OFF(field))
static_assert(sizeof(borg_gpu_t) <= 1024, "register block must fit the 10-bit bus");

// Wire packet sizes (software/borg/borg_kernel.c RX_*).
static constexpr size_t LEN_MVP = 66, LEN_GEOM = 520, LEN_TEXROW = 275, LEN_SHADER = 517,
                        LEN_PUSH = 132, LEN_BLEND = 10, LEN_STATE = 22, LEN_TEXG = 292;
static constexpr int TEX_ROW_DIM = 64;

static uint32_t le32(const uint8_t *b) {
  return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}
static bool csum_ok(const uint8_t *p, size_t n) {
  uint8_t c = 0;
  for (size_t i = 1; i + 1 < n; i++) c ^= p[i];
  return c == p[n - 1];
}

void Driver::init(int width, int height) {
  W = width; H = height;
  half_w = (uint32_t)0;   // set below as FP32 bits
  auto f2u = [](float f) { uint32_t u; memcpy(&u, &f, 4); return u; };
  half_w = f2u((float)W / 2);
  half_h = f2u((float)H / 2);

  const uint32_t frame_fb_words = (uint32_t)(W * H / 2);
  const uint32_t frame_stride = frame_fb_words + 1;
  WR(flush_fb_base, DRAM_OUT_BASE_SPI);
  unsigned log2_w = 0;
  for (unsigned w = (unsigned)W; w > 1; w >>= 1) log2_w++;
  WR(flush_width, log2_w);

  uint32_t fb_end = (uint32_t)DRAM_OUT_BASE_SPI + 2u * frame_stride * 4u;
  tbr_bin_base = fb_end;
  tbr_setup_base = tbr_bin_base + (uint32_t)((W >> 2) * (H >> 2)) * TBR_BIN_ROW_BYTES;
  cull_cfg = 2u << CULL_CFG_REG_T__CULL_MODE_bp;
  sampler_desc[0] = (2u << 3) | (2u << 6) | (2u << 9);
  // Descriptor 0's texel store starts white, like the firmware's boot fill.
  for (uint32_t a = 0; a < 64 * 64 * 4; a += 4) s.w32(TEX_TEXEL_ADDR + a, 0xFFFFFFFFu);
}

void Driver::stage_shader(uint8_t stage, const uint8_t *blob, size_t) {
  uint32_t n = blob[0];
  if (stage == 0 && n > DRAW_VERT_SHADER_MAX_WORDS) return;
  if (stage == 1 && n > BORG_IMEM_FRAG_LEN) return;
  static spirb_shader_t parsed;
  uint8_t u0 = stage == 0 ? DRAW_VS_CONST_U0 : DRAW_FS_CONST_U0;
  uint8_t words = stage == 0 ? DRAW_VS_CONST_MAX_WORDS : DRAW_FS_CONST_WORDS;
  if (spirb_parse(blob, &parsed) < 0 || !parsed.has_draw_ext) return;
  for (int i = 0; i < parsed.num_window; i++)
    if (parsed.window_regs[i] < u0 || parsed.window_regs[i] >= u0 + words) return;

  const uint8_t *w = blob + 6;
  uint32_t addr = stage == 0 ? DRAW_VERT_SHADER_SPI : SEQ_FRAG_SHADER_ADDR;
  for (uint32_t i = 0; i < n; i++) s.w32(addr + i * 4, le32(w + i * 4));
  if ((stage == 0 && n < DRAW_VERT_SHADER_MAX_WORDS) || (stage == 1 && n < BORG_IMEM_FRAG_LEN))
    s.w32(addr + n * 4, 0);
  if (stage == 0) {
    WR(seq_vert_addr, DRAW_VERT_SHADER_SPI);
    WR(seq_vert_len, n);
    vert = parsed; vert_ok = true;
  } else {
    WR(seq_frag_addr, SEQ_FRAG_SHADER_ADDR);
    WR(seq_frag_len, n);
    frag = parsed;
  }
}

void Driver::set_texture_desc(const uint32_t w[3], const uint32_t samp[4]) {
  s.w32(TEX_DESC_TABLE_ADDR + 0, TEX_TEXEL_ADDR);
  for (int i = 0; i < 3; i++) s.w32(TEX_DESC_TABLE_ADDR + 4 + i * 4, w[i]);
  for (int i = 4; i < 16; i++) s.w32(TEX_DESC_TABLE_ADDR + i * 4, 0);
  for (int i = 0; i < 4; i++) s.w32(SAMPLER_DESC_TABLE_ADDR + i * 4, samp[i]);
  WR(tex_desc_base, TEX_DESC_TABLE_ADDR);
  WR(sampler_desc_base, SAMPLER_DESC_TABLE_ADDR);
}

void Driver::write_texels(uint32_t off, const uint8_t *d, uint32_t n) {
  for (uint32_t i = 0; i + 4 <= n; i += 4) s.w32(TEX_TEXEL_ADDR + off + i, le32(d + i));
}

void Driver::set_push_constants(const uint32_t *w, uint32_t off, uint32_t n) {
  if (off >= BORG_PUSH_CONST_MAX_WORDS) return;
  if (n > BORG_PUSH_CONST_MAX_WORDS - off) n = BORG_PUSH_CONST_MAX_WORDS - off;
  for (uint32_t i = 0; i < n; i++) s.w32(BORG_PUSH_CONST_SPI + (off + i) * 4, w[i]);
  WR(ls_base, BORG_PUSH_CONST_SPI & LS_BASE_REG_T__BASE_ADDR_bm);
}

void Driver::submit_geom(const uint32_t mvp[16]) {
  const uint32_t one = 0x3f800000u;
  for (int i = 0; i < 16; i++) s.w32(DRAW_UBO_SPI + (DRAW_UBO_MVP_WORD + i) * 4, mvp[i]);
  for (int t = 0; t < ntris; t++)
    for (int v = 0; v < 3; v++) {
      int i = t * 3 + v, vi = idx[i];
      uint32_t pb = DRAW_UBO_SPI + (DRAW_UBO_POS_WORD + 4 * i) * 4;
      uint32_t ab = DRAW_UBO_SPI + (DRAW_UBO_ATTR_WORD + 4 * i) * 4;
      s.w32(pb + 0, pos[vi * 3 + 0]); s.w32(pb + 4, pos[vi * 3 + 1]);
      s.w32(pb + 8, pos[vi * 3 + 2]); s.w32(pb + 12, one);
      s.w32(ab + 0, uv[i * 2 + 0]); s.w32(ab + 4, uv[i * 2 + 1]);
      s.w32(ab + 8, 0); s.w32(ab + 12, 0);
    }
}

static int record_shift(int num_varyings) {
  uint32_t bytes = (48u + 3u * (uint32_t)num_varyings) * 4u;
  for (int shift = 8; shift <= 10; shift++)
    if (bytes <= (1u << shift)) return shift;
  return -1;
}

void Driver::render() {
  int shift = record_shift(vert.num_varyings);
  if (!vert_ok || shift < 0) return;
  const uint32_t cc_lo = ((uint32_t)clear_rgb16 << 16) | FP16_MAX_DEPTH;
  const uint32_t cc_hi = ((uint32_t)clear_rgb16 << 16) | clear_rgb16;

  WR(seq_fb_base, DRAM_OUT_BASE_SPI);
  WR(seq_tiles_per_row, W >> 2);
  WR(seq_clear_lo, cc_lo);
  WR(seq_clear_hi, cc_hi);
  WR(seq_bin_base, tbr_bin_base);
  WR(seq_bin_row_bytes, TBR_BIN_ROW_BYTES);
  WR(seq_setup_base, tbr_setup_base);
  WR(tile_bz, cc_lo);
  WR(frag_pc, BORG_IMEM_FRAG_OFFSET);
  WR(viewport_sx, half_w); WR(viewport_sy, half_h);
  WR(viewport_ox, half_w); WR(viewport_oy, half_h);
  WR(depth_scale, 0x3f800000u); WR(depth_offset, 0);
  WR(ls_base, DRAW_UBO_SPI & LS_BASE_REG_T__BASE_ADDR_bm);
  for (int i = 0; i < vert.num_window; i++)
    s.w32(DRAW_VS_CONST_SPI + (vert.window_regs[i] - DRAW_VS_CONST_U0) * 4, vert.window_vals[i]);
  for (int i = 0; i < frag.num_window; i++)
    s.w32(DRAW_FS_CONST_SPI + (frag.window_regs[i] - DRAW_FS_CONST_U0) * 4, frag.window_vals[i]);
  WR(draw_vs_const, DRAW_VS_CONST_SPI);
  WR(draw_fs_const, DRAW_FS_CONST_SPI);
  WR(tex_desc_base, TEX_DESC_TABLE_ADDR);
  WR(sampler_desc_base, SAMPLER_DESC_TABLE_ADDR);
  WR(sample_mask_cfg, 0xF | (1u << 6));
  WR(cull_cfg, cull_cfg);
  WR(draw_cfg, 1u | ((uint32_t)shift << 6));
  WR(draw_vertex_count, ntris * 3);
  WR(draw_instance_count, 1);
  WR(draw_first_vertex, 0); WR(draw_first_instance, 0);
  WR(draw_vertex_offset, 0); WR(draw_index_base, 0);

  WR(seq_trigger, 1);
  while (RD(status) & STATUS_REG_T__SEQ_BUSY_bm) {}
  while (!(RD(status) & STATUS_REG_T__IDLE_bm)) {}
  draws++;
}

size_t Driver::packet(const uint8_t *p, size_t n) {
  if (n == 0) return 0;
  switch (p[0]) {
  case 0xAD:   // MVP: closes a draw
    if (n < LEN_MVP || !csum_ok(p, LEN_MVP)) return 0;
    if (have_geom) {
      uint32_t mvp[16];
      for (int i = 0; i < 16; i++) mvp[i] = le32(p + 1 + i * 4);
      submit_geom(mvp);
      render();
    }
    return LEN_MVP;
  case 0xAE: {  // geometry
    if (n < LEN_GEOM || !csum_ok(p, LEN_GEOM)) return 0;
    int nv = p[1], nt = p[2];
    if (nv < 1 || nv > MAX_V || nt < 1 || nt > MAX_T) return 0;
    int vb = 3, ib = vb + MAX_V * 12, ub = ib + MAX_T * 3;
    for (int i = 0; i < nv * 3; i++) pos[i] = le32(p + vb + i * 4);
    for (int i = 0; i < nt * 3; i++) idx[i] = p[ib + i];
    for (int i = 0; i < nt * 6; i++) uv[i] = le32(p + ub + i * 4);
    nverts = nv; ntris = nt; have_geom = true;
    return LEN_GEOM;
  }
  case 0xAF: {  // legacy 64-wide texture row
    if (n < LEN_TEXROW || !csum_ok(p, LEN_TEXROW)) return 0;
    uint32_t samp[4];
    for (int i = 0; i < 4; i++) samp[i] = le32(p + 2 + i * 4);
    for (int i = 0; i < 4; i++) sampler_desc[i] = samp[i];
    uint32_t w[3] = { (uint32_t)(TEX_ROW_DIM - 1) | ((uint32_t)(TEX_ROW_DIM - 1) << 16) | (1u << 28) | (1u << 30),
                      (uint32_t)BORG_TEX_FORMAT_R8G8B8A8_UNORM << 14, (uint32_t)TEX_ROW_DIM * 4 };
    set_texture_desc(w, sampler_desc);
    uint32_t base = TEX_TEXEL_ADDR + (uint32_t)p[1] * TEX_ROW_DIM * 4;
    for (int x = 0; x < TEX_ROW_DIM; x++) s.w32(base + x * 4, le32(p + 18 + x * 4));
    return LEN_TEXROW;
  }
  case 0xB0: {  // shader
    if (n < LEN_SHADER || !csum_ok(p, LEN_SHADER)) return 0;
    uint32_t blen = p[2] | ((uint32_t)p[3] << 8);
    if (p[1] > 1 || blen < 6 || blen > 512) return 0;
    stage_shader(p[1], p + 4, blen);
    return LEN_SHADER;
  }
  case 0xB1: return 1;   // reload marker: not a draw packet
  case 0xB2: {  // push constants
    if (n < LEN_PUSH || !csum_ok(p, LEN_PUSH)) return 0;
    uint32_t off = p[1], nw = p[2], w[32];
    if (nw < 1 || nw > 32 || off >= 32 || nw > 32 - off) return 0;
    for (uint32_t i = 0; i < nw; i++) w[i] = le32(p + 3 + i * 4);
    set_push_constants(w, off, nw);
    return LEN_PUSH;
  }
  case 0xB3:   // blend
    if (n < LEN_BLEND || !csum_ok(p, LEN_BLEND)) return 0;
    WR(blend_cfg, le32(p + 1));
    WR(blend_const, le32(p + 5));
    return LEN_BLEND;
  case 0xB4:   // raster / stencil / depth / cull
    if (n < LEN_STATE || !csum_ok(p, LEN_STATE)) return 0;
    WR(stencil_cfg, le32(p + 1));
    WR(stencil_front, le32(p + 5));
    WR(stencil_back, le32(p + 9));
    WR(depth_cfg, le32(p + 13));
    cull_cfg = le32(p + 17);
    return LEN_STATE;
  case 0xB5: {  // generic texture chunk
    if (n < LEN_TEXG || !csum_ok(p, LEN_TEXG)) return 0;
    uint32_t off = le32(p + 1), nb = p[5] | ((uint32_t)p[6] << 8);
    if (nb > 256 || (off & 3u) || off + nb > TEX_REGION_BYTES - 256) return 0;
    uint32_t w[3], samp[4];
    for (int i = 0; i < 3; i++) w[i] = le32(p + 7 + i * 4);
    for (int i = 0; i < 4; i++) samp[i] = le32(p + 19 + i * 4);
    set_texture_desc(w, samp);
    write_texels(off, p + 35, nb);
    return LEN_TEXG;
  }
  default: return 0;
  }
}

int Driver::run_stream(const std::vector<uint8_t> &b) {
  size_t i = 0;
  while (i < b.size()) {
    size_t used = packet(b.data() + i, b.size() - i);
    if (!used) { fprintf(stderr, "[direct] bad packet 0x%02x at byte %zu\n", b[i], i); break; }
    i += used;
  }
  return draws;
}

std::vector<uint8_t> Driver::framebuffer_rgb() const {
  std::vector<uint8_t> rgb((size_t)W * H * 3);
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++) {
      uint32_t tiles_per_row = W >> 2, tile = (y >> 2) * tiles_per_row + (x >> 2);
      uint32_t ti = (x & 3) | ((y & 3) << 2);
      uint32_t word = s.r32(DRAM_OUT_BASE_SPI + (tile * 8 + (ti >> 1)) * 4);
      uint16_t px = (ti & 1) ? (uint16_t)(word >> 16) : (uint16_t)word;
      uint8_t r = ((px >> 11) & 0x1F) << 3, g = ((px >> 5) & 0x3F) << 2, bl = (px & 0x1F) << 3;
      r |= r >> 5; g |= g >> 6; bl |= bl >> 5;
      uint8_t *o = &rgb[((size_t)y * W + x) * 3];
      o[0] = r; o[1] = g; o[2] = bl;
    }
  return rgb;
}
