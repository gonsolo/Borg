// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgFp16Fma — in-tree fused multiply-add for the GPU's FP16 domain.
  *
  * Computes `round_RNE( (negate ? -(a*b) : a*b) + c )` with a SINGLE rounding,
  * round-to-nearest-even, on standard IEEE-754 binary16.  The sole FP16 FMA across
  * all targets and the per-lane arithmetic core for 4-lane SIMT.
  *
  * Operand muxing (ADD/MUL/FMA/FNEG) stays in `BorgLane`; this unit sees the
  * resolved a/b/c + negate.  Proper IEEE Inf/NaN handling (edge functions overflow
  * FP16 to ±Inf).  Verified bit-identical to HardFloat (30k RTL co-sim + verilator
  * goldens at fragLanes 1 and 4).
  *
  * FOUR-STAGE pipeline (THREE internal registers) to close 25 MHz timing on the
  * 4-lane ULX3S build (the single-register version capped the SoC clock at ~19 MHz —
  * its mantissa-multiply→align→sum cone was the whole-design critical path).  Operands
  * are valid at busy_counter==4 (regfile and uniform reads both complete then); the
  * result is consumed by write-back at busy_counter==1.
  *   - Stage 1 (→ regA @ pipeEn1, counter==4): unpack + 11×11 multiply + exponents
  *     + signs + Inf/NaN classification.
  *   - Stage 2 (→ regB @ pipeEn2, counter==3): top-exponent + alignment + signed sum.
  *   - Stage 3 (→ regC, FREE-RUNNING): normalize + round + pack.
  *   - `io.out` IS regC — registered, so write-back AND the dispatcher's edge/frag
  *     snoop at counter==1 read a register (short path), not the round cone.
  * After the split the critical path is the balanced stage-2 align+sum (26.14 MHz,
  * PASS at 25).  regC is FREE-RUNNING (plain RegNext): its input stage3(regB) is
  * stable because regB holds during non-busy, so it re-latches the same value while
  * dropping the high-fanout pipeEn3 enable net to the 16-bit output register.
  * Same arithmetic as the single-register version — purely register placement.
  *
  * Renders correctly in chisel-sim, verilator (N=1 and N=4 goldens), arcilator, and
  * on ULX3S hardware.  (The deeper pipeline grows BorgLane past arcilator's --inline
  * threshold, so the per-lane rcp LUT moves from core.rcpLutA_ext to
  * core.lanes_0.rcpLutA_ext — ArcBorgSimulator.cpp load_luts() pokes the new path.)
  *
  * @doc:custom-fma
  */
class BorgFp16FmaIO(val cfg: BorgConfig) extends Bundle {
  val a      = Input(UInt(cfg.totalBits.W))
  val b      = Input(UInt(cfg.totalBits.W))
  val c      = Input(UInt(cfg.totalBits.W))
  val negate = Input(Bool())   // negate the a*b product (FNEG)
  val pipeEn1 = Input(Bool())  // regA enable (counter==4): hold during non-busy
  val pipeEn2 = Input(Bool())  // regB enable (counter==3): hold during non-busy
  val out    = Output(UInt(cfg.totalBits.W))   // registered, 3-cycle latency
}

class BorgFp16Fma(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgFp16FmaIO(cfg))

  private val EXP    = cfg.exp                 // 5
  private val SIG    = cfg.sig                 // 11 (10 stored frac + implicit 1)
  private val MANT   = SIG - 1                 // 10
  private val BIAS   = (1 << (EXP - 1)) - 1    // 15
  private val EXPMAX = (1 << EXP) - 1          // 31 (Inf/NaN exponent field)

  private val F  = 2 * SIG + 18                // 40 for FP16 (alignment field)
  private val FE = F + 1                       // + appended per-operand sticky bit
  private val EW = EXP + 8                     // signed exponent arithmetic width

  // ---- Unpack: value = sig * 2^(eVal - MANT) ----
  private case class Unpacked(sign: Bool, sig: UInt, eVal: SInt, isZero: Bool, isInf: Bool, isNaN: Bool)
  private def unpack(x: UInt): Unpacked = {
    val expF = x(EXP + MANT - 1, MANT)
    val frac = x(MANT - 1, 0)
    val impl = (expF =/= 0.U)
    val eVal = Mux(expF === 0.U, 1.S(EW.W), expF.zext.asTypeOf(SInt(EW.W))) - BIAS.S(EW.W)
    Unpacked(
      sign   = x(EXP + MANT),
      sig    = Cat(impl, frac),
      eVal   = eVal,
      isZero = (expF === 0.U) && (frac === 0.U),
      isInf  = (expF === EXPMAX.U) && (frac === 0.U),
      isNaN  = (expF === EXPMAX.U) && (frac =/= 0.U)
    )
  }

  // ==========================================================================
  // Stage 1 (combinational): unpack + multiply + exponents + Inf/NaN class
  // ==========================================================================
  private val ua = unpack(io.a)
  private val ub = unpack(io.b)
  private val uc = unpack(io.c)

  private val s1_prodSign   = ua.sign ^ ub.sign ^ io.negate
  private val s1_prodZero   = ua.isZero || ub.isZero
  private val s1_prodSig    = ua.sig * ub.sig                       // 2*SIG bits
  private val s1_prodLowExp = (ua.eVal +& ub.eVal) - (2 * MANT).S
  private val s1_cSign      = uc.sign
  private val s1_cZero      = uc.isZero
  private val s1_cSig       = uc.sig
  private val s1_cLowExp    = uc.eVal - MANT.S

  // IEEE special-case classification (matches HardFloat over the domain).
  private val anyNaN       = ua.isNaN || ub.isNaN || uc.isNaN
  private val infTimesZero = (ua.isInf && ub.isZero) || (ua.isZero && ub.isInf)
  private val prodInf      = (ua.isInf || ub.isInf) && !infTimesZero
  private val cInf         = uc.isInf
  private val infMinusInf  = prodInf && cInf && (s1_prodSign =/= s1_cSign)
  private val s1_specialNaN   = anyNaN || infTimesZero || infMinusInf
  private val s1_specialInf   = !s1_specialNaN && (prodInf || cInf)
  private val s1_specialSign  = Mux(prodInf, s1_prodSign, s1_cSign)

  // ---- regMid (pipeEn1; holds during non-busy → no X churn) ----
  // Reset-initialised: arcilator starts resetless regs as X and propagates it into
  // a hang; verilator/chisel-sim tolerate the X.  Inits are functionally neutral
  // (operands are captured before the result is ever consumed).
  private val m_prodSig    = RegEnable(s1_prodSig,    0.U, io.pipeEn1)
  private val m_cSig       = RegEnable(s1_cSig,       0.U, io.pipeEn1)
  private val m_prodSign   = RegEnable(s1_prodSign,   false.B, io.pipeEn1)
  private val m_cSign      = RegEnable(s1_cSign,      false.B, io.pipeEn1)
  private val m_prodZero   = RegEnable(s1_prodZero,   false.B, io.pipeEn1)
  private val m_cZero      = RegEnable(s1_cZero,      false.B, io.pipeEn1)
  private val m_prodLowExp = RegEnable(s1_prodLowExp, 0.S(EW.W), io.pipeEn1)
  private val m_cLowExp    = RegEnable(s1_cLowExp,    0.S(EW.W), io.pipeEn1)
  private val m_specialNaN = RegEnable(s1_specialNaN, false.B, io.pipeEn1)
  private val m_specialInf = RegEnable(s1_specialInf, false.B, io.pipeEn1)
  private val m_specialSign= RegEnable(s1_specialSign,false.B, io.pipeEn1)

  // ==========================================================================
  // Stage 2 (combinational from regMid): align + signed sum
  // ==========================================================================
  private val MINEXP  = (-128).S(EW.W)
  private val prodTop = Mux(m_prodZero, MINEXP, m_prodLowExp + (2 * SIG - 1).S)
  private val cTop    = Mux(m_cZero,    MINEXP, m_cLowExp + (SIG - 1).S)
  private val topExp  = Mux(prodTop > cTop, prodTop, cTop) + 2.S

  private def place(sig: UInt, sigBits: Int, downS: SInt): (UInt, Bool) = {
    val topAligned = (sig << (F - sigBits)).asUInt
    val downClamped = Mux(downS < 0.S, 0.U, Mux(downS > F.S, F.U, downS.asUInt))
    val down = downClamped(log2Ceil(F + 1) - 1, 0)
    val shifted = (topAligned >> down)(F - 1, 0)
    val mask = ((1.U << down) - 1.U)(F - 1, 0)
    val sticky = (topAligned & mask).orR
    (shifted, sticky)
  }

  private val prodDown = topExp - (m_prodLowExp + (2 * SIG - 1).S)
  private val cDown    = topExp - (m_cLowExp + (SIG - 1).S)
  private val (prodFieldRaw, prodStk) = place(m_prodSig, 2 * SIG, prodDown)
  private val (cFieldRaw, cStk)        = place(m_cSig, SIG, cDown)
  private val prodField = Mux(m_prodZero, 0.U(F.W), prodFieldRaw)
  private val cField    = Mux(m_cZero,    0.U(F.W), cFieldRaw)
  private val prodExt   = Cat(prodField, Mux(m_prodZero, false.B, prodStk))
  private val cExt      = Cat(cField,    Mux(m_cZero,    false.B, cStk))

  private val prodSigned = Mux(m_prodSign, -(prodExt.zext), prodExt.zext)
  private val cSignedV   = Mux(m_cSign,    -(cExt.zext),    cExt.zext)
  private val sumSigned  = prodSigned +& cSignedV
  private val s2_sign    = sumSigned < 0.S
  private val magW       = FE + 1
  private val s2_mag     = Mux(s2_sign, (-sumSigned).asUInt, sumSigned.asUInt)(magW - 1, 0)

  // ---- regB (pipeEn2; holds during non-busy → no X churn) ----
  private val magR        = RegEnable(s2_mag,                       0.U, io.pipeEn2)
  private val signR       = RegEnable(s2_sign,                      false.B, io.pipeEn2)
  private val topExpR     = RegEnable(topExp,                       0.S(EW.W), io.pipeEn2)
  private val useSpecialR = RegEnable(m_specialNaN || m_specialInf, false.B, io.pipeEn2)
  private val specNaNR    = RegEnable(m_specialNaN,                 false.B, io.pipeEn2)
  private val specSignR   = RegEnable(m_specialSign,                false.B, io.pipeEn2)

  // ==========================================================================
  // Stage 3 (combinational): normalize + round-to-nearest-even + pack
  // ==========================================================================
  private val isZeroResult = magR === 0.U
  private val msbPos = {
    val idx = WireDefault(0.U(log2Ceil(magW).W))
    for (i <- 0 until magW) { when(magR(i)) { idx := i.U } }
    idx
  }
  private val expBiased = msbPos.zext.asTypeOf(SInt(EW.W)) + topExpR - F.S + BIAS.S
  private val effExp    = Mux(expBiased < 1.S, 1.S(EW.W), expBiased)
  private val dropAmtS  = effExp - (BIAS + MANT).S + F.S - topExpR
  private val dropAmt   = Mux(dropAmtS < 0.S, 0.U, dropAmtS.asUInt)(log2Ceil(magW + 1) - 1, 0)

  private val keep      = (magR >> dropAmt)
  private val guardMask = Mux(dropAmt === 0.U, 0.U, (1.U << (dropAmt - 1.U)))(magW - 1, 0)
  private val stkMask   = Mux(dropAmt <= 1.U, 0.U, ((1.U << (dropAmt - 1.U)) - 1.U))(magW - 1, 0)
  private val guard     = (magR & guardMask).orR
  private val sticky    = (magR & stkMask).orR
  private val lsb       = keep(0)
  private val roundUp   = guard && (sticky || lsb)
  private val sigRounded = keep(MANT, 0) +& roundUp

  private val subnorm      = expBiased < 1.S
  private val carry        = sigRounded(SIG)
  private val becameNormal = subnorm && sigRounded(MANT)
  private val baseExp      = Mux(subnorm, 0.S(EW.W), expBiased)
  private val finalExpS    = Mux(carry, baseExp + 1.S, Mux(becameNormal, 1.S(EW.W), baseExp))
  private val finalFrac    = Mux(carry, 0.U(MANT.W), sigRounded(MANT - 1, 0))

  private val overflow  = finalExpS >= EXPMAX.S
  private val expField  = Mux(overflow, EXPMAX.U(EXP.W),
                          Mux(finalExpS < 0.S, 0.U(EXP.W), finalExpS.asUInt(EXP - 1, 0)))
  private val fracField = Mux(overflow, 0.U(MANT.W), finalFrac)

  private val normalOut  = Cat(signR, expField, fracField)
  private val zeroOut    = Cat(false.B, 0.U((EXP + MANT).W))
  private val nanOut     = Cat(false.B, EXPMAX.U(EXP.W), (1 << (MANT - 1)).U(MANT.W))
  private val infOut     = Cat(specSignR, EXPMAX.U(EXP.W), 0.U(MANT.W))
  private val specialOut = Mux(specNaNR, nanOut, infOut)
  private val s3_out     = Mux(useSpecialR, specialOut, Mux(isZeroResult, zeroOut, normalOut))

  // regC: register the final result so write-back + dispatcher snoop read a reg.
  io.out := RegNext(s3_out, 0.U(cfg.totalBits.W))
}
// @doc:end
