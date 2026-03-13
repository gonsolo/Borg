// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

#include "borg_math.h"
#include "compiler/vert.borg.h"
#include "compiler/rasterize.borg.h"

// --- Hardware addresses ---
#define UART_TX      (*(volatile uint32_t *)0x08000080)
#define UART_STATUS  (*(volatile uint32_t *)0x08000084)
#define UART_BAUD    (*(volatile uint32_t *)0x08000088)

#define BORG_BASE     0x080000C0
#define BORG_REG(n)   (*(volatile uint32_t *)(BORG_BASE + (n)*4))
#define BORG_IMEM(n)  (*(volatile uint32_t *)(BORG_BASE + 32 + (n)*4))
#define BORG_CONTROL  (*(volatile uint32_t *)(BORG_BASE + 60))
#define BORG_STATUS   (*(volatile uint32_t *)(BORG_BASE + 60))

// PSRAM layout:
// Input at 0x01001000:
//   [0]    = cos(angle) in FP16
//   [1..6] = 3 vertices (vx0,vy0, vx1,vy1, vx2,vy2) in FP16
//   [7]    = sin(angle) in FP16
//   [8]    = -sin(angle) in FP16
//
// Output at 0x01001000 + 128:
//   [0..6]   = raw inputs echoed back
//   [7..9]   = sin, cos, nsin
//   [10..15] = rotated vertices (rx0,ry0, rx1,ry1, rx2,ry2)
//   [16..21] = screen-space vertices (sx0,sy0, sx1,sy1, sx2,sy2)
//   [22..27] = edge vectors (dx0,ndy0, dx1,ndy1, dx2,ndy2)
//   [32..287] = 16×16 framebuffer (1 word per pixel, 0 or 1)
//   [288]    = DONE marker (0xDEAD)

#define PSRAM_IN(n)   (*(volatile uint32_t *)(0x01001000 + (n)*4))
#define PSRAM_OUT(n)  (*(volatile uint32_t *)(0x01001000 + 128 + (n)*4))

#define FB_WIDTH  16
#define FB_HEIGHT 16
#define FB_OFFSET 32   // framebuffer starts at output word 32

#define FP16_EIGHT 0x4800   // 8.0

// --- UART ---
void putc_uart(int c) {
    while (UART_STATUS & 1) ;
    UART_TX = c;
}
#define puts_uart(s) do { char *_p = (s); while (*_p) putc_uart(*_p++); } while(0)



// --- Borg GPU helpers ---

static void borg_run(void) {
    BORG_CONTROL = 2;           // Reset PC
    (void)BORG_STATUS;          // Sync barrier
    BORG_CONTROL = 1;           // Start execution
    int timeout = 100000;
    while (!(BORG_STATUS & 2) && timeout > 0)
        timeout--;
}

static uint16_t borg_fp16_add(uint16_t a, uint16_t b) {
    BORG_IMEM(0) = 0x0220;     // fadd r0, r1, r2
    BORG_IMEM(1) = 0x0000;     // halt
    BORG_REG(1) = a;
    BORG_REG(2) = b;
    borg_run();
    return BORG_REG(0) & 0xFFFF;
}

static uint16_t borg_fp16_sub(uint16_t a, uint16_t b) {
    return borg_fp16_add(a, b ^ 0x8000);
}

static inline int fp16_ge_zero(uint16_t v) {
    return (v & 0x8000) == 0;
}

int main() {
    for (volatile int i = 0; i < 10000; i++) ;
    UART_BAUD = 34;

    // --- Read input from PSRAM ---
    uint16_t cos_val  = PSRAM_IN(0);
    uint16_t vx[3], vy[3];
    for (int v = 0; v < 3; v++) {
        vx[v] = PSRAM_IN(1 + v * 2);
        vy[v] = PSRAM_IN(2 + v * 2);
    }
    uint16_t sin_val  = PSRAM_IN(7);
    uint16_t nsin_val = PSRAM_IN(8);

    // Clear DONE marker so host doesn't read stale data
    PSRAM_OUT(FB_OFFSET + FB_WIDTH * FB_HEIGHT) = 0;

    // Echo raw inputs to PSRAM output for host verification
    PSRAM_OUT(0) = cos_val;
    for (int v = 0; v < 3; v++) {
        PSRAM_OUT(1 + v * 2) = vx[v];
        PSRAM_OUT(2 + v * 2) = vy[v];
    }
    PSRAM_OUT(7) = sin_val;
    PSRAM_OUT(8) = cos_val;
    PSRAM_OUT(9) = nsin_val;

    // --- Stage 1: Vertex shader (rotation) ---
    BORG_IMEM(0) = 0x2560;     // fmul r0, r3, r5  (c*x)
    BORG_IMEM(1) = 0x4680;     // fmadd r0, r4, r6, r0  (rx = -s*y + c*x)
    BORG_IMEM(2) = 0x2544;     // fmul r1, r2, r5  (s*x)
    BORG_IMEM(3) = 0x4E64;     // fmadd r1, r3, r6, r1  (ry = c*y + s*x)
    BORG_IMEM(4) = 0x0000;     // halt

    uint16_t rx[3], ry[3];
    for (int v = 0; v < 3; v++) {
        BORG_CONTROL = 2;
        BORG_REG(VERT_BORG_REG_COS)  = cos_val;
        BORG_REG(VERT_BORG_REG_X)    = vx[v];
        BORG_REG(VERT_BORG_REG_NSIN) = nsin_val;
        BORG_REG(VERT_BORG_REG_SIN)  = sin_val;
        BORG_REG(VERT_BORG_REG_Y)    = vy[v];
        borg_run();
        rx[v] = BORG_REG(VERT_BORG_REG_RX) & 0xFFFF;
        ry[v] = BORG_REG(VERT_BORG_REG_RY) & 0xFFFF;
    }

    for (int v = 0; v < 3; v++) {
        PSRAM_OUT(10 + v * 2 + 0) = rx[v];
        PSRAM_OUT(10 + v * 2 + 1) = ry[v];
    }

    // --- Screen-space translation: add 8.0 to center in 16×16 grid ---
    uint16_t sx[3], sy[3];
    for (int v = 0; v < 3; v++) {
        sx[v] = borg_fp16_add(rx[v], FP16_EIGHT);
        sy[v] = borg_fp16_add(ry[v], FP16_EIGHT);
        PSRAM_OUT(16 + v * 2 + 0) = sx[v];
        PSRAM_OUT(16 + v * 2 + 1) = sy[v];
    }

    // --- Stage 2: Rasterizer ---
    // Precompute edge vectors
    uint16_t dx[3], neg_dy[3];
    dx[0] = borg_fp16_sub(sx[1], sx[0]);  neg_dy[0] = fp16_neg(borg_fp16_sub(sy[1], sy[0]));
    dx[1] = borg_fp16_sub(sx[2], sx[1]);  neg_dy[1] = fp16_neg(borg_fp16_sub(sy[2], sy[1]));
    dx[2] = borg_fp16_sub(sx[0], sx[2]);  neg_dy[2] = fp16_neg(borg_fp16_sub(sy[0], sy[2]));

    for (int e = 0; e < 3; e++) {
        PSRAM_OUT(22 + e * 2 + 0) = dx[e];
        PSRAM_OUT(22 + e * 2 + 1) = neg_dy[e];
    }

    uint16_t ax_arr[3] = { sx[0], sx[1], sx[2] };
    uint16_t ay_arr[3] = { sy[0], sy[1], sy[2] };

    // Precomputed FP16 pixel center coordinates: 0.5, 1.5, ..., 15.5
    static const uint16_t pc_lut[16] = {
        0x3800, 0x3E00, 0x4100, 0x4300,  // 0.5, 1.5, 2.5, 3.5
        0x4480, 0x4580, 0x4680, 0x4780,  // 4.5, 5.5, 6.5, 7.5
        0x4840, 0x48C0, 0x4940, 0x49C0,  // 8.5, 9.5, 10.5, 11.5
        0x4A40, 0x4AC0, 0x4B40, 0x4BC0   // 12.5, 13.5, 14.5, 15.5
    };

    // --- Rasterization loop ---
    for (int py = 0; py < FB_HEIGHT; py++) {
        uint16_t pcy = pc_lut[py];

        for (int px = 0; px < FB_WIDTH; px++) {
            uint16_t pcx = pc_lut[px];

            // Phase 1: dpx/dpy via ADD shader
            BORG_IMEM(0) = 0x0220;     // fadd r0, r1, r2
            BORG_IMEM(1) = 0x0000;     // halt

            uint16_t dpx_arr[3], dpy_arr[3];
            for (int e = 0; e < 3; e++) {
                BORG_REG(1) = pcx;
                BORG_REG(2) = ax_arr[e] ^ 0x8000;
                borg_run();
                dpx_arr[e] = BORG_REG(0) & 0xFFFF;

                BORG_REG(1) = pcy;
                BORG_REG(2) = ay_arr[e] ^ 0x8000;
                borg_run();
                dpy_arr[e] = BORG_REG(0) & 0xFFFF;
            }

            // Phase 2: edge = dx * dpy + neg_dy * dpx
            BORG_IMEM(0) = 0x2420;     // fmul r0, r1, r4
            BORG_IMEM(1) = 0x4340;     // fmadd r0, r2, r3, r0
            BORG_IMEM(2) = 0x0000;     // halt

            int inside = 1;
            for (int e = 0; e < 3; e++) {
                BORG_REG(RASTERIZE_BORG_REG_DX)     = dx[e];
                BORG_REG(RASTERIZE_BORG_REG_NEG_DY) = neg_dy[e];
                BORG_REG(RASTERIZE_BORG_REG_DPX)    = dpx_arr[e];
                BORG_REG(RASTERIZE_BORG_REG_DPY)    = dpy_arr[e];
                borg_run();

                uint16_t edge = BORG_REG(RASTERIZE_BORG_REG_EDGE) & 0xFFFF;
                if (fp16_ge_zero(edge) && edge != 0) {
                    inside = 0;
                    break;
                }
            }

            PSRAM_OUT(FB_OFFSET + py * FB_WIDTH + px) = inside ? 1 : 0;
        }
    }

    // Done marker
    PSRAM_OUT(FB_OFFSET + FB_WIDTH * FB_HEIGHT) = 0xDEAD;
    puts_uart("DONE\r\n");

    while (1) ;
    return 0;
}
