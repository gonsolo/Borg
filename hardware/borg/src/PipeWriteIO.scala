// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Pipeline write-back snoop.
  * Defined from BorgCore's perspective (Output = core drives it).
  * Use Flipped() in BorgRasterizerIO.
  */
class PipeWriteIO(val totalBits: Int) extends Bundle {
  val en   = Output(Bool())
  val addr = Output(UInt(log2Ceil(32).W))
  val data = Output(UInt(totalBits.W))
}
