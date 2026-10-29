// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class InstrCacheIO(val wordAddrWidth: Int) extends Bundle {
  val cpu = Flipped(new HuttInstrBus(wordAddrWidth))  // Hutt CPU side
  val mem = new HuttInstrBus(wordAddrWidth)           // MemoryController side

  // Debug: hits and fills, to trace a bad word to the fill that wrote it.
  val dbgState        = Output(UInt(3.W))
  val dbgAddrReg       = Output(UInt(wordAddrWidth.W))
  val dbgIsHit         = Output(Bool())
  val dbgFillSeq       = Output(UInt(32.W))
  val dbgFillWordAddr  = Output(UInt(wordAddrWidth.W))
  val dbgFillData      = Output(UInt(32.W))
}

/** Direct-mapped instruction cache with 1-word lines; a hit takes 2 cycles.
  * The default wordAddrWidth 23 addresses a 32 MB SDRAM.
  */
class InstrCache(val wordAddrWidth: Int = 23, val numLines: Int = 512) extends Module {
  val indexBits = log2Ceil(numLines)
  val tagBits   = wordAddrWidth - indexBits

  val io = IO(new InstrCacheIO(wordAddrWidth))

  // Valid bit in the tag's MSB.
  val tagMem  = SyncReadMem(numLines, UInt((tagBits + 1).W))
  val dataMem = SyncReadMem(numLines, UInt(32.W))

  // sFlush clears every tag after reset, so valid bits do not survive a reset.
  val sFlush :: sIdle :: sCheck :: sMissReq :: sMissWait :: Nil = Enum(5)
  val state    = RegInit(sFlush)
  val flushIdx = RegInit(0.U(indexBits.W))

  val addrReg = RegInit(0.U(wordAddrWidth.W))

  val cpuIndex = io.cpu.req.bits(indexBits - 1, 0)
  val tagOut   = tagMem.read(cpuIndex, io.cpu.req.fire)
  val dataOut  = dataMem.read(cpuIndex, io.cpu.req.fire)

  // Valid in sCheck.
  val storedValid = tagOut(tagBits)
  val storedTag   = tagOut(tagBits - 1, 0)
  val reqTag      = addrReg(wordAddrWidth - 1, indexBits)
  val isHit       = storedValid && (storedTag === reqTag)

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
      // The CPU waits with resp.ready, so the fill and the forward share a cycle.
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
