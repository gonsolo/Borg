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
  * Handshake (single transaction, lenIn === 1):
  *   - Arbiter waits for `busy === false`.
  *   - Arbiter pulses `startRead` or `startWrite` for 1 cycle with addrIn/
  *     dataIn/byteEnIn valid.
  *   - Backend goes `busy === true` and executes the transaction.
  *   - Backend pulses `done` for 1 cycle. On a read, `dataOut` is valid
  *     during that pulse.
  *   - Backend returns to `busy === false`; arbiter may issue the next.
  *
  * Burst write (lenIn > 1): the arbiter pulses `startWrite` once with the base
  * `addrIn`, the word count `lenIn`, and the FIRST word on `dataIn`.  The backend
  * writes consecutive words `addrIn, addrIn+1, …` (auto-incrementing internally).
  * After consuming each word it pulses `accept`; the arbiter must present the
  * NEXT word on `dataIn` on the following cycle.  After the last word the backend
  * pulses `done`.  (Reads stay single-transaction for now; burst reads are future
  * work for the line-buffered scanout.)
  *
  * Directions are from the arbiter's point of view.
  */
class MemBackendIO extends Bundle {
  // Arbiter → Backend
  val addrIn     = Output(UInt(24.W))  // 16-bit-word base address (drops byte LSB)
  val dataIn     = Output(UInt(16.W))  // halfword to write (current burst beat)
  val byteEnIn   = Output(UInt(2.W))   // byte mask for writes: bit i = update lane i
  val lenIn      = Output(UInt(7.W))   // burst word count (1..64); 1 = single transaction
  val startRead  = Output(Bool())      // 1-cycle pulse — begin read
  val startWrite = Output(Bool())      // 1-cycle pulse — begin (burst) write

  // Backend → Arbiter
  val dataOut    = Input(UInt(16.W))   // read halfword; valid when done && was read
  val accept     = Input(Bool())       // burst write: consumed dataIn, present next word
  val done       = Input(Bool())       // 1-cycle pulse — transaction (or burst) complete
  val busy       = Input(Bool())       // 1 while transaction in flight
}
