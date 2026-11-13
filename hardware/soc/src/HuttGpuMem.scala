// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.GpuMemIO
import hutt.{HuttBus, HuttInstrBus, HuttSize}

class HuttGpuMemIO(val addrWidth: Int, val instrWidth: Int) extends Bundle {
  val data  = Flipped(new HuttBus(addrWidth, 64))
  val instr = Flipped(new HuttInstrBus(instrWidth))
  val mem   = new GpuMemIO
}

/** RV64 Hutt's caches on a [[GpuMemIO]] port: the memory at `base` is the port's from 0. A read
  * takes the 32-bit words that cover it (up to three); a write of whole halfwords at an even
  * address is one burst, any other reads those words first and writes back the halfwords it
  * covers. An instruction fetch is one read. Data goes before instructions.
  */
class HuttGpuMem(val addrWidth: Int, val instrWidth: Int, base: BigInt) extends Module {
  val io = IO(new HuttGpuMemIO(addrWidth, instrWidth))

  val sIdle :: sRead :: sBurst :: sResp :: sFetch :: sFetchResp :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val addr  = Reg(UInt(addrWidth.W))
  val size  = Reg(UInt(2.W))
  val write = Reg(Bool())
  val wdata = Reg(UInt(64.W))
  val buf   = Reg(Vec(12, UInt(8.W)))    // the covering words, from `addr & ~1`
  val k     = Reg(UInt(2.W))             // words read
  val beat  = Reg(UInt(3.W))
  val fetch = Reg(UInt(32.W))

  val n      = (1.U << size)(3, 0)                    // bytes
  val lead   = addr(0)                                // a byte before the first in `buf`
  val hwBase = Cat(addr(addrWidth - 1, 1), 0.U(1.W)) - base.U
  val words  = (n +& lead +& 3.U) >> 2                // covering words to read
  val beats  = (n +& lead +& 1.U) >> 1                // covering halfwords to write
  val whole  = !lead && !n(0)
  // The covering bytes with the write merged in, as halfwords.
  val merged = VecInit((0 until 12).map(i => Mux(i.U >= lead && i.U < lead +& n, (wdata >> ((i.U - lead) << 3))(7, 0), buf(i))))
  val hw     = VecInit((0 until 5).map(i => Cat(merged(2 * i + 1), merged(2 * i))))
  val loaded = Cat((0 until 9).reverse.map(i => buf(i))) >> Cat(lead, 0.U(3.W))

  io.data.req.ready  := state === sIdle
  io.data.resp.valid := state === sResp
  io.data.resp.bits  := MuxLookup(size, loaded(63, 0))(Seq(HuttSize.Byte -> loaded(7, 0), HuttSize.Half -> loaded(15, 0), HuttSize.Word -> loaded(31, 0)))
  io.instr.req.ready  := state === sIdle && !io.data.req.valid
  io.instr.resp.valid := state === sFetchResp
  io.instr.resp.bits  := fetch

  io.mem.req   := state === sRead || state === sFetch
  io.mem.addr  := MuxCase(hwBase, Seq((state === sFetch) -> (Cat(addr(instrWidth - 1, 0), 0.U(2.W)) - base.U),
                                      (state === sRead) -> (hwBase + (k << 2))))
  io.mem.wr    := state === sBurst
  io.mem.wlen  := beats
  io.mem.wdata := Mux(whole, (wdata >> (beat << 4))(15, 0), hw(beat))

  switch(state) {
    is(sIdle) {
      when(io.data.req.fire) {
        val r = io.data.req.bits
        addr := r.addr; size := r.size; write := r.write; wdata := r.data; k := 0.U; beat := 0.U
        state := Mux(r.write && !r.addr(0) && !(1.U << r.size)(0), sBurst, sRead)
      }.elsewhen(io.instr.req.fire) {
        addr := io.instr.req.bits
        state := sFetch
      }
    }
    is(sRead) {
      when(io.mem.ready) {
        for (i <- 0 until 4) buf(Cat(k, i.U(2.W))) := io.mem.data(8 * i + 7, 8 * i)
        k := k + 1.U
        when(k + 1.U === words) { state := Mux(write, sBurst, sResp) }
      }
    }
    is(sBurst) {
      when(io.mem.waccept) { beat := beat + 1.U }
      when(io.mem.ready) { state := sResp }
    }
    is(sResp) {
      when(io.data.resp.fire) { state := sIdle }
    }
    is(sFetch) {
      when(io.mem.ready) { fetch := io.mem.data; state := sFetchResp }
    }
    is(sFetchResp) {
      when(io.instr.resp.fire) { state := sIdle }
    }
  }
}
