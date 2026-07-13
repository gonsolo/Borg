# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0
#
# Minimal RV64 M-mode entry point for sdram_torture.c -- no OpenSBI, no
# Linux, no MMU. FlashBootLoader copies this straight to SDRAM address 0
# and the CPU starts executing at PC=0 in M-mode, same as OpenSBI's own
# reset entry on this platform. All we need is a stack pointer before
# calling into C.

	.section .text.start
	.global _start
_start:
	.option push
	.option norelax
	la	gp, __global_pointer$
	.option pop
	la	sp, _stack_top

	# Zero .bss -- SDRAM retains whatever the previous firmware left
	# behind (FlashBootLoader doesn't clear it), and .bss's zero-init
	# contract is the STARTUP code's responsibility, not the linker's.
	la	t0, __bss_start
	la	t1, __bss_end
1:	bge	t0, t1, 2f
	sd	zero, 0(t0)
	addi	t0, t0, 8
	j	1b
2:
	call	main
3:	j	3b
