// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** Halfword interface from [[MemoryController]] (directions as seen there) to a memory backend.
  * When !busy, a one-cycle startRead/startWrite starts a transfer; `done` pulses at the end,
  * with read data on dataOut. A burst write (lenIn > 1) starts with the first word; after each
  * `accept` the next word must be on dataIn in the following cycle. Reads are single.
  */
class MemBackendIO extends Bundle {
  val addrIn     = Output(UInt(24.W))  // 16-bit-word base address (drops byte LSB)
  val dataIn     = Output(UInt(16.W))  // halfword to write (current burst beat)
  val byteEnIn   = Output(UInt(2.W))   // byte mask for writes: bit i = update lane i
  val lenIn      = Output(UInt(7.W))   // burst word count (1..64); 1 = single transaction
  val startRead  = Output(Bool())      // 1-cycle pulse — begin read
  val startWrite = Output(Bool())      // 1-cycle pulse — begin (burst) write

  val dataOut    = Input(UInt(16.W))   // read halfword; valid when done && was read
  val accept     = Input(Bool())       // burst write: consumed dataIn, present next word
  val done       = Input(Bool())       // 1-cycle pulse — transaction (or burst) complete
  val busy       = Input(Bool())       // 1 while transaction in flight
}
