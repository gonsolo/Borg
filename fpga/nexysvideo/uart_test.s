# uart_test.s: full SoC check over UART.
# Prints a message, the SoC ID register (expect 'A'), and a DDR3 write/read-back result (P = pass, F = fail).
.section .text
.global _start
_start:
    li   s0, 0x08000000      # SoC peripheral base
main:
    la   a1, msg             # print the message, one byte at a time
1:  lbu  a0, 0(a1)
    beqz a0, 2f
    jal  ra, putc
    addi a1, a1, 1
    j    1b
2:  lw   a0, 0x08(s0)        # SoC ID register: should read 0x41 ('A')
    andi a0, a0, 0xFF
    jal  ra, putc
    li   a0, 0x20            # space
    jal  ra, putc
    li   t2, 0x00100000      # DDR3 test address
    li   t3, 0x12345678
    sw   t3, 0(t2)
    lw   t4, 0(t2)
    li   a0, 0x50            # 'P'
    beq  t3, t4, 3f
    li   a0, 0x46            # 'F'
3:  jal  ra, putc
    li   a0, 0x0D            # carriage return
    jal  ra, putc
    li   a0, 0x0A            # new line
    jal  ra, putc
    li   t1, 2000000         # pause, then repeat
4:  addi t1, t1, -1
    bnez t1, 4b
    j    main

putc:                        # wait until the UART is free, then send a0
    lw   t0, 0x1C(s0)        # UART status: bit 0 = busy
    andi t0, t0, 1
    bnez t0, putc
    sw   a0, 0x18(s0)        # UART data
    ret

msg: .asciz "Hello from the SoC, ID="