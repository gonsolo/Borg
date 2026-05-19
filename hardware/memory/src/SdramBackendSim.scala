// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Behavioral SDRAM — drop-in replacement for SdramBackend backed by SyncReadMem.
// Implements the new 16-bit-word MemBackendIO protocol (one halfword per
// transaction) for use in cocotb/test simulations.

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

/** Behavioral SDRAM model.
  *
  * @param words   Number of 16-bit words backing the memory (default 4096 = 8 KB).
  * @param rdDelay Cycles between startRead and done (simulates CAS latency).
  * @param wrDelay Cycles between startWrite and done (simulates write latency).
  */
class SdramBackendSim(
  words:   Int = 4096,
  rdDelay: Int = 4,
  wrDelay: Int = 2
) extends Module {

  val io = IO(new SdramBackendSimIO)

  val mem      = SyncReadMem(words, UInt(16.W))
  val wordBits = log2Up(words)

  val sIdle :: sRd :: sWr :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val isWrite = RegInit(false.B)
  val addrReg = RegInit(0.U(24.W))
  val dataReg = RegInit(0.U(16.W))
  val readReg = RegInit(0.U(16.W))
  val counter = RegInit(0.U(8.W))

  // Synchronous read port — issue address now, data available next cycle.
  val rdAddrWire = WireDefault(0.U(wordBits.W))
  val rdData     = mem.read(rdAddrWire)

  io.backend.dataOut := readReg
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

    is(sWr) {
      counter := counter + 1.U
      when(counter >= wrDelay.U) {
        mem.write(addrReg(wordBits - 1, 0), dataReg)
        state := sDone
      }
    }

    is(sDone) {
      state := sIdle
    }
  }
}
