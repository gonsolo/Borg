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
  // The ISA v2 form where it is the same operation: no window operand (funct3 = 0) and, for the
  // RISC-V forms, no r0, which is x0 there.
  private def rv(f3: Int, regs: Int*) = f3 == 0 && regs.forall(_ != 0)
  private def r(f7: Int, v2: (Int, Int, Int) => BigInt)(rs1: Int, rs2: Int, rd: Int, f3: Int): BigInt =
    if (rv(f3, rs1, rs2, rd)) v2(rd, rs1, rs2) else encodeRType(f7, rs2, rs1, rd, f3)
  private def r1(f7: Int, v2: (Int, Int) => BigInt)(rs1: Int, rd: Int, f3: Int): BigInt =
    if (rv(f3, rs1, rd)) v2(rd, rs1) else encodeRType(f7, 0, rs1, rd, f3)
  private def c(f7: Int, rs1: Int, rd: Int, f3: Int, v2: => BigInt): BigInt =
    if (f3 == 0) v2 else encodeRType(f7, 0, rs1, rd, f3)
  // Ops whose v1 form is gone: only the v2 form.
  private def only(ok: Boolean, v2: => BigInt): BigInt = { require(ok, "no v1 form: no window operand, no r0"); v2 }
  private def r2(v2: (Int, Int, Int) => BigInt)(rs1: Int, rs2: Int, rd: Int, f3: Int): BigInt = only(rv(f3, rs1, rs2, rd), v2(rd, rs1, rs2))
  private def r12(v2: (Int, Int) => BigInt)(rs1: Int, rd: Int, f3: Int): BigInt = only(rv(f3, rs1, rd), v2(rd, rs1))

  def ADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r(FUNCT7_FADD, FADD_S(_, _, _))(rs1, rs2, rd, funct3)
  def MUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r(FUNCT7_FMUL, FMUL_S(_, _, _))(rs1, rs2, rd, funct3)
  def FNEG(rs1: Int, rd: Int, funct3: Int = 0): BigInt = r1(FUNCT7_FNEG, (d, a) => FSGNJN_S(d, a, a))(rs1, rd, funct3)
  def FMOV(rs1: Int, rd: Int, funct3: Int = 0): BigInt = r12((d, a) => FSGNJ_S(d, a, a))(rs1, rd, funct3)
  def FRCP(rs1: Int, rd: Int, funct3: Int = 0): BigInt = only(funct3 == 0, FRCP_V2(rd, rs1))
  def IADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r2(Opcodes.ADD(_, _, _))(rs1, rs2, rd, funct3)
  def ISHL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r(FUNCT7_ISHL, SLL(_, _, _))(rs1, rs2, rd, funct3)
  def ISHR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r2(SRA(_, _, _))(rs1, rs2, rd, funct3)
  def IMUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r2(Opcodes.MUL(_, _, _))(rs1, rs2, rd, funct3)
  def ISUB(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r2(SUB(_, _, _))(rs1, rs2, rd, funct3)
  def IAND(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r(FUNCT7_IAND, AND(_, _, _))(rs1, rs2, rd, funct3)
  def IOR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r(FUNCT7_IOR, OR(_, _, _))(rs1, rs2, rd, funct3)
  def IXOR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r(FUNCT7_IXOR, XOR(_, _, _))(rs1, rs2, rd, funct3)
  def ISLT(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r2(SLT(_, _, _))(rs1, rs2, rd, funct3)
  def ISEQ(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISEQ, rs2, rs1, rd, funct3)
  def I2F(rs1: Int, rd: Int, funct3: Int = 0): BigInt = r12(FCVT_S_W(_, _))(rs1, rd, funct3)
  def F2I(rs1: Int, rd: Int, funct3: Int = 0): BigInt = r12(FCVT_W_S(_, _, 1))(rs1, rd, funct3)
  def F2H(rs1: Int, rd: Int, funct3: Int = 0): BigInt = r12(FCVT_H_S(_, _))(rs1, rd, funct3)
  def H2F(rs1: Int, rd: Int, funct3: Int = 0): BigInt = r12(FCVT_S_H(_, _))(rs1, rd, funct3)
  def FRSQ(rs1: Int, rd: Int, funct3: Int = 0): BigInt = only(funct3 == 0, FRSQ_V2(rd, rs1))
  def FSRGB(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FSRGB, 0, rs1, rd, funct3)
  /** fine = true: DPdxFine/DPdyFine (each lane's own row/column); else coarse. */
  def DDX(rs1: Int, rd: Int, funct3: Int = 0, fine: Boolean = false): BigInt =
    only(funct3 == 0, DDX_V2(rd, rs1, if (fine) 1 else 0))
  def DDY(rs1: Int, rd: Int, funct3: Int = 0, fine: Boolean = false): BigInt =
    only(funct3 == 0, DDY_V2(rd, rs1, if (fine) 1 else 0))
  /** BRZ rs1, offset: if rs1 == 0, jump by offset words from this instruction (-512..511). */
  def BRZ(rs1: Int, offset: Int, funct3: Int = 0): BigInt = {
    require(offset >= -512 && offset < 512, s"branch offset out of range: $offset")
    only(rv(funct3, rs1), BEQ(4 * offset, rs1, 0))
  }
  def EXPUSH(rs1: Int, funct3: Int = 0): BigInt = only(funct3 == 0, EXPUSH_V2(rs1))
  def EXELSE(funct3: Int = 0): BigInt = only(funct3 == 0, EXELSE_V2())
  def EXPOP(funct3: Int = 0): BigInt = only(funct3 == 0, EXPOP_V2())
  def BARRIER(funct3: Int = 0): BigInt = only(funct3 == 0, BARRIER_V2())
  def EXANY(rd: Int, funct3: Int = 0): BigInt = only(funct3 == 0, EXANY_V2(rd))
  def ZTEST(funct3: Int = 0): BigInt = only(funct3 == 0, ZTEST_V2())
  def BRNZ(rs1: Int, offset: Int, funct3: Int = 0): BigInt = {
    require(offset >= -512 && offset < 512, s"branch offset out of range: $offset")
    only(rv(funct3, rs1), BNE(4 * offset, rs1, 0))
  }
  def FMA(rs1: Int, rs2: Int, rs3: Int, rd: Int, funct3: Int = 0): BigInt =
    if (rv(funct3, rs1, rs2, rs3, rd)) FMADD_S(rd, rs1, rs2, rs3) else encodeR4Type(rs3, 0, rs2, rs1, rd, funct3)
  def SMASK(rd: Int): BigInt = CSRRS(rd, 0, Csr.SampleMask)
  def ATTIDX(rd: Int): BigInt = CSRRS(rd, 0, Csr.Attachment)
  def TLD(rd: Int, word: Int = 0): BigInt = only(word < 2, TLD_V2(rd, word))
  def JMP(offset: Int): BigInt = {
    require(offset >= -(1 << 17) && offset < (1 << 17), s"JMP offset out of range: $offset")
    JAL(0, 4 * offset)
  }
  def ISRL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r2(SRL(_, _, _))(rs1, rs2, rd, funct3)
  def ISLTU(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = r2(SLTU(_, _, _))(rs1, rs2, rd, funct3)
  /** SOUT: store rs2 as output component `index` (packed into rs1:rd). */
  def SOUT(rs2: Int, index: Int, funct3: Int = 0): BigInt = {
    val (hi, lo) = indexFields(index)
    if (funct3 == 0) SOUT_V2(rs2, index) else encodeRType(FUNCT7_SOUT, rs2, hi, lo, funct3)
  }
  /** FATTR: rd..rd+2 = component `index`'s per-vertex values (packed into rs2:rs1). */
  def FATTR(rd: Int, index: Int): BigInt = {
    require(rd <= 29, s"FATTR writes rd..rd+2, so rd <= 29: $rd")
    indexFields(index)
    FATTR_V2(rd, index)
  }
  // @doc:end

  /** Operand shape of an instruction, which decides its C macro signature. */
}
