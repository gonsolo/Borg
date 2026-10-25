// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** ISA v2 CSR write (CSRRW): the lane's rs1 at write-back, gated by the execution mask. */
class CsrWriteIO(val totalBits: Int) extends Bundle {
  val en   = Output(Bool())
  val addr = Output(UInt(12.W))
  val data = Output(UInt(totalBits.W))
}
