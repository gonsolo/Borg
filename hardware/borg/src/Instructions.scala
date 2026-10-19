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

  // @doc:isa-bitfields
  val BF_RS1    = field(FIELD_RS1)
  val BF_RS2    = field(FIELD_RS2)
  val BF_RS3    = field(FIELD_RS3)
  val BF_RD     = field(FIELD_RD)
  val BF_OP     = BitField(6, 0)
  val BF_FUNCT3 = field(FIELD_F3)
  val BF_FUNCT7 = BitField(31, 25)
  val BF_F7_OP  = BF_FUNCT7
  val BITS_OPCODE_FMA_BIT = 2

  val OPCODE_ALU = OPCODE_FADD
  val OPCODE_FMA = OPCODE_FMADD

  /** A 10-bit SOUT/FATTR component index split over two register fields. */
  def indexFields(index: Int): (Int, Int) = {
    require(index >= 0 && index < 1024, s"component index out of range: $index")
    ((index >> 5) & 0x1f, index & 0x1f)
  }

  // --- Base Instruction Encoders ---
  // Long, not Int: funct7 values >= 0x40, and R4-type rs3 >= 16, set bit 31,
  // which overflows an Int shift into a negative literal.
  def encodeRType(funct7: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_ALU): BigInt =
    BigInt((funct7.toLong << BF_FUNCT7.lo) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  def encodeR4Type(rs3: Int, funct2: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_FMA): BigInt =
    BigInt((rs3.toLong << BF_RS3.lo) | (funct2 << 25) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  // @doc:isa-encoders
  def ADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FADD, rs2, rs1, rd, funct3)
  def MUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FMUL, rs2, rs1, rd, funct3)
  def FNEG(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FNEG, 0, rs1, rd, funct3)
  def FMOV(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FMOV, 0, rs1, rd, funct3)
  def FSTEP(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FSTEP, 0, rs1, rd, funct3)
  def FRCP(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FRCP, 0, rs1, rd, funct3)
  def IADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IADD, rs2, rs1, rd, funct3)
  def ISHL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISHL, rs2, rs1, rd, funct3)
  def ISHR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISHR, rs2, rs1, rd, funct3)
  def IMUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IMUL, rs2, rs1, rd, funct3)
  def ISUB(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISUB, rs2, rs1, rd, funct3)
  def IAND(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IAND, rs2, rs1, rd, funct3)
  def IOR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IOR, rs2, rs1, rd, funct3)
  def IXOR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IXOR, rs2, rs1, rd, funct3)
  def ISLT(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISLT, rs2, rs1, rd, funct3)
  def ISEQ(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISEQ, rs2, rs1, rd, funct3)
  def I2F(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_I2F, 0, rs1, rd, funct3)
  def F2I(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_F2I, 0, rs1, rd, funct3)
  def F2H(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_F2H, 0, rs1, rd, funct3)
  def H2F(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_H2F, 0, rs1, rd, funct3)
  def FRSQ(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FRSQ, 0, rs1, rd, funct3)
  def FSRGB(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FSRGB, 0, rs1, rd, funct3)
  /** fine = true: DPdxFine/DPdyFine (each lane's own row/column); else coarse. */
  def DDX(rs1: Int, rd: Int, funct3: Int = 0, fine: Boolean = false): BigInt =
    encodeRType(FUNCT7_DDX, if (fine) 1 else 0, rs1, rd, funct3)
  def DDY(rs1: Int, rd: Int, funct3: Int = 0, fine: Boolean = false): BigInt =
    encodeRType(FUNCT7_DDY, if (fine) 1 else 0, rs1, rd, funct3)
  def LOAD(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_LOAD, 0, rs1, rd, funct3)
  /** STORE has no destination register; rd is encoded as 0. */
  /** private = true (rd = 1): Function/Private memory, written by helper
    * invocations too; a plain STORE (storage buffers, images) is not. */
  def STORE(rs1: Int, rs2: Int, funct3: Int = 0, `private`: Boolean = false): BigInt =
    encodeRType(FUNCT7_STORE, rs2, rs1, if (`private`) 1 else 0, funct3)
  /** BRZ rs1, offset: if rs1 == 0, jump by offset words from this instruction (-512..511). */
  def BRZ(rs1: Int, offset: Int, funct3: Int = 0): BigInt = {
    require(offset >= -512 && offset < 512, s"branch offset out of range: $offset")
    Opcodes.BRZ(rs1 = rs1, offset = offset, f3 = funct3)
  }
  def EXPUSH(rs1: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXPUSH, 0, rs1, 0, funct3)
  def EXELSE(funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXELSE, 0, 0, 0, funct3)
  def EXPOP(funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXPOP, 0, 0, 0, funct3)
  def BARRIER(funct3: Int = 0): BigInt = encodeRType(FUNCT7_BARRIER, 0, 0, 0, funct3)
  def EXANY(rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXANY, 0, 0, rd, funct3)
  def ZTEST(funct3: Int = 0): BigInt = encodeRType(FUNCT7_ZTEST, 0, 0, 0, funct3)
  def BRNZ(rs1: Int, offset: Int, funct3: Int = 0): BigInt = {
    require(offset >= -512 && offset < 512, s"branch offset out of range: $offset")
    Opcodes.BRNZ(rs1 = rs1, offset = offset, f3 = funct3)
  }
  def FMA(rs1: Int, rs2: Int, rs3: Int, rd: Int, funct3: Int = 0): BigInt = encodeR4Type(rs3, 0, rs2, rs1, rd, funct3)
  /** TEX: sample; rs3 names the register holding the control word. */
  def TEX(rd: Int, rs1: Int, rs2: Int, rs3: Int, funct3: Int = 0): BigInt = {
    require(rd <= 28, s"TEX writes rd..rd+3, so rd <= 28: $rd")
    encodeR4Type(rs3, FUNCT2_TEX, rs2, rs1, rd, funct3)
  }
  /** TEXA: w/layer, LOD/bias and depth reference for the next TEX. */
  def TEXA(rs1: Int, rs2: Int, rs3: Int, funct3: Int = 0): BigInt = encodeR4Type(rs3, FUNCT2_TEXA, rs2, rs1, 0, funct3)
  def SMASK(rd: Int): BigInt = encodeRType(FUNCT7_SMASK, 0, 0, rd)
  def ATTIDX(rd: Int): BigInt = encodeRType(FUNCT7_ATTIDX, 0, 0, rd)
  def TLD(rd: Int, word: Int = 0): BigInt = encodeRType(FUNCT7_TLD, 0, 0, rd, word)
  def JMP(offset: Int): BigInt = {
    require(offset >= -(1 << 17) && offset < (1 << 17), s"JMP offset out of range: $offset")
    Opcodes.JMP(joffset = offset)
  }
  def ISRL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISRL, rs2, rs1, rd, funct3)
  def ISLTU(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISLTU, rs2, rs1, rd, funct3)
  /** SOUT: store rs2 as output component `index` (packed into rs1:rd). */
  def SOUT(rs2: Int, index: Int, funct3: Int = 0): BigInt = {
    val (hi, lo) = indexFields(index)
    encodeRType(FUNCT7_SOUT, rs2, hi, lo, funct3)
  }
  /** FATTR: rd..rd+2 = component `index`'s per-vertex values (packed into rs2:rs1). */
  def FATTR(rd: Int, index: Int): BigInt = {
    require(rd <= 29, s"FATTR writes rd..rd+2, so rd <= 29: $rd")
    val (hi, lo) = indexFields(index)
    encodeRType(FUNCT7_FATTR, hi, lo, rd)
  }
  // @doc:end

  /** Operand shape of an instruction, which decides its C macro signature. */
}
