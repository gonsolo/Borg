// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// vkcube — textured cube with Borg logo on all visible faces.
// Dynamic GPU Matrix Hardware rendering bounds testing Khronos geometry
// coordinates directly.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_isa.h"
#include "borg_math.h"
#include "borg_sys.h"
#include "compiler/shader_blobs.h"

#define FP16_N1 0xBC00 // -1.0 in FP16
#define FP16_P1 0x3C00 //  1.0 in FP16

#define TEX_WIDTH 256
#define TEX_HEIGHT 256

// 8 cube vertex positions (Khronos axes, FP16)
static const fp16_t cube_verts[8][3] = {
    {FP16_N1, FP16_N1, FP16_N1}, // 0: Front Top-Left
    {FP16_P1, FP16_N1, FP16_N1}, // 1: Front Top-Right
    {FP16_P1, FP16_P1, FP16_N1}, // 2: Front Bottom-Right
    {FP16_N1, FP16_P1, FP16_N1}, // 3: Front Bottom-Left
    {FP16_N1, FP16_N1, FP16_P1}, // 4: Back Top-Left
    {FP16_P1, FP16_N1, FP16_P1}, // 5: Back Top-Right
    {FP16_P1, FP16_P1, FP16_P1}, // 6: Back Bottom-Right
    {FP16_N1, FP16_P1, FP16_P1}, // 7: Back Bottom-Left
};

// 6 faces as quads: { TL, BL, BR, TR } vertex indices into cube_verts
static const uint8_t cube_faces[6][4] = {
    {0, 3, 2, 1}, // Front
    {1, 2, 6, 5}, // Right
    {4, 0, 1, 5}, // Top
    {5, 6, 7, 4}, // Back
    {4, 7, 3, 0}, // Left
    {3, 7, 6, 2}, // Bottom
};

// Each quad → 2 triangles: (TL,BL,BR) and (TL,BR,TR), always the same UV split.
static const uint8_t quad_qi[2][3] = {{0, 1, 2}, {0, 2, 3}};
static const fp16_t quad_uvs[2][3][2] = {
    {{FP16_ZERO, FP16_ZERO}, {FP16_ZERO, FP16_ONE}, {FP16_ONE, FP16_ONE}},
    {{FP16_ZERO, FP16_ZERO}, {FP16_ONE, FP16_ONE}, {FP16_ONE, FP16_ZERO}},
};

static void mat4_identity(fp16_t m[16]) {
  for (int i = 0; i < 16; i++)
    m[i] = FP16_ZERO;
  m[0] = FP16_ONE;
  m[5] = FP16_ONE;
  m[10] = FP16_ONE;
  m[15] = FP16_ONE;
}

static void mat4_mul(fp16_t out[16], const fp16_t a[16], const fp16_t b[16]) {
  for (int col = 0; col < 4; col++) {
    for (int row = 0; row < 4; row++) {
      fp16_t sum = FP16_ZERO;
      for (int k = 0; k < 4; k++) {
        fp16_t term = borg_fp16_mul(a[k * 4 + row], b[col * 4 + k]);
        sum = borg_fp16_add(sum, term);
      }
      out[col * 4 + row] = sum;
    }
  }
}

static void mat4_scale(fp16_t m[16], float s) {
  mat4_identity(m);
  fp16_t v = fp16_from_float(s);
  m[0] = v;
  m[5] = v;
  m[10] = v;
}

static void mat4_rotate_x(fp16_t m[16], float radians) {
  mat4_identity(m);
  fp16_t a = fp16_from_float(radians);
  fp16_t s = fp16_sin(a), c = fp16_cos(a);
  m[5] = c;
  m[9] = fp16_neg(s);
  m[6] = s;
  m[10] = c;
}

static void mat4_rotate_y(fp16_t m[16], float radians) {
  mat4_identity(m);
  fp16_t a = fp16_from_float(radians);
  fp16_t s = fp16_sin(a), c = fp16_cos(a);
  m[0] = c;
  m[8] = s;
  m[2] = fp16_neg(s);
  m[10] = c;
}

static void mat4_translate_z(fp16_t m[16], float z) {
  mat4_identity(m);
  m[14] = fp16_from_float(z);
}

static void draw_cube(const borg_draw_data_t *draw) {
  for (int f = 0; f < 6; f++) {
    for (int t = 0; t < 2; t++) {
      borg_vertex_t tri[3];
      for (int v = 0; v < 3; v++) {
        const fp16_t *vp = cube_verts[cube_faces[f][quad_qi[t][v]]];
        tri[v] = (borg_vertex_t){
            .pos = {vp[0], vp[1], vp[2]},
            .color = {FP16_ONE, FP16_ONE, FP16_ONE},
            .uv = {quad_uvs[t][v][0], quad_uvs[t][v][1]},
        };
      }
      borgCmdDraw(draw, tri, 0);
    }
  }
}

int main() {
  borgCreateDevice();

  BorgShaderModule vert, rast, frag;
  borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
  borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
  borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
  borgCreateGraphicsPipeline(&vert, &rast, &frag);

  fp16_t s[16], rx[16], ry[16], tz[16], t1[16], t2[16];
  mat4_scale(s, 0.25f);
  mat4_translate_z(tz, 0.5f);

  extern void puts_uart(const char *s); // already implemented in borg_driver.c

  // Read shared parameters from PSRAM (offset 2 and 3 -> PSRAM base + 8 and 12)
  union {
    uint32_t u;
    float f;
  } rot_x_reader, rot_y_reader;

  while (1) {
    // Read the rotation angles from the host (shared via PSRAM_IN)
    rot_x_reader.u = PSRAM_IN(2);
    rot_y_reader.u = PSRAM_IN(3);

    float rx_f = rot_x_reader.f;
    float ry_f = rot_y_reader.f;

    // Default rotation when no angles set by host (headless / FPGA)
    // Compare raw uint32 to avoid soft-float __eqsf2 (0x0 == IEEE 754 +0.0)
    if (rot_x_reader.u == 0 && rot_y_reader.u == 0) {
      rx_f = -0.4363f;
      ry_f =  0.6109f;
    }

    mat4_rotate_x(rx, rx_f);
    mat4_rotate_y(ry, ry_f);

    // MVP = Translate · Scale · Rx · Ry
    borg_draw_data_t draw;
    mat4_mul(t1, rx, ry);
    mat4_mul(t2, s, t1);
    mat4_mul(draw.uniforms, tz, t2);

    borg_clear_zbuffer(0);
    borg_set_texture(TEX_WIDTH, TEX_HEIGHT);
    draw_cube(&draw);
    borg_present(0);

    // Wait until the host/viewer clears the DONE marker before rendering
    // the next frame.  On FPGA the marker is never cleared, so the firmware
    // spins here preserving the framebuffer.  The interactive simulation
    // viewer clears it in get_framebuffer() to request a new frame.
    int done_offset = BORG_FB_WIDTH * BORG_FB_HEIGHT * 4; // FB(w*h*3) + ZB(w*h)
    while (PSRAM_OUT(done_offset) == DONE_MARKER)
      ;
  }
  return 0;
}
