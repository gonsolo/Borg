.include "macros.s"

.section .boot,"ax"
_boot:
.option norvc
    j _start          # Reset
    j _trap_handler   # Trap
.option rvc    
    short_isr_entry   # Interrupt, vectored here
    csrr a0, mcause
    slli s1, a0, 2
    li a1, 28
    beq s1, a1, _isr_timer
    addi a1, s1, 0x80
    lw a1, (a1)
    jr a1

_start:
    li gp, 0x1000400    # These required if testing against
    li tp, 0x8000000    # a different Risc-V core, but do no harm on TinyQV
    la sp, __StackTop
    jal __runtime_init
    li a1, 0x40000      # Enable UART receive interupt
    csrs mie, a1
    call main
    j .

# The trap handler should probably do something more useful.
_trap_handler:
    mret

_isr_timer:
    full_isr_entry
    call tqv_timer_interrupt
    isr_exit

# Raw interrupt redirects, this allows a specific low-level interrupt function to be
# installed simply by defining a function tqv_user_interruptnn_raw.
# On entry only s1, a0 and a1 are saved.  There is no stack.  The short_isr_exit macro would normally be used to return.
.globl tqv_user_interrupt04_raw, tqv_user_interrupt05_raw, tqv_user_interrupt06_raw, tqv_user_interrupt07_raw
.globl tqv_user_interrupt08_raw, tqv_user_interrupt09_raw, tqv_user_interrupt10_raw, tqv_user_interrupt11_raw
.globl tqv_user_interrupt12_raw, tqv_user_interrupt13_raw, tqv_user_interrupt14_raw, tqv_user_interrupt15_raw
.weak tqv_user_interrupt04_raw, tqv_user_interrupt05_raw, tqv_user_interrupt06_raw, tqv_user_interrupt07_raw
.weak tqv_user_interrupt08_raw, tqv_user_interrupt09_raw, tqv_user_interrupt10_raw, tqv_user_interrupt11_raw
.weak tqv_user_interrupt12_raw, tqv_user_interrupt13_raw, tqv_user_interrupt14_raw, tqv_user_interrupt15_raw
tqv_user_interrupt04_raw:
tqv_user_interrupt05_raw:
tqv_user_interrupt06_raw:
tqv_user_interrupt07_raw:
tqv_user_interrupt08_raw:
tqv_user_interrupt09_raw:
tqv_user_interrupt10_raw:
tqv_user_interrupt11_raw:
tqv_user_interrupt12_raw:
tqv_user_interrupt13_raw:
tqv_user_interrupt14_raw:
tqv_user_interrupt15_raw:
    addi a1, s1, 0xb0
    full_isr_entry
    lw a1, (a1)
    jalr ra, (a1)
    isr_exit

.section .vectors,"a"
    .word isr_in0    # 0xc0-0xfc is vectors for the raw interrupts
    .word isr_in1
    .word isr_uart_byte_available
    .word isr_uart_writable
    .word tqv_user_interrupt04_raw
    .word tqv_user_interrupt05_raw
    .word tqv_user_interrupt06_raw
    .word tqv_user_interrupt07_raw
    .word tqv_user_interrupt08_raw
    .word tqv_user_interrupt09_raw
    .word tqv_user_interrupt10_raw
    .word tqv_user_interrupt11_raw
    .word tqv_user_interrupt12_raw
    .word tqv_user_interrupt13_raw
    .word tqv_user_interrupt14_raw
    .word tqv_user_interrupt15_raw
    .word tqv_user_interrupt04     # 0x100-0x12c is vectors for the custom interrupts (with full context saved)
    .word tqv_user_interrupt05
    .word tqv_user_interrupt06
    .word tqv_user_interrupt07
    .word tqv_user_interrupt08
    .word tqv_user_interrupt09
    .word tqv_user_interrupt10
    .word tqv_user_interrupt11
    .word tqv_user_interrupt12
    .word tqv_user_interrupt13
    .word tqv_user_interrupt14
    .word tqv_user_interrupt15

.section .text

# These ISRs are entered with only s1, a0, a1 saved
# The ISR must save and restore any other registers it modifies.
# a0 is set to mcause on entry, so 1 << a0 is the corresponding bit in mip/mie
.globl isr_in0, isr_in1, isr_uart_byte_available, isr_uart_writable, tqv_timer_interrupt
.weak isr_in0, isr_in1, isr_uart_byte_available, isr_uart_writable, tqv_timer_interrupt
isr_in0:  # Default implementation just acks the interrupt
isr_in1:
    li a1, 1
    sll a1, a1, a0
    csrc mip, a1
    short_isr_exit

isr_uart_byte_available:  # Default implementation clears interrupt enable 
isr_uart_writable:        # to avoid an infinite interrupt loop
    li a1, 1
    sll a1, a1, a0
    csrc mie, a1
    short_isr_exit

# This is designed to be implemented in C, it's a normal function.
tqv_timer_interrupt:      # Default implementation just clears interrupt enable
    li a0, 0x80
    csrc mie, a0
    ret

# Interrupt redirects, this allows a specific interrupt function to be
# installed simply by defining a function tqv_user_interruptnn
.globl tqv_user_interrupt04, tqv_user_interrupt05, tqv_user_interrupt06, tqv_user_interrupt07
.globl tqv_user_interrupt08, tqv_user_interrupt09, tqv_user_interrupt10, tqv_user_interrupt11
.globl tqv_user_interrupt12, tqv_user_interrupt13, tqv_user_interrupt14, tqv_user_interrupt15
.weak tqv_user_interrupt04, tqv_user_interrupt05, tqv_user_interrupt06, tqv_user_interrupt07
.weak tqv_user_interrupt08, tqv_user_interrupt09, tqv_user_interrupt10, tqv_user_interrupt11
.weak tqv_user_interrupt12, tqv_user_interrupt13, tqv_user_interrupt14, tqv_user_interrupt15
tqv_user_interrupt04:
tqv_user_interrupt05:
tqv_user_interrupt06:
tqv_user_interrupt07:
tqv_user_interrupt08:
tqv_user_interrupt09:
tqv_user_interrupt10:
tqv_user_interrupt11:
tqv_user_interrupt12:
tqv_user_interrupt13:
tqv_user_interrupt14:
tqv_user_interrupt15:
    j tqv_user_interrupt

# A user interrupt - again this is a normal function that can be implemented in C.
# Default implementation just disables the interrupt and returns
# a0 contains mcause.
.globl tqv_user_interrupt
.weak tqv_user_interrupt
tqv_user_interrupt:
    li a1, 1
    sll a1, a1, a0
    csrc mie, a1
    ret
