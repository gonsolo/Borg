// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** One register write of a state block, on its way to the MMIO bus. */
class RegWrite extends Bundle {
  val addr = UInt(10.W)
  val data = UInt(32.W)
}

class BorgRegPlayerIO extends Bundle {
  val start = Input(Bool())
  val addr  = Input(UInt(GpuMemIO.AddrBits.W))
  val busy  = Output(Bool())
  val done  = Output(Bool())                    // one cycle, after the last write has settled
  val dmaStart = Output(Bool())
  val dmaDesc  = Output(new DMADescriptor)
  val dmaBusy  = Input(Bool())
  val snoop    = Flipped(Valid(UInt(32.W)))
  val write    = Decoupled(new RegWrite)
}

/** BorgRegPlayer -- plays a **state block** from GPU memory into the MMIO
  * registers (docs/B1_geometry_front_end.md, "Render lists").
  *
  * A block is a count `n` followed by `n` pairs (register byte offset,
  * value). Each pair becomes one register write, exactly as if the driver had
  * written it over the bus, so every register a draw can set is settable per
  * draw without a second copy of the register map. The words are read one
  * pair at a time, so the bus may hold a write back for as long as it likes.
  */
class BorgRegPlayer extends Module {
  val io = IO(new BorgRegPlayerIO)

  private val sIdle :: sCount :: sWaitCount :: sPair :: sWaitPair :: sWrite :: sSettle :: Nil = Enum(7)
  private val state = RegInit(sIdle)
  private val cur   = Reg(UInt(GpuMemIO.AddrBits.W))
  private val left  = Reg(UInt(16.W))
  private val word  = RegInit(0.U(1.W))
  private val pair  = Reg(new RegWrite)
  private val settle = RegInit(0.U(2.W))

  io.busy := state =/= sIdle
  io.done := false.B
  io.dmaStart := false.B
  io.dmaDesc.baseAddr := cur
  io.dmaDesc.length   := 1.U
  io.dmaDesc.dest     := 2.U      // snoop only
  io.dmaDesc.offset   := 0.U
  io.write.valid := state === sWrite
  io.write.bits  := pair

  switch(state) {
    is(sIdle) {
      when(io.start) { cur := io.addr; state := sCount }
    }
    is(sCount) {
      io.dmaStart := true.B
      state := sWaitCount
    }
    is(sWaitCount) {
      when(io.snoop.valid) { left := io.snoop.bits(15, 0); cur := cur + 4.U }
      when(!io.dmaBusy) { state := Mux(left === 0.U, sSettle, sPair); settle := 0.U }
    }
    is(sPair) {
      io.dmaDesc.length := 2.U
      io.dmaStart := true.B
      word  := 0.U
      state := sWaitPair
    }
    is(sWaitPair) {
      io.dmaDesc.length := 2.U
      when(io.snoop.valid) {
        when(word === 0.U) { pair.addr := io.snoop.bits(9, 0) }.otherwise { pair.data := io.snoop.bits }
        word := 1.U
      }
      when(!io.dmaBusy) { state := sWrite }
    }
    is(sWrite) {
      when(io.write.ready) {
        cur  := cur + 8.U
        left := left - 1.U
        settle := 0.U
        state := Mux(left === 1.U, sSettle, sPair)
      }
    }
    is(sSettle) {
      // The sequencer's configuration may sit behind a register stage
      // (BorgConfig.pipelineSeqConfig): let the last write arrive.
      settle := settle + 1.U
      when(settle === 2.U) { io.done := true.B; state := sIdle }
    }
  }
}
