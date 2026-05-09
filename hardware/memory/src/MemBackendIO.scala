// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** Backend-agnostic byte-serial memory interface (arbiter's perspective).
  *
  * Sits between [[MemoryController]] (the arbiter) and whichever physical
  * memory backend is chosen per target: [[QspiBackend]] or [[SdramBackend]].
  *
  * Directions are from the arbiter's point of view:
  *   - Outputs: arbiter → backend  (commands / data to write)
  *   - Inputs:  backend → arbiter  (responses / data read back)
  */
class MemBackendIO extends Bundle {
  // Arbiter → Backend
  val addrIn     = Output(UInt(25.W))  // byte address for the transaction
  val dataIn     = Output(UInt(8.W))   // current write byte
  val startRead  = Output(Bool())      // begin a read transaction
  val startWrite = Output(Bool())      // begin a write transaction
  val stallTxn   = Output(Bool())      // pause the in-flight transaction
  val stopTxn    = Output(Bool())      // abort the in-flight transaction

  // Backend → Arbiter
  val dataOut    = Input(UInt(8.W))    // read byte from backend
  val dataReq    = Input(Bool())       // backend consumed current byte, wants next
  val dataReady  = Input(Bool())       // backend has a valid read byte on dataOut
  val busy       = Input(Bool())       // backend has a transaction in flight
}
