// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// vkcube — 12-triangle colored cube (orthographic projection).
// Front and back faces are visible; side faces are degenerate under
// orthographic projection and are skipped by the area guard in borg_cmd_draw.

#include "borg_driver.h"
#include "borg_mmio.h"
#include "compiler/shader_blobs.h"

// Cube half-extent: 0.5 in FP16
#define P  0x3800   //  0.5
#define N  0xB800   // -0.5

// Z depths (positive, 0=near, 1=far)
#define Z_NEAR 0x3266  // 0.2
#define Z_FAR  0x3A66  // 0.8

#define NO_UV { FP16_ZERO, FP16_ZERO }

// Per-face colors (R, G, B in FP16)
#define RED   { FP16_ONE,  FP16_ZERO, FP16_ZERO }
#define GREEN { FP16_ZERO, FP16_ONE,  FP16_ZERO }
#define BLUE  { FP16_ZERO, FP16_ZERO, FP16_ONE  }
#define YELLOW { FP16_ONE,  FP16_ONE,  FP16_ZERO }
#define CYAN  { FP16_ZERO, FP16_ONE,  FP16_ONE  }
#define MAGENTA { FP16_ONE,  FP16_ZERO, FP16_ONE  }

// 8 cube vertices: (x, y, z)
// Indexed by face below.
//   0: (N,N,Z_NEAR)  1: (P,N,Z_NEAR)  2: (P,P,Z_NEAR)  3: (N,P,Z_NEAR)
//   4: (N,N,Z_FAR)   5: (P,N,Z_FAR)   6: (P,P,Z_FAR)   7: (N,P,Z_FAR)

// 12 triangles (2 per face), CW winding order.
static const borg_vertex_t cube_tris[12][3] = {
    // Front face (z=Z_NEAR) — RED
    { { .pos={N,N,Z_NEAR}, .color=RED, .uv=NO_UV },
      { .pos={N,P,Z_NEAR}, .color=RED, .uv=NO_UV },
      { .pos={P,P,Z_NEAR}, .color=RED, .uv=NO_UV } },
    { { .pos={N,N,Z_NEAR}, .color=RED, .uv=NO_UV },
      { .pos={P,P,Z_NEAR}, .color=RED, .uv=NO_UV },
      { .pos={P,N,Z_NEAR}, .color=RED, .uv=NO_UV } },

    // Back face (z=Z_FAR) — GREEN
    { { .pos={P,N,Z_FAR}, .color=GREEN, .uv=NO_UV },
      { .pos={P,P,Z_FAR}, .color=GREEN, .uv=NO_UV },
      { .pos={N,P,Z_FAR}, .color=GREEN, .uv=NO_UV } },
    { { .pos={P,N,Z_FAR}, .color=GREEN, .uv=NO_UV },
      { .pos={N,P,Z_FAR}, .color=GREEN, .uv=NO_UV },
      { .pos={N,N,Z_FAR}, .color=GREEN, .uv=NO_UV } },

    // Right face (x=P) — BLUE
    { { .pos={P,N,Z_NEAR}, .color=BLUE, .uv=NO_UV },
      { .pos={P,P,Z_NEAR}, .color=BLUE, .uv=NO_UV },
      { .pos={P,P,Z_FAR},  .color=BLUE, .uv=NO_UV } },
    { { .pos={P,N,Z_NEAR}, .color=BLUE, .uv=NO_UV },
      { .pos={P,P,Z_FAR},  .color=BLUE, .uv=NO_UV },
      { .pos={P,N,Z_FAR},  .color=BLUE, .uv=NO_UV } },

    // Left face (x=N) — YELLOW
    { { .pos={N,N,Z_FAR},  .color=YELLOW, .uv=NO_UV },
      { .pos={N,P,Z_FAR},  .color=YELLOW, .uv=NO_UV },
      { .pos={N,P,Z_NEAR}, .color=YELLOW, .uv=NO_UV } },
    { { .pos={N,N,Z_FAR},  .color=YELLOW, .uv=NO_UV },
      { .pos={N,P,Z_NEAR}, .color=YELLOW, .uv=NO_UV },
      { .pos={N,N,Z_NEAR}, .color=YELLOW, .uv=NO_UV } },

    // Top face (y=P) — CYAN
    { { .pos={N,P,Z_NEAR}, .color=CYAN, .uv=NO_UV },
      { .pos={N,P,Z_FAR},  .color=CYAN, .uv=NO_UV },
      { .pos={P,P,Z_FAR},  .color=CYAN, .uv=NO_UV } },
    { { .pos={N,P,Z_NEAR}, .color=CYAN, .uv=NO_UV },
      { .pos={P,P,Z_FAR},  .color=CYAN, .uv=NO_UV },
      { .pos={P,P,Z_NEAR}, .color=CYAN, .uv=NO_UV } },

    // Bottom face (y=N) — MAGENTA
    { { .pos={N,N,Z_FAR},  .color=MAGENTA, .uv=NO_UV },
      { .pos={N,N,Z_NEAR}, .color=MAGENTA, .uv=NO_UV },
      { .pos={P,N,Z_NEAR}, .color=MAGENTA, .uv=NO_UV } },
    { { .pos={N,N,Z_FAR},  .color=MAGENTA, .uv=NO_UV },
      { .pos={P,N,Z_NEAR}, .color=MAGENTA, .uv=NO_UV },
      { .pos={P,N,Z_FAR},  .color=MAGENTA, .uv=NO_UV } },
};

#define FP16_36DEG 0x3909

int main() {
    borg_init(vert_borg, vert_borg_len,
              rasterize_borg, rasterize_borg_len,
              frag_borg, frag_borg_len);

    borg_draw_data_t draw;
    borg_set_angle(&draw, FP16_36DEG);

    borg_clear_zbuffer(0);

    for (int i = 0; i < 12; i++) {
        borg_cmd_draw(&draw, cube_tris[i], 0);
        // Per-triangle progress marker
        while (UART_STATUS & 1) ;
        UART_TX = '0' + i;
    }

    borg_present(0);

    while (UART_STATUS & 1) ;
    UART_TX = 'X';

    while (1) ;
    return 0;
}
