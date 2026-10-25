// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

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
  val OutPos0 = 0x808  // gl_Position x..w at 0x808..0x80B
  val SetupScreen0 = 0x810; val SetupDet = 0x816  // the setup ROM's results for the draw walker
  // The raster ROMs' results for the dispatcher: E0..E2, Zn, Zf, FragCoord.z, the sample depths.
  val RastE0 = 0x818; val RastZn = 0x81B; val RastZf = 0x81C; val RastFragZ = 0x81D; val RastSampleZ0 = 0x820
  val Root = 0xCC0
  val Uniform0 = 0x840  // the per-draw and per-triangle words, uniform k at 0x840 + k (k < 32)
  // System values (B8 step 7), read-only.
  val FragX = 0xCC1; val FragY = 0xCC2; val FragZ = 0xCC3; val InvW = 0xCC4
  val Bary0 = 0xCC5; val Bary1 = 0xCC6; val Bary2 = 0xCC7
  val VertexIndex = 0xCC8; val InstanceIndex = 0xCC9; val LocalIndex = 0xCCA; val LocalId = 0xCCB
  val GroupIdX = 0xCCC; val GroupIdY = 0xCCD; val GroupIdZ = 0xCCE; val Attachment = 0xCCF
}
