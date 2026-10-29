// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

/** Bridges the 16-bit-word [[MemBackendIO]] to the byte-serial
  * [[QspiController]], two bytes per halfword. */
class QspiBackendIO extends Bundle {
  val backend  = Flipped(new MemBackendIO)
  val qspiPins = new QspiPinsIO
}

class QspiBackend extends Module {
  val io = IO(new QspiBackendIO)

  val q = Module(new QspiController())

  // sRdReq/sWrReq hold start until the controller goes busy: it refuses a
  // start in the PSRAM CS-high gap (ram_a_block), which dropped one-cycle pulses.
  val sIdle :: sRdReq :: sRdB0 :: sRdB1 :: sRdDone :: sWrReq :: sWrB0 :: sWrB1 :: sWrDone :: Nil = Enum(9)
  val state = RegInit(sIdle)

  val byteLo = RegInit(0.U(8.W))
  val byteHi = RegInit(0.U(8.W))
  val dataReg = RegInit(0.U(16.W))
  // Combinational: QspiCtrl reads addr_in only in the start cycle.
  val byteAddr = Wire(UInt(25.W))
  byteAddr := Cat(io.backend.addrIn, 0.U(1.W))

  q.io.addr_in     := byteAddr
  // QspiCtrl samples data_in in the data_req cycle, before state reaches sWrB1.
  q.io.data_in     := Mux(state === sWrB0 && !q.io.data_req, dataReg(7, 0), dataReg(15, 8))
  q.io.start_read  := (state === sIdle && io.backend.startRead) || (state === sRdReq)
  q.io.start_write := (state === sIdle && io.backend.startWrite) || (state === sWrReq)
  q.io.stall_txn   := false.B
  q.io.stop_txn    := (state === sRdDone) || (state === sWrDone)

  io.qspiPins.dataOut     := q.io.spi_data_out
  io.qspiPins.dataOe      := q.io.spi_data_oe
  io.qspiPins.clkOut      := q.io.spi_clk_out
  io.qspiPins.flashSelect := q.io.spi_flash_select
  io.qspiPins.ramASelect  := q.io.spi_ram_a_select
  io.qspiPins.ramBSelect  := q.io.spi_ram_b_select
  q.io.spi_data_in := io.qspiPins.dataIn

  io.backend.dataOut := Cat(byteHi, byteLo)
  io.backend.accept  := false.B   // no burst streaming
  io.backend.done    := (state === sRdDone) || (state === sWrDone)
  io.backend.busy    := (state =/= sIdle)

  switch(state) {
    is(sIdle) {
      when(io.backend.startRead) {
        state := sRdReq
      }.elsewhen(io.backend.startWrite) {
        dataReg := io.backend.dataIn
        state   := sWrReq
      }
    }

    is(sRdReq) {
      when(q.io.busy) { state := sRdB0 }
    }
    is(sWrReq) {
      when(q.io.busy) { state := sWrB0 }
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
