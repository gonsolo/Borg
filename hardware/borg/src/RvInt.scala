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
  /** System-value CSRs read where v1 keeps them: a register (r5-r8, r29-r31) or a window word. */
  val sysReg = MuxLookup(csr, 0.U(5.W))(Seq(Csr.FragX -> 30, Csr.FragY -> 31, Csr.VertexIndex -> 30,
    Csr.InstanceIndex -> 31, Csr.LocalIndex -> 30, Csr.LocalId -> 31, Csr.Bary0 -> 5, Csr.Bary1 -> 6,
    Csr.Bary2 -> 7, Csr.InvW -> 8, Csr.FragZ -> 29).map { case (c, r) => c.U -> r.U(5.W) })
  private val uniform = csr(11, 5) === (Csr.Uniform0 >> 5).U
  val sysWin = Mux(uniform, csr(4, 0), MuxLookup(csr, 0.U(5.W))(Seq(Csr.GroupIdX -> 29, Csr.GroupIdY -> 30,
    Csr.GroupIdZ -> 31).map { case (c, r) => c.U -> r.U(5.W) }))
  val csrReg = csrrs && sysReg =/= 0.U
  val csrWin = csrrs && (uniform || sysWin =/= 0.U)
  /** sample_mask reads each lane's coverage, through the lanes' SMASK path. */
  val csrMask = csrrs && csr === Csr.SampleMask.U
  /** LB, LH, LW, LBU, LHU at rs1 + imm12; SW at rs1 + the S-type immediate. */
  val load  = opc === Opcodes.OPCODE_LW.U && (f3 === 0.U || f3 === 1.U || f3 === 2.U || f3 === 4.U || f3 === 5.U)
  val store = opc === Opcodes.OPCODE_SW.U && f3 === 2.U
  val width = f3
  /** Custom-1 (B8 step 11): TEX by descriptor address (rs3), TEXA, and TEXS (sampler address, control word). */
  val custom1 = opc === Opcodes.OPCODE_TEXD.U
  val texd  = custom1 && f3 === 0.U && instr(26, 25) === 0.U
  val texad = custom1 && f3 === 0.U && instr(26, 25) === 1.U
  val texs  = custom1 && f3 === 1.U && f7 === 0.U
  /** Custom-0..3 (B8 step 15): v1's special, attachment, geometry and control ops, same operand fields. */
  private def is(m: Long, k: Long) = (instr & k.U(32.W)) === m.U(32.W)
  val frcp   = is(Opcodes.MATCH_FRCP_V2, Opcodes.MASK_FRCP_V2); val frsq = is(Opcodes.MATCH_FRSQ_V2, Opcodes.MASK_FRSQ_V2)
  val ddx    = is(Opcodes.MATCH_DDX_V2, Opcodes.MASK_DDX_V2); val ddy = is(Opcodes.MATCH_DDY_V2, Opcodes.MASK_DDY_V2)
  val tld    = is(Opcodes.MATCH_TLD_V2, Opcodes.MASK_TLD_V2); val ztest = is(Opcodes.MATCH_ZTEST_V2, Opcodes.MASK_ZTEST_V2)
  val sout   = is(Opcodes.MATCH_SOUT_V2, Opcodes.MASK_SOUT_V2); val fattr = is(Opcodes.MATCH_FATTR_V2, Opcodes.MASK_FATTR_V2)
  val expush = is(Opcodes.MATCH_EXPUSH_V2, Opcodes.MASK_EXPUSH_V2); val exelse = is(Opcodes.MATCH_EXELSE_V2, Opcodes.MASK_EXELSE_V2)
  val expop  = is(Opcodes.MATCH_EXPOP_V2, Opcodes.MASK_EXPOP_V2); val exany = is(Opcodes.MATCH_EXANY_V2, Opcodes.MASK_EXANY_V2)
  val barrier = is(Opcodes.MATCH_BARRIER_V2, Opcodes.MASK_BARRIER_V2)
  val fexp2  = is(Opcodes.MATCH_FEXP2, Opcodes.MASK_FEXP2); val flog2 = is(Opcodes.MATCH_FLOG2, Opcodes.MASK_FLOG2)
  /** Zaamo AMO*.W and Zacas AMOCAS.W (B8 step 12) at rs1; AMOCAS compares with rd. */
  val amo    = opc === Opcodes.OPCODE_AMOADD_W.U && f3 === 2.U
  val amoOp  = instr(31, 27)
  val amocas = amo && amoOp === 5.U
  val memImm = Mux(amo, 0.U(32.W), Mux(store, Cat(instr(31, 25), instr(11, 7)), instr(31, 20)).asSInt.pad(32).asUInt)
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
  /** Zfinx and Zhinxmin (B8 step 4): FP32 in the x registers, RNE (other rounding modes are not built). */
  val opFp  = opc === Opcodes.OPCODE_FADD_S.U && instr(26, 25) === 0.U
  private val f5 = instr(31, 27)
  val fadd  = opFp && f5 === 0.U
  val fsub  = opFp && f5 === 1.U
  val fmul  = opFp && f5 === 2.U
  val fcvtSH = opc === Opcodes.OPCODE_FADD_S.U && instr(31, 25) === 0x20.U && rs2f === 2.U
  val fcvtHS = opc === Opcodes.OPCODE_FADD_S.U && instr(31, 25) === 0x22.U && rs2f === 0.U
  private val r4fmt = instr(26, 25) === 0.U
  val fmadd  = opc === Opcodes.OPCODE_FMADD_S.U && r4fmt
  val fmsub  = opc === Opcodes.OPCODE_FMSUB_S.U && r4fmt
  val fnmsub = opc === Opcodes.OPCODE_FNMSUB_S.U && r4fmt
  val fnmadd = opc === Opcodes.OPCODE_FNMADD_S.U && r4fmt
  val r4    = fmadd || fmsub || fnmsub || fnmadd
  private val fpTable = Seq[(Bool, Int)](
    (opFp && f5 === 4.U && f3 === 0.U) -> RvInt.Fsgnj, (opFp && f5 === 4.U && f3 === 1.U) -> RvInt.Fsgnjn,
    (opFp && f5 === 4.U && f3 === 2.U) -> RvInt.Fsgnjx,
    (opFp && f5 === 5.U && f3 === 0.U) -> RvInt.Fmin, (opFp && f5 === 5.U && f3 === 1.U) -> RvInt.Fmax,
    (opFp && f5 === 0x14.U && f3 === 2.U) -> RvInt.Feq, (opFp && f5 === 0x14.U && f3 === 1.U) -> RvInt.Flt,
    (opFp && f5 === 0x14.U && f3 === 0.U) -> RvInt.Fle,
    (opFp && f5 === 0x1C.U && f3 === 1.U && rs2f === 0.U) -> RvInt.Fclass,
    (opFp && f5 === 0x18.U && rs2f === 0.U) -> RvInt.FcvtWS, (opFp && f5 === 0x18.U && rs2f === 1.U) -> RvInt.FcvtWUS,
    (opFp && f5 === 0x1A.U && rs2f === 0.U) -> RvInt.FcvtSW, (opFp && f5 === 0x1A.U && rs2f === 1.U) -> RvInt.FcvtSWU)
  val fpx   = fpTable.map(_._1).reduce(_ || _)
  val fp    = fadd || fsub || fmul || r4 || fpx || fcvtSH || fcvtHS
  val rvx   = rvxTable.map(_._1).reduce(_ || _) || fpx
  val rvxOp = Mux1H((rvxTable ++ fpTable).map { case (c, k) => c -> k.U(6.W) })
  val any   = op || opImm || lui || auipc || csrrs || load || fp || amo
  /** BEQ..BGEU and JAL (rd = x0: no return address yet); offsets in words. */
  val br    = opc === Opcodes.OPCODE_BEQ.U && f3 =/= 2.U && f3 =/= 3.U
  val jal   = opc === Opcodes.OPCODE_JAL.U
  val brOff  = (Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W)).asSInt >> 2).pad(20)
  val jalOff = (Cat(instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W)).asSInt >> 2).pad(20)
  def cond(a: UInt, b: UInt): Bool = MuxLookup(f3, false.B)(Seq(0.U -> (a === b), 1.U -> (a =/= b),
    4.U -> (a.asSInt < b.asSInt), 5.U -> (a.asSInt >= b.asSInt), 6.U -> (a < b), 7.U -> (a >= b)))
  private val base  = op && f7 === 0.U || opImm
  private val shift = (op || opImm) && f7 === 0.U
  val add  = base && f3 === 0.U || lui || auipc || csrrs && !csrMask
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
  val aZero = (op || opImm || csrrw || load || store || br || fp || amo) && instr(19, 15) === 0.U || lui
  /** Operand B is x0, or the immediate. */
  val bZero = (op || store || br || fadd || fsub || fmul || r4 || fpx || amo) && instr(24, 20) === 0.U
  val bImm  = opImm || lui || auipc
  val imm   = Mux(opImm, instr(31, 20).asSInt.pad(32).asUInt, Cat(instr(31, 12), 0.U(12.W)))
}

object RvInt {
  def apply(instr: UInt): RvInt = new RvInt(instr)

  /** The value an AMO writes, from the old word, rs2 and (AMOCAS) the compare value. */
  def amoResult(op: UInt, old: UInt, x: UInt, cmp: UInt): UInt = MuxLookup(op, x)(Seq(
    0x00.U -> (old + x), 0x04.U -> (old ^ x), 0x0C.U -> (old & x), 0x08.U -> (old | x),
    0x10.U -> Mux(old.asSInt < x.asSInt, old, x), 0x14.U -> Mux(old.asSInt < x.asSInt, x, old),
    0x18.U -> Mux(old < x, old, x), 0x1C.U -> Mux(old < x, x, old), 0x05.U -> Mux(old === cmp, x, old)))
  val Mulh = 1; val Mulhsu = 2; val Mulhu = 3; val CzeroEqz = 4; val CzeroNez = 5; val Andn = 6; val Orn = 7
  val Xnor = 8; val Min = 9; val Minu = 10; val Max = 11; val Maxu = 12; val SextB = 13; val SextH = 14
  val ZextH = 15; val Clz = 16; val Ctz = 17; val Cpop = 18; val Rol = 19; val Ror = 20; val Rev8 = 21
  val Fsgnj = 22; val Fsgnjn = 23; val Fsgnjx = 24; val Fmin = 25; val Fmax = 26; val Feq = 27; val Flt = 28
  val Fle = 29; val Fclass = 30; val FcvtWS = 31; val FcvtWUS = 32; val FcvtSW = 33; val FcvtSWU = 34

  /** IEEE binary32 (w = 32) or binary16 helpers on raw bits. */
  private class Fp(x: UInt, w: Int) {
    val e = if (w == 32) 8 else 5; val m = w - 1 - e
    val sign = x(w - 1); val exp = x(w - 2, m); val man = x(m - 1, 0)
    val nan = exp.andR && man.orR; val snan = nan && !man(m - 1); val inf = exp.andR && !man.orR
    val zero = !exp.orR && !man.orR; val sub = !exp.orR && man.orR
    val key = Mux(sign, ~x, x | (BigInt(1) << (w - 1)).U)   // orders like the value, -0 below +0
  }

  /** Round the unsigned magnitude u (nonzero) to a float of width w, sign s: RNE. */
  private def fromUnsigned(u: UInt, s: Bool, w: Int): UInt = {
    val e = if (w == 32) 8 else 5; val m = w - 1 - e; val bias = (1 << (e - 1)) - 1
    val msb = Log2(u)
    val norm = (u << ((w - 1).U - msb))(w - 1, 0)            // msb at bit w-1
    val mant = norm(w - 2, w - 1 - m); val guard = norm(w - 2 - m); val sticky = norm(w - 3 - m, 0).orR
    val up = guard && (sticky || mant(0))
    val sum = Cat(msb +& bias.U, mant) +& up                   // a carry out of mant bumps the exponent
    Mux(u === 0.U, 0.U(w.W), Cat(s, sum(w - 2, 0)))
  }

  /** Truncate to an integer, saturating; NaN gives the largest value (RISC-V). */
  private def toInt(f: Fp, w: Int, signed: Boolean): UInt = {
    val bias = (1 << (f.e - 1)) - 1
    val ue = f.exp.zext - bias.S
    val sig = Cat(1.U(1.W), f.man)
    val mag = Mux(ue < 0.S, 0.U, Mux(ue >= f.m.S, (sig << (ue - f.m.S).asUInt(4, 0))(w, 0), sig >> (f.m.S - ue).asUInt))
    val big = ue >= (if (signed) w - 1 else w).S
    if (signed) {
      val max = ((BigInt(1) << (w - 1)) - 1).U(w.W); val min = (BigInt(1) << (w - 1)).U(w.W)
      Mux(f.nan, max, Mux(big, Mux(f.sign, min, max), Mux(f.sign, (0.U - mag)(w - 1, 0), mag(w - 1, 0))))
    } else {
      Mux(f.nan || (!f.sign && big), ((BigInt(1) << w) - 1).U(w.W), Mux(f.sign, 0.U(w.W), mag(w - 1, 0)))
    }
  }

  private def fpOps(a: UInt, b: UInt, w: Int): Seq[(UInt, UInt)] = {
    val (fa, fb) = (new Fp(a, w), new Fp(b, w))
    val qnan = if (w == 32) "h7fc00000".U(w.W) else "h7e00".U(w.W)
    val bothZero = fa.zero && fb.zero; val unord = fa.nan || fb.nan
    val lt = fa.key < fb.key
    def pick(takeA: Bool) = Mux(fa.nan && fb.nan, qnan, Mux(fa.nan, b, Mux(fb.nan, a, Mux(takeA, a, b))))
    val cls = Cat(fa.nan && !fa.snan, fa.snan, !fa.sign && fa.inf, !fa.sign && !fa.exp.andR && fa.exp.orR,
                  !fa.sign && fa.sub, !fa.sign && fa.zero, fa.sign && fa.zero, fa.sign && fa.sub,
                  fa.sign && !fa.exp.andR && fa.exp.orR, fa.sign && fa.inf)
    Seq(Fsgnj.U -> Cat(b(w - 1), a(w - 2, 0)), Fsgnjn.U -> Cat(~b(w - 1), a(w - 2, 0)),
        Fsgnjx.U -> Cat(a(w - 1) ^ b(w - 1), a(w - 2, 0)),
        Fmin.U -> pick(lt), Fmax.U -> pick(!lt),
        Feq.U -> (!unord && (a === b || bothZero)), Flt.U -> (!unord && lt && !bothZero),
        Fle.U -> (!unord && (lt || a === b || bothZero)), Fclass.U -> cls,
        FcvtWS.U -> toInt(fa, w, true), FcvtWUS.U -> toInt(fa, w, false),
        FcvtSW.U -> fromUnsigned(Mux(a(w - 1), 0.U - a, a)(w - 1, 0), a(w - 1), w), FcvtSWU.U -> fromUnsigned(a, false.B, w))
      .map { case (k, v) => k -> v.asUInt.zext.asUInt.pad(w)(w - 1, 0) }
  }

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
      Rev8.U -> Cat((0 until w / 8).map(i => a(8 * i + 7, 8 * i)))) ++ fpOps(a, b, w)
    ).asUInt.pad(w)(w - 1, 0)
  }
}
