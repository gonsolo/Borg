.macro short_isr_entry
    .2byte 0xF028      # Save context, x9-x11 to gp-0x200
.endm

.macro short_isr_exit
    .2byte 0x3502      # Load context, x9-x11 from gp-0x200
    mret
.endm

.macro full_isr_entry    # Use after short_isr_entry to save all registers
    sw4 x12, -0x1f4(gp)  # Save x12-x15 to gp-0x1f4 (following on from save context in isr_entry)
    sw4 x5, -0x1e4(gp)   # Save x5-x8
    mv s0, ra
    mv s1, sp
    la sp, __interrupt_stack_top
.endm

.macro isr_exit         # Exit from a full ISR
    mv ra, s0
    mv sp, s1
    .2byte 0x3702       # Load context, x9-x15 from gp-0x200
    lw4 x5, -0x1e4(gp)  # Load x5-x8
    mret
.endm
