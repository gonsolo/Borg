// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** Direct-mapped instruction cache between the Hutt CPU and the memory
  * controller's instruction fetch port.
  *
  * Parameters: 512 lines × 1 word = 2 KB data (2 EBR tiles on ECP5).
  * Index = bits [8:0] of the word address.  Tag = bits [22:9].
  *
  * Timing:
  *   - Cache hit:  2 cycles (1 BRAM read latency + 1 response cycle).
  *   - Cache miss: 2 cycles overhead + SDRAM latency (~30–50 cycles).
  *
  * A direct-mapped 1-word-line cache captures all temporal locality from
  * repeated instruction fetches (loops), which dominate firmware hot paths.
  *
  * @param wordAddrWidth width of the word address on both ports (default 23
  *                      for a 32 MB SDRAM addressed as 8 M × 32-bit words).
  */
class InstrCacheIO(val wordAddrWidth: Int) extends Bundle {
  val cpu = Flipped(new HuttInstrBus(wordAddrWidth))  // Hutt CPU side
  val mem = new HuttInstrBus(wordAddrWidth)           // MemoryController side

  // Debug (task #15): expose the cache's own hit/miss decision and fill
  // events so a corrupted word can be traced back to the exact historical
  // fill that wrote it, rather than assumed to be a live SDRAM race on every
  // hit -- a direct-mapped cache serves hits from BRAM in 2 cycles, far
  // faster than the real SDRAM path, so what looks like a fast/racy fetch at
  // the CPU is very often just a hit on a line filled long before.
  val dbgState        = Output(UInt(3.W))
  val dbgAddrReg       = Output(UInt(wordAddrWidth.W))
  val dbgIsHit         = Output(Bool())
  val dbgFillSeq       = Output(UInt(32.W))
  val dbgFillWordAddr  = Output(UInt(wordAddrWidth.W))
  val dbgFillData      = Output(UInt(32.W))
}

class InstrCache(val wordAddrWidth: Int = 23, val numLines: Int = 512) extends Module {
  val indexBits = log2Ceil(numLines)
  val tagBits   = wordAddrWidth - indexBits

  val io = IO(new InstrCacheIO(wordAddrWidth))

  // BRAM storage: tag[tagBits-1:0] packed with valid flag in the MSB.
  // tagMem width = tagBits + 1 (valid bit is MSB).
  val tagMem  = SyncReadMem(numLines, UInt((tagBits + 1).W))
  val dataMem = SyncReadMem(numLines, UInt(32.W))

  // sFlush: on every reset, iterate through all entries and write the
  // "invalid" pattern (0) into tagMem so stale valid bits don't survive
  // resets between cocotb test cases.  Takes numLines cycles; negligible
  // at any realistic clock frequency.
  val sFlush :: sIdle :: sCheck :: sMissReq :: sMissWait :: Nil = Enum(5)
  val state    = RegInit(sFlush)
  val flushIdx = RegInit(0.U(indexBits.W))

  val addrReg = RegInit(0.U(wordAddrWidth.W))

  // SyncReadMem reads are issued combinationally but data is available one
  // cycle later.  We gate the read enable on req.fire so we only trigger a
  // BRAM read when a new CPU request is accepted (state=sIdle, ready=true).
  val cpuIndex = io.cpu.req.bits(indexBits - 1, 0)
  val tagOut   = tagMem.read(cpuIndex, io.cpu.req.fire)
  val dataOut  = dataMem.read(cpuIndex, io.cpu.req.fire)

  // Tag/valid breakdown of the BRAM output (valid in sCheck, one cycle after
  // the read was issued in sIdle).
  val storedValid = tagOut(tagBits)
  val storedTag   = tagOut(tagBits - 1, 0)
  val reqTag      = addrReg(wordAddrWidth - 1, indexBits)
  val isHit       = storedValid && (storedTag === reqTag)

  // Output defaults
  io.cpu.req.ready  := false.B
  io.cpu.resp.valid := false.B
  io.cpu.resp.bits  := dataOut
  io.mem.req.valid  := false.B
  io.mem.req.bits   := addrReg
  io.mem.resp.ready := false.B

  io.dbgState  := state
  io.dbgAddrReg := addrReg
  io.dbgIsHit   := isHit

  val dbgFillSeqReg      = RegInit(0.U(32.W))
  val dbgFillWordAddrReg = RegInit(0.U(wordAddrWidth.W))
  val dbgFillDataReg     = RegInit(0.U(32.W))
  io.dbgFillSeq      := dbgFillSeqReg
  io.dbgFillWordAddr := dbgFillWordAddrReg
  io.dbgFillData     := dbgFillDataReg

  switch(state) {

    is(sFlush) {
      // Invalidate all cache lines at reset (one per cycle).
      tagMem.write(flushIdx, 0.U)
      flushIdx := flushIdx + 1.U
      when(flushIdx === (numLines - 1).U) { state := sIdle }
    }

    is(sIdle) {
      io.cpu.req.ready := true.B
      when(io.cpu.req.fire) {
        addrReg := io.cpu.req.bits
        state   := sCheck
      }
    }

    is(sCheck) {
      when(isHit) {
        io.cpu.resp.valid := true.B
        io.cpu.resp.bits  := dataOut
        when(io.cpu.resp.fire) { state := sIdle }
      }.otherwise {
        state := sMissReq
      }
    }

    is(sMissReq) {
      io.mem.req.valid := true.B
      io.mem.req.bits  := addrReg
      when(io.mem.req.fire) { state := sMissWait }
    }

    is(sMissWait) {
      // Forward the memory controller response to the CPU and fill the cache
      // in the same cycle the response arrives.  The CPU is in sFetchResp
      // with resp.ready=true, so resp.fire coincides with mem.resp.fire.
      io.mem.resp.ready := true.B
      io.cpu.resp.valid := io.mem.resp.valid
      io.cpu.resp.bits  := io.mem.resp.bits
      when(io.mem.resp.fire) {
        dataMem.write(addrReg(indexBits - 1, 0), io.mem.resp.bits)
        tagMem.write(
          addrReg(indexBits - 1, 0),
          Cat(true.B, addrReg(wordAddrWidth - 1, indexBits))
        )
        dbgFillSeqReg      := dbgFillSeqReg + 1.U
        dbgFillWordAddrReg := addrReg
        dbgFillDataReg     := io.mem.resp.bits
        state := sIdle
      }
    }
  }
}
