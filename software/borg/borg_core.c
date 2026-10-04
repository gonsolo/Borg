// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_core -- hardware-independent half of the Borg driver; see borg_core.h.

#include "borg_core.h"
#include "borg_fpu.h"
#include "borg_isa.h"
#include "borg_spirb.h"
#include "borg_sys.h"
#include "borg_layout.h"
#include "borg_regs.h"
#include <stdint.h>

// --- Platform access: three primitives, nothing else touches hardware. ---
#ifdef BORG_HOST
extern void bh_reg_write(uint32_t reg_off, uint32_t v);
extern uint32_t bh_reg_read(uint32_t reg_off);
extern void bh_dram_write(uint32_t spi_addr, uint32_t v);
extern uint32_t bh_dram_read(uint32_t spi_addr);
#define BREG_W(field, v) bh_reg_write((uint32_t)offsetof(borg_gpu_t, field), (uint32_t)(v))
#define BREG_R(field)    bh_reg_read((uint32_t)offsetof(borg_gpu_t, field))
#define BDRAM_W(a, v)    bh_dram_write((uint32_t)(a), (uint32_t)(v))
#else
#define BORG_GPU_PTR ((volatile borg_gpu_t *)(uintptr_t)BORG_BASE)
#define BREG_W(field, v) (BORG_GPU_PTR->field = (v))
#define BREG_R(field)    (BORG_GPU_PTR->field)
#define BDRAM_W(a, v)    DRAM_OUT_RAW(a) = (v)
#endif

// --- State ---
int borg_fb_width, borg_fb_height;   // set by borg_core_init
uint32_t borg_cull_cfg = 2u << CULL_CFG_REG_T__CULL_MODE_bp;   // cull back faces until a 0xB4 says otherwise
uint32_t tbr_bin_base = 0, tbr_setup_base = 0;

static borg_float_t half_width_f, half_height_f;
static spirb_shader_t g_draw_vert;     // varying count sizes the records; its window fills DRAW_VS_CONST
static int g_draw_vert_ok = 0;
static spirb_shader_t frag_shader;     // its window fills DRAW_FS_CONST
static int g_draw_vertex_count = 0;
// Draw parameters of the generic path (0xBA); the cube path leaves them at a plain list draw.
static struct {
  uint32_t topology, index_type, restart, load, instance_count, first_vertex, first_instance, index_base, ubo_base;
  int32_t vertex_offset;
} g_dp = {0, 0, 0, 0, 1, 0, 0, 0, 0, 0};
static uint16_t clear_r, clear_g, clear_b;
#ifdef BORG_HOST
#include <string.h>
// Render lists: the state a draw is queued with that the packet handlers otherwise write
// straight to registers or fixed memory.
static struct {
  uint32_t blend_cfg, blend_const, stencil_cfg, stencil_front, stencil_back, depth_cfg;
  int raster_valid;
} g_st;
static struct {
  uint8_t blob[6 + 512];
  uint32_t bytes;
  uint32_t code, consts;      // arena addresses of the code and constant window, 0 = not staged
} g_sh[2];
static uint32_t g_vtab[BORG_MAX_VATTRS][4];   // vertex attribute descriptors, words 0..3
static int g_vtab_dirty = 1;
#endif
static int g_natt = 1;                 // colour attachments of the pass (host)
static uint32_t g_att_fmt = 0;         // flush formats of attachments 1-3, 3 bits each
static int g_flush_format = 0;         // FlushFormat: 0 R5G6B5 (2 B/px), 1 R8G8B8A8, 2 B8G8R8A8, 3 RAW32 (4 B/px), 5 RAW8 (1 B/px)
static uint32_t flush_bytes_per_pixel(int f) { return f == 5 ? 1u : f ? 4u : 2u; }

// Words per framebuffer, plus the DONE marker word.
static uint32_t frame_stride_words(void) {
  return (uint32_t)borg_fb_width * (uint32_t)borg_fb_height * flush_bytes_per_pixel(g_flush_format) / 4u + 1u;
}

void borg_core_set_flush_format(int fmt) { g_flush_format = (fmt >= 1 && fmt <= 3) ? fmt : 0; }
int borg_core_flush_format(void) { return g_flush_format; }

// Sampler descriptor 0 until the host sends the app's: nearest filtering,
// CLAMP_TO_EDGE (VkSamplerAddressMode 2) on U, V, W, no LOD bias, LOD clamped to [0, 0].
static uint32_t g_sampler_desc[4] = {(2u << 3) | (2u << 6) | (2u << 9), 0, 0, 0};
static int g_texture_bound = 0;        // the host owns descriptor 0 (0xB5)

static borg_float_t rx_geom_pos[BC_GEOM_MAX_VERTS * 3];
static uint8_t      rx_geom_idx[BC_GEOM_MAX_TRIS * 3];
static borg_float_t rx_geom_uv[BC_GEOM_MAX_TRIS * 3 * 2];
#ifdef BORG_HOST
static borg_float_t rx_attr4[BC_GEOM_MAX_TRIS * 3 * 4];
static int          rx_have_attr4 = 0;
#endif
static int rx_geom_nverts = 0, rx_geom_ntris = 0, rx_have_geom = 0;
static borg_float_t host_mvp[16];
static int have_mvp = 0;

static inline uint32_t le32(const uint8_t *b) {
  return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

// --- Init ---
// Flusher setup and the TBR DRAM regions, which follow the two framebuffers: their size, and so
// everything after them, depends on the colour format.
static void core_apply_layout(void) {
  const int width = borg_fb_width, height = borg_fb_height;
  const uint32_t frame_stride = frame_stride_words();
  BREG_W(flush_fb_base, DRAM_OUT_SPI(0));
  BREG_W(flush_format, g_flush_format);
  unsigned int log2_w = 0;
  for (unsigned int w = (unsigned int)width; w > 1; w >>= 1) log2_w++;
  BREG_W(flush_width, log2_w);

  // TBR DRAM regions: bin lists, then the setup store.
  // Two frames, one for a target over 4 MB (the simulator's render lists only use frame 0).
  const uint32_t frames = frame_stride > 0x100000u ? 1u : 2u;
  uint32_t fb_end_spi = (uint32_t)DRAM_SPI_BASE + (uint32_t)DRAM_OUT_OFFSET + frames * frame_stride * 4u;
  tbr_bin_base = fb_end_spi;
  // The bins are those of one render window (64 x 64 tiles at most).
  const int win_w = width < 256 ? width : 256, win_h = height < 256 ? height : 256;
  tbr_setup_base = tbr_bin_base + (uint32_t)((win_w >> 2) * (win_h >> 2)) * TBR_BIN_ROW_BYTES;
}

void borg_core_init(int width, int height) {
  borg_fb_width = width;
  borg_fb_height = height;
  half_width_f  = borg_float_from_uint((uint32_t)width / 2);
  half_height_f = borg_float_from_uint((uint32_t)height / 2);
  core_apply_layout();

  // Texel store of descriptor 0 starts white, so a texel the host never wrote is unobtrusive.
  for (int y = 0; y < BC_TEX_DIM; y++)
    for (int x = 0; x < BC_TEX_DIM; x++)
      BDRAM_W(TEX_TEXEL_ADDR + (uint32_t)(y * BC_TEX_DIM + x) * 4, 0xFFFFFFFFu);
}

#ifdef BORG_HOST   // only the simulator's host driver sends a render-target packet; the board's firmware stays lean
// float32 -> FP16 (round to nearest, saturating): the tile buffer's clear colour is FP16.
static uint16_t f32_to_fp16(uint32_t f) {
  uint32_t sign = (f >> 16) & 0x8000u, exp = (f >> 23) & 0xFFu, man = f & 0x7FFFFFu;
  if (exp == 0xFFu) return (uint16_t)(sign | 0x7C00u | (man ? 0x200u : 0));   // inf / nan
  int e = (int)exp - 127 + 15;
  if (e >= 31) return (uint16_t)(sign | 0x7BFFu);                              // saturate
  if (e <= 0) {
    if (e < -10) return (uint16_t)sign;
    man |= 0x800000u;
    uint32_t shift = (uint32_t)(14 - e);
    uint32_t half = man >> shift, rem = man & ((1u << shift) - 1u), mid = 1u << (shift - 1);
    if (rem > mid || (rem == mid && (half & 1u))) half++;
    return (uint16_t)(sign | half);
  }
  uint32_t half = ((uint32_t)e << 10) | (man >> 13), rem = man & 0x1FFFu;
  if (rem > 0x1000u || (rem == 0x1000u && (half & 1u))) half++;
  return (uint16_t)(sign | half);
}
#endif

// --- Shaders ---
void borg_stage_shader(uint8_t stage, const uint8_t *blob) {
  // .borg blob: byte 0 = num_instrs, 6-byte header, then little-endian u32 words. Only the
  // instruction words are staged; the sequencer re-DMAs them into IMEM each render.
  uint32_t n = blob[0];
  if (stage == 0 && n > DRAW_VERT_SHADER_MAX_WORDS) return;
  if (stage == 1 && n > BORG_IMEM_FRAG_LEN) return;
  // A blob must carry its draw extension, and every window word must land inside its
  // stage's window (u25-u31 vertex, u20-u31 fragment).
  static spirb_shader_t parsed;
  uint8_t u0 = (stage == 0) ? DRAW_VS_CONST_U0 : DRAW_FS_CONST_U0;
  uint8_t words = (stage == 0) ? DRAW_VS_CONST_MAX_WORDS : DRAW_FS_CONST_WORDS;
  if (spirb_parse(blob, &parsed) < 0 || !parsed.has_draw_ext) return;
  for (int i = 0; i < parsed.num_window; i++)
    if (parsed.window_regs[i] < u0 || parsed.window_regs[i] >= u0 + words) return;

#ifdef BORG_HOST
  {   // Render lists keep their own copy of each shader: a new one when the blob changes.
    uint32_t bytes = 6 + n * 4;
    if (bytes <= sizeof(g_sh[stage].blob) &&
        (g_sh[stage].bytes != bytes || memcmp(g_sh[stage].blob, blob, bytes) != 0)) {
      memcpy(g_sh[stage].blob, blob, bytes);
      g_sh[stage].bytes = bytes;
      g_sh[stage].code = 0;
    }
  }
#endif
  const uint8_t *w = blob + 6;
  uint32_t addr = (stage == 0) ? DRAW_VERT_SHADER_SPI : SEQ_FRAG_SHADER_ADDR;
  for (uint32_t i = 0; i < n; i++) BDRAM_W(addr + i * 4, le32(w + i * 4));
  // Zero word (HALT) after the program: real SDRAM holds power-up junk and the core
  // would run off the end of a blob a compiler did not terminate.
  if ((stage == 0 && n < DRAW_VERT_SHADER_MAX_WORDS) || (stage == 1 && n < BORG_IMEM_FRAG_LEN))
    BDRAM_W(addr + n * 4, 0);
  if (stage == 0) {
    BREG_W(seq_vert_addr, DRAW_VERT_SHADER_SPI);
    BREG_W(seq_vert_len, n);
    g_draw_vert = parsed;
    g_draw_vert_ok = 1;
  } else {
    BREG_W(seq_frag_addr, SEQ_FRAG_SHADER_ADDR);
    BREG_W(seq_frag_len, n);
    frag_shader = parsed;
  }
}

// --- Textures ---
void borg_set_sampler(const uint32_t desc[4]) {
  for (int i = 0; i < 4; i++) g_sampler_desc[i] = desc[i];
}

void borg_set_texture(int tex_width, int tex_height) {
  // Descriptor 0 (docs/B2_texture_unit.md): a 2D, one-level, one-layer RGBA8 image, linear.
  BDRAM_W(TEX_DESC_TABLE_ADDR + 0, TEX_TEXEL_ADDR);
  BDRAM_W(TEX_DESC_TABLE_ADDR + 4,
          (uint32_t)(tex_width - 1) | ((uint32_t)(tex_height - 1) << 16) | (1u << 28) | (1u << 30));
  BDRAM_W(TEX_DESC_TABLE_ADDR + 8, (uint32_t)BORG_TEX_FORMAT_R8G8B8A8_UNORM << 14);
  BDRAM_W(TEX_DESC_TABLE_ADDR + 12, (uint32_t)tex_width * 4);
  for (int w = 4; w < 16; w++) BDRAM_W(TEX_DESC_TABLE_ADDR + (uint32_t)w * 4, 0);
  for (int w = 0; w < 4; w++) BDRAM_W(SAMPLER_DESC_TABLE_ADDR + (uint32_t)w * 4, g_sampler_desc[w]);
  // Written after the tables: a write to either register also drops the cached descriptors.
  BREG_W(tex_desc_base, TEX_DESC_TABLE_ADDR);
  BREG_W(sampler_desc_base, SAMPLER_DESC_TABLE_ADDR);
}

void borg_set_texture_desc(const uint32_t w[3], const uint32_t samp[4]) {
  BDRAM_W(TEX_DESC_TABLE_ADDR + 0, TEX_TEXEL_ADDR);
  for (int i = 0; i < 3; i++) BDRAM_W(TEX_DESC_TABLE_ADDR + 4 + (uint32_t)i * 4, w[i]);
  for (int i = 4; i < 16; i++) BDRAM_W(TEX_DESC_TABLE_ADDR + (uint32_t)i * 4, 0);
  for (int i = 0; i < 4; i++) BDRAM_W(SAMPLER_DESC_TABLE_ADDR + (uint32_t)i * 4, samp[i]);
  BREG_W(tex_desc_base, TEX_DESC_TABLE_ADDR);
  BREG_W(sampler_desc_base, SAMPLER_DESC_TABLE_ADDR);
}

void borg_write_texels(uint32_t off, const uint8_t *data, uint32_t n) {
  for (uint32_t i = 0; i + 4 <= n; i += 4) BDRAM_W(TEX_TEXEL_ADDR + off + i, le32(data + i));
}

// One 64-wide row of descriptor 0's RGBA8 texels, linear, a 32-bit write per texel.
void borg_upload_texture_row(const uint8_t *row, int y, int dim) {
  uint32_t base = TEX_TEXEL_ADDR + (uint32_t)y * (uint32_t)dim * 4;
  for (int x = 0; x < dim; x++) BDRAM_W(base + (uint32_t)x * 4, le32(&row[x * 4]));
}

// Push constants: stage the range and point LS_BASE at it (LOAD/STORE already exist). LS_BASE is
// rewritten on every call because it is also the base for ordinary SSBO-style LOAD/STORE.
void borg_set_push_constants(const uint32_t *words, uint32_t off_words, uint32_t nwords) {
  if (!words || nwords == 0) return;
  if (off_words >= BORG_PUSH_CONST_MAX_WORDS) return;
  if (nwords > BORG_PUSH_CONST_MAX_WORDS - off_words) nwords = BORG_PUSH_CONST_MAX_WORDS - off_words;
  for (uint32_t i = 0; i < nwords; i++) BDRAM_W(BORG_PUSH_CONST_SPI + (off_words + i) * 4, words[i]);
  BREG_W(ls_base, BORG_PUSH_CONST_SPI & LS_BASE_REG_T__BASE_ADDR_bm);
}

// --- Draw front end (docs/B1_geometry_front_end.md) ---
#define DRAW_RECORD_SHIFT_MIN 8
#define DRAW_RECORD_SHIFT_MAX 10
static int draw_record_shift(int num_varyings) {
  uint32_t bytes = (48u + 3u * (uint32_t)num_varyings) * 4u;
  for (int shift = DRAW_RECORD_SHIFT_MIN; shift <= DRAW_RECORD_SHIFT_MAX; shift++)
    if (bytes <= (1u << shift)) return shift;
  return -1;
}

void borg_core_set_geom(const borg_float_t *pos, const uint8_t *idx, const borg_float_t *uv,
                        int nverts, int ntris) {
  for (int i = 0; i < nverts * 3; i++) rx_geom_pos[i] = pos[i];
  for (int i = 0; i < ntris * 3; i++) rx_geom_idx[i] = idx[i];
  for (int i = 0; i < ntris * 6; i++) rx_geom_uv[i] = uv ? uv[i] : BORG_FLOAT_ZERO;
  rx_geom_nverts = nverts;
  rx_geom_ntris = ntris;
  rx_have_geom = 1;
#ifdef BORG_HOST
  rx_have_attr4 = 0;
#endif
}

int borg_core_ready(void) { return rx_have_geom && have_mvp; }
const borg_float_t *borg_core_mvp(void) { return host_mvp; }

void borg_core_set_clear(uint16_t r, uint16_t g, uint16_t b) { clear_r = r; clear_g = g; clear_b = b; }

// VertexIndex for a non-indexed "list" draw is 3*triangle + corner, so the deduplicated positions are
// expanded per corner into cube.vert's UBO.
void borg_core_stage(const borg_float_t *mvp) {
  if (!g_texture_bound) {
    borg_set_texture(BC_TEX_DIM, BC_TEX_DIM);
    g_texture_bound = 1;
  }
  if (!mvp) mvp = host_mvp;
  for (int i = 0; i < 16; i++) BDRAM_W(DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_MVP_WORD + i) * 4, mvp[i]);
  for (int t = 0; t < rx_geom_ntris; t++) {
    for (int v = 0; v < 3; v++) {
      int i = t * 3 + v;   // gl_VertexIndex
      int vi = rx_geom_idx[i];
      uint32_t pbase = DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_POS_WORD  + 4 * i) * 4;
      uint32_t abase = DRAW_UBO_SPI + (uint32_t)(DRAW_UBO_ATTR_WORD + 4 * i) * 4;
      BDRAM_W(pbase + 0,  rx_geom_pos[vi * 3 + 0]);
      BDRAM_W(pbase + 4,  rx_geom_pos[vi * 3 + 1]);
      BDRAM_W(pbase + 8,  rx_geom_pos[vi * 3 + 2]);
      BDRAM_W(pbase + 12, BORG_FLOAT_ONE);
#ifdef BORG_HOST
      if (rx_have_attr4) {
        for (int c = 0; c < 4; c++) BDRAM_W(abase + 4 * c, rx_attr4[i * 4 + c]);
        continue;
      }
#endif
      BDRAM_W(abase + 0,  rx_geom_uv[i * 2 + 0]);
      BDRAM_W(abase + 4,  rx_geom_uv[i * 2 + 1]);
      BDRAM_W(abase + 8,  BORG_FLOAT_ZERO);
      BDRAM_W(abase + 12, BORG_FLOAT_ZERO);
    }
  }
  g_draw_vertex_count = rx_geom_ntris * 3;
  g_dp.topology = g_dp.index_type = g_dp.restart = g_dp.load = 0;
  g_dp.instance_count = 1;
  g_dp.first_vertex = g_dp.first_instance = g_dp.index_base = g_dp.ubo_base = 0;
  g_dp.vertex_offset = 0;
}

// Tiles per side of one render window: the bin table holds 4096 tiles (maxBinTiles), so 64.
// The host simulator may use smaller windows and render only every borg_nparts-th one, to spread
// a large target over several processes (env BORG_WINDOW_TILES, BORG_PART=k/n).
#ifdef BORG_HOST
#include <stdlib.h>
#include <stdio.h>
static int window_tiles(void) { const char *e = getenv("BORG_WINDOW_TILES"); int v = e ? atoi(e) : 0; return v > 0 && v <= 64 ? v : 64; }
#define WINDOW_TILES window_tiles()
#else
#define WINDOW_TILES 64
#endif

void borg_core_render(int frame) {
  // Nothing to draw before a draw-mode vertex shader arrives, or when its varyings do not fit.
  int record_shift = draw_record_shift(g_draw_vert.num_varyings);
  if (!g_draw_vert_ok || record_shift < 0) return;

  const uint32_t frame_stride = frame_stride_words();
  uint32_t cc_lo = ((uint32_t)clear_b << 16) | FP16_MAX_DEPTH;
  uint32_t cc_hi = ((uint32_t)clear_r << 16) | clear_g;

  BREG_W(seq_fb_base,       DRAM_OUT_SPI((uint32_t)frame * frame_stride));
  // A framebuffer wider than one render's bin table (WINDOW_TILES x WINDOW_TILES tiles) is drawn as
  // several windows, the same draw once per window (docs/B1_geometry_front_end.md, "Render windows").
  const int fb_tiles = borg_fb_width >> 2;
  const int win_tiles = fb_tiles < WINDOW_TILES ? fb_tiles : WINDOW_TILES;
  BREG_W(seq_tiles_per_row, win_tiles);
  BREG_W(seq_tile_rows,     win_tiles);
  BREG_W(fb_pitch,          fb_tiles);
  BREG_W(seq_clear_lo,      cc_lo);
  BREG_W(seq_clear_hi,      cc_hi);
  BREG_W(seq_bin_base,      tbr_bin_base);
  BREG_W(seq_bin_row_bytes, TBR_BIN_ROW_BYTES);
  BREG_W(seq_setup_base,    tbr_setup_base);
  BREG_W(tile_bz,           cc_lo);
  // Chains the dispatcher to the fragment shader; without it every pixel keeps the clear colour.
  BREG_W(frag_pc, BORG_IMEM_FRAG_OFFSET);

  BREG_W(viewport_sx, half_width_f);  BREG_W(viewport_sy, half_height_f);
  BREG_W(viewport_ox, half_width_f);  BREG_W(viewport_oy, half_height_f);
  BREG_W(depth_scale, BORG_FLOAT_ONE); BREG_W(depth_offset, BORG_FLOAT_ZERO);

  BREG_W(ls_base, DRAW_UBO_SPI & LS_BASE_REG_T__BASE_ADDR_bm);
  // The shaders' constant windows, from their blobs' draw extensions (range-checked in borg_stage_shader).
  for (int i = 0; i < g_draw_vert.num_window; i++)
    BDRAM_W(DRAW_VS_CONST_SPI + (uint32_t)(g_draw_vert.window_regs[i] - DRAW_VS_CONST_U0) * 4,
            g_draw_vert.window_vals[i]);
  for (int i = 0; i < frag_shader.num_window; i++)
    BDRAM_W(DRAW_FS_CONST_SPI + (uint32_t)(frag_shader.window_regs[i] - DRAW_FS_CONST_U0) * 4,
            frag_shader.window_vals[i]);

  BREG_W(draw_vs_const, DRAW_VS_CONST_SPI);
  BREG_W(draw_fs_const, DRAW_FS_CONST_SPI);
  BREG_W(tex_desc_base,     TEX_DESC_TABLE_ADDR);
  BREG_W(sampler_desc_base, SAMPLER_DESC_TABLE_ADDR);
  // One sample (bit 6), all mask bits set; at one sample the raster ROM skips the per-sample depths.
  BREG_W(sample_mask_cfg, 0xF | (1u << 6));
  BREG_W(cull_cfg, borg_cull_cfg);

  // TILE_LOAD: the aspects a tile starts from (bit 0 colour, 1 depth, 2 stencil) and keep
  // (bit 3): the tiles the draw does not reach are left alone.
  BREG_W(tile_load, g_dp.load);
  BREG_W(draw_cfg, 1u | (g_dp.topology << 1) | (g_dp.index_type << 3) | (g_dp.restart << 5) |
                   ((uint32_t)record_shift << 6));   // mode=1
  BREG_W(draw_vertex_count,   g_draw_vertex_count);
  BREG_W(draw_instance_count, g_dp.instance_count);
  BREG_W(draw_first_vertex, g_dp.first_vertex); BREG_W(draw_first_instance, g_dp.first_instance);
  BREG_W(draw_vertex_offset, (uint32_t)g_dp.vertex_offset); BREG_W(draw_index_base, g_dp.index_base);

  if (g_draw_vertex_count > 0) {
    int part = 0, nparts = 1, widx = 0;
#ifdef BORG_HOST
    { const char *e = getenv("BORG_PART"); if (e) sscanf(e, "%d/%d", &part, &nparts); }
#endif
    for (int wy = 0; wy < fb_tiles; wy += win_tiles)
      for (int wx = 0; wx < fb_tiles; wx += win_tiles) {
        if (widx++ % nparts != part) continue;
        BREG_W(fb_origin, (uint32_t)wx | ((uint32_t)wy << 16));
        BREG_W(seq_trigger, 1);
        while (BREG_R(status) & STATUS_REG_T__SEQ_BUSY_bm)
          ;
      }
  }
}

void borg_core_wait_idle(void) {
  while (!(BREG_R(status) & STATUS_REG_T__IDLE_bm))
    ;
}

#ifdef BORG_HOST
// --- Render lists (docs/B1_geometry_front_end.md, "Render lists") ---------------------------
// Every queued draw is an entry (state block, parameter block) of one list; the hardware bins
// all of them and renders each tile once. A block is a count and that many (register offset,
// value) pairs, written to the registers as the driver would. Identical state blocks are
// shared, so draws that differ only in their parameters cost no state switch.
#define LIST_MAX_REGS 40
static struct {
  uint32_t n;                 // entries
  uint32_t top;               // bump pointer into the arena (byte offset from BORG_LIST_SPI)
  uint32_t state_addr;        // the last state block, and its content
  uint32_t state[LIST_MAX_REGS][2];
  int state_n;
  uint32_t vtab_addr;         // vertex attribute descriptors of the last draw
  uint32_t load;              // TILE_LOAD of the render: the first draw's
  int shift;                  // record stride of the render
  uint32_t tris;              // an upper bound of the triangles queued
} g_list = {0, BORG_LIST_ENTRY_BYTES, 0, {{0}}, 0, 0, 0, 0, 0};

static uint32_t list_alloc(uint32_t bytes) {
  uint32_t a = BORG_LIST_SPI + g_list.top;
  g_list.top += (bytes + 3u) & ~3u;
  return a;
}

// The strip of tile rows this simulator renders (strip k of n), for a pass split over several.
static int g_strip_k = 0, g_strip_n = 1, g_strip_rows = 0;
void borg_core_set_strip(int k, int n, int rows) { g_strip_k = k; g_strip_n = n > 0 ? n : 1; g_strip_rows = rows; }

static void list_reset(void) {
  g_list.n = 0; g_list.top = BORG_LIST_ENTRY_BYTES; g_list.state_addr = 0; g_list.state_n = 0;
  g_list.vtab_addr = 0; g_list.tris = 0;
  g_sh[0].code = g_sh[1].code = 0;     // the arena's shader copies are gone
  g_vtab_dirty = 1;
}

// A stage's code and constant window, in the arena.
static void list_stage(int stage, const spirb_shader_t *sh) {
  if (g_sh[stage].code) return;
  const uint32_t n = g_sh[stage].blob[0];
  const uint32_t words = stage == 0 ? DRAW_VS_CONST_MAX_WORDS : DRAW_FS_CONST_WORDS;
  const uint32_t u0 = stage == 0 ? DRAW_VS_CONST_U0 : DRAW_FS_CONST_U0;
  uint32_t a = list_alloc((n + 1) * 4);
  for (uint32_t i = 0; i < n; i++) BDRAM_W(a + i * 4, le32(g_sh[stage].blob + 6 + i * 4));
  BDRAM_W(a + n * 4, 0);               // HALT
  uint32_t c = list_alloc(words * 4);
  for (uint32_t i = 0; i < words; i++) BDRAM_W(c + i * 4, 0);
  for (int i = 0; i < sh->num_window; i++) BDRAM_W(c + (uint32_t)(sh->window_regs[i] - u0) * 4, sh->window_vals[i]);
  g_sh[stage].code = a;
  g_sh[stage].consts = c;
}

static uint32_t list_block(uint32_t regs[][2], int n) {
  uint32_t a = list_alloc(4 + (uint32_t)n * 8);
  BDRAM_W(a, (uint32_t)n);
  for (int i = 0; i < n; i++) { BDRAM_W(a + 4 + (uint32_t)i * 8, regs[i][0]); BDRAM_W(a + 8 + (uint32_t)i * 8, regs[i][1]); }
  return a;
}

#define LREG(arr, n, field, v) do { arr[n][0] = (uint32_t)offsetof(borg_gpu_t, field); arr[n][1] = (uint32_t)(v); n++; } while (0)

// 65,535 triangles at most per render, and as many setup records (256 B each) as fit below the heap.
static uint32_t setup_room_tris(void) {
  const uint32_t room = (BORG_HEAP_SPI - tbr_setup_base) / TBR_SETUP_ENTRY_BYTES;
  return room < 60000u ? room : 60000u;
}

void borg_core_list_draw(void) {
  int shift = draw_record_shift(g_draw_vert.num_varyings);
  if (!g_draw_vert_ok || shift < 0 || g_draw_vertex_count <= 0 || !g_sh[0].bytes || !g_sh[1].bytes) return;
  // One record stride, at most 65,535 triangles and one arena per render: otherwise render
  // what is queued and start the next list with this draw.
  uint32_t tris = (uint32_t)g_draw_vertex_count * (g_dp.instance_count ? g_dp.instance_count : 1);
  if (g_list.n && (shift != g_list.shift || g_list.tris + tris > setup_room_tris() ||
                   g_list.n >= BORG_LIST_ENTRY_BYTES / 8 - 1 || g_list.top + 0x4000 > BORG_LIST_BYTES))
    borg_core_list_flush();
  if (g_list.n == 0) { g_list.load = g_dp.load; g_list.shift = shift; }
  g_list.tris += tris;

  list_stage(0, &g_draw_vert);
  list_stage(1, &frag_shader);
  if (g_vtab_dirty || !g_list.vtab_addr) {
    g_list.vtab_addr = list_alloc(BORG_MAX_VATTRS * 64);
    for (int s = 0; s < BORG_MAX_VATTRS; s++)
      for (int w = 0; w < 16; w++) BDRAM_W(g_list.vtab_addr + (uint32_t)(s * 64 + w * 4), w < 4 ? g_vtab[s][w] : 0);
    g_vtab_dirty = 0;
  }

  uint32_t st[LIST_MAX_REGS][2]; int n = 0;
  LREG(st, n, seq_vert_addr, g_sh[0].code); LREG(st, n, seq_vert_len, g_sh[0].blob[0]);
  LREG(st, n, seq_frag_addr, g_sh[1].code); LREG(st, n, seq_frag_len, g_sh[1].blob[0]);
  LREG(st, n, draw_vs_const, g_sh[0].consts); LREG(st, n, draw_fs_const, g_sh[1].consts);
  LREG(st, n, tex_desc_base, TEX_DESC_TABLE_ADDR); LREG(st, n, sampler_desc_base, SAMPLER_DESC_TABLE_ADDR);
  LREG(st, n, ls_base, (g_dp.ubo_base ? g_dp.ubo_base : DRAW_UBO_SPI) & LS_BASE_REG_T__BASE_ADDR_bm);
  LREG(st, n, blend_cfg, g_st.blend_cfg); LREG(st, n, blend_const, g_st.blend_const);
  if (g_st.raster_valid) {
    LREG(st, n, stencil_cfg, g_st.stencil_cfg); LREG(st, n, stencil_front, g_st.stencil_front);
    LREG(st, n, stencil_back, g_st.stencil_back); LREG(st, n, depth_cfg, g_st.depth_cfg);
  }
  LREG(st, n, cull_cfg, borg_cull_cfg);
  LREG(st, n, viewport_sx, half_width_f); LREG(st, n, viewport_sy, half_height_f);
  LREG(st, n, viewport_ox, half_width_f); LREG(st, n, viewport_oy, half_height_f);
  LREG(st, n, depth_scale, BORG_FLOAT_ONE); LREG(st, n, depth_offset, BORG_FLOAT_ZERO);
  LREG(st, n, draw_cfg, 1u | (g_dp.topology << 1) | (g_dp.index_type << 3) | (g_dp.restart << 5) |
                        ((uint32_t)shift << 6));
  if (!g_list.state_addr || n != g_list.state_n || memcmp(st, g_list.state, (size_t)n * 8) != 0) {
    g_list.state_addr = list_block(st, n);
    memcpy(g_list.state, st, (size_t)n * 8);
    g_list.state_n = n;
  }

  uint32_t pr[8][2]; int m = 0;
  LREG(pr, m, draw_vertex_count, g_draw_vertex_count); LREG(pr, m, draw_instance_count, g_dp.instance_count);
  LREG(pr, m, draw_first_vertex, g_dp.first_vertex); LREG(pr, m, draw_first_instance, g_dp.first_instance);
  LREG(pr, m, draw_vertex_offset, (uint32_t)g_dp.vertex_offset); LREG(pr, m, draw_index_base, g_dp.index_base);
  // The vertex shader's attribute descriptors are slots BORG_VATTR_SLOT0.. of ITS table: the
  // state block's table is the fragment shader's, which Pass 2 writes back.
  LREG(pr, m, tex_desc_base, g_list.vtab_addr - BORG_VATTR_SLOT0 * 64);
  LREG(pr, m, ls_base, (g_dp.ubo_base ? g_dp.ubo_base : DRAW_UBO_SPI) & LS_BASE_REG_T__BASE_ADDR_bm);   // the vertex shader's LOADs
  uint32_t params = list_block(pr, m);

  BDRAM_W(BORG_LIST_SPI + g_list.n * 8, g_list.state_addr);
  BDRAM_W(BORG_LIST_SPI + g_list.n * 8 + 4, params);
  g_list.n++;
}

// The registers of the render itself; the draws' own are in their blocks.
static void list_trigger(uint32_t list, uint32_t load) {
  const uint32_t frame_stride = frame_stride_words();
  uint32_t cc_lo = ((uint32_t)clear_b << 16) | FP16_MAX_DEPTH;
  uint32_t cc_hi = ((uint32_t)clear_r << 16) | clear_g;
  BREG_W(seq_fb_base, DRAM_OUT_SPI(0 * frame_stride));
  const int fb_tiles = borg_fb_width >> 2;
  const int win_tiles = fb_tiles < WINDOW_TILES ? fb_tiles : WINDOW_TILES;
  BREG_W(seq_tiles_per_row, win_tiles);
  BREG_W(seq_tile_rows,     win_tiles);
  BREG_W(fb_pitch,          fb_tiles);
  BREG_W(seq_clear_lo,      cc_lo);
  BREG_W(seq_clear_hi,      cc_hi);
  BREG_W(seq_bin_base,      tbr_bin_base);
  BREG_W(seq_bin_row_bytes, TBR_BIN_ROW_BYTES);
  BREG_W(seq_setup_base,    tbr_setup_base);
  BREG_W(tile_bz,           cc_lo);
  BREG_W(frag_pc, BORG_IMEM_FRAG_OFFSET);
  BREG_W(sample_mask_cfg, 0xF | (1u << 6));
  BREG_W(tile_load, load);
  BREG_W(draw_cfg, 1u | ((uint32_t)g_list.shift << 6));   // the record stride, before any block
  BREG_W(render_list, list);
  BREG_W(att_cfg, g_natt > 1 ? (uint32_t)(g_natt - 1) | (7u << 2) : 0u);   // all of 1-3 load what the host put there
  if (g_natt > 1) {
    BREG_W(att_format, g_att_fmt);
    BREG_W(att_base1, BORG_ATT_SPI(1)); BREG_W(att_base2, BORG_ATT_SPI(2)); BREG_W(att_base3, BORG_ATT_SPI(3));
  }
  if (g_strip_n > 1) {
    const int fb_rows = borg_fb_height >> 2, per = g_strip_rows, y0 = g_strip_k * per;
    const int rows = per < fb_rows - y0 ? per : fb_rows - y0;
    for (int wy = y0; wy < y0 + rows; wy += win_tiles)
      for (int wx = 0; wx < fb_tiles; wx += win_tiles) {
        BREG_W(seq_tile_rows, y0 + rows - wy < win_tiles ? y0 + rows - wy : win_tiles);
        BREG_W(fb_origin, (uint32_t)wx | ((uint32_t)wy << 16));
        BREG_W(seq_trigger, 1);
        while (BREG_R(status) & STATUS_REG_T__SEQ_BUSY_bm)
          ;
      }
    borg_core_wait_idle();
    BREG_W(render_list, 0);
    return;
  }
  int part = 0, nparts = 1, widx = 0;
  { const char *e = getenv("BORG_PART"); if (e) sscanf(e, "%d/%d", &part, &nparts); }
  for (int wy = 0; wy < fb_tiles; wy += win_tiles)
    for (int wx = 0; wx < fb_tiles; wx += win_tiles) {
      if (widx++ % nparts != part) continue;
      BREG_W(fb_origin, (uint32_t)wx | ((uint32_t)wy << 16));
      BREG_W(seq_trigger, 1);
      while (BREG_R(status) & STATUS_REG_T__SEQ_BUSY_bm)
        ;
    }
  borg_core_wait_idle();
  BREG_W(render_list, 0);
}

void borg_core_list_flush(void) {
  if (g_list.n == 0) return;
  BDRAM_W(BORG_LIST_SPI + g_list.n * 8, 0);
  BDRAM_W(BORG_LIST_SPI + g_list.n * 8 + 4, 0);
  list_trigger(BORG_LIST_SPI, g_list.load);
  if (BREG_R(seq_trigger) & 2u) {
    // A bin overflowed and nothing was rendered: the draws one render each, the first as the
    // list was to start, the rest continuing from what it left (load, keep).
    uint32_t one = list_alloc(16);
    for (uint32_t i = 0; i < g_list.n; i++) {
      const uint32_t e = BORG_LIST_SPI + i * 8;
      BDRAM_W(one, bh_dram_read(e)); BDRAM_W(one + 4, bh_dram_read(e + 4));
      BDRAM_W(one + 8, 0); BDRAM_W(one + 12, 0);
      list_trigger(one, i == 0 ? g_list.load : (g_list.load | 9u));
    }
  }
  list_reset();
}
#endif

void borg_core_draw(const borg_float_t *mvp, int frame) {
  borg_core_stage(mvp);
  borg_core_render(frame);
  borg_core_wait_idle();
}

// --- Packets ---
int borg_core_pkt_len(uint8_t marker) {
  switch (marker) {
  case 0xAD: return BC_PKT_LEN_MVP;
  case 0xAE: return BC_PKT_LEN_GEOM;
  case 0xAF: return BC_PKT_LEN_TEXROW;
  case 0xB0: return BC_PKT_LEN_SHADER;
  case 0xB2: return BC_PKT_LEN_PUSH;
  case 0xB3: return BC_PKT_LEN_BLEND;
  case 0xB4: return BC_PKT_LEN_STATE;
  case 0xB5: return BC_PKT_LEN_TEXG;
#ifdef BORG_HOST
  case 0xB6: return BC_PKT_LEN_TARGET;
  case 0xB7: return BC_PKT_LEN_ATTR4;
  case 0xB8: return BC_PKT_LEN_MEM;
  case 0xBB: return BC_PKT_LEN_PASS;
  case 0xBF: return BC_PKT_LEN_ATT;
  case 0xB9: return BC_PKT_LEN_VATTR;
  case 0xBA: return BC_PKT_LEN_DRAW;
#endif
  default:   return 0;
  }
}

static int csum_ok(const uint8_t *p, int n) {
  uint8_t c = 0;
  for (int i = 1; i < n - 1; i++) c ^= p[i];
  return c == p[n - 1];
}

int borg_core_packet(const uint8_t *p) {
#ifdef BORG_HOST
  // Texture, push-constant and geometry packets overwrite memory the queued draws read, and a
  // target or pass packet starts another render: the queued draws come first.
  switch (p[0]) {
  case 0xAD: case 0xAE: case 0xAF: case 0xB2: case 0xB5: case 0xB6: case 0xB7: case 0xBB: case 0xBF:
    borg_core_list_flush();
  }
#endif
  int n = borg_core_pkt_len(p[0]);
  if (!n || !csum_ok(p, n)) return BC_BAD;
  switch (p[0]) {
  case 0xAD:   // 4x4 MVP: 16 LE float32
    for (int i = 0; i < 16; i++) host_mvp[i] = le32(p + 1 + i * 4);
    have_mvp = 1;
    return BC_MVP;
  case 0xAE: { // host geometry: fixed-offset regions padded to max size
    int nv = p[1], nt = p[2];
    if (nv < 1 || nv > BC_GEOM_MAX_VERTS || nt < 1 || nt > BC_GEOM_MAX_TRIS) return BC_BAD;
    int vbase = 3, ibase = vbase + BC_GEOM_MAX_VERTS * 12, ubase = ibase + BC_GEOM_MAX_TRIS * 3;
    for (int i = 0; i < nv * 3; i++) rx_geom_pos[i] = le32(p + vbase + i * 4);
    for (int i = 0; i < nt * 3; i++) rx_geom_idx[i] = p[ibase + i];
    for (int i = 0; i < nt * 6; i++) rx_geom_uv[i] = le32(p + ubase + i * 4);
    rx_geom_nverts = nv; rx_geom_ntris = nt; rx_have_geom = 1;
#ifdef BORG_HOST
    rx_have_attr4 = 0;
#endif
    return BC_GEOM;
  }
  case 0xAF: { // legacy texture row: y, sampler (4 words), BC_TEX_DIM RGBA8 texels
    int y = p[1];
    if (y >= BC_TEX_DIM) return BC_BAD;
    uint32_t samp[4];
    for (int w = 0; w < 4; w++) samp[w] = le32(p + 2 + w * 4);
    borg_set_sampler(samp);
    borg_upload_texture_row(p + 2 + 16, y, BC_TEX_DIM);
    return BC_TEXROW;
  }
  case 0xB0: { // borgc shader upload
    uint8_t stage = p[1];
    uint32_t blen = (uint32_t)p[2] | ((uint32_t)p[3] << 8);
    if (stage > 1 || blen < 6 || blen > BC_SHADER_MAX) return BC_BAD;
    borg_stage_shader(stage, p + 4);
    return stage == 0 ? BC_SHADER_VERT : BC_SHADER_FRAG;
  }
  case 0xB2: { // push constants: off_words, n_words, LE words
    uint32_t off = p[1], nw = p[2], w[BC_PUSH_MAX_WORDS];
    if (nw < 1 || nw > BC_PUSH_MAX_WORDS || off >= BC_PUSH_MAX_WORDS || nw > BC_PUSH_MAX_WORDS - off)
      return BC_BAD;
    for (uint32_t i = 0; i < nw; i++) w[i] = le32(p + 3 + i * 4);
    borg_set_push_constants(w, off, nw);
    return BC_STATE;
  }
  case 0xB3:   // blend: BLEND_CFG, BLEND_CONST (the registers' own layout)
    BREG_W(blend_cfg, le32(p + 1));
    BREG_W(blend_const, le32(p + 5));
#ifdef BORG_HOST
    g_st.blend_cfg = le32(p + 1); g_st.blend_const = le32(p + 5);
#endif
    return BC_STATE;
  case 0xB4:   // raster state: STENCIL_CFG, STENCIL_FRONT, STENCIL_BACK, DEPTH_CFG, CULL_CFG
    BREG_W(stencil_cfg,   le32(p + 1));
    BREG_W(stencil_front, le32(p + 5));
    BREG_W(stencil_back,  le32(p + 9));
    BREG_W(depth_cfg,     le32(p + 13));
    borg_cull_cfg = le32(p + 17);
#ifdef BORG_HOST
    g_st.stencil_cfg = le32(p + 1); g_st.stencil_front = le32(p + 5); g_st.stencil_back = le32(p + 9);
    g_st.depth_cfg = le32(p + 13); g_st.raster_valid = 1;
#endif
    return BC_STATE;
#ifdef BORG_HOST
  case 0xB6: { // render target: flush format + clear colour (4 x float32)
    int fmt = p[1];
    if (fmt > 3 && fmt != 5) return BC_BAD;
    if (fmt != g_flush_format) {
      g_flush_format = fmt;
      core_apply_layout();
    }
    borg_core_set_clear(f32_to_fp16(le32(p + 2)), f32_to_fp16(le32(p + 6)), f32_to_fp16(le32(p + 10)));
    return BC_TARGET;
  }
  case 0xBF: { // colour attachments of the pass: count, then the flush formats of 1-3
    if (p[1] < 1 || p[1] > 4) return BC_BAD;
    for (int k = 0; k < 3; k++) if (p[2 + k] > 7) return BC_BAD;
    g_natt = p[1];
    g_att_fmt = (uint32_t)p[2] | (uint32_t)p[3] << 3 | (uint32_t)p[4] << 6;
    return BC_ATT;
  }
  case 0xBB: { // pass: colour flush format; flags bit 0 depth attachment, 1 stencil, 2 D32_SFLOAT
    int fmt = p[1];
    if ((fmt > 3 && fmt != 5) || p[2] > 7) return BC_BAD;
    if (fmt != g_flush_format) {
      g_flush_format = fmt;
      core_apply_layout();
    }
    BREG_W(flush_zb_base, (p[2] & 1) ? BORG_ZB_SPI : 0);
    BREG_W(flush_sb_base, (p[2] & 2) ? BORG_SB_SPI : 0);
    BREG_W(depth_format, (p[2] >> 2) & 1);
    return BC_PASS;
  }
  case 0xB8: { // heap write: byte offset from BORG_HEAP_SPI, nbytes, data
    uint32_t off = le32(p + 1), nb = (uint32_t)p[5] | ((uint32_t)p[6] << 8);
    if (nb > BC_MEM_DATA || (off & 3u) || off + nb > BORG_HEAP_BYTES) return BC_BAD;
    for (uint32_t i = 0; i < nb; i += 4) BDRAM_W(BORG_HEAP_SPI + off + i, le32(p + 7 + i));
    return BC_MEM;
  }
  case 0xB9: { // vertex attribute: slot, TexFormat code, heap offset (signed), count, stride, swizzle bits
    uint32_t slot = p[1], fmt = p[2];
    uint32_t base = BORG_HEAP_SPI + le32(p + 3), count = le32(p + 7), stride = le32(p + 11), swz = le32(p + 15);
    if (slot >= BORG_MAX_VATTRS || fmt == 0 || fmt > 51 || count < 1 || count > 4096u * 4096u || stride == 0 || stride > 16) return BC_BAD;
    // A typed fetch from a 4096-wide linear image of tight `stride`-byte elements: element i is
    // texel (i & 4095, i >> 12).
    uint32_t rows = (count + 4095) >> 12, pitch = stride << 12;
    uint32_t w1 = 4095u | ((rows - 1) << 16) | (1u << 28) | (1u << 30);
    uint32_t d = TEX_DESC_TABLE_ADDR + (BORG_VATTR_SLOT0 + slot) * 64;
    g_vtab[slot][0] = base;
    g_vtab[slot][1] = w1;
    g_vtab[slot][2] = (fmt << 14) | (swz & 0xFFF00000u);
    g_vtab[slot][3] = pitch;
    g_vtab_dirty = 1;
    BDRAM_W(d + 0, base);
    BDRAM_W(d + 4, w1);
    BDRAM_W(d + 8, (fmt << 14) | (swz & 0xFFF00000u));
    BDRAM_W(d + 12, pitch);
    for (int i = 4; i < 16; i++) BDRAM_W(d + (uint32_t)i * 4, 0);
    BREG_W(tex_desc_base, TEX_DESC_TABLE_ADDR);   // drops the unit's cached descriptor
    return BC_VATTR;
  }
  case 0xBA: { // draw: topology, index type, restart, counts, first vertex/instance, vertex offset, index base
    // p[3]: bit 0 primitive restart; bits 4:1 TILE_LOAD (colour, depth, stencil, keep). A draw
    // that loads colour alone continues its pass: it keeps the tiles it does not reach.
    if (p[1] > 2 || p[2] > 2 || p[3] > 31) return BC_BAD;
    g_dp.topology = p[1]; g_dp.index_type = p[2]; g_dp.restart = p[3] & 1; g_dp.load = (p[3] >> 1) & 15;
    if (g_dp.load == 1) g_dp.load = 9;
    g_draw_vertex_count = (int)le32(p + 4);
    g_dp.instance_count = le32(p + 8);
    g_dp.first_vertex = le32(p + 12);
    g_dp.first_instance = le32(p + 16);
    g_dp.vertex_offset = (int32_t)le32(p + 20);
    g_dp.index_base = g_dp.index_type ? BORG_HEAP_SPI + le32(p + 24) : 0;
    g_dp.ubo_base = le32(p + 28) ? BORG_HEAP_SPI + le32(p + 28) - 1 : 0;   // heap offset + 1; 0 = no uniform buffer
    return BC_DRAW;
  }
  case 0xB7: { // vec4 attribute 1 per corner (overrides the 2-float texture coordinate)
    int n = p[1];
    if (n < 1 || n > BC_GEOM_MAX_TRIS * 3) return BC_BAD;
    for (int i = 0; i < n * 4; i++) rx_attr4[i] = le32(p + 2 + i * 4);
    rx_have_attr4 = 1;
    return BC_STATE;
  }
#endif
  case 0xB5: { // generic texture chunk: texel byte offset, nbytes, descriptor words 1..3, sampler, texels
    uint32_t off = le32(p + 1), nb = (uint32_t)p[5] | ((uint32_t)p[6] << 8);
    if (nb > BC_TEXG_DATA || (off & 3u) || off + nb > TEX_REGION_BYTES - 256) return BC_BAD;
    uint32_t w[3], samp[4];
    for (int i = 0; i < 3; i++) w[i] = le32(p + 7 + i * 4);
    for (int i = 0; i < 4; i++) samp[i] = le32(p + 19 + i * 4);
    borg_set_texture_desc(w, samp);
    borg_write_texels(off, p + 35, nb);
    g_texture_bound = 1;   // the host owns descriptor 0 now
    return BC_TEXG;
  }
  }
  return BC_BAD;
}
