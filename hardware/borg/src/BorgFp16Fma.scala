// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgFp16Fma — in-tree fused multiply-add for the GPU's FP16 domain.
  *
  * Computes `round_RNE( (negate ? -(a*b) : a*b) + c )` with a SINGLE rounding
  * (true fused multiply-add), round-to-nearest-even, on standard IEEE-754
  * binary16 values. Drop-in replacement for the Berkeley HardFloat `MulAddRecFN`
  * path in [[BorgCore.wireFma]] (selected by `cfg.useCustomFma`) and the per-lane
  * arithmetic core for 4-lane SIMT.
  *
  * Operand muxing (ADD/MUL/FMA/FNEG) stays in `BorgCore`; this unit sees the
  * resolved `a`, `b`, `c` and a `negate` flag:
  *   ADD: a=1.0,b=rs1,c=rs2 · MUL: a=rs1,b=rs2,c=0 · FMA: a=rs1,b=rs2,c=rs3 ·
  *   FNEG: a=1.0,b=rs1,c=0,negate.
  *
  * Pipelining: split into two combinational halves with a register (`pipeEn`)
  * after the mantissa multiply + alignment and before normalize/round — the same
  * critical-path split as HardFloat's `preMul → [reg] → postMul → round`, so the
  * core's 4-cycle FMA timing is unchanged.
  *
  * Verified bit-identical to HardFloat over the GPU domain: 30k-case RTL co-sim
  * (EphemeralSimulator) + 400k-case standalone arcilator co-sim + a verilator
  * golden render.  IEEE Inf/NaN inputs are handled (edge functions overflow FP16
  * to ±Inf), so proper Inf arithmetic — not a blanket NaN — is produced.
  *
  * NOTE (arcilator): the full SoC render works in verilator and synthesises
  * correctly, but arcilator's CIRCT backend mis-schedules the custom-FMA design
  * shape (the failure is invariant to this module's logic/value/structure — even
  * a constant or passthrough fails identically — and is absent with HardFloat).
  * Use verilator for custom-FMA simulation until the arcilator issue is resolved.
  *
  * @doc:custom-fma
  */
class BorgFp16FmaIO(val cfg: BorgConfig) extends Bundle {
  val a      = Input(UInt(cfg.totalBits.W))
  val b      = Input(UInt(cfg.totalBits.W))
  val c      = Input(UInt(cfg.totalBits.W))
  val negate = Input(Bool())   // negate the a*b product (FNEG)
  val pipeEn = Input(Bool())   // stage1 → stage2 pipeline register enable
  val out    = Output(UInt(cfg.totalBits.W))
}

class BorgFp16Fma(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgFp16FmaIO(cfg))

  private val EXP    = cfg.exp                 // 5
  private val SIG    = cfg.sig                 // 11 (10 stored frac + implicit 1)
  private val MANT   = SIG - 1                 // 10
  private val BIAS   = (1 << (EXP - 1)) - 1    // 15
  private val EXPMAX = (1 << EXP) - 1          // 31 (Inf/NaN exponent field)

  // Fixed-point alignment field: product significand (2*SIG) + headroom for the
  // addend's tail before bits collapse into a sticky.  Generous; area is cheap.
  private val F  = 2 * SIG + 18                // 40 for FP16
  private val FE = F + 1                       // field + appended per-operand sticky
  private val EW = EXP + 8                     // signed exponent arithmetic width

  // ---- Unpack: value = sig * 2^(eVal - MANT), sig = SIG-bit (impl bit + frac) ----
  private case class Unpacked(sign: Bool, sig: UInt, eVal: SInt, isZero: Bool, isInf: Bool, isNaN: Bool)
  private def unpack(x: UInt): Unpacked = {
    val expF = x(EXP + MANT - 1, MANT)
    val frac = x(MANT - 1, 0)
    val impl = (expF =/= 0.U)
    val eVal = Mux(expF === 0.U, 1.S(EW.W), expF.zext.asTypeOf(SInt(EW.W))) - BIAS.S(EW.W)
    Unpacked(
      sign   = x(EXP + MANT),
      sig    = Cat(impl, frac),                // SIG bits
      eVal   = eVal,
      isZero = (expF === 0.U) && (frac === 0.U),
      isInf  = (expF === EXPMAX.U) && (frac === 0.U),
      isNaN  = (expF === EXPMAX.U) && (frac =/= 0.U)
    )
  }

  private val ua = unpack(io.a)
  private val ub = unpack(io.b)
  private val uc = unpack(io.c)

  // ---- Product (exact) and addend, in fixed-point at 2^(lowExp) ----
  private val prodSign   = ua.sign ^ ub.sign ^ io.negate
  private val prodZero   = ua.isZero || ub.isZero
  private val prodSig    = ua.sig * ub.sig                       // 2*SIG bits
  private val prodLowExp = (ua.eVal +& ub.eVal) - (2 * MANT).S
  private val cSign      = uc.sign
  private val cZero      = uc.isZero
  private val cSig       = uc.sig
  private val cLowExp    = uc.eVal - MANT.S

  // ---- Common top exponent: shift both operands DOWN into the field from here ----
  private val MINEXP  = (-128).S(EW.W)
  private val prodTop = Mux(prodZero, MINEXP, prodLowExp + (2 * SIG - 1).S)
  private val cTop    = Mux(cZero,    MINEXP, cLowExp + (SIG - 1).S)
  private val topExp  = Mux(prodTop > cTop, prodTop, cTop) + 2.S

  // place `sig` (sigBits wide) left-aligned to the top of an F-bit field, then
  // shift down by `down` (>=0); returns (field, sticky-of-dropped-low-bits).
  private def place(sig: UInt, sigBits: Int, downS: SInt): (UInt, Bool) = {
    val topAligned = (sig << (F - sigBits)).asUInt
    // Clamp shift to [0,F] AND to a narrow width — an un-narrowed `1.U << down`
    // infers an ~8192-bit intermediate (mis-lowered + synthesises terribly).
    val downClamped = Mux(downS < 0.S, 0.U, Mux(downS > F.S, F.U, downS.asUInt))
    val down = downClamped(log2Ceil(F + 1) - 1, 0)               // 6 bits for F=40
    val shifted = (topAligned >> down)(F - 1, 0)
    val mask = ((1.U << down) - 1.U)(F - 1, 0)                   // 1.U << 6-bit → 64-bit
    val sticky = (topAligned & mask).orR
    (shifted, sticky)
  }

  private val prodDown = topExp - (prodLowExp + (2 * SIG - 1).S)
  private val cDown    = topExp - (cLowExp + (SIG - 1).S)
  private val (prodFieldRaw, prodStk) = place(prodSig, 2 * SIG, prodDown)
  private val (cFieldRaw, cStk)        = place(cSig, SIG, cDown)
  private val prodField = Mux(prodZero, 0.U(F.W), prodFieldRaw)
  private val cField    = Mux(cZero,    0.U(F.W), cFieldRaw)
  private val prodExt   = Cat(prodField, Mux(prodZero, false.B, prodStk))   // FE bits
  private val cExt      = Cat(cField,    Mux(cZero,    false.B, cStk))       // FE bits

  // ---- Signed sum (appended sticky bit makes subtraction borrow correctly) ----
  private val prodSigned = Mux(prodSign, -(prodExt.zext), prodExt.zext)
  private val cSignedV   = Mux(cSign,    -(cExt.zext),    cExt.zext)
  private val sumSigned  = prodSigned +& cSignedV
  private val resultSign = sumSigned < 0.S
  private val magW       = FE + 1
  private val mag        = Mux(resultSign, (-sumSigned).asUInt, sumSigned.asUInt)(magW - 1, 0)

  // ---- IEEE special-case results (Inf/NaN), matching HardFloat ----
  private val anyNaN       = ua.isNaN || ub.isNaN || uc.isNaN
  private val infTimesZero = (ua.isInf && ub.isZero) || (ua.isZero && ub.isInf)
  private val prodInf      = (ua.isInf || ub.isInf) && !infTimesZero
  private val cInf         = uc.isInf
  private val infMinusInf  = prodInf && cInf && (prodSign =/= cSign)
  private val specialNaN   = anyNaN || infTimesZero || infMinusInf
  private val specialInf   = !specialNaN && (prodInf || cInf)
  private val specialInfSign = Mux(prodInf, prodSign, cSign)
  private val useSpecial   = specialNaN || specialInf

  // ---- Pipeline register (matches busy_counter==2 split) ----
  private val magR        = RegEnable(mag,            io.pipeEn)
  private val signR       = RegEnable(resultSign,     io.pipeEn)
  private val topExpR     = RegEnable(topExp,         io.pipeEn)
  private val useSpecialR = RegEnable(useSpecial,     io.pipeEn)
  private val specNaNR    = RegEnable(specialNaN,     io.pipeEn)
  private val specSignR   = RegEnable(specialInfSign, io.pipeEn)

  // ---- Stage 2: normalize + round-to-nearest-even + pack ----
  private val isZeroResult = magR === 0.U
  // Highest set bit (0-based) via explicit priority chain (arcilator-robust).
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
  private val zeroOut    = Cat(false.B, 0.U((EXP + MANT).W))                          // +0
  private val nanOut     = Cat(false.B, EXPMAX.U(EXP.W), (1 << (MANT - 1)).U(MANT.W)) // qNaN
  private val infOut     = Cat(specSignR, EXPMAX.U(EXP.W), 0.U(MANT.W))               // ±Inf
  private val specialOut = Mux(specNaNR, nanOut, infOut)

  io.out := Mux(useSpecialR, specialOut, Mux(isZeroResult, zeroOut, normalOut))
}
// @doc:end
