# uart_hello.s — Minimal RISC-V firmware for TinyQV UART test.
# No C runtime, no BSS, no stack, no PSRAM.  Runs entirely from SDRAM
# instruction fetches.  If you see 'Z' on tio, SDRAM reads work.
#
# UART_BAUD   = 0x08000020  (PERI_DEBUG_UART_BAUD, idx=8, 8*4=0x20)
# UART_STATUS = 0x0800001C  (PERI_DEBUG_UART_STATUS, idx=7)
# UART_TX     = 0x08000018  (PERI_DEBUG_UART, idx=6)
#
# UART_BAUD_DEFAULT = 125_000_000 / 115200 = 1085

.section .text
.global _start

_start:
    # SoC debug UART (socRegion, addr[5:2] = index):
    # UART_TX     = 0x08000018  (index 6, PERI_DEBUG_UART)
    # UART_STATUS = 0x0800001C  (index 7, PERI_DEBUG_UART_STATUS, bit0=tx_busy)
    # UART_BAUD   = 0x08000020  (index 8, PERI_DEBUG_UART_BAUD)

    # ── Program baud divider ──────────────────────────────────────────────────
    li   t0, 0x08000020   # UART_BAUD register
    li   t1, 1085         # 125 MHz / 115200
    sw   t1, 0(t0)

    # ── Write 'U' to signal we got past baud setup ────────────────────────────
    li   a0, 0x55         # 'U'
    call putchar

loop:
    # ── Write 'Z' repeatedly so tio sees output even if it connects late ──────
    li   a0, 0x5A         # 'Z'
    call putchar

    # ── Short delay between bytes ─────────────────────────────────────────────
    li   s0, 100
1:  addi s0, s0, -1
    bnez s0, 1b

    j    loop

# ── putchar: poll UART_STATUS bit 0 then write to UART_TX ───────────────────
putchar:
    li   t0, 0x0800001C   # UART_STATUS (bit0=tx_busy)
    li   s1, 0x08000018   # UART_TX
wait:
    lw   t1, 0(t0)
    andi t1, t1, 1        # bit 0 = tx_busy
    bnez t1, wait
    sw   a0, 0(s1)
    ret
