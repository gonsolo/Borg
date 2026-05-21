// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** RV32I opcodes (instr[6:0]). */
object Opcode {
  val Lui    = "b0110111".U(7.W)
  val Auipc  = "b0010111".U(7.W)
  val Jal    = "b1101111".U(7.W)
  val Jalr   = "b1100111".U(7.W)
  val Branch = "b1100011".U(7.W)
  val Load   = "b0000011".U(7.W)
  val Store  = "b0100011".U(7.W)
  val OpImm  = "b0010011".U(7.W)
  val Op     = "b0110011".U(7.W)
  val MiscMem = "b0001111".U(7.W)  // FENCE / FENCE.I — treated as nop
  val System  = "b1110011".U(7.W)  // ECALL / EBREAK / CSR — treated as nop in this RV32I-only core
}

/** ALU operation selector. */
object AluOp extends ChiselEnum {
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And = Value
}

/** Decoded instruction fields and control signals.
  *
  * Combinationally derived from the 32-bit instruction word — held by the EXEC
  * state of the Hutt FSM via Mux/wire reads, no explicit pipeline registers.
  */
class HuttDecoded extends Bundle {
  val opcode = UInt(7.W)
  val rd     = UInt(5.W)
  val rs1    = UInt(5.W)
  val rs2    = UInt(5.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)

  // Pre-computed immediates (sign-extended to 32 bits)
  val iImm = UInt(32.W)
  val sImm = UInt(32.W)
  val bImm = UInt(32.W)
  val uImm = UInt(32.W)
  val jImm = UInt(32.W)

  // Convenience flags
  val isLui    = Bool()
  val isAuipc  = Bool()
  val isJal    = Bool()
  val isJalr   = Bool()
  val isBranch = Bool()
  val isLoad   = Bool()
  val isStore  = Bool()
  val isOpImm  = Bool()
  val isOp     = Bool()
  val isNop    = Bool()   // FENCE/SYSTEM treated as nop
}

object HuttDecode {

  /** Pure combinational decoder. */
  def apply(instr: UInt): HuttDecoded = {
    val d = Wire(new HuttDecoded)

    d.opcode := instr(6, 0)
    d.rd     := instr(11, 7)
    d.funct3 := instr(14, 12)
    d.rs1    := instr(19, 15)
    d.rs2    := instr(24, 20)
    d.funct7 := instr(31, 25)

    // Sign-extended immediates per the RV32I encoding.
    val signBit = instr(31)
    d.iImm := Cat(Fill(20, signBit), instr(31, 20))
    d.sImm := Cat(Fill(20, signBit), instr(31, 25), instr(11, 7))
    d.bImm := Cat(Fill(19, signBit), instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
    d.uImm := Cat(instr(31, 12), 0.U(12.W))
    d.jImm := Cat(Fill(11, signBit), instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))

    d.isLui    := d.opcode === Opcode.Lui
    d.isAuipc  := d.opcode === Opcode.Auipc
    d.isJal    := d.opcode === Opcode.Jal
    d.isJalr   := d.opcode === Opcode.Jalr
    d.isBranch := d.opcode === Opcode.Branch
    d.isLoad   := d.opcode === Opcode.Load
    d.isStore  := d.opcode === Opcode.Store
    d.isOpImm  := d.opcode === Opcode.OpImm
    d.isOp     := d.opcode === Opcode.Op
    d.isNop    := (d.opcode === Opcode.MiscMem) || (d.opcode === Opcode.System)

    d
  }
}
