# Software multiply routines for the Hutt core (RV32I, ilp32 -- no M extension
# emitted by the compiler; see software/hutt/mul.s for the RV64 lp64 sibling,
# XLEN-selected in the Makefile).  Shift-and-add algorithm, RV32E-compatible
# register usage (x0-x15) so it also works if the ABI is ever narrowed further.

.section .text

# 32-bit multiply: a0 = a0 * a1
.globl mul32x32
.globl __mulsi3
mul32x32:
__mulsi3:
    mv a2, a0          # a2 = multiplicand
    li a0, 0           # a0 = result = 0
    beqz a1, .Lmul32_done
.Lmul32_loop:
    andi a3, a1, 1     # test LSB of multiplier
    beqz a3, .Lmul32_skip
    add a0, a0, a2     # result += multiplicand
.Lmul32_skip:
    slli a2, a2, 1     # multiplicand <<= 1
    srli a1, a1, 1     # multiplier >>= 1
    bnez a1, .Lmul32_loop
.Lmul32_done:
    ret

# 64-bit multiply: (a1:a0) = (a1:a0) * (a3:a2)
# Result: low 32 bits in a0, high 32 bits in a1
# Uses only RV32E-compatible registers (x0-x15)
.globl __muldi3
__muldi3:
    # Save callee-saved registers
    addi sp, sp, -8
    sw s0, 0(sp)
    sw s1, 4(sp)

    # s0:s1 = multiplicand (a1:a0)
    mv s0, a0
    mv s1, a1
    # a2:a3 = multiplier (already in place)
    li a0, 0            # result low
    li a1, 0            # result high

.Lmul64_loop:
    # Check if multiplier is zero (both halves)
    or t0, a2, a3
    beqz t0, .Lmul64_done

    andi t0, a2, 1      # test LSB of multiplier low
    beqz t0, .Lmul64_skip
    add a0, a0, s0      # result_low += multiplicand_low
    sltu t0, a0, s0     # carry
    add a1, a1, s1      # result_high += multiplicand_high
    add a1, a1, t0      # result_high += carry

.Lmul64_skip:
    # Shift multiplicand left by 1 (64-bit)
    srli t0, s0, 31     # carry from low to high
    slli s0, s0, 1
    slli s1, s1, 1
    or s1, s1, t0

    # Shift multiplier right by 1 (64-bit)
    slli t0, a3, 31     # carry from high to low
    srli a2, a2, 1
    or a2, a2, t0
    srli a3, a3, 1

    j .Lmul64_loop

.Lmul64_done:
    lw s0, 0(sp)
    lw s1, 4(sp)
    addi sp, sp, 8
    ret
