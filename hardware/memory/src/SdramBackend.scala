// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Byte-serial bridge between MemoryController and SdramController.
//
// The MemBackendIO protocol is byte-serial: each read/write transaction
// transfers one byte at a time via dataReady/dataReq handshake signals.
// The SDRAM controller works with 16-bit words.  This bridge:
//
//   Read path:
//     1. Receives startRead + addrIn (byte address).
//     2. Issues a 16-bit word read to the SDRAM controller.
//     3. Presents the correct byte (low or high) first, then the other.
//     4. Asserts dataReady for each byte; waits for stop_txn.
//
//   Write path:
//     1. Receives startWrite + addrIn + dataIn (first byte).
//     2. Accumulates bytes (up to 2) into a 16-bit word.
//     3. Issues a 16-bit word write to the SDRAM controller.
//     4. Asserts dataReq after each byte is consumed.
//
// The arbiter always accesses memory in natural-aligned 1–4 byte
// transactions.  For simplicity this bridge does a fresh 16-bit SDRAM
// access for every byte pair.  The bank-open tracking inside
// SdramController amortises the ACTIVATE overhead for sequential accesses
// in the same row.

package memory

import chisel3._
import chisel3.util._

/** Physical SDRAM pin bundle — 16-bit wide bus, suitable for the
  * ISSI IS42S16160G-7TL on the ULX3S board.
  * Bidirectional DQ is split into separate in/out/oe signals.
  */
class SdramPinsIO extends Bundle {
  val cs_n   = Output(Bool())
  val ras_n  = Output(Bool())
  val cas_n  = Output(Bool())
  val we_n   = Output(Bool())
  val cke    = Output(Bool())
  val ba     = Output(UInt(2.W))   // bank select
  val addr   = Output(UInt(13.W)) // row/col address
  val dqm    = Output(UInt(2.W))  // byte mask (16-bit bus → 2 bytes)
  val dq_out = Output(UInt(16.W))
  val dq_oe  = Output(Bool())     // common output enable for dq bus
  val dq_in  = Input(UInt(16.W))
}

/** Combined IO for the SDRAM backend module. */
class SdramBackendIO extends Bundle {
  val backend   = Flipped(new MemBackendIO)
  val sdramPins = new SdramPinsIO
}

class SdramBackend extends Module {
  val io = IO(new SdramBackendIO)

  // ── SDRAM controller sub-module ──
  val sdram = Module(new SdramController)

  // ── Wire SDRAM physical pins ──
  // Outputs from controller → outside world
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
  // Input from outside world → controller
  sdram.io.pins.dq_in := io.sdramPins.dq_in

  // ── FSM state encoding ──
  val sIdle   = 0.U(4.W)
  val sRdReq  = 1.U(4.W)   // hold rd high for 2 cycles past register delay
  val sRdWait = 2.U(4.W)   // wait for SDRAM rdy (read CAS latency)
  val sReadB  = 3.U(4.W)   // present bytes from latched word
  val sWrReq  = 4.U(4.W)   // emit dataReq, wait for arbiter to put byte 1 on dataIn
  val sWrWord = 5.U(4.W)   // latch byte 1, issue SDRAM write
  val sWrWait = 6.U(4.W)   // wait for SDRAM write rdy
  val sAck    = 7.U(4.W)   // drain any in-flight SDRAM txn

  val state    = RegInit(sIdle)
  val rdDelCtr = RegInit(0.U(2.W))  // counts 2 cycles to step past register latency

  // Byte address of the current transaction (25-bit)
  val byteAddr    = RegInit(0.U(25.W))
  // Byte lane of transaction start: 0 = low byte first, 1 = high byte first
  val lane0       = RegInit(false.B)
  // Latched 16-bit word from a SDRAM read
  val readWord    = RegInit(0.U(16.W))
  // Which byte of readWord we are presenting next (0 or 1)
  val readByteIdx = RegInit(0.U(1.W))
  // Accumulated write word
  val writeWord   = RegInit(0.U(16.W))

  // Word address from byte address
  val wordAddr = byteAddr(24, 1)

  // ── SDRAM sys interface defaults ──
  sdram.io.sys.rd  := false.B
  sdram.io.sys.wr  := false.B
  sdram.io.sys.ack := false.B
  sdram.io.sys.ab  := Cat(0.U(0.W), wordAddr)
  sdram.io.sys.di  := writeWord

  // ── Backend → Arbiter defaults ──
  io.backend.dataOut   := 0.U
  io.backend.dataReq   := false.B
  io.backend.dataReady := false.B
  io.backend.busy      := (state =/= sIdle)

  switch(state) {
    is(sIdle) {
      when(io.backend.startRead) {
        byteAddr    := io.backend.addrIn
        lane0       := io.backend.addrIn(0).asBool
        readByteIdx := 0.U
        rdDelCtr    := 0.U
        state       := sRdReq
      }.elsewhen(io.backend.startWrite) {
        byteAddr := io.backend.addrIn
        lane0    := io.backend.addrIn(0).asBool
        // First byte into low or high lane
        writeWord := Mux(io.backend.addrIn(0),
          Cat(io.backend.dataIn, 0.U(8.W)),   // odd addr: first byte goes high
          Cat(0.U(8.W), io.backend.dataIn))   // even addr: first byte goes low
        state := sWrReq
      }
    }

    is(sRdReq) {
      // Hold rd high to step past the 1-cycle registered-input delay,
      // and give the FSM one extra idle cycle to de-assert the stale rdy.
      sdram.io.sys.rd := true.B
      sdram.io.sys.ab := Cat(0.U(0.W), wordAddr)
      rdDelCtr := rdDelCtr + 1.U
      when(rdDelCtr >= 1.U) {   // 2 cycles total: de-asserts stale rdy from IDLE
        state := sRdWait
      }
      when(io.backend.stopTxn) { state := sAck }
    }

    is(sRdWait) {
      // Keep rd high; wait for the SDRAM to assert rdy (data valid)
      sdram.io.sys.rd := true.B
      sdram.io.sys.ab := Cat(0.U(0.W), wordAddr)
      when(sdram.io.sys.rdy) {
        readWord := sdram.io.sys.do_     // latch the 16-bit word
        sdram.io.sys.ack := true.B      // release SDRAM
        state := sReadB
      }
      when(io.backend.stopTxn) { state := sAck }
    }

    is(sReadB) {
      // Present bytes from the latched word to the arbiter one at a time.
      // readByteIdx=0: first byte (lane0=0 → low, lane0=1 → high)
      // readByteIdx=1: second byte (the other lane)
      val sendLow = (readByteIdx === 0.U) === !lane0
      io.backend.dataReady := true.B
      io.backend.dataOut   := Mux(sendLow, readWord(7, 0), readWord(15, 8))

      when(io.backend.stopTxn) {
        state := sIdle
      }.otherwise {
        // Advance byte index; after 2 bytes fetch next word
        when(readByteIdx === 1.U) {
          byteAddr    := byteAddr + 2.U
          readByteIdx := 0.U
          rdDelCtr    := 0.U
          state       := sRdReq
        }.otherwise {
          readByteIdx := readByteIdx + 1.U
        }
      }
    }

    is(sWrReq) {
      // Emit dataReq to tell arbiter "byte 0 consumed, send byte 1"
      io.backend.dataReq := true.B
      when(io.backend.stopTxn) {
        state := sWrWord  // flush with whatever byte we have
      }.otherwise {
        state := sWrWord  // next cycle: latch dataIn as byte 1
      }
    }

    is(sWrWord) {
      // Latch the second byte from dataIn (which arbiter put there after dataReq)
      val secondByte = io.backend.dataIn
      writeWord := Mux(!lane0,
        Cat(secondByte, writeWord(7, 0)),    // even: byte 1 = high lane
        Cat(writeWord(15, 8), secondByte))   // odd:  byte 1 = low lane
      // Kick off the SDRAM write
      sdram.io.sys.wr := true.B
      sdram.io.sys.ab := Cat(0.U(0.W), wordAddr)
      state := sWrWait
    }

    is(sWrWait) {
      // Hold wr high; wait for SDRAM rdy
      sdram.io.sys.wr := true.B
      sdram.io.sys.ab := Cat(0.U(0.W), wordAddr)
      sdram.io.sys.di := writeWord
      when(sdram.io.sys.rdy) {
        sdram.io.sys.ack := true.B
        io.backend.dataReq := true.B   // tells arbiter write is done
        state := sIdle
      }
      when(io.backend.stopTxn) { state := sAck }
    }

    is(sAck) {
      // Drain any in-flight SDRAM transaction by asserting ack
      sdram.io.sys.ack := true.B
      state := sIdle
    }
  }
}

