// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** Physical QSPI pin bundle — used by [[QspiBackend]] to connect
  * [[QspiController]] to the platform-specific pad mux.
  * dataIn is the only Input; all other signals drive the physical bus.
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
