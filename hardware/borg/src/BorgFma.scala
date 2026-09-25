// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgFma — in-tree fused multiply-add for the GPU's float domain.
  *
  * Computes `round_RNE( (negate ? -(a*b) : a*b) + c )` with a SINGLE rounding,
  * round-to-nearest-even, on standard IEEE-754 (binary16 or binary32,
  * per `cfg.fp`: [[FloatConfig.FP16]] for iCE40, [[FloatConfig.FP32]] for the
  * ASIC). Renamed from `BorgFp16Fma` 2026-09-25 -- FP32 has been the default
  * since 2026-09-15 and the old name was actively misleading about what the
  * wafer.space build instantiates. The sole FMA across all targets and the
  * per-lane arithmetic core for 4-lane SIMT; [[BorgSampler]] also builds its
  * own instance for texture filtering math.
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
class BorgFmaIO(val cfg: BorgConfig) extends Bundle {
  val a      = Input(UInt(cfg.totalBits.W))
  val b      = Input(UInt(cfg.totalBits.W))
  val c      = Input(UInt(cfg.totalBits.W))
  val negate = Input(Bool())   // negate the a*b product (FNEG)
  val pipeEn1 = Input(Bool())  // regA enable (counter==4): hold during non-busy
  val pipeEn2 = Input(Bool())  // regB enable (counter==3): hold during non-busy
  val out    = Output(UInt(cfg.totalBits.W))   // registered, 3-cycle latency
}

class BorgFma(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgFmaIO(cfg))

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
  // Truncated to EW bits (low-order bits + resulting sign, same as the
  // implicit truncation this makes explicit): EW = EXP+8 already reserves 8
  // bits of deliberate headroom over FP16's ~5-bit raw exponent range for
  // exactly this kind of chained exponent arithmetic (see EW's own comment
  // above); the 1 extra bit s1_prodLowExp's type carries beyond EW is
  // Chisel's generic conservative width growth from chaining +&/-, not real
  // additional range the value needs.
  private val m_prodLowExp = RegEnable(s1_prodLowExp(EW - 1, 0).asSInt, 0.S(EW.W), io.pipeEn1)
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

  // ---- Optional stage-2 split (cfg.fmaStages >= 4), for FP32 timing ----
  // Cut point: after alignment, before the signed sum.  That puts the two F-bit
  // barrel shifts + sticky reduction on one side and the three serial carry
  // chains (negate, add, negate) on the other -- the two halves of what makes
  // this stage 58.035 ns at FP32.  See BorgConfig.fmaExtraStage for the numbers.
  //
  // reg2a is FREE-RUNNING (plain RegNext) for exactly the reason regC is: its
  // input is combinational from regMid, which holds while pipeEn1 is low, so
  // re-latching an unchanged value is harmless -- and it avoids routing another
  // high-fanout enable net.  topExp is truncated to EW here, which is what the
  // regB capture below already did, so the value is unchanged either way.
  //
  // Compile-time branch: at fmaExtraStage=false this emits the identical
  // hardware it did before the parameter existed, not merely equivalent.
  private val (x_prodExt, x_cExt, x_prodSign, x_cSign, x_topExp,
               x_specialNaN, x_specialInf, x_specialSign) =
    if (cfg.fmaStages >= 4) (
      RegNext(prodExt,       0.U(FE.W)),
      RegNext(cExt,          0.U(FE.W)),
      RegNext(m_prodSign,    false.B),
      RegNext(m_cSign,       false.B),
      RegNext(topExp,        0.S(EW.W)),
      RegNext(m_specialNaN,  false.B),
      RegNext(m_specialInf,  false.B),
      RegNext(m_specialSign, false.B)
    ) else (
      prodExt, cExt, m_prodSign, m_cSign, topExp,
      m_specialNaN, m_specialInf, m_specialSign
    )

  private val prodSigned = Mux(x_prodSign, -(x_prodExt.zext), x_prodExt.zext)
  private val cSignedV   = Mux(x_cSign,    -(x_cExt.zext),    x_cExt.zext)
  private val sumSigned  = prodSigned +& cSignedV
  private val s2_sign    = sumSigned < 0.S
  private val magW       = FE + 1
  private val s2_mag     = Mux(s2_sign, (-sumSigned).asUInt, sumSigned.asUInt)(magW - 1, 0)

  // ---- regB (pipeEn2; holds during non-busy → no X churn) ----
  // Width made explicit (s2_mag is already magW bits): Log2(magR) in stage 3
  // needs the width known at construction, and a bare 0.U init defers it.
  private val magR        = RegEnable(s2_mag,                       0.U(magW.W), io.pipeEn2)
  private val signR       = RegEnable(s2_sign,                      false.B, io.pipeEn2)
  private val topExpR     = RegEnable(x_topExp,                     0.S(EW.W), io.pipeEn2)
  private val useSpecialR = RegEnable(x_specialNaN || x_specialInf, false.B, io.pipeEn2)
  private val specNaNR    = RegEnable(x_specialNaN,                 false.B, io.pipeEn2)
  private val specSignR   = RegEnable(x_specialSign,                false.B, io.pipeEn2)

  // ==========================================================================
  // Stage 3 (combinational): normalize + round-to-nearest-even + pack
  // ==========================================================================
  private val isZeroResult = magR === 0.U
  // Position of the highest set bit.  Log2 emits a logarithmic tree; the
  // previous form -- `for (i <- 0 until magW) when(magR(i)) { idx := i.U }` --
  // is a magW-deep last-connect-wins mux cascade, which at FP16 (magW=42) was
  // harmless but at FP32 (magW=68) became the FMA's critical path.  Identical
  // semantics: both yield the index of the top set bit, and the magR===0 case
  // is handled separately by isZeroResult, so Log2's undefined-at-zero does
  // not matter.
  private val msbPos = Log2(magR)
  private val expBiased = msbPos.zext.asTypeOf(SInt(EW.W)) + topExpR - F.S + BIAS.S
  private val effExp    = Mux(expBiased < 1.S, 1.S(EW.W), expBiased)
  private val dropAmtS  = effExp - (BIAS + MANT).S + F.S - topExpR
  private val dropAmt   = Mux(dropAmtS < 0.S, 0.U, dropAmtS.asUInt)(log2Ceil(magW + 1) - 1, 0)

  // ---- Optional stage-3 split (cfg.fmaStages >= 5) ----
  // Cut point: after the priority encode and exponent arithmetic have produced
  // dropAmt, before the variable shift + guard/sticky + round.  With stages=4
  // the critical path lands here (measured: starts at magR, 36.197 ns at FP32),
  // because this half chains a magW-bit priority encoder, a magW-bit barrel
  // shift, two more variable-shift mask builds and two OR reductions.
  //
  // Free-running for the same reason as reg2a/regC: regB holds while pipeEn2
  // is low, so these inputs are stable and re-latching is harmless.  expBiased
  // is truncated to EW, exactly as the topExpR capture above already does --
  // EW = EXP+8 covers its full range (|expBiased| stays well under 2^15).
  private val (y_magR, y_dropAmt, y_expBiased, y_sign, y_isZero,
               y_useSpecial, y_specNaN, y_specSign) =
    if (cfg.fmaStages >= 5) (
      RegNext(magR,         0.U(magW.W)),
      RegNext(dropAmt,      0.U(log2Ceil(magW + 1).W)),
      RegNext(expBiased,    0.S(EW.W)),
      RegNext(signR,        false.B),
      RegNext(isZeroResult, false.B),
      RegNext(useSpecialR,  false.B),
      RegNext(specNaNR,     false.B),
      RegNext(specSignR,    false.B)
    ) else (
      magR, dropAmt, expBiased, signR, isZeroResult,
      useSpecialR, specNaNR, specSignR
    )

  private val keep      = (y_magR >> y_dropAmt)
  private val guardMask = Mux(y_dropAmt === 0.U, 0.U, (1.U << (y_dropAmt - 1.U)))(magW - 1, 0)
  private val stkMask   = Mux(y_dropAmt <= 1.U, 0.U, ((1.U << (y_dropAmt - 1.U)) - 1.U))(magW - 1, 0)
  private val guard     = (y_magR & guardMask).orR
  private val sticky    = (y_magR & stkMask).orR
  private val lsb       = keep(0)
  private val roundUp   = guard && (sticky || lsb)
  private val sigRounded = keep(MANT, 0) +& roundUp

  private val subnorm      = y_expBiased < 1.S
  private val carry        = sigRounded(SIG)
  private val becameNormal = subnorm && sigRounded(MANT)
  private val baseExp      = Mux(subnorm, 0.S(EW.W), y_expBiased)
  private val finalExpS    = Mux(carry, baseExp + 1.S, Mux(becameNormal, 1.S(EW.W), baseExp))
  private val finalFrac    = Mux(carry, 0.U(MANT.W), sigRounded(MANT - 1, 0))

  private val overflow  = finalExpS >= EXPMAX.S
  private val expField  = Mux(overflow, EXPMAX.U(EXP.W),
                          Mux(finalExpS < 0.S, 0.U(EXP.W), finalExpS.asUInt(EXP - 1, 0)))
  private val fracField = Mux(overflow, 0.U(MANT.W), finalFrac)

  private val normalOut  = Cat(y_sign, expField, fracField)
  private val zeroOut    = Cat(false.B, 0.U((EXP + MANT).W))
  private val nanOut     = Cat(false.B, EXPMAX.U(EXP.W), (1 << (MANT - 1)).U(MANT.W))
  private val infOut     = Cat(y_specSign, EXPMAX.U(EXP.W), 0.U(MANT.W))
  private val specialOut = Mux(y_specNaN, nanOut, infOut)
  private val s3_out     = Mux(y_useSpecial, specialOut, Mux(y_isZero, zeroOut, normalOut))

  // regC: register the final result so write-back + dispatcher snoop read a reg.
  io.out := RegNext(s3_out, 0.U(cfg.totalBits.W))
}
// @doc:end
