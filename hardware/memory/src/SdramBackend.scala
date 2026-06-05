// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// 16-bit-word bridge between MemoryController (MemBackendIO) and SdramController.
//
// One halfword per transaction. The arbiter issues two back-to-back transactions
// for 32-bit CPU accesses; byte buffering moved to MemoryController.
//
// FSM (5 states):
//   sIdle  — wait for startRead/startWrite.
//   sHold  — assert rd/wr to SdramController. Wait for sysRdy rising edge
//            (sysRdy stays high between transactions, so we MUST detect the
//            0→1 edge, not the level — otherwise we'd accept the previous
//            transaction's stale sysDo).
//   sAck   — assert ack so SdramController returns to sIDLE.
//   sDone  — pulse done; reader observes dataOut here.
//   → sIdle.
//
// CRITICAL TIMING: the SDRAM clock is 90° behind the SOC clock (10 ns offset
// at 25 MHz). DQ becomes valid at SOC cycle X+4 after a READ at cycle X, not
// X+3 as a naive CL=2 calculation suggests. The fix is in SdramController:
// `dly := CL.U` (was `CL-1`). With that fix, sysDo captures valid DQ in
// sRWRDY, and our rdyRising-driven capture reads the correct value.

package memory

import chisel3._
import chisel3.util._

/** Physical SDRAM pin bundle — 16-bit wide bus (IS42S16160G). */
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

class SdramBackend(clockMhz: Int = 125) extends Module {
  val io = IO(new SdramBackendIO)

  val sdram = Module(new SdramController(clockMhz))

  // ── Physical pin wiring ──
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

  // ── FSM ──
  // Burst write streams `lenReg` words through the (unchanged) single-word
  // SdramController: each word is one open-row write; between words we ack the
  // controller, pull the next word (accept), and re-issue.  This keeps the
  // MemoryController in its burst (no per-word re-arbitration) and is correct
  // on real hardware with zero change to the SDRAM pin timing.  A faster
  // back-to-back controller (tWR only at burst end) is a later step.
  val sIdle :: sHold :: sAck :: sLoad :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val isWrite     = RegInit(false.B)
  val addrReg     = RegInit(0.U(24.W))
  val dataReg     = RegInit(0.U(16.W))
  val readDataReg = RegInit(0.U(16.W))
  val lenReg      = RegInit(1.U(7.W))   // burst word count latched at startWrite
  val beatReg     = RegInit(0.U(7.W))   // words completed so far

  // Rising-edge detector on sdram.io.sys.rdy.
  val rdyPrev   = RegNext(sdram.io.sys.rdy, false.B)
  val rdyRising = sdram.io.sys.rdy && !rdyPrev

  // SDRAM sys interface
  sdram.io.sys.rd  := (state === sHold) && !isWrite
  sdram.io.sys.wr  := (state === sHold) &&  isWrite
  sdram.io.sys.ack := state === sAck
  sdram.io.sys.ab  := addrReg
  sdram.io.sys.di  := dataReg

  val acceptW = WireDefault(false.B)   // burst-beat pull: present next word next cycle

  // Backend → Arbiter
  io.backend.dataOut := readDataReg
  io.backend.accept  := acceptW
  io.backend.done    := state === sDone
  io.backend.busy    := state =/= sIdle

  // Debug
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
      }.elsewhen(io.backend.startWrite) {
        addrReg := io.backend.addrIn
        dataReg := io.backend.dataIn
        isWrite := true.B
        lenReg  := io.backend.lenIn
        beatReg := 0.U
        state   := sHold
      }
    }

    is(sHold) {
      when(rdyRising) {
        when(!isWrite) {
          readDataReg := sdram.io.sys.do_
        }
        state := sAck
      }
    }

    is(sAck) {
      // Controller is acked here (returns to sIDLE).  If this is a burst write
      // with more words to go, pull the next word and re-issue; else finish.
      when(isWrite && (beatReg + 1.U) < lenReg) {
        acceptW := true.B          // arbiter presents the next word next cycle
        addrReg := addrReg + 1.U
        beatReg := beatReg + 1.U
        state   := sLoad
      }.otherwise {
        state := sDone
      }
    }

    is(sLoad) {
      // The arbiter advanced its word register on accept; latch the next word.
      dataReg := io.backend.dataIn
      state   := sHold
    }

    is(sDone) {
      state := sIdle
    }
  }
}
