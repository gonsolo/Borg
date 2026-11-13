// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class DataCacheIO(val addrWidth: Int) extends Bundle {
  val cpu   = Flipped(new HuttBus(addrWidth, 64))
  val mem   = new HuttBus(addrWidth, 64)
  val flush = Input(Bool())   // another master wrote memory: every line goes
}

/** Direct-mapped, write-through data cache on RV64 Hutt's data bus, 16-byte lines. A read hit
  * answers in the cycle after the request; a read miss fills its line with two doubleword reads;
  * a write goes to memory and updates a line it hits. `flush` empties the cache in a cycle, also
  * during a fill. Addresses outside `cacheable`, and unaligned accesses, pass through; an
  * unaligned write empties the cache.
  */
class DataCache(val addrWidth: Int = 28, val numLines: Int = 1024, cacheable: UInt => Bool = _ => true.B) extends Module {
  val io = IO(new DataCacheIO(addrWidth))
  private val indexBits = log2Ceil(numLines)
  private val tagBits   = addrWidth - indexBits - 4

  val tags  = SyncReadMem(numLines, UInt(tagBits.W))
  val data  = SyncReadMem(numLines * 2, Vec(8, UInt(8.W)))
  val valid = RegInit(VecInit(Seq.fill(numLines)(false.B)))

  val sIdle :: sLookup :: sFill :: sPass :: sResp :: Nil = Enum(5)
  val state = RegInit(sIdle)
  val req   = Reg(new HuttBusReq(addrWidth, 64))
  val fillW = Reg(UInt(1.W))
  val stale = Reg(Bool())
  val word  = Reg(UInt(64.W))

  val index  = req.addr(indexBits + 3, 4)
  val tag    = req.addr(addrWidth - 1, indexBits + 4)
  val off    = req.addr(2, 0)
  val cpuIdx = io.cpu.req.bits.addr(indexBits + 3, 4)
  val tagOut  = tags.read(cpuIdx, io.cpu.req.fire)
  val dataOut = data.read(io.cpu.req.bits.addr(indexBits + 3, 3), io.cpu.req.fire).asUInt
  val hit     = valid(index) && tagOut === tag
  val aligned = MuxLookup(req.size, true.B)(Seq(
    HuttSize.Half -> !off(0), HuttSize.Word -> (off(1, 0) === 0.U), HuttSize.Double -> (off === 0.U)))
  val cached  = cacheable(req.addr) && aligned

  def extract(w: UInt): UInt = {
    val s = w >> Cat(off, 0.U(3.W))
    MuxLookup(req.size, s)(Seq(HuttSize.Byte -> s(7, 0), HuttSize.Half -> s(15, 0), HuttSize.Word -> s(31, 0)))
  }
  val mask = (MuxLookup(req.size, "hff".U)(Seq(HuttSize.Byte -> "h01".U, HuttSize.Half -> "h03".U, HuttSize.Word -> "h0f".U)) << off)(7, 0)
  val wdata = (req.data << Cat(off, 0.U(3.W)))(63, 0)
  val hitNow = state === sLookup && cached && !req.write && hit

  io.cpu.req.ready  := state === sIdle
  io.cpu.resp.valid := hitNow || state === sResp
  io.cpu.resp.bits  := Mux(state === sResp, word, extract(dataOut))
  io.mem.req.valid  := state === sFill || state === sPass
  io.mem.req.bits   := req
  when(state === sFill) {
    io.mem.req.bits.addr  := Cat(req.addr(addrWidth - 1, 4), fillW, 0.U(3.W))
    io.mem.req.bits.write := false.B
    io.mem.req.bits.size  := HuttSize.Double
  }
  io.mem.resp.ready := true.B

  // A request in flight on `mem` waits for its response before the next goes out.
  val sent = RegInit(false.B)
  when(io.mem.req.fire) { sent := true.B }
  when(io.mem.resp.fire) { sent := false.B }
  when(sent) { io.mem.req.valid := false.B }

  // An unaligned write passes, and may touch two lines: they all go.
  val unalignedWrite = state === sLookup && req.write && cacheable(req.addr) && !aligned
  when(io.flush || unalignedWrite) { valid.foreach(_ := false.B); stale := true.B }

  // One masked write port: a write that hits, or a fill's doubleword.
  val wrEn   = WireDefault(false.B)
  val wrAddr = WireDefault(Cat(index, req.addr(3)))
  val wrData = WireDefault(UInt(64.W), wdata)
  val wrMask = WireDefault(UInt(8.W), mask)
  when(wrEn) { data.write(wrAddr, VecInit((0 until 8).map(i => wrData(8 * i + 7, 8 * i))), wrMask.asBools) }

  switch(state) {
    is(sIdle) {
      when(io.cpu.req.fire) { req := io.cpu.req.bits; state := sLookup }
    }
    is(sLookup) {
      when(!cached || req.write) {
        wrEn  := cached && hit
        state := sPass
      }.elsewhen(hit) {
        word  := extract(dataOut)
        state := Mux(io.cpu.resp.ready, sIdle, sResp)
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
        wrMask := "hff".U
        when(fillW === req.addr(3)) { word := extract(w) }
        fillW := 1.U
        when(fillW === 1.U) {
          tags.write(index, tag)
          when(!stale && !io.flush) { valid(index) := true.B }
          state := sResp
        }
      }
    }
    is(sPass) {
      when(io.mem.resp.fire) { word := io.mem.resp.bits; state := sResp }
    }
    is(sResp) {
      when(io.cpu.resp.fire) { state := sIdle }
    }
  }
}
