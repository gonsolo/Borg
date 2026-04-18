// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** Physical QSPI pin bundle (from MemoryController's perspective).
  * dataIn is the only Input; all outputs drive the physical bus.
  */
class QspiPinsIO extends Bundle {
  val dataIn      = Input(UInt(4.W))
  val dataOut     = Output(UInt(4.W))
  val dataOe      = Output(UInt(4.W))
  val clkOut      = Output(Bool())
  val flashSelect = Output(Bool())
  val ramASelect  = Output(Bool())
  val ramBSelect  = Output(Bool())
}
