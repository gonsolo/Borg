// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** Major opcodes (instr[6:0]), from hardware/hutt/isa via [[HuttOpcodes]]. */
object Opcode {
  import HuttOpcodes._
  val Lui     = OPCODE_LUI.U(7.W)
  val Auipc   = OPCODE_AUIPC.U(7.W)
  val Jal     = OPCODE_JAL.U(7.W)
  val Jalr    = OPCODE_JALR.U(7.W)
  val Branch  = OPCODE_BEQ.U(7.W)
  val Load    = OPCODE_LB.U(7.W)
  val Store   = OPCODE_SB.U(7.W)
  val OpImm   = OPCODE_ADDI.U(7.W)
  val Op      = OPCODE_ADD.U(7.W)
  val OpImm32 = OPCODE_ADDIW.U(7.W)  // RV64 ADDIW/SLLIW/SRLIW/SRAIW
  val Op32    = OPCODE_ADDW.U(7.W)   // RV64 ADDW/SUBW/SLLW/SRLW/SRAW
  val MiscMem = OPCODE_FENCE.U(7.W)  // FENCE / FENCE.I -- treated as nop
  val System  = OPCODE_ECALL.U(7.W)  // ECALL / EBREAK / xRET / SFENCE.VMA / CSR
  val Amo     = OPCODE_LR_W.U(7.W)   // A extension: LR/SC/AMO* (xlen=64 only)
}

/** A-extension funct5 selectors (instr[31:27]). */
object AmoFunct5 {
  import HuttOpcodes._
  val Lr   = FUNCT5_LR_W.U(5.W)
  val Sc   = FUNCT5_SC_W.U(5.W)
}

/** ALU operation selector. `*W` are RV64 word ops; the M family exists only for xlen=64. */
object AluOp extends ChiselEnum {
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And,
      AddW, SubW, SllW, SrlW, SraW,
      Mul, Mulh, Mulhsu, Mulhu, Div, Divu, Rem, Remu,
      MulW, DivW, DivuW, RemW, RemuW = Value
}

/** Decoded instruction fields and control signals, combinational from the instruction word. */
class HuttDecoded(val xlen: Int = 32) extends Bundle {
  val opcode = UInt(7.W)
  val rd     = UInt(5.W)
  val rs1    = UInt(5.W)
  val rs2    = UInt(5.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)

  // Sign-extended to XLEN.
  val iImm = UInt(xlen.W)
  val sImm = UInt(xlen.W)
  val bImm = UInt(xlen.W)
  val uImm = UInt(xlen.W)
  val jImm = UInt(xlen.W)

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
  val isEcall  = Bool()   // ECALL — trap; cause depends on privilege level
  val isEbreak = Bool()   // EBREAK — trap; cause 3
  val isMret      = Bool()   // MRET — return from M-mode trap
  val isSret      = Bool()   // SRET — return from S-mode trap
  val isSfenceVma = Bool()   // SFENCE.VMA — supervisor TLB flush
  val isAmo    = Bool()   // A extension: LR/SC/AMO* (xlen=64 only)
  val isLr     = Bool()   // LR.W/LR.D
  val isSc     = Bool()   // SC.W/SC.D
  val amoFunct5 = UInt(5.W)  // AMO op selector (instr[31:27]); valid when isAmo
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

    d.iImm := riscv.Rv.immI(instr, xlen)
    d.sImm := riscv.Rv.immS(instr, xlen)
    d.bImm := riscv.Rv.immB(instr, xlen)
    d.uImm := riscv.Rv.immU(instr, xlen)
    d.jImm := riscv.Rv.immJ(instr, xlen)

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
    // ECALL/EBREAK/xRET use funct3=0 and differ in funct12.
    d.isCsr    := (d.opcode === Opcode.System) && (d.funct3 =/= 0.U)
    val funct12 = instr(31, 20)
    val isSystem0 = (d.opcode === Opcode.System) && (d.funct3 === 0.U)
    d.isEcall  := isSystem0 && (funct12 === HuttOpcodes.FUNCT12_ECALL.U)
    d.isEbreak := isSystem0 && (funct12 === HuttOpcodes.FUNCT12_EBREAK.U)
    d.isMret      := isSystem0 && (funct12 === HuttOpcodes.FUNCT12_MRET.U)
    d.isSret      := isSystem0 && (funct12 === HuttOpcodes.FUNCT12_SRET.U)
    d.isSfenceVma := isSystem0 && (d.funct7 === HuttOpcodes.FUNCT7_SFENCE_VMA.U)
    // FENCE, WFI, and unknown SYSTEM+funct3=0 are harmless NOPs.
    d.isNop    := (d.opcode === Opcode.MiscMem) ||
                  (isSystem0 && !d.isEcall && !d.isEbreak && !d.isMret && !d.isSret && !d.isSfenceVma)

    // A extension, xlen=64 only: a constant false.B on RV32 lets synthesis drop the AMO FSM.
    d.amoFunct5 := instr(31, 27)
    d.isAmo := (if (xlen == 64) d.opcode === Opcode.Amo else false.B)
    d.isLr  := d.isAmo && (d.amoFunct5 === AmoFunct5.Lr)
    d.isSc  := d.isAmo && (d.amoFunct5 === AmoFunct5.Sc)

    d
  }
}
