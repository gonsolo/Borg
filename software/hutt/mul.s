# Software multiply routines for the Hutt core (RV64I — no M extension).
#
# The riscv64 toolchain's libgcc is built for rv64imafdc (hardware MUL), so it
# does NOT ship __muldi3 (and its __mulsi3 assumes MUL).  The FPU/MUL-less Hutt
# needs soft shift-and-add versions.  These MUST be RV64-correct: the previous
# RV32 (ilp32) versions treated 64-bit args as register pairs and saved s0/s1
# with 32-bit sw/lw, corrupting every 64-bit multiply and clobbering callee-
# saved registers on RV64.  RV64 lp64: each integer arg is one 64-bit register.

.section .text

# 32-bit multiply (SImode): a0 = (int)a0 * (int)a1, sign-extended to 64 bits.
# Zero-extend the multiplier to 32 bits first so a negative operand does not make
# the shift loop run the full 64 iterations; the low 32 bits of the product are
# identical regardless, and sext.w yields the canonical SImode result.
.globl mul32x32
.globl __mulsi3
mul32x32:
__mulsi3:
    slli a1, a1, 32
    srli a1, a1, 32        # a1 = zext32(multiplier)
    mv   a2, a0            # a2 = multiplicand
    li   a0, 0             # a0 = result
    beqz a1, .Lsi_done
.Lsi_loop:
    andi a3, a1, 1
    beqz a3, .Lsi_skip
    add  a0, a0, a2
.Lsi_skip:
    slli a2, a2, 1
    srli a1, a1, 1
    bnez a1, .Lsi_loop
.Lsi_done:
    sext.w a0, a0
    ret

# 64-bit multiply (DImode): a0 = a0 * a1 (low 64 bits).  RV64: both operands are
# full 64-bit registers; slli/srli are 64-bit shifts.  No callee-saved regs used,
# so nothing to spill.
.globl __muldi3
__muldi3:
    mv   a2, a0            # a2 = multiplicand
    li   a0, 0             # a0 = result
    beqz a1, .Ldi_done
.Ldi_loop:
    andi a3, a1, 1
    beqz a3, .Ldi_skip
    add  a0, a0, a2
.Ldi_skip:
    slli a2, a2, 1
    srli a1, a1, 1
    bnez a1, .Ldi_loop
.Ldi_done:
    ret
