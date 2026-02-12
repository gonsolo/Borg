.include "macros.s"

.set UART_TX_BUF_SIZE, 64

.ifdef TINYQV_SIM
.set UART_RX_BUF_SIZE, 64
.else
.set UART_RX_BUF_SIZE, 65536
.endif

.section .text
.globl isr_uart_writable  # Note no stack, and only use a0, a1 and s1
isr_uart_writable:
    # Load read and write pointers.  If equal, nothing more to send
    lw2 s1, uart_tx_write_ptr
    beq a0, s1, 1f

    # Send the byte to the UART
    lbu s1, (a0)
    sw s1, 0x80(tp)

    # Increment read pointer and compare with end of buffer
    addi a0, a0, 1
    la s1, uart_tx_buffer + UART_TX_BUF_SIZE
    beq a0, s1, 2f

    # Store the incremented read pointer
    sw a0, uart_tx_read_ptr, s1
    short_isr_exit
2:
    la a0, uart_tx_buffer
    sw a0, uart_tx_read_ptr, s1
    short_isr_exit

1:
    # Nothing more to send, disable interrupt
    li a0, 0x80000
    csrc mie, a0
    short_isr_exit

.globl uart_putc
uart_putc:
    la a4, uart_tx_buffer + UART_TX_BUF_SIZE

    # Disable interrupts as uart buffer ptrs aren't locked
    li a3, 0x8
    csrrc a3, mstatus, a3

    # Load the pointers and store the byte
    lw2 a1, uart_tx_write_ptr
    sb a0, (a1)

    # Increment the write pointer
    addi a1, a1, 1
    beq a1, a4, 1f
2:
    # Check if we've caught up with the read pointer
    beq a2, a1, 3f

5:
    # Store the incremented write pointer, re-enable interrupts
    sw a1, uart_tx_write_ptr, a0
    li a1, 0x80000
    csrs mie, a1
    csrs mstatus, a3
    ret
1:
    la a1, uart_tx_buffer
    j 2b

3:
    # Re-enable interrupts and wait for read pointer to change
    csrs mstatus, a3
4:
    lw a4, uart_tx_read_ptr
    beq a2, a4, 4b

    # Disable interrupts to protect re-enabling the writable interrup
    csrc mstatus, a3
    j 5b


.section .text
.globl isr_uart_byte_available  # Note no stack, and only use a0, a1 and s1
isr_uart_byte_available:
    # Increment the write pointer and check it didn't catch the
    # read pointer
    lw2 s1, uart_rx_write_ptr
    la a1, uart_rx_buffer + UART_RX_BUF_SIZE
    addi s1, s1, 1
    beq s1, a1, 1f

    lw a1, 0x80(tp)

2:  beq s1, a0, 3f # No space - discard the read byte

    # Store received byte
    sb a1, -1(s1)

    # Save incremented write pointer
    sw s1, uart_rx_write_ptr, a0
3:  lh a0, uart_rx_interrupt_char
    beq a0, a1, 4f
    short_isr_exit

1:  la s1, uart_rx_buffer
    beq s1, a0, 3b

    # Store received byte
    lw a0, 0x80(tp)
    sb a0, -1(a1)
    sw s1, uart_rx_write_ptr, a0
    
    lh a0, uart_rx_interrupt_char
    beq a0, a1, 4f
    short_isr_exit

4:  li a0, 1
    sb a0, uart_rx_interrupt_seen, a1
    short_isr_exit


.globl uart_getc
uart_getc:
    li a0, -1
    la a1, uart_rx_buffer + UART_RX_BUF_SIZE

    # Disable interrupts as uart buffer ptrs aren't locked
    li a4, 0x8
    csrrc a4, mstatus, a4

    # Load pointers, check if any data is available
    lw2 a2, uart_rx_write_ptr
    beq a2, a3, 1f

    lbu a0, (a3)
    addi a3, a3, 1
    beq a3, a1, 2f
    sw a3, uart_rx_read_ptr, a2

1:  csrs mstatus, a4
    ret

2:  la a1, uart_rx_buffer
    sw a1, uart_rx_read_ptr, a2
    csrs mstatus, a4
    ret

.globl uart_is_char_available
uart_is_char_available:
    lw2 a2, uart_rx_write_ptr
    beq a2, a3, 1f
    li a0, 0
    ret

1:  li a0, 1
    ret


.section .peri_data.uart, "a"
uart_tx_write_ptr:
    .word uart_tx_buffer
uart_tx_read_ptr:
    .word uart_tx_buffer

uart_rx_write_ptr:
    .word uart_rx_buffer
uart_rx_read_ptr:
    .word uart_rx_buffer

.globl uart_rx_interrupt_char, uart_rx_interrupt_seen
uart_rx_interrupt_char:
    .hword 3
uart_rx_interrupt_seen:
    .byte 0
