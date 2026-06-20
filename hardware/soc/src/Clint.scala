// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import hutt.HuttBus

/** Core-Local Interruptor (CLINT) — 64-bit mtime counter + mtimecmp compare.
  *
  * MMIO layout (32-bit word accesses from the CPU):
  *   offset 0x0: mtime_lo     (R/W)
  *   offset 0x4: mtime_hi     (R/W)
  *   offset 0x8: mtimecmp_lo  (R/W)
  *   offset 0xC: mtimecmp_hi  (R/W)
  *
  * timerIrq is asserted when mtime >= mtimecmp (unsigned 64-bit compare).
  * On reset mtimecmp = 0xFFFF_FFFF_FFFF_FFFF so no interrupt fires at boot.
  */
class ClintIO extends Bundle {
  val mmio     = Flipped(new HuttBus(24))
  val timerIrq = Output(Bool())
}

class Clint extends Module {
  val io = IO(new ClintIO)

  val mtime    = RegInit(0.U(64.W))
  val mtimecmp = RegInit((-1L).S(64.W).asUInt)

  mtime := mtime + 1.U
  io.timerIrq := (mtime >= mtimecmp)

  val addr = io.mmio.req.bits.addr(3, 2)  // 2-bit word selector within CLINT

  val respPending = RegInit(false.B)
  val respData    = Reg(UInt(32.W))

  io.mmio.req.ready  := !respPending
  io.mmio.resp.valid := respPending
  io.mmio.resp.bits  := respData

  when(io.mmio.req.fire) {
    when(io.mmio.req.bits.write) {
      switch(addr) {
        is(0.U) { mtime    := Cat(mtime(63, 32),    io.mmio.req.bits.data(31, 0)) }
        is(1.U) { mtime    := Cat(io.mmio.req.bits.data(31, 0), mtime(31, 0)) }
        is(2.U) { mtimecmp := Cat(mtimecmp(63, 32), io.mmio.req.bits.data(31, 0)) }
        is(3.U) { mtimecmp := Cat(io.mmio.req.bits.data(31, 0), mtimecmp(31, 0)) }
      }
      respData := 0.U
    }.otherwise {
      switch(addr) {
        is(0.U) { respData := mtime(31, 0) }
        is(1.U) { respData := mtime(63, 32) }
        is(2.U) { respData := mtimecmp(31, 0) }
        is(3.U) { respData := mtimecmp(63, 32) }
      }
    }
    respPending := true.B
  }

  when(io.mmio.resp.fire) { respPending := false.B }
}
