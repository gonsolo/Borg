// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.GpuMemIO
import memory.MemBackendIO

/** [[memory.MemoryController]]'s halfword backend on a [[GpuMemIO]] port, so the CPU shares one
  * memory port with Borg and the host link. A single write with one byte lane reads the halfword
  * first; a burst writes whole halfwords.
  */
class GpuMemBackendIO extends Bundle {
  val be  = Flipped(new MemBackendIO)
  val mem = new GpuMemIO
}

class GpuMemBackend extends Module {
  val io = IO(new GpuMemBackendIO)
  val sIdle :: sRead :: sMerge :: sWrite :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val addr  = Reg(UInt(32.W))
  val len   = Reg(UInt(7.W))
  val word  = Reg(UInt(16.W))
  val mask  = Reg(UInt(2.W))
  val first = Reg(Bool())

  io.be.busy    := state =/= sIdle
  io.be.done    := false.B
  io.be.accept  := state === sWrite && io.mem.waccept
  io.be.dataOut := io.mem.data(15, 0)

  io.mem.addr  := addr
  io.mem.req   := state === sRead || state === sMerge
  io.mem.wr    := state === sWrite
  io.mem.wlen  := len
  io.mem.wdata := Mux(first, word, io.be.dataIn)

  switch(state) {
    is(sIdle) {
      addr  := io.be.addrIn << 1
      len   := io.be.lenIn
      word  := io.be.dataIn
      mask  := io.be.byteEnIn
      first := true.B
      when(io.be.startRead) { state := sRead }
      when(io.be.startWrite) { state := Mux(io.be.lenIn === 1.U && io.be.byteEnIn =/= 3.U, sMerge, sWrite) }
    }
    is(sRead) {
      when(io.mem.ready) { io.be.done := true.B; state := sIdle }
    }
    is(sMerge) {
      when(io.mem.ready) {
        val old = io.mem.data(15, 0)
        word  := Cat(Mux(mask(1), word(15, 8), old(15, 8)), Mux(mask(0), word(7, 0), old(7, 0)))
        state := sWrite
      }
    }
    is(sWrite) {
      when(io.mem.waccept) { first := false.B }
      when(io.mem.ready) { io.be.done := true.B; state := sIdle }
    }
  }
}
