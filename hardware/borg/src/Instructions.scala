// SPDX-FileCopyrightText: © 2026
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.UInt

/** Field accessors and an assembler for tests and ROMs. The encodings themselves are
  * hardware/borg/isa/opcodes, generated into [[Opcodes]].
  */
object Instructions {
  import Opcodes._

  case class BitField(hi: Int, lo: Int) {
    def apply(u: UInt): UInt = u(hi, lo)
  }
  private def field(f: (Int, Int)) = BitField(f._1, f._2)

  val BF_RS1    = field(FIELD_RS1)
  val BF_RS2    = field(FIELD_RS2)
  val BF_RS3    = field(FIELD_RS3)
  val BF_RD     = field(FIELD_RD)

  /** A 10-bit SOUT/FATTR component index split over two register fields. */
  def indexFields(index: Int): (Int, Int) = {
    require(index >= 0 && index < 1024, s"component index out of range: $index")
    ((index >> 5) & 0x1f, index & 0x1f)
  }

  // @doc:isa-encoders
  // Pseudo-instructions and checked encoders; plain instructions are Opcodes' (rd, rs1, rs2).
  def FNEG(rs1: Int, rd: Int): BigInt = FSGNJN_S(rd, rs1, rs1)
  def FMOV(rs1: Int, rd: Int): BigInt = FSGNJ_S(rd, rs1, rs1)
  /** fine = true: DPdxFine/DPdyFine (each lane's own row/column); else coarse. */
  def DDX(rs1: Int, rd: Int, fine: Boolean = false): BigInt = DDX_V2(rd, rs1, if (fine) 1 else 0)
  def DDY(rs1: Int, rd: Int, fine: Boolean = false): BigInt = DDY_V2(rd, rs1, if (fine) 1 else 0)
  /** BRZ rs1, offset: if rs1 == 0, jump by offset words from this instruction (-512..511). */
  def BRZ(rs1: Int, offset: Int): BigInt = BEQ(branch(rs1, offset), rs1, 0)
  def BRNZ(rs1: Int, offset: Int): BigInt = BNE(branch(rs1, offset), rs1, 0)
  private def branch(rs1: Int, offset: Int): Int = {
    require(rs1 != 0 && offset >= -512 && offset < 512, s"branch on x$rs1 by $offset words")
    4 * offset
  }
  def SMASK(rd: Int): BigInt = CSRRS(rd, 0, Csr.SampleMask)
  def ATTIDX(rd: Int): BigInt = CSRRS(rd, 0, Csr.Attachment)
  def TLD(rd: Int, word: Int = 0): BigInt = { require(word < 2, s"TLD word $word"); TLD_V2(rd, word) }
  def JMP(offset: Int): BigInt = {
    require(offset >= -(1 << 17) && offset < (1 << 17), s"JMP offset out of range: $offset")
    JAL(0, 4 * offset)
  }
  /** SOUT: store rs2 as output component `index` (packed into rs1:rd). */
  def SOUT(rs2: Int, index: Int): BigInt = { indexFields(index); SOUT_V2(rs2, index) }
  /** FATTR: rd..rd+2 = component `index`'s per-vertex values (packed into rs2:rs1). */
  def FATTR(rd: Int, index: Int): BigInt = {
    require(rd <= 29, s"FATTR writes rd..rd+2, so rd <= 29: $rd")
    indexFields(index)
    FATTR_V2(rd, index)
  }
  // @doc:end

  /** Operand shape of an instruction, which decides its C macro signature. */
}
