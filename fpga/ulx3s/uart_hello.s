# uart_hello.s — Write 'Z' to UART TX
.section .text
.global _start
_start:
    li   t0, 0x08000018
loop:
    li   a0, 0x5A
    sw   a0, 0(t0)
    li   t1, 200
1:  addi t1, t1, -1
    bnez t1, 1b
    j    loop
