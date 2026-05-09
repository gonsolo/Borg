// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

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

/** SDRAM memory backend stub.
  *
  * Implements [[MemBackendIO]] so it can be dropped in place of
  * [[QspiBackend]] on ULX3S without changing [[MemoryController]].
  *
  * TODO (Phase 3): implement real SDRAM controller using sdram_pnru
  * or equivalent, driven by mem.io.backend.
  *
  * Current state: all arbiter inputs held idle; SDRAM pins deasserted.
  */
class SdramBackendIO extends Bundle {
  val backend    = Flipped(new MemBackendIO)
  val sdramPins  = new SdramPinsIO
}

class SdramBackend extends Module {
  val io = IO(new SdramBackendIO)

  // Stub: tell the arbiter we are never busy and never have data
  io.backend.dataOut   := 0.U
  io.backend.dataReq   := false.B
  io.backend.dataReady := false.B
  io.backend.busy      := false.B

  // Deassert all SDRAM control signals
  io.sdramPins.cs_n   := true.B   // chip select inactive
  io.sdramPins.ras_n  := true.B
  io.sdramPins.cas_n  := true.B
  io.sdramPins.we_n   := true.B
  io.sdramPins.cke    := false.B
  io.sdramPins.ba     := 0.U
  io.sdramPins.addr   := 0.U
  io.sdramPins.dqm    := 3.U     // both byte masks active (no output)
  io.sdramPins.dq_out := 0.U
  io.sdramPins.dq_oe  := false.B
}
