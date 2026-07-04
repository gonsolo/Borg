// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Borg SoC OpenSBI platform driver.
//
// Console:  Borg debug UART (write-only, 115200 baud at 25 MHz).
//           The UART is an MMIO shift-register: write one byte to
//           BORG_UART_TX (0x08000018) once the status bit clears.
//           There is no RX in this path; OpenSBI-to-Linux hand-off uses
//           the SBI console ecall (earlycon=sbi on the kernel command line).
//
// Timer:    CLINT at 0x02000000.  mtime increments every SoC clock cycle
//           (25 MHz on ULX3S), so timebase-frequency = 25_000_000.
//           The CLINT driver is discovered from the embedded device tree.
//
// IPI:      CLINT software-interrupt register (MSIP at 0x02000000 word 0).
//           One hart only, but the mechanism must still be wired.

#include <sbi/riscv_encoding.h>
#include <sbi/riscv_io.h>
#include <sbi/sbi_console.h>
#include <sbi/sbi_platform.h>
#include <sbi/sbi_scratch.h>
#include <sbi/sbi_types.h>
#include <sbi_utils/fdt/fdt_driver.h>
#include <sbi_utils/fdt/fdt_helper.h>
#include <sbi_utils/timer/fdt_timer.h>

// ---------------------------------------------------------------------------
// Borg debug UART (SoC inline register at byte address 0x08000018)
// ---------------------------------------------------------------------------
#define BORG_UART_TX     ((volatile uint32_t *)0x08000018UL)
#define BORG_UART_STATUS ((volatile uint32_t *)0x0800001CUL)

static void borg_putc(char c)
{
    // Spin until TX shift-register is free (status bit 0 = busy).
    while (readl(BORG_UART_STATUS) & 1u)
        ;
    writel((uint32_t)(unsigned char)c, BORG_UART_TX);
}

static struct sbi_console_device borg_console = {
    .name         = "borg-uart",
    .console_putc = borg_putc,
};

// ---------------------------------------------------------------------------
// Platform callbacks
// ---------------------------------------------------------------------------

static int borg_early_init(bool cold_boot)
{
    if (!cold_boot)
        return 0;
    sbi_console_set_device(&borg_console);

    // FDT-based drivers discover CLINT (timer + IPI) from the embedded DTB.
    // No manual CLINT setup is required here. IPI (aclint-mswi) has no
    // standalone fdt_ipi_init() in this OpenSBI version — it registers into
    // the shared "early drivers" table instead, dispatched generically via
    // fdt_driver_init_all() (same pattern platform/generic/platform.c uses).
    // Both calls MUST happen in early_init, not final_init: they register
    // MMIO regions with sbi_domain_root_add_memrange(), and sbi_init.c calls
    // sbi_domain_finalize() between early_init and final_init — after that,
    // every root_add_memregion() call is rejected outright (domain_finalized
    // check in lib/sbi/sbi_domain.c), which silently manifested as SBI_EINVAL
    // (-3) from both fdt_timer_init() and fdt_driver_init_all() and a hang in
    // sbi_hart_hang(). Found via simulation trace + sbi_domain_dump_all(),
    // then confirmed by reading platform/generic/platform.c's early_init.
    //
    // Deliberately NOT calling fdt_serial_init() here: our console is already
    // registered directly above (sbi_console_set_device), and the borg-uart
    // DTS node is status="disabled" with a custom compatible string no driver
    // recognizes — fdt_serial_init() falls back to scanning the whole DT for
    // a match, finds none, and returns SBI_ENODEV, which is fatal here.
    const void *fdt = fdt_get_address();

    int rc_timer = fdt_timer_init();
    if (rc_timer)
        return rc_timer;
    return fdt_driver_init_all(fdt, fdt_early_drivers);
}

static int borg_final_init(bool cold_boot)
{
    return 0;
}

static void borg_early_exit(void) { }
static void borg_final_exit(void) { }

// ---------------------------------------------------------------------------
// Platform descriptor
// ---------------------------------------------------------------------------

const struct sbi_platform_operations borg_ops = {
    .early_init  = borg_early_init,
    .final_init  = borg_final_init,
    .early_exit  = borg_early_exit,
    .final_exit  = borg_final_exit,
};

const struct sbi_platform platform = {
    .opensbi_version  = OPENSBI_VERSION,
    .platform_version = SBI_PLATFORM_VERSION(0x0, 0x01),
    .name             = "Borg SoC (Hutt RV64, ULX3S)",
    .features         = SBI_PLATFORM_DEFAULT_FEATURES,
    .hart_count       = 1,
    .hart_stack_size  = SBI_PLATFORM_DEFAULT_HART_STACK_SIZE,
    .heap_size        = SBI_PLATFORM_DEFAULT_HEAP_SIZE(1),
    .platform_ops_addr = (unsigned long)&borg_ops,
};
