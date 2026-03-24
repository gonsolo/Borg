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

  // --- RISC-V Instruction Format Definitions ---
  val BF_RS1    = BitField(19, 15)
  val BF_RS2    = BitField(24, 20)
  val BF_RS3    = BitField(31, 27)
  val BF_RD     = BitField(11, 7)
  val BF_OP     = BitField(6, 0)
  val BF_FUNCT3 = BitField(14, 12)
  val BF_FUNCT7 = BitField(31, 25)
  
  // Custom hardware decode boundaries
  val BF_F7_OP = BitField(28, 25)
  val BITS_OPCODE_FMA_BIT = 2

  // --- Opcodes ---
  // Note: Borg natively checks bit 2 of the opcode to quickly identify FMA.
  val OPCODE_ALU = 0x00
  val OPCODE_FMA = 0x04

  val FUNCT7_ADD   = 0x00
  val FUNCT7_MUL   = 0x04
  val FUNCT7_FNEG  = 0x06
  val FUNCT7_FSTEP = 0x08
  val FUNCT7_FRCP  = 0x0A

  // --- Base Instruction Encoders ---
  def encodeRType(funct7: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_ALU): BigInt =
    BigInt((funct7 << BF_FUNCT7.lo) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  def encodeR4Type(rs3: Int, funct2: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_FMA): BigInt =
    BigInt((rs3 << BF_RS3.lo) | (funct2 << 25) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  // --- Specific Instruction Constructors (Software usage) ---
  def ADD(rs1: Int, rs2: Int, rd: Int): BigInt = encodeRType(FUNCT7_ADD, rs2, rs1, rd)
  def MUL(rs1: Int, rs2: Int, rd: Int): BigInt = encodeRType(FUNCT7_MUL, rs2, rs1, rd)
  def FNEG(rs1: Int, rd: Int): BigInt = encodeRType(FUNCT7_FNEG, 0, rs1, rd)
  def FSTEP(rs1: Int, rd: Int): BigInt = encodeRType(FUNCT7_FSTEP, 0, rs1, rd)
  def FRCP(rs1: Int, rd: Int): BigInt = encodeRType(FUNCT7_FRCP, 0, rs1, rd)
  def FMA(rs1: Int, rs2: Int, rs3: Int, rd: Int): BigInt = encodeR4Type(rs3, 0, rs2, rs1, rd)

  // --- String Formatters for C / Python Generation ---
  def PY_ARGS_R    = s"(rs2 << ${BF_RS2.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"
  def PY_ARGS_R4   = s"(rs3 << ${BF_RS3.lo}) | (rs2 << ${BF_RS2.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"
  def PY_ARGS_FNEG = s"(rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"

  def C_ARGS_R     = s"((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
  def C_ARGS_R4    = s"((rs3) << ${BF_RS3.lo}) | ((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
  def C_ARGS_FNEG  = s"((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
}
