// SPDX-License-Identifier: GPL-3.0-or-later
//
// Framebuffer-pattern diagnostic for borg-triangle.bit (32x32 scanout).
//
// The CPU writes a known colour gradient straight into the tiled RGB565
// framebuffer in SDRAM -- NO GPU, no draw front end, no host upload -- and
// points the HDMI scanout at it. If the display shows a clean gradient, the
// scanout + SDRAM read path is fine at this clock and any corruption seen with
// borg_triangle_demo.c comes from the GPU/flush side. If it shows sparse or
// scrambled pixels, the scanout/SDRAM path itself is at fault.
//
// Layout (HdmiScanoutFp16.scala): tile_addr = fbBase + tile_index*32, tile
// index = tileRow*8 + tileCol, pixel_addr = tile_addr + pixel_index*2,
// pixel_index = row*4 + col inside the 4x4 tile.

#include <stdint.h>
#include "borg_sys.h"

#define FB_BASE  0x46000u
#define FB_SIZE  32

#define UART_CYCLES_PER_BIT  (FPGA_CLOCK_HZ / BORG_UART_BAUD)
#define UART_CYCLES_PER_BYTE (UART_CYCLES_PER_BIT * 10)
#define UART_TX_DELAY_ITERS  (UART_CYCLES_PER_BYTE * 3 / 4)

static void putc_uart(int c) {
  UART_TX = c;
  for (volatile int i = 0; i < UART_TX_DELAY_ITERS; i++) ;
}
static void puts_uart(const char *s) { while (*s) putc_uart(*s++); }

static uint16_t pixel(int x, int y) {
  // R grows with x, G grows with y, B fixed: a smooth diagonal gradient with a
  // white 1-pixel border so scrambled tiles are obvious.
  if (x == 0 || y == 0 || x == FB_SIZE - 1 || y == FB_SIZE - 1) return 0xFFFF;
  uint16_t r = (uint16_t)x;            // 5 bits
  uint16_t g = (uint16_t)(y * 2);      // 6 bits
  uint16_t b = 8;                      // 5 bits
  return (uint16_t)((r << 11) | (g << 5) | b);
}

int main(void) {
  STARTUP_DELAY();
  UART_BAUD = UART_BAUD_DEFAULT;
  puts_uart("fbpattern\r\n");

  volatile uint16_t *fb = (volatile uint16_t *)(uintptr_t)FB_BASE;
  for (int ty = 0; ty < FB_SIZE / 4; ty++)
    for (int tx = 0; tx < FB_SIZE / 4; tx++)
      for (int py = 0; py < 4; py++)
        for (int px = 0; px < 4; px++) {
          uint32_t tile = (uint32_t)(ty * (FB_SIZE / 4) + tx);
          uint32_t idx  = tile * 16 + (uint32_t)(py * 4 + px);   // halfword index
          fb[idx] = pixel(tx * 4 + px, ty * 4 + py);
        }

  *(volatile uint32_t *)0x08000010u = FB_BASE;   // PERI_SCANOUT_FB0
  *(volatile uint32_t *)0x08000028u = FB_BASE;   // PERI_SCANOUT_FB1
  puts_uart("pattern written\r\n");

  for (;;) {
    putc_uart('P');
    for (volatile long d = 0; d < 2000000L; d++) ;
  }
}
