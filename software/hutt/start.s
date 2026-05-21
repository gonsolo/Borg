.section .boot,"ax"
_boot:
.option norvc
    j _start

_start:
    # DEBUG beacons: write to debug UART (0x08000018) directly, before
    # touching sp / SDRAM, to bisect where the firmware hangs.
    # 'a' = reached _start ; 'b' = after sp setup ; 'c' = after __runtime_init.
    li t0, 0x08000018
    li t1, 'a'
    sw t1, 0(t0)
    li t2, 200
1:  addi t2, t2, -1
    bnez t2, 1b

    li gp, 0x1000400    # global pointer
    li tp, 0x8000000    # thread pointer (peripheral base)
    la sp, __StackTop

    li t0, 0x08000018
    li t1, 'b'
    sw t1, 0(t0)
    li t2, 200
1:  addi t2, t2, -1
    bnez t2, 1b

    jal __runtime_init

    li t0, 0x08000018
    li t1, 'c'
    sw t1, 0(t0)
    li t2, 200
1:  addi t2, t2, -1
    bnez t2, 1b

    call main
    j .

_trap_handler:
    j _trap_handler
