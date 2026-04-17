// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// borg_textest — fullscreen textured quad diagnostic.
//
// Two NDC-space triangles covering the entire screen, no rotation, no MVP.
// The output should be a pixel-perfect copy of the raw texture.
// Use this to verify Morton encoding, UV interpolation, and channel order.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_sys.h"
#include "compiler/shader_blobs.h"



#define TEX_WIDTH  256
#define TEX_HEIGHT 256

int main() {
  borgCreateDevice();

  BorgShaderModule vert, rast, frag;
  borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
  borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
  borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
  borgCreateGraphicsPipeline(&vert, &rast, &frag);

  // Identity MVP — pass positions through unchanged.
  fp16_t mvp[16] = {0};
  mvp[0] = FP16_ONE;  // m[0][0]
  mvp[5] = FP16_ONE;  // m[1][1]
  mvp[10] = FP16_ONE; // m[2][2]
  mvp[15] = FP16_ONE; // m[3][3]

  borg_draw_data_t draw;
  for (int i = 0; i < 16; i++) draw.uniforms[i] = mvp[i];

  // Centred 16x16 quad (NDC ±0.5 → screen coords 8..24 in a 32-wide fb).
  // All vertices are well inside the framebuffer so edge functions are valid.
  // UV still spans [0,1] so the full texture is exercised.
  //   v0(-0.5,-0.5)  v1(+0.5,-0.5)  v2(+0.5,+0.5)  v3(-0.5,+0.5)
  //   UV: (0,0)       (1,0)           (1,1)            (0,1)
  //
  // Tri A: v0, v2, v1  — area < 0 (CCW in screen space, y-down)
  // Tri B: v0, v3, v2
  #define FP16_HALF 0x3800  // 0.5 in FP16
  #define FP16_NEG_HALF 0xB800  // -0.5 in FP16
  borg_vertex_t triA[3] = {
    { .pos = {FP16_NEG_HALF, FP16_NEG_HALF, FP16_ZERO}, .color = {FP16_ONE,FP16_ONE,FP16_ONE}, .uv = {FP16_ZERO, FP16_ZERO} },
    { .pos = {FP16_HALF,     FP16_HALF,     FP16_ZERO}, .color = {FP16_ONE,FP16_ONE,FP16_ONE}, .uv = {FP16_ONE,  FP16_ONE}  },
    { .pos = {FP16_HALF,     FP16_NEG_HALF, FP16_ZERO}, .color = {FP16_ONE,FP16_ONE,FP16_ONE}, .uv = {FP16_ONE,  FP16_ZERO} },
  };
  borg_vertex_t triB[3] = {
    { .pos = {FP16_NEG_HALF, FP16_NEG_HALF, FP16_ZERO}, .color = {FP16_ONE,FP16_ONE,FP16_ONE}, .uv = {FP16_ZERO, FP16_ZERO} },
    { .pos = {FP16_NEG_HALF, FP16_HALF,     FP16_ZERO}, .color = {FP16_ONE,FP16_ONE,FP16_ONE}, .uv = {FP16_ZERO, FP16_ONE}  },
    { .pos = {FP16_HALF,     FP16_HALF,     FP16_ZERO}, .color = {FP16_ONE,FP16_ONE,FP16_ONE}, .uv = {FP16_ONE,  FP16_ONE}  },
  };

  borg_clear_zbuffer(0, (rgb16_t){FP16_ZERO, FP16_ZERO, FP16_ZERO});
  borg_set_texture(TEX_WIDTH, TEX_HEIGHT);
  borgCmdDraw(&draw, triA, 0);
  borgCmdDraw(&draw, triB, 0);
  borg_present(0);

  while (1);
  return 0;
}
