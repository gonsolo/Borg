// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

/** QSPI memory backend.
  *
  * Bridges the 16-bit-word [[MemBackendIO]] to physical QSPI pins via
  * [[QspiController]]. The QspiController's internal protocol is byte-serial,
  * so this wrapper buffers 2 bytes per halfword transaction.
  *
  * NOTE: this is a quick rewrite to match the new MemBackendIO. The pico-ice /
  * ASIC build paths are not currently exercised by the ULX3S debugging
  * session, so deeper testing is deferred.
  */
class QspiBackendIO extends Bundle {
  val backend  = Flipped(new MemBackendIO)
  val qspiPins = new QspiPinsIO
}

class QspiBackend extends Module {
  val io = IO(new QspiBackendIO)

  val q = Module(new QspiController())

  // ── FSM ────────────────────────────────────────────────────────────────────
  // sIdle  — wait for start pulse from arbiter.
  // sRdB0  — QSPI read in progress; wait for first byte (data_ready).
  // sRdB1  — wait for second byte; assemble halfword.
  // sRdDone — pulse done (one cycle).
  // sWrB0  — drive byte 0 to QSPI; wait for data_req then drive byte 1.
  // sWrB1  — wait for QSPI write complete.
  // sWrDone — pulse done.
  val sIdle :: sRdB0 :: sRdB1 :: sRdDone :: sWrB0 :: sWrB1 :: sWrDone :: Nil = Enum(7)
  val state = RegInit(sIdle)

  val byteLo = RegInit(0.U(8.W))
  val byteHi = RegInit(0.U(8.W))
  val dataReg = RegInit(0.U(16.W))
  // Combinational: QspiCtrl only reads addr_in once (at IDLE→CMD), so the
  // address must be valid at the cycle start_read/start_write is asserted,
  // not one cycle later (which a register would cause).
  val byteAddr = Wire(UInt(25.W))
  byteAddr := Cat(io.backend.addrIn, 0.U(1.W))

  // QspiController inputs
  q.io.addr_in     := byteAddr
  // Switch to high byte immediately when data_req fires: QspiCtrl samples
  // data_in on the same cycle data_req is asserted, but the state register
  // only transitions to sWrB1 after that clock edge.
  q.io.data_in     := Mux(state === sWrB0 && !q.io.data_req, dataReg(7, 0), dataReg(15, 8))
  q.io.start_read  := (state === sIdle && io.backend.startRead)
  q.io.start_write := (state === sIdle && io.backend.startWrite)
  q.io.stall_txn   := false.B
  q.io.stop_txn    := (state === sRdDone) || (state === sWrDone)

  // Pin wiring
  io.qspiPins.dataOut     := q.io.spi_data_out
  io.qspiPins.dataOe      := q.io.spi_data_oe
  io.qspiPins.clkOut      := q.io.spi_clk_out
  io.qspiPins.flashSelect := q.io.spi_flash_select
  io.qspiPins.ramASelect  := q.io.spi_ram_a_select
  io.qspiPins.ramBSelect  := q.io.spi_ram_b_select
  q.io.spi_data_in := io.qspiPins.dataIn

  // Backend outputs
  io.backend.dataOut := Cat(byteHi, byteLo)
  io.backend.accept  := false.B   // single-transaction backend (no burst streaming yet)
  io.backend.done    := (state === sRdDone) || (state === sWrDone)
  io.backend.busy    := (state =/= sIdle)

  switch(state) {
    is(sIdle) {
      when(io.backend.startRead) {
        state := sRdB0
      }.elsewhen(io.backend.startWrite) {
        dataReg := io.backend.dataIn
        state   := sWrB0
      }
    }

    is(sRdB0) {
      when(q.io.data_ready) {
        byteLo := q.io.data_out
        state  := sRdB1
      }
    }
    is(sRdB1) {
      when(q.io.data_ready) {
        byteHi := q.io.data_out
        state  := sRdDone
      }
    }
    is(sRdDone) { state := sIdle }

    is(sWrB0) {
      when(q.io.data_req) { state := sWrB1 }
    }
    is(sWrB1) {
      when(q.io.data_req) { state := sWrDone }
    }
    is(sWrDone) { state := sIdle }
  }
}
