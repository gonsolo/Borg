// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

/** IS42S16160G pins, 16-bit bus. */
class SdramPinsIO extends Bundle {
  val cs_n   = Output(Bool())
  val ras_n  = Output(Bool())
  val cas_n  = Output(Bool())
  val we_n   = Output(Bool())
  val cke    = Output(Bool())
  val ba     = Output(UInt(2.W))
  val addr   = Output(UInt(13.W))
  val dqm    = Output(UInt(2.W))
  val dq_out = Output(UInt(16.W))
  val dq_oe  = Output(Bool())
  val dq_in  = Input(UInt(16.W))
}

class SdramBackendIO extends Bundle {
  val backend          = Flipped(new MemBackendIO)
  val sdramPins        = new SdramPinsIO
  val debug_be_state   = Output(UInt(3.W))
  val debug_ctrl_state = Output(UInt(3.W))
  val debug_ctrl_rdy   = Output(Bool())
  val debug_readWord   = Output(UInt(16.W))
}

/** MemBackendIO to SdramController bridge, one halfword per transaction. A
  * burst write streams its words through the single-word controller. */
class SdramBackend(clockMhz: Int = 125) extends Module {
  val io = IO(new SdramBackendIO)

  val sdram = Module(new SdramController(clockMhz))

  io.sdramPins.cs_n   := sdram.io.pins.cs_n
  io.sdramPins.ras_n  := sdram.io.pins.ras_n
  io.sdramPins.cas_n  := sdram.io.pins.cas_n
  io.sdramPins.we_n   := sdram.io.pins.we_n
  io.sdramPins.cke    := sdram.io.pins.cke
  io.sdramPins.ba     := sdram.io.pins.ba
  io.sdramPins.addr   := sdram.io.pins.addr
  io.sdramPins.dqm    := sdram.io.pins.dqm
  io.sdramPins.dq_out := sdram.io.pins.dq_out
  io.sdramPins.dq_oe  := sdram.io.pins.dq_oe
  sdram.io.pins.dq_in := io.sdramPins.dq_in

  val sIdle :: sHold :: sAck :: sLoad :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val isWrite     = RegInit(false.B)
  val addrReg     = RegInit(0.U(24.W))
  val dataReg     = RegInit(0.U(16.W))
  val readDataReg = RegInit(0.U(16.W))
  val lenReg      = RegInit(1.U(7.W))
  val beatReg     = RegInit(0.U(7.W))   // words completed so far

  // rdy stays high between transactions: only its rising edge completes one.
  val rdyPrev   = RegNext(sdram.io.sys.rdy, false.B)
  val rdyRising = sdram.io.sys.rdy && !rdyPrev

  // A refresh can drop and raise rdy before the controller registers our
  // request; only sRDWR proves it is serving ours.
  val seenRdwr = RegInit(false.B)

  sdram.io.sys.rd  := (state === sHold) && !isWrite
  sdram.io.sys.wr  := (state === sHold) &&  isWrite
  sdram.io.sys.ack := state === sAck
  sdram.io.sys.ab  := addrReg
  sdram.io.sys.di  := dataReg

  val acceptW = WireDefault(false.B)   // the arbiter presents the next word next cycle

  io.backend.dataOut := readDataReg
  io.backend.accept  := acceptW
  io.backend.done    := state === sDone
  io.backend.busy    := state =/= sIdle

  io.debug_be_state   := state
  io.debug_ctrl_state := sdram.io.sys.debug_state
  io.debug_ctrl_rdy   := sdram.io.sys.rdy
  io.debug_readWord   := readDataReg

  switch(state) {
    is(sIdle) {
      when(io.backend.startRead) {
        addrReg := io.backend.addrIn
        isWrite := false.B
        lenReg  := 1.U
        beatReg := 0.U
        state   := sHold
        seenRdwr := false.B
      }.elsewhen(io.backend.startWrite) {
        addrReg := io.backend.addrIn
        dataReg := io.backend.dataIn
        isWrite := true.B
        lenReg  := io.backend.lenIn
        beatReg := 0.U
        state   := sHold
        seenRdwr := false.B
      }
    }

    is(sHold) {
      when(sdram.io.sys.debug_state === 4.U) { seenRdwr := true.B }  // sRDWR
      when(rdyRising && seenRdwr) {
        when(!isWrite) {
          readDataReg := sdram.io.sys.do_
        }
        state := sAck
      }
    }

    is(sAck) {
      when(isWrite && (beatReg + 1.U) < lenReg) {
        acceptW := true.B
        addrReg := addrReg + 1.U
        beatReg := beatReg + 1.U
        state   := sLoad
      }.otherwise {
        state := sDone
      }
    }

    is(sLoad) {
      dataReg := io.backend.dataIn
      state   := sHold
    }

    is(sDone) {
      state := sIdle
    }
  }
}
