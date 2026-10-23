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

/** ISA v2 CSR write (CSRRW): the lane's rs1 at write-back, gated by the execution mask. */
class CsrWriteIO(val totalBits: Int) extends Bundle {
  val en   = Output(Bool())
  val addr = Output(UInt(12.W))
  val data = Output(UInt(totalBits.W))
}

/** CSR numbers (docs/B8_isa_v2.md). */
object Csr {
  val OutC0 = 0x800; val OutC1 = 0x801; val OutC2 = 0x802; val OutC3 = 0x803
  val OutDepth = 0x804; val Discard = 0x805; val SampleMask = 0x806
}
