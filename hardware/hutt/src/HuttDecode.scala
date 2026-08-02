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
  val Amo     = "b0101111".U(7.W)  // A extension: LR/SC/AMO* (xlen=64 only)
}

/** A-extension funct5 selectors (instr[31:27]). */
object AmoFunct5 {
  val Add  = "b00000".U(5.W)
  val Swap = "b00001".U(5.W)
  val Lr   = "b00010".U(5.W)
  val Sc   = "b00011".U(5.W)
  val Xor  = "b00100".U(5.W)
  val Or   = "b01000".U(5.W)
  val And  = "b01100".U(5.W)
  val Min  = "b10000".U(5.W)
  val Max  = "b10100".U(5.W)
  val Minu = "b11000".U(5.W)
  val Maxu = "b11100".U(5.W)
}

/** ALU operation selector.  The `*W` variants are RV64 word ops (32-bit result
  * sign-extended to 64); never selected on RV32.  The Mul/Div/Rem family (M
  * extension) and their `*W` forms are only implemented in hardware for
  * xlen=64 builds (see HuttAlu) — required for OpenSBI/Linux, whose own
  * source unconditionally uses integer divide. */
object AluOp extends ChiselEnum {
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And,
      AddW, SubW, SllW, SrlW, SraW,
      Mul, Mulh, Mulhsu, Mulhu, Div, Divu, Rem, Remu,
      MulW, DivW, DivuW, RemW, RemuW = Value
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
    d.isMret      := isSystem0 && (funct12 === "h302".U)
    d.isSret      := isSystem0 && (funct12 === "h102".U)
    d.isSfenceVma := isSystem0 && (d.funct7 === 9.U)  // funct7=0b0001001 per spec
    // FENCE, WFI, and unknown SYSTEM+funct3=0 are harmless NOPs.
    d.isNop    := (d.opcode === Opcode.MiscMem) ||
                  (isSystem0 && !d.isEcall && !d.isEbreak && !d.isMret && !d.isSret && !d.isSfenceVma)

    // A extension: opcode 0101111, funct3 010=word/011=doubleword, funct5=instr[31:27].
    // xlen=64 only (RV32/ASIC never emits these -- GCC needs -march=...a to
    // even encode them). Hardwiring isAmo/isLr/isSc to Chisel-level false.B
    // for xlen=32 (rather than just relying on the opcode never appearing
    // in practice) lets yosys's constant-propagation prove Hutt's entire
    // AMO/LR/SC FSM (sAmoLoadReq/sAmoLoadResp/sAmoStoreReq/sAmoStoreResp,
    // the reservation registers, the 9-term amoNewVal ALU) unreachable and
    // eliminate it -- a runtime-only "instructions never appear" guarantee
    // is data-dependent and can't be constant-folded by synthesis at all.
    d.amoFunct5 := instr(31, 27)
    d.isAmo := (if (xlen == 64) d.opcode === Opcode.Amo else false.B)
    d.isLr  := d.isAmo && (d.amoFunct5 === AmoFunct5.Lr)
    d.isSc  := d.isAmo && (d.amoFunct5 === AmoFunct5.Sc)

    d
  }
}
