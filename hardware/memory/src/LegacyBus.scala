// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

// MemoryController's internal bus bundles; its IO wraps them as HuttBus and HuttInstrBus.

/** Halfword-streaming instruction fetch from a 23-bit halfword address: `instr_fetch_restart`
  * starts a stream, `instr_ready` pulses per halfword, `instr_fetch_stall` pauses it.
  */
class InstrFetchIO extends Bundle {
  val instr_addr          = Output(UInt(23.W))
  val instr_fetch_restart = Output(Bool())
  val instr_fetch_stall   = Output(Bool())
  val instr_fetch_started = Input(Bool())
  val instr_fetch_stopped = Input(Bool())
  val instr_data          = Input(UInt(16.W))
  val instr_ready         = Input(Bool())
}

/** CPU data bus. `writeN`/`readN`: 3 idle, 2 word, 1 halfword, 0 byte. The Hutt adapter ties
  * `dataContinue` (a back-to-back burst) to 0.
  */
class MemBusIO extends Bundle {
  val addr         = Output(UInt(25.W))
  val writeN       = Output(UInt(2.W))
  val readN        = Output(UInt(2.W))
  val dataOut      = Output(UInt(32.W))
  val dataContinue = Output(Bool())
  val ready        = Input(Bool())
  val dataIn       = Input(UInt(32.W))
}
