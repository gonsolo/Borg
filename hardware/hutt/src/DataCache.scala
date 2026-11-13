// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class DataCacheIO(val addrWidth: Int) extends Bundle {
  val cpu   = Flipped(new HuttBus(addrWidth, 32))
  val mem   = new HuttBus(addrWidth, 32)
  val flush = Input(Bool())   // another master wrote memory: every line goes
}

/** Direct-mapped, write-through data cache on Hutt's 32-bit memory bus, 16-byte lines. A hit
  * answers in 2 cycles; a read miss fills its line with four word reads; a write goes to memory
  * and updates a line it hits. `flush` empties the cache in a cycle, also during a fill.
  * A word not aligned to 4 bytes passes uncached.
  */
class DataCache(val addrWidth: Int = 25, val numLines: Int = 1024) extends Module {
  val io = IO(new DataCacheIO(addrWidth))
  private val indexBits = log2Ceil(numLines)
  private val tagBits   = addrWidth - indexBits - 4

  val tags  = SyncReadMem(numLines, UInt(tagBits.W))
  val data  = SyncReadMem(numLines * 4, Vec(4, UInt(8.W)))
  val valid = RegInit(VecInit(Seq.fill(numLines)(false.B)))

  val sIdle :: sLookup :: sFill :: sWrite :: sPass :: sResp :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val req   = Reg(new HuttBusReq(addrWidth, 32))
  val fillW = Reg(UInt(2.W))
  val stale = Reg(Bool())
  val word  = Reg(UInt(32.W))

  val index = req.addr(indexBits + 3, 4)
  val tag   = req.addr(addrWidth - 1, indexBits + 4)
  val off   = req.addr(1, 0)
  val cpuIdx = io.cpu.req.bits.addr(indexBits + 3, 4)
  val tagOut  = tags.read(cpuIdx, io.cpu.req.fire)
  val dataOut = data.read(io.cpu.req.bits.addr(indexBits + 3, 2), io.cpu.req.fire).asUInt
  val hit     = valid(index) && tagOut === tag
  val aligned = MuxLookup(req.size, true.B)(Seq(HuttSize.Half -> !off(0), HuttSize.Word -> (off === 0.U)))

  def extract(w: UInt): UInt = {
    val s = w >> Cat(off, 0.U(3.W))
    MuxLookup(req.size, s)(Seq(HuttSize.Byte -> s(7, 0), HuttSize.Half -> s(15, 0)))
  }
  val mask = MuxLookup(req.size, "b1111".U)(Seq(HuttSize.Byte -> ("b0001".U << off), HuttSize.Half -> ("b0011".U << off)))(3, 0)
  val wdata = (req.data << Cat(off, 0.U(3.W)))(31, 0)

  io.cpu.req.ready  := state === sIdle
  io.cpu.resp.valid := state === sResp
  io.cpu.resp.bits  := word
  io.mem.req.valid  := state === sFill || state === sWrite || state === sPass
  io.mem.req.bits   := req
  when(state === sFill) {
    io.mem.req.bits.addr  := Cat(req.addr(addrWidth - 1, 4), fillW, 0.U(2.W))
    io.mem.req.bits.write := false.B
    io.mem.req.bits.size  := HuttSize.Word
  }
  io.mem.resp.ready := true.B

  // A request in flight on `mem` waits for its response before the next goes out.
  val sent = RegInit(false.B)
  when(io.mem.req.fire) { sent := true.B }
  when(io.mem.resp.fire) { sent := false.B }
  when(sent) { io.mem.req.valid := false.B }

  when(io.flush) { valid.foreach(_ := false.B); stale := true.B }

  // One masked write port: a write that hits, or a fill's word.
  val wrEn   = WireDefault(false.B)
  val wrAddr = WireDefault(Cat(index, req.addr(3, 2)))
  val wrData = WireDefault(UInt(32.W), wdata)
  val wrMask = WireDefault(UInt(4.W), mask)
  when(wrEn) { data.write(wrAddr, VecInit((0 until 4).map(i => wrData(8 * i + 7, 8 * i))), wrMask.asBools) }

  switch(state) {
    is(sIdle) {
      when(io.cpu.req.fire) { req := io.cpu.req.bits; state := sLookup }
    }
    is(sLookup) {
      when(!aligned) {
        state := sPass
      }.elsewhen(req.write) {
        wrEn := hit
        state := sWrite
      }.elsewhen(hit) {
        word := extract(dataOut)
        state := sResp
      }.otherwise {
        fillW := 0.U
        stale := io.flush
        state := sFill
      }
    }
    is(sFill) {
      when(io.mem.resp.fire) {
        val w = io.mem.resp.bits
        wrEn   := true.B
        wrAddr := Cat(index, fillW)
        wrData := w
        wrMask := "b1111".U
        when(fillW === req.addr(3, 2)) { word := extract(w) }
        fillW := fillW + 1.U
        when(fillW === 3.U) {
          tags.write(index, tag)
          when(!stale && !io.flush) { valid(index) := true.B }
          state := sResp
        }
      }
    }
    is(sWrite) {
      when(io.mem.resp.fire) { state := sResp }
    }
    is(sPass) {
      when(io.mem.resp.fire) { word := io.mem.resp.bits; state := sResp }
    }
    is(sResp) {
      when(io.cpu.resp.fire) { state := sIdle }
    }
  }
}
