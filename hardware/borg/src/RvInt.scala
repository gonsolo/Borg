// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** ISA v2 step 1 (docs/B8_isa_v2.md): RISC-V OP, OP-IMM, LUI and AUIPC, decoded onto the v1 integer ALU. */
class RvInt(instr: UInt) {
  private val opc = instr(6, 0); private val f3 = instr(14, 12); private val f7 = instr(31, 25)
  val op    = opc === Opcodes.OPCODE_ADD.U
  val opImm = opc === Opcodes.OPCODE_ADDI.U
  val lui   = opc === Opcodes.OPCODE_LUI.U
  val auipc = opc === Opcodes.OPCODE_AUIPC.U
  val csrrw = opc === Opcodes.OPCODE_CSRRW.U && f3 === 1.U
  val csrrs = opc === Opcodes.OPCODE_CSRRS.U && f3 === 2.U
  val csr   = instr(31, 20)
  /** LB, LH, LW, LBU, LHU at rs1 + imm12; SW at rs1 + the S-type immediate. */
  val load  = opc === Opcodes.OPCODE_LW.U && (f3 === 0.U || f3 === 1.U || f3 === 2.U || f3 === 4.U || f3 === 5.U)
  val store = opc === Opcodes.OPCODE_SW.U && f3 === 2.U
  val width = f3
  val memImm = Mux(store, Cat(instr(31, 25), instr(11, 7)), instr(31, 20)).asSInt.pad(32).asUInt
  val any   = op || opImm || lui || auipc || csrrs || load
  /** BEQ..BGEU and JAL (rd = x0: no return address yet); offsets in words. */
  val br    = opc === Opcodes.OPCODE_BEQ.U && f3 =/= 2.U && f3 =/= 3.U
  val jal   = opc === Opcodes.OPCODE_JAL.U
  val brOff  = (Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W)).asSInt >> 2).pad(20)
  val jalOff = (Cat(instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W)).asSInt >> 2).pad(20)
  def cond(a: UInt, b: UInt): Bool = MuxLookup(f3, false.B)(Seq(0.U -> (a === b), 1.U -> (a =/= b),
    4.U -> (a.asSInt < b.asSInt), 5.U -> (a.asSInt >= b.asSInt), 6.U -> (a < b), 7.U -> (a >= b)))
  private val base  = op && f7 === 0.U || opImm
  private val shift = (op || opImm) && f7 === 0.U
  val add  = base && f3 === 0.U || lui || auipc || csrrs
  val sub  = op && f7 === 0x20.U && f3 === 0.U
  val sll  = shift && f3 === 1.U
  val slt  = base && f3 === 2.U
  val sltu = base && f3 === 3.U
  val xor  = base && f3 === 4.U
  val srl  = shift && f3 === 5.U
  val sra  = (op || opImm) && f7 === 0x20.U && f3 === 5.U
  val or   = base && f3 === 6.U
  val and  = base && f3 === 7.U
  /** Operand A is x0 (or LUI's zero); AUIPC's is the instruction's byte address. */
  val aZero = (op || opImm || csrrw || load || store || br) && instr(19, 15) === 0.U || lui
  /** Operand B is x0, or the immediate. */
  val bZero = (op || store || br) && instr(24, 20) === 0.U
  val bImm  = opImm || lui || auipc
  val imm   = Mux(opImm, instr(31, 20).asSInt.pad(32).asUInt, Cat(instr(31, 12), 0.U(12.W)))
}

object RvInt {
  def apply(instr: UInt): RvInt = new RvInt(instr)
}
