// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** 16-bit-word memory interface (arbiter's perspective).
  *
  * Sits between [[MemoryController]] (the arbiter) and the physical memory
  * backend ([[SdramBackend]] or [[QspiBackend]]). Each transaction transfers
  * a single 16-bit halfword. Multi-halfword CPU accesses (a 32-bit `lw`/`sw`)
  * are issued by the arbiter as TWO back-to-back transactions.
  *
  * Handshake:
  *   - Arbiter waits for `busy === false`.
  *   - Arbiter pulses `startRead` or `startWrite` for 1 cycle with addrIn/
  *     dataIn/byteEnIn valid.
  *   - Backend goes `busy === true` and executes the transaction.
  *   - Backend pulses `done` for 1 cycle. On a read, `dataOut` is valid
  *     during that pulse.
  *   - Backend returns to `busy === false`; arbiter may issue the next.
  *
  * Directions are from the arbiter's point of view.
  */
class MemBackendIO extends Bundle {
  // Arbiter → Backend
  val addrIn     = Output(UInt(24.W))  // 16-bit-word address (drops byte LSB)
  val dataIn     = Output(UInt(16.W))  // halfword to write
  val byteEnIn   = Output(UInt(2.W))   // byte mask for writes: bit i = update lane i
  val startRead  = Output(Bool())      // 1-cycle pulse — begin read
  val startWrite = Output(Bool())      // 1-cycle pulse — begin write

  // Backend → Arbiter
  val dataOut    = Input(UInt(16.W))   // read halfword; valid when done && was read
  val done       = Input(Bool())       // 1-cycle pulse — transaction complete
  val busy       = Input(Bool())       // 1 while transaction in flight
}
