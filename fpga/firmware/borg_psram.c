// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// PSRAM output-only Borg rotation test.
// Hardcodes test data (like borg_rotate.c), runs the shader,
// writes results to PSRAM for the host to read.

#include "borg_math.h"

// --- Hardware addresses ---
#define UART_TX      (*(volatile uint32_t *)0x08000080)
#define UART_STATUS  (*(volatile uint32_t *)0x08000084)
#define UART_BAUD    (*(volatile uint32_t *)0x08000088)

#define BORG_BASE     0x080000C0
#define BORG_REG(n)   (*(volatile uint32_t *)(BORG_BASE + (n)*4))
#define BORG_IMEM(n)  (*(volatile uint32_t *)(BORG_BASE + 32 + (n)*4))
#define BORG_CONTROL  (*(volatile uint32_t *)(BORG_BASE + 60))
#define BORG_STATUS   (*(volatile uint32_t *)(BORG_BASE + 60))

// PSRAM output area at CPU 0x01001000 = SPI 0x001000
#define PSRAM_OUT(n) (*(volatile uint32_t *)(0x01001000 + (n)*4))
// Output area at +128 bytes to avoid overlapping input data
#define PSRAM_RESULT(n) (*(volatile uint32_t *)(0x01001000 + 128 + (n)*4))

// FP16 encoded shader instructions
#define INSTR_FMUL_CX    0x2340
#define INSTR_FMADD_RX   0x4680
#define INSTR_FMUL_SX    0x23A4
#define INSTR_FMADD_RY   0x4E44

#define DONE_MARKER 0xDEAD

// --- UART ---
void putc_uart(int c) {
    while (UART_STATUS & 1) ;
    UART_TX = c;
}
#define puts_uart(s) do { char *_p = (s); while (*_p) putc_uart(*_p++); } while(0)

static char hex_chars[] = "0123456789abcdef";
#define print_hex16(v) do { \
    unsigned int _v = (v); \
    putc_uart('0'); putc_uart('x'); \
    putc_uart(hex_chars[(_v >> 12) & 0xF]); \
    putc_uart(hex_chars[(_v >> 8) & 0xF]); \
    putc_uart(hex_chars[(_v >> 4) & 0xF]); \
    putc_uart(hex_chars[_v & 0xF]); \
  } while(0)

int main() {
    for (volatile int i = 0; i < 10000; i++) ;
    UART_BAUD = 34;

    puts_uart("PSRAM OUT test v2\r\n");

    // Load shader
    BORG_CONTROL = 2;
    BORG_IMEM(0) = INSTR_FMUL_CX;
    BORG_IMEM(1) = INSTR_FMADD_RX;
    BORG_IMEM(2) = INSTR_FMUL_SX;
    BORG_IMEM(3) = INSTR_FMADD_RY;
    BORG_IMEM(4) = 0x0000;

    // Read number of test cases from PSRAM input area
    uint32_t n_tests = PSRAM_OUT(0);
    puts_uart("N_TESTS="); print_hex16(n_tests); puts_uart("\r\n");

    for (int t = 0; t < n_tests; t++) {
        uint32_t base = 1 + t * 5;
        uint16_t cos_val  = PSRAM_OUT(base + 0);
        uint16_t x_val    = PSRAM_OUT(base + 1);
        uint16_t nsin_val = PSRAM_OUT(base + 2);
        uint16_t sin_val  = PSRAM_OUT(base + 3);
        uint16_t y_val    = PSRAM_OUT(base + 4);

        BORG_REG(2) = cos_val;
        BORG_REG(3) = x_val;
        BORG_REG(4) = nsin_val;
        BORG_REG(5) = sin_val;
        BORG_REG(6) = y_val;

        BORG_CONTROL = 2;
        BORG_CONTROL = 1;

        while (!(BORG_STATUS & 2)) ;

        uint16_t rx = BORG_REG(0) & 0xFFFF;
        uint16_t ry = BORG_REG(1) & 0xFFFF;

        // Print via UART
        puts_uart("T"); putc_uart('0' + t);
        puts_uart(" rx="); print_hex16(rx);
        puts_uart(" ry="); print_hex16(ry);
        puts_uart("\r\n");

        // Write results to PSRAM (separate output region)
        PSRAM_RESULT(t * 2 + 0) = rx;
        PSRAM_RESULT(t * 2 + 1) = ry;
    }

    // Done marker
    PSRAM_RESULT(n_tests * 2) = DONE_MARKER;
    puts_uart("DONE\r\n");

    while (1) ;
    return 0;
}
