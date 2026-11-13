# Hutt's first words on the Nexys: the UART, and a copy 1 MB into RAM for the host to read.
	.globl _start
_start:
	li	t0, 0x08000018
	la	a0, msg
	li	a2, 0x80100000
1:	lbu	a1, 0(a0)
	beqz	a1, 3f
	sb	a1, 0(a2)
2:	lw	t1, 4(t0)
	andi	t1, t1, 1
	bnez	t1, 2b
	sw	a1, 0(t0)
	addi	a0, a0, 1
	addi	a2, a2, 1
	j	1b
3:	li	a2, 0x80100100
	li	a1, 0x600dc0de
	sw	a1, 0(a2)
4:	j	4b
msg:	.asciz	"Hello from Hutt on the Nexys Video\r\n"
