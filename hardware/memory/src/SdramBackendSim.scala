// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

class SdramBackendSimIO extends Bundle {
  val backend          = Flipped(new MemBackendIO)
  val debug_be_state   = Output(UInt(3.W))
  val debug_ctrl_state = Output(UInt(3.W))
  val debug_ctrl_rdy   = Output(Bool())
  val debug_readWord   = Output(UInt(16.W))
}

/** Host backdoor for Verilator preload and readback. */
class SdramDbgIO extends Bundle {
  val we    = Input(Bool())
  val waddr = Input(UInt(24.W))
  val wdata = Input(UInt(16.W))
  val raddr = Input(UInt(24.W))
  val rdata = Output(UInt(16.W))
}

/** Behavioral drop-in for SdramBackend: `words` 16-bit words, `rdDelay` and
  * `wrDelay` cycles from start to done. */
class SdramBackendSim(
  words:   Int = 4096,
  rdDelay: Int = 4,
  wrDelay: Int = 2,
  dbg:     Boolean = false
) extends Module {

  val io = IO(new SdramBackendSimIO)

  val dbgIO: Option[SdramDbgIO] = if (dbg) Some(IO(new SdramDbgIO)) else None

  val mem      = SyncReadMem(words, UInt(16.W))
  val wordBits = log2Up(words)

  dbgIO.foreach { d =>
    when(d.we) { mem.write(d.waddr(wordBits - 1, 0), d.wdata) }
    d.rdata := mem.read(d.raddr(wordBits - 1, 0))
  }

  val sIdle :: sRd :: sWr :: sWrBeat :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val isWrite = RegInit(false.B)
  val addrReg = RegInit(0.U(24.W))
  val dataReg = RegInit(0.U(16.W))
  val readReg = RegInit(0.U(16.W))
  val counter = RegInit(0.U(8.W))
  val lenReg  = RegInit(1.U(7.W))
  val beatReg = RegInit(0.U(7.W))   // words written so far this burst

  val rdAddrWire = WireDefault(0.U(wordBits.W))
  val rdData     = mem.read(rdAddrWire)

  val acceptW = WireDefault(false.B)   // the arbiter presents the next word next cycle

  io.backend.dataOut := readReg
  io.backend.accept  := acceptW
  io.backend.done    := state === sDone
  io.backend.busy    := state =/= sIdle

  io.debug_be_state   := state
  io.debug_ctrl_state := 0.U
  io.debug_ctrl_rdy   := false.B
  io.debug_readWord   := readReg

  switch(state) {
    is(sIdle) {
      when(io.backend.startRead) {
        addrReg := io.backend.addrIn
        isWrite := false.B
        counter := 0.U
        state   := sRd
      }.elsewhen(io.backend.startWrite) {
        addrReg := io.backend.addrIn
        dataReg := io.backend.dataIn
        lenReg  := io.backend.lenIn
        beatReg := 0.U
        isWrite := true.B
        counter := 0.U
        state   := sWr
      }
    }

    is(sRd) {
      rdAddrWire := addrReg(wordBits - 1, 0)
      counter    := counter + 1.U
      when(counter >= rdDelay.U) {
        readReg := rdData
        state   := sDone
      }
    }

    // The first word pays the write latency, the rest one per cycle.
    is(sWr) {
      counter := counter + 1.U
      when(counter >= wrDelay.U) {
        mem.write(addrReg(wordBits - 1, 0), dataReg)
        when(beatReg + 1.U < lenReg) {
          acceptW := true.B
          beatReg := beatReg + 1.U
          addrReg := addrReg + 1.U
          state   := sWrBeat
        }.otherwise {
          state := sDone
        }
      }
    }

    is(sWrBeat) {
      mem.write(addrReg(wordBits - 1, 0), io.backend.dataIn)
      when(beatReg + 1.U < lenReg) {
        acceptW := true.B
        beatReg := beatReg + 1.U
        addrReg := addrReg + 1.U
        state   := sWrBeat
      }.otherwise {
        state := sDone
      }
    }

    is(sDone) {
      state := sIdle
    }
  }
}
