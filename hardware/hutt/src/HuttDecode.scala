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
  val OpImm32 = "b0011011".U(7.W)  // RV64 ADDIW/SLLIW/SRLIW/SRAIW
  val Op32    = "b0111011".U(7.W)  // RV64 ADDW/SUBW/SLLW/SRLW/SRAW
  val MiscMem = "b0001111".U(7.W)  // FENCE / FENCE.I — treated as nop
  val System  = "b1110011".U(7.W)  // ECALL / EBREAK / CSR — treated as nop in this RV32I-only core
}

/** ALU operation selector.  The `*W` variants are RV64 word ops (32-bit result
  * sign-extended to 64); never selected on RV32. */
object AluOp extends ChiselEnum {
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And,
      AddW, SubW, SllW, SrlW, SraW = Value
}

/** Decoded instruction fields and control signals.
  *
  * Combinationally derived from the 32-bit instruction word — held by the EXEC
  * state of the Hutt FSM via Mux/wire reads, no explicit pipeline registers.
  */
class HuttDecoded(val xlen: Int = 32) extends Bundle {
  val opcode = UInt(7.W)
  val rd     = UInt(5.W)
  val rs1    = UInt(5.W)
  val rs2    = UInt(5.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)

  // Pre-computed immediates (sign-extended to XLEN bits)
  val iImm = UInt(xlen.W)
  val sImm = UInt(xlen.W)
  val bImm = UInt(xlen.W)
  val uImm = UInt(xlen.W)
  val jImm = UInt(xlen.W)

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
  val isOpImm32 = Bool()  // RV64 ADDIW/SLLIW/SRLIW/SRAIW
  val isOp32    = Bool()  // RV64 ADDW/SUBW/SLLW/SRLW/SRAW
  val isNop    = Bool()   // FENCE/WFI/unknown-SYSTEM treated as nop
  val isCsr    = Bool()   // SYSTEM with funct3 != 0 → CSR read/write
  val isEcall  = Bool()   // ECALL — triggers M-mode trap (cause 11)
  val isEbreak = Bool()   // EBREAK — triggers M-mode trap (cause 3)
  val isMret   = Bool()   // MRET — return from M-mode trap
}

object HuttDecode {

  /** Pure combinational decoder.  `xlen` sets immediate sign-extension width. */
  def apply(instr: UInt, xlen: Int = 32): HuttDecoded = {
    val d = Wire(new HuttDecoded(xlen))

    d.opcode := instr(6, 0)
    d.rd     := instr(11, 7)
    d.funct3 := instr(14, 12)
    d.rs1    := instr(19, 15)
    d.rs2    := instr(24, 20)
    d.funct7 := instr(31, 25)

    // Sign-extended immediates per the RISC-V base encoding (to XLEN bits).
    val signBit = instr(31)
    d.iImm := Cat(Fill(xlen - 12, signBit), instr(31, 20))
    d.sImm := Cat(Fill(xlen - 12, signBit), instr(31, 25), instr(11, 7))
    d.bImm := Cat(Fill(xlen - 13, signBit), instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
    // LUI/AUIPC: the 20-bit upper immediate sits at bits [31:12]; on RV64 it is
    // sign-extended above bit 31.
    d.uImm := (if (xlen <= 32) Cat(instr(31, 12), 0.U(12.W))
               else Cat(Fill(xlen - 32, instr(31)), instr(31, 12), 0.U(12.W)))
    d.jImm := Cat(Fill(xlen - 21, signBit), instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))

    d.isLui    := d.opcode === Opcode.Lui
    d.isAuipc  := d.opcode === Opcode.Auipc
    d.isJal    := d.opcode === Opcode.Jal
    d.isJalr   := d.opcode === Opcode.Jalr
    d.isBranch := d.opcode === Opcode.Branch
    d.isLoad   := d.opcode === Opcode.Load
    d.isStore  := d.opcode === Opcode.Store
    d.isOpImm  := d.opcode === Opcode.OpImm
    d.isOp     := d.opcode === Opcode.Op
    d.isOpImm32 := d.opcode === Opcode.OpImm32
    d.isOp32    := d.opcode === Opcode.Op32
    // CSR ops: SYSTEM + funct3 != 0.  ECALL/EBREAK/MRET use funct3=0 and are
    // distinguished by funct12 (instr[31:20]).
    d.isCsr    := (d.opcode === Opcode.System) && (d.funct3 =/= 0.U)
    val funct12 = instr(31, 20)
    val isSystem0 = (d.opcode === Opcode.System) && (d.funct3 === 0.U)
    d.isEcall  := isSystem0 && (funct12 === "h000".U)
    d.isEbreak := isSystem0 && (funct12 === "h001".U)
    d.isMret   := isSystem0 && (funct12 === "h302".U)
    // FENCE, WFI, and unknown SYSTEM+funct3=0 are harmless NOPs.
    d.isNop    := (d.opcode === Opcode.MiscMem) ||
                  (isSystem0 && !d.isEcall && !d.isEbreak && !d.isMret)

    d
  }
}
