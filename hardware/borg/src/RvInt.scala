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
  /** Zmmul, Zicond and the Zbb subset (B8 step 3): MUL runs as IMUL, the rest by rvxOp. */
  val mul = op && f7 === 1.U && f3 === 0.U
  private val rs2f = instr(24, 20)
  private val rvxTable = Seq[(Bool, Int)](
    (op && f7 === 1.U && f3 === 1.U) -> RvInt.Mulh, (op && f7 === 1.U && f3 === 2.U) -> RvInt.Mulhsu,
    (op && f7 === 1.U && f3 === 3.U) -> RvInt.Mulhu,
    (op && f7 === 7.U && f3 === 5.U) -> RvInt.CzeroEqz, (op && f7 === 7.U && f3 === 7.U) -> RvInt.CzeroNez,
    (op && f7 === 0x20.U && f3 === 7.U) -> RvInt.Andn, (op && f7 === 0x20.U && f3 === 6.U) -> RvInt.Orn,
    (op && f7 === 0x20.U && f3 === 4.U) -> RvInt.Xnor,
    (op && f7 === 5.U && f3 === 4.U) -> RvInt.Min, (op && f7 === 5.U && f3 === 5.U) -> RvInt.Minu,
    (op && f7 === 5.U && f3 === 6.U) -> RvInt.Max, (op && f7 === 5.U && f3 === 7.U) -> RvInt.Maxu,
    (opImm && f7 === 0x30.U && f3 === 1.U && rs2f === 4.U) -> RvInt.SextB,
    (opImm && f7 === 0x30.U && f3 === 1.U && rs2f === 5.U) -> RvInt.SextH,
    (op && f7 === 4.U && f3 === 4.U && rs2f === 0.U) -> RvInt.ZextH,
    (opImm && f7 === 0x30.U && f3 === 1.U && rs2f === 0.U) -> RvInt.Clz,
    (opImm && f7 === 0x30.U && f3 === 1.U && rs2f === 1.U) -> RvInt.Ctz,
    (opImm && f7 === 0x30.U && f3 === 1.U && rs2f === 2.U) -> RvInt.Cpop,
    (op && f7 === 0x30.U && f3 === 1.U) -> RvInt.Rol, ((op || opImm) && f7 === 0x30.U && f3 === 5.U) -> RvInt.Ror,
    (opImm && instr(31, 20) === 0x698.U && f3 === 5.U) -> RvInt.Rev8)
  val rvx   = rvxTable.map(_._1).reduce(_ || _)
  val rvxOp = Mux1H(rvxTable.map { case (c, k) => c -> k.U(5.W) })
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
  val Mulh = 1; val Mulhsu = 2; val Mulhu = 3; val CzeroEqz = 4; val CzeroNez = 5; val Andn = 6; val Orn = 7
  val Xnor = 8; val Min = 9; val Minu = 10; val Max = 11; val Maxu = 12; val SextB = 13; val SextH = 14
  val ZextH = 15; val Clz = 16; val Ctz = 17; val Cpop = 18; val Rol = 19; val Ror = 20; val Rev8 = 21

  /** The result of a Zmmul/Zicond/Zbb op on a w-bit word. */
  def exec(op: UInt, a: UInt, b: UInt, w: Int): UInt = {
    val sh = b(log2Ceil(w) - 1, 0)
    val ps = (a.asSInt * b.asSInt).asUInt; val pu = a * b; val psu = (a.asSInt * Cat(0.U(1.W), b).asSInt).asUInt
    MuxLookup(op, 0.U(w.W))(Seq(
      Mulh.U -> ps(2 * w - 1, w), Mulhsu.U -> psu(2 * w - 1, w), Mulhu.U -> pu(2 * w - 1, w),
      CzeroEqz.U -> Mux(b === 0.U, 0.U, a), CzeroNez.U -> Mux(b =/= 0.U, 0.U, a),
      Andn.U -> (a & ~b), Orn.U -> (a | ~b), Xnor.U -> ~(a ^ b),
      Min.U -> Mux(a.asSInt < b.asSInt, a, b), Minu.U -> Mux(a < b, a, b),
      Max.U -> Mux(a.asSInt < b.asSInt, b, a), Maxu.U -> Mux(a < b, b, a),
      SextB.U -> a(7, 0).asSInt.pad(w).asUInt, SextH.U -> a(15, 0).asSInt.pad(w).asUInt, ZextH.U -> a(15, 0).pad(w),
      Clz.U -> Mux(a === 0.U, w.U, (w - 1).U - Log2(a)), Ctz.U -> Mux(a === 0.U, w.U, PriorityEncoder(a)),
      Cpop.U -> PopCount(a), Rol.U -> ((a << sh) | (a >> ((w.U - sh)(log2Ceil(w) - 1, 0))))(w - 1, 0),
      Ror.U -> ((a >> sh) | (a << ((w.U - sh)(log2Ceil(w) - 1, 0))))(w - 1, 0),
      Rev8.U -> Cat((0 until w / 8).map(i => a(8 * i + 7, 8 * i))))
    ).asUInt.pad(w)(w - 1, 0)
  }
}
