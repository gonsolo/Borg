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

// --- Bus idle sentinel ---
#define BUS_IDLE 3

// --- PSRAM layout constants ---
// Pure arithmetic layout macros live in borg_layout.h (shared with simulator).
#include "borg_layout.h"

// Fixed PSRAM byte address for texture data — defined in borg_layout.h as
// TEX_PSRAM_BYTE_ADDR_FIXED.  Always fits in the 16-bit TEX_CONFIG register.

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
#define PSRAM_IN(n)   (*(volatile uint32_t *)(uintptr_t)(PSRAM_BASE + (n) * 4))
#define PSRAM_OUT(n)  (*(volatile uint32_t *)(uintptr_t)(PSRAM_BASE + PSRAM_OUT_OFFSET + (n) * 4))
// Companion to PSRAM_OUT: converts the same word-index n to the raw SPI byte
// address used by the hardware flusher (gpuMem.addr in MemoryController).
// PSRAM_OUT(n) and PSRAM_OUT_SPI(n) address the same physical PSRAM word;
// the only difference is PSRAM_BASE (CPU-mapped) vs PSRAM_SPI_BASE (raw SPI).
#define PSRAM_OUT_SPI(n) (PSRAM_SPI_BASE + PSRAM_OUT_OFFSET + (uint32_t)(n) * 4u)
// Raw SPI byte address accessor: reads/writes a 32-bit word at an absolute SPI
// byte address.  Used by the sequencer path to access shader/descriptor regions
// at fixed addresses (Step 29.5).
#define PSRAM_OUT_RAW(spi_addr) (*(volatile uint32_t *)(uintptr_t)(PSRAM_BASE - PSRAM_SPI_BASE + (spi_addr)))

// --- Peripheral base addresses ---
// These come from the SoC address map (soc.rdl).
// Fallbacks for builds that don't include RDL-generated headers.
#ifndef GPIO_BASE
#define GPIO_BASE 0x08000400
#endif
#ifndef UART_BASE
#define UART_BASE 0x08000800
#endif
#ifndef BORG_BASE
#define BORG_BASE 0x08000C00
#endif

// --- UART accessor macros ---
#define UART_TX      (*(volatile uint32_t *)(uintptr_t)(UART_BASE + 0x0))
#define UART_STATUS  (*(volatile uint32_t *)(uintptr_t)(UART_BASE + 0x4))
#define UART_BAUD    (*(volatile uint32_t *)(uintptr_t)(UART_BASE + 0x8))
// SoC control region: GPIO_OUT_SEL at socPeriU(3)*4=0x0C from 0x08000000.
// bits[7:6]=01 routes PeriUart TX (peri_out[6]) to uo_out[6] → ftdi_rxd.
#define SOC_GPIO_OUT_SEL (*(volatile uint32_t *)(uintptr_t)0x0800000C)

