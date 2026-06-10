// SPDX-FileCopyrightText: © 2026
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.UInt

/** Instruction specification for the Borg FP16 shader processor.
  * This acts as the single source of truth for both hardware decoding
  * and firmware/Python instruction generation.
  */
object Instructions {

  case class BitField(hi: Int, lo: Int) {
    def apply(u: UInt): UInt = u(hi, lo)
  }

  // @doc:isa-bitfields
  // --- RISC-V Instruction Format Definitions ---
  val BF_RS1    = BitField(19, 15)
  val BF_RS2    = BitField(24, 20)
  val BF_RS3    = BitField(31, 27)
  val BF_RD     = BitField(11, 7)
  val BF_OP     = BitField(6, 0)
  val BF_FUNCT3 = BitField(14, 12)
  val BF_FUNCT7 = BitField(31, 25)

  // Custom hardware decode boundaries. The funct7 sub-op uses the full 7 bits
  // (31:25); the original FP ops only set bits 28:25 (so widening this is
  // backward compatible), while the integer ops below use the upper bits too.
  val BF_F7_OP = BitField(31, 25)
  val BITS_OPCODE_FMA_BIT = 2

  // --- Opcodes ---
  val OPCODE_ALU = 0x00
  val OPCODE_FMA = 0x04

  val FUNCT7_ADD   = 0x00
  val FUNCT7_MUL   = 0x04
  val FUNCT7_FNEG  = 0x06
  val FUNCT7_FSTEP = 0x08
  val FUNCT7_FRCP  = 0x0A
  val FUNCT7_FTEX  = 0x0C  // Texture sample: FTEX rd, rs1(U), rs2(V) → rd=texR, rd+1=texG, rd+2=texB
  // Integer ops (16-bit, operate on the raw register bits). Use the widened
  // 7-bit funct7 field; the upper bits keep them distinct from the FP ops.
  val FUNCT7_IADD  = 0x0E  // rd = rs1 + rs2        (16-bit wrap)
  val FUNCT7_ISHL  = 0x10  // rd = rs1 << (rs2 & 15)
  val FUNCT7_ISHR  = 0x12  // rd = rs1 >> (rs2 & 15) (arithmetic)
  val FUNCT7_IMUL  = 0x14  // rd = (rs1 * rs2)[15:0]
  val FUNCT7_I2F   = 0x16  // rd = fp16(signed int16 rs1)  (unary)
  val FUNCT7_F2I   = 0x18  // rd = signed int16(fp16 rs1)  (unary, truncate)
  // @doc:end

  // --- Base Instruction Encoders ---
  def encodeRType(funct7: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_ALU): BigInt =
    BigInt((funct7 << BF_FUNCT7.lo) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  def encodeR4Type(rs3: Int, funct2: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_FMA): BigInt =
    BigInt((rs3 << BF_RS3.lo) | (funct2 << 25) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  // @doc:isa-encoders
  def ADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ADD, rs2, rs1, rd, funct3)
  def MUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_MUL, rs2, rs1, rd, funct3)
  def FNEG(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FNEG, 0, rs1, rd, funct3)
  def FSTEP(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FSTEP, 0, rs1, rd, funct3)
  def FRCP(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FRCP, 0, rs1, rd, funct3)
  def FTEX(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FTEX, rs2, rs1, rd, funct3)
  def IADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IADD, rs2, rs1, rd, funct3)
  def ISHL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISHL, rs2, rs1, rd, funct3)
  def ISHR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISHR, rs2, rs1, rd, funct3)
  def IMUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IMUL, rs2, rs1, rd, funct3)
  def I2F(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_I2F, 0, rs1, rd, funct3)
  def F2I(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_F2I, 0, rs1, rd, funct3)
  def FMA(rs1: Int, rs2: Int, rs3: Int, rd: Int, funct3: Int = 0): BigInt = encodeR4Type(rs3, 0, rs2, rs1, rd, funct3)
  // @doc:end

  // --- String Formatters for C / Python Generation ---
  def PY_ARGS_R    = s"(funct3 << ${BF_FUNCT3.lo}) | (rs2 << ${BF_RS2.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"
  def PY_ARGS_R4   = s"(funct3 << ${BF_FUNCT3.lo}) | (rs3 << ${BF_RS3.lo}) | (rs2 << ${BF_RS2.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"
  def PY_ARGS_FNEG = s"(funct3 << ${BF_FUNCT3.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"

  def C_ARGS_R     = s"((funct3) << ${BF_FUNCT3.lo}) | ((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
  def C_ARGS_R4    = s"((funct3) << ${BF_FUNCT3.lo}) | ((rs3) << ${BF_RS3.lo}) | ((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
  def C_ARGS_FNEG  = s"((funct3) << ${BF_FUNCT3.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
}
