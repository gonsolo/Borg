.section .text

.globl debug_uart_putc
debug_uart_putc:
    lw a5, 0x1c(tp)
    andi a5, a5, 1
    bnez a5, debug_uart_putc
    sw a0, 0x18(tp)
    ret

.globl spi_send_data
spi_send_data:
    lbu a5, 0x4e0(tp)
    andi a5, a5, 2
    bnez a5, spi_send_data
    sb a1, 0x4e0(tp)
    sb a0, 0x4e1(tp)
    ret

.globl spi_send_recv_data
spi_send_recv_data:
    lbu a5, 0x4e0(tp)
    andi a5, a5, 1
    bnez a5, spi_send_recv_data
    sb a1, 0x4e0(tp)
    sb a0, 0x4e1(tp)
1:
    lbu a5, 0x4e0(tp)
    andi a5, a5, 1
    bnez a5, 1b
    lbu a0, 0x4e2(tp)
    ret

.globl spi_set_config
spi_set_config:
    sb a0, 0x4e4(tp)
    ret
