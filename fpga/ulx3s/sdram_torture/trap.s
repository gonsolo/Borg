# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0
#
# Minimal M-mode direct trap vector for the CLINT timer interrupt. Hutt
# only samples pending interrupts at instruction-fetch boundaries
# (Hutt.scala's is(sFetchReq) check), so this can never truly preempt an
# in-flight SDRAM transaction mid-cycle -- but it still inserts genuinely
# asynchronous (from the main loop's perspective) extra SDRAM traffic
# unpredictably interspersed with the foreground test's own accesses,
# which a bare interrupt-free loop can never exercise at all.

	.section .text
	.global trap_vector
	.align 4
trap_vector:
	addi	sp, sp, -128
	sd	ra, 0(sp)
	sd	t0, 8(sp)
	sd	t1, 16(sp)
	sd	t2, 24(sp)
	sd	a0, 32(sp)
	sd	a1, 40(sp)
	sd	a2, 48(sp)
	sd	a3, 56(sp)
	sd	a4, 64(sp)
	sd	a5, 72(sp)
	sd	a6, 80(sp)
	sd	a7, 88(sp)

	call	timer_isr

	ld	ra, 0(sp)
	ld	t0, 8(sp)
	ld	t1, 16(sp)
	ld	t2, 24(sp)
	ld	a0, 32(sp)
	ld	a1, 40(sp)
	ld	a2, 48(sp)
	ld	a3, 56(sp)
	ld	a4, 64(sp)
	ld	a5, 72(sp)
	ld	a6, 80(sp)
	ld	a7, 88(sp)
	addi	sp, sp, 128
	mret
