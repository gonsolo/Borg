# Memory timing on Hutt, by the CLINT's mtime (one tick per 25 MHz cycle); results at 0x100100:
# copy 64 KB with ld/sd, read a 16 KB buffer 16 times, read one address 4096 times, a loop
# without memory 4096 times; then the marker 0x600dc0de.
	.globl _start
_start:
	li	s0, 0x02000000		# mtime
	ld	s1, 0(s0)
	li	a0, 0x400000		# copy 64 KB to 0x500000
	li	a1, 0x500000
	li	a2, 0x410000
1:	ld	t0, 0(a0)
	sd	t0, 0(a1)
	addi	a0, a0, 8
	addi	a1, a1, 8
	bltu	a0, a2, 1b
	ld	s2, 0(s0)
	li	t2, 16			# read 16 KB 16 times
	li	t1, 0
2:	li	a0, 0x400000
	li	a2, 0x404000
3:	ld	t0, 0(a0)
	add	t1, t1, t0
	addi	a0, a0, 8
	bltu	a0, a2, 3b
	addi	t2, t2, -1
	bnez	t2, 2b
	ld	s3, 0(s0)
	li	a0, 0x400000		# the same 8 bytes 4096 times: always the cached line
	li	t2, 4096
5:	ld	t0, 0(a0)
	add	t1, t1, t0
	addi	t2, t2, -1
	bnez	t2, 5b
	ld	s4, 0(s0)
	li	t2, 4096		# no memory: the loop alone
6:	add	t1, t1, t2
	addi	t2, t2, -1
	bnez	t2, 6b
	ld	s5, 0(s0)
	li	a0, 0x100100
	sub	t0, s2, s1
	sd	t0, 0(a0)
	sub	t0, s3, s2
	sd	t0, 8(a0)
	sub	t0, s4, s3
	sd	t0, 16(a0)
	sub	t0, s5, s4
	sd	t0, 24(a0)
	sd	t1, 32(a0)
	li	t0, 0x600dc0de
	sw	t0, 40(a0)
4:	j	4b
