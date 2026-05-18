// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

/** Internal legacy bus bundles, formerly imported from `tinyqv.cpu`.
  *
  * These match the byte/halfword-streaming protocol the [[MemoryController]]
  * FSM was designed around.  They are no longer exposed at the SoC boundary —
  * the [[MemoryController]] now wraps them with [[hutt.HuttBus]] and
  * [[hutt.HuttInstrBus]] adapters at its IO surface.
  *
  * Kept in the `memory` package so the controller's FSM body can remain
  * unchanged while we delete the `hardware/tinyqv/` module.
  */

/** Instruction fetch — 16-bit halfword streaming.
  *
  * Protocol:
  *  - CPU drives `instr_addr` (23-bit halfword address) and pulses
  *    `instr_fetch_restart` to begin a new stream.  MemoryController pulses
  *    `instr_fetch_started` when the backend transaction begins.
  *  - Halfwords arrive on `instr_data` with `instr_ready` pulses.
  *  - CPU may assert `instr_fetch_stall` to pause the stream.
  *  - MemoryController pulses `instr_fetch_stopped` when the stream ends.
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

/** CPU data bus — byte/halfword/word access with TinyQV-style level signaling.
  *
  * Encoding for `writeN`/`readN`:
  *   - `11` (3) — idle, no transaction
  *   - `10` (2) — word     (4 bytes)
  *   - `01` (1) — halfword (2 bytes)
  *   - `00` (0) — byte
  *
  * `dataContinue=1` extends the current transaction into a back-to-back burst
  * (TinyQV used this for unaligned access spanning two words).  Hutt doesn't
  * use bursts; the adapter ties it to 0.
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
