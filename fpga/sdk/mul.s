.section .text

.globl mul32x32
.globl __mulsi3
mul32x32:
__mulsi3:
    mul16 a2, a0, a1
    srai a1, a1, 16
    mul16 a0, a0, a1
    slli a0, a0, 16
    add a0, a0, a2
    ret

.globl __muldi3
__muldi3:
    li a5, 0xFFFF
    and a5, a0, a5
    srli a4, a0, 16
    mul16 t0, a5, a2
    sw t0, -4(sp)
    mul16 t0, a4, a2
    mul16 t2, a1, a2
    srli a2, a2, 16
    mul16 a5, a5, a2
    add t1, t0, a5
    sltu t0, t1, t0
    slli t0, t0, 16
    add t2, t2, t0
    slli a1, a1, 16
    or a4, a4, a1
    mul16 a4, a4, a2
    add t2, t2, a4
    mul16 a5, a0, a3
    srli a3, a3, 16
    mul16 a0, a0, a3
    slli a0, a0, 16
    add a0, a0, a5
    add a1, a0, t2
    slli t2, t1, 16
    lw t0, -4(sp)
    add a0, t0, t2
    sltu t2, a0, t0
    add a1, a1, t2
    srli t1, t1, 16
    add a1, a1, t1
    ret
