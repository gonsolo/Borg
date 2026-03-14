.macro short_isr_entry
    sw x9, -0x200(gp)   # Save x9-x11 to gp-0x200
    sw x10, -0x1fc(gp)
    sw x11, -0x1f8(gp)
.endm

.macro short_isr_exit
    lw x9, -0x200(gp)   # Load x9-x11 from gp-0x200
    lw x10, -0x1fc(gp)
    lw x11, -0x1f8(gp)
    mret
.endm

.macro full_isr_entry    # Use after short_isr_entry to save all registers
    sw x12, -0x1f4(gp)  # Save x12-x15 to gp-0x1f4
    sw x13, -0x1f0(gp)
    sw x14, -0x1ec(gp)
    sw x15, -0x1e8(gp)
    sw x5, -0x1e4(gp)   # Save x5-x8
    sw x6, -0x1e0(gp)
    sw x7, -0x1dc(gp)
    sw x8, -0x1d8(gp)
    mv s0, ra
    mv s1, sp
    la sp, __interrupt_stack_top
.endm

.macro isr_exit         # Exit from a full ISR
    mv ra, s0
    mv sp, s1
    lw x9, -0x200(gp)   # Load x9-x15 from gp-0x200
    lw x10, -0x1fc(gp)
    lw x11, -0x1f8(gp)
    lw x12, -0x1f4(gp)
    lw x13, -0x1f0(gp)
    lw x14, -0x1ec(gp)
    lw x15, -0x1e8(gp)
    lw x5, -0x1e4(gp)  # Load x5-x8
    lw x6, -0x1e0(gp)
    lw x7, -0x1dc(gp)
    lw x8, -0x1d8(gp)
    mret
.endm

