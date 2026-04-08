// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Borg system constants — clock, PSRAM layout, sentinels.
//
// These are system-level constants that don't fit in SystemRDL register
// descriptions (they are not hardware registers).

#pragma once

// --- System clock ---
#ifndef CLOCK_MHZ
#define CLOCK_MHZ 4
#endif
#define FPGA_CLOCK_HZ (CLOCK_MHZ * 1000000)
#define UART_BAUD_DEFAULT (FPGA_CLOCK_HZ / 115200)

// --- Bus idle sentinel (TinyQV convention) ---
#define BUS_IDLE 3

// --- PSRAM layout constants ---
// These describe firmware-level memory layout conventions, not hardware registers.
// The PSRAM base address comes from the RDL-generated soc_regs.h.
#define PSRAM_SPI_BASE    0x001000    // 24-bit SPI/QSPI address
#define PSRAM_OUT_OFFSET  128         // Word offset: PSRAM_OUT(n) = PSRAM_IN(n + 128)
#define TEX_PSRAM_OFFSET  4200        // Word index where texture data begins

// --- Frame completion sentinel ---
#define DONE_MARKER       0xDEAD

// --- Startup delay ---
#define STARTUP_DELAY_CYCLES 10000
#define STARTUP_DELAY() do { \
    for (volatile int i = 0; i < STARTUP_DELAY_CYCLES; i++) ; \
  } while (0)

// --- PSRAM accessor macros ---
// PSRAM_BASE is defined in the RDL-generated headers (from soc.rdl).
// If not available (e.g. native test builds), provide a fallback.
#ifndef PSRAM_BASE
#define PSRAM_BASE 0x01001000
#endif
#define PSRAM_IN(n)   (*(volatile uint32_t *)(PSRAM_BASE + (n) * 4))
#define PSRAM_OUT(n)  (*(volatile uint32_t *)(PSRAM_BASE + PSRAM_OUT_OFFSET * 4 + (n) * 4))

// --- Peripheral base addresses ---
// These come from the SoC address map (soc.rdl).
// Fallbacks for builds that don't include RDL-generated headers.
#ifndef GPIO_BASE
#define GPIO_BASE 0x08000200
#endif
#ifndef UART_BASE
#define UART_BASE 0x08000400
#endif
#ifndef BORG_BASE
#define BORG_BASE 0x08000600
#endif

// --- UART accessor macros ---
#define UART_TX      (*(volatile uint32_t *)(UART_BASE + 0x0))
#define UART_STATUS  (*(volatile uint32_t *)(UART_BASE + 0x4))
#define UART_BAUD    (*(volatile uint32_t *)(UART_BASE + 0x8))

// --- Borg GPU pointer ---
#include "borg_regs.h"
#define BORG_GPU ((volatile borg_gpu_t*) BORG_BASE)
