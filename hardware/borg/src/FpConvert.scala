// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FP16 <-> FP32 boundary conversions for the FP32 shader-ALU datapath
  * (`feat/fp32-datapath`, see the branch plan doc). The general compute
  * path (FMA, register file, uniform bank) runs FP32; texture sampling,
  * the reciprocal/rsqrt/sRGB LUTs (`Fp16Special`), and the tile-buffer
  * color format all stay FP16-native. These two units are the explicit
  * conversion at those boundaries — new hardware, not a byte reinterpret.
  *
  * Both follow the same unpack/normalize/pack shape as `BorgFp16Fma`'s
  * internal stages, just for a single operand instead of a fused a*b+c.
  */
private object FpConvertConsts {
  val EXP16 = 5;  val MANT16 = 10; val BIAS16 = 15;  val EXPMAX16 = 31
  val EXP32 = 8;  val MANT32 = 23; val BIAS32 = 127; val EXPMAX32 = 255
  val SIG32 = MANT32 + 1 // 24: implicit bit + stored mantissa
  val EW    = 16          // signed exponent-arithmetic width; generous headroom
}

/** Fp16ToFp32 — exact (lossless) widen. Re-biases the exponent and
  * zero-pads the mantissa; FP16 subnormals are renormalized into FP32
  * normals (FP16's subnormal range sits entirely above FP32's normal
  * floor, so no FP32 subnormal output is ever needed). NaNs are
  * canonicalized to a fixed quiet-NaN pattern, matching `BorgFp16Fma`'s
  * convention — payloads are not tracked anywhere else in the design.
  */
class Fp16ToFp32IO extends Bundle {
  val in  = Input(UInt(16.W))
  val out = Output(UInt(32.W))
}

class Fp16ToFp32 extends Module {
  import FpConvertConsts._

  val io = IO(new Fp16ToFp32IO)

  private val sign = io.in(15)
  private val expF = io.in(14, 10)
  private val frac = io.in(9, 0)

  private val isZero      = (expF === 0.U) && (frac === 0.U)
  private val isSubnormal = (expF === 0.U) && (frac =/= 0.U)
  private val isInf       = (expF === EXPMAX16.U) && (frac === 0.U)
  private val isNaN       = (expF === EXPMAX16.U) && (frac =/= 0.U)

  // Normalize a subnormal: find the leading 1 in `frac` and how far left it
  // must shift to become the (now explicit-in-FP32) implicit bit.
  private val msbPos = Log2(frac)                         // valid when frac =/= 0
  private val shift  = MANT16.U - msbPos
  private val subShifted = (Cat(0.U(1.W), frac) << shift)(MANT16, 0)

  private val eNormal    = expF.zext.asTypeOf(SInt(EW.W)) - BIAS16.S(EW.W)
  private val eSubnormal = (1 - BIAS16).S(EW.W) - shift.zext.asTypeOf(SInt(EW.W))
  private val eVal       = Mux(isSubnormal, eSubnormal, eNormal)

  private val fracOut10   = Mux(isSubnormal, subShifted(MANT16 - 1, 0), frac)
  private val fracField32 = Cat(fracOut10, 0.U((MANT32 - MANT16).W))
  private val expField32  = (eVal + BIAS32.S(EW.W)).asUInt(EXP32 - 1, 0)

  private val normalOut = Cat(sign, expField32, fracField32)
  private val zeroOut   = Cat(sign, 0.U((EXP32 + MANT32).W))
  private val infOut    = Cat(sign, EXPMAX32.U(EXP32.W), 0.U(MANT32.W))
  private val nanOut    = Cat(false.B, EXPMAX32.U(EXP32.W), (1 << (MANT32 - 1)).U(MANT32.W))

  io.out := Mux(isNaN, nanOut, Mux(isInf, infOut, Mux(isZero, zeroOut, normalOut)))
}

/** Fp32ToFp16 — narrow with round-to-nearest-even, the tile-buffer write
  * boundary. Values outside FP16's range saturate to +-Inf (overflow) or
  * flush to zero / round into an FP16 subnormal (underflow) rather than
  * wrapping. NaNs canonicalize the same way `Fp16ToFp32` does.
  */
class Fp32ToFp16IO extends Bundle {
  val in  = Input(UInt(32.W))
  val out = Output(UInt(16.W))
}

class Fp32ToFp16 extends Module {
  import FpConvertConsts._

  val io = IO(new Fp32ToFp16IO)

  private val sign = io.in(31)
  private val expF = io.in(30, 23)
  private val frac = io.in(22, 0)
  private val impl = expF =/= 0.U
  private val sig  = Cat(impl, frac)                        // 24 bits (SIG32)
  private val eVal = Mux(expF === 0.U, 1.S(EW.W), expF.zext.asTypeOf(SInt(EW.W))) - BIAS32.S(EW.W)

  private val isZero = (expF === 0.U) && (frac === 0.U)
  private val isInf  = (expF === EXPMAX32.U) && (frac === 0.U)
  private val isNaN  = (expF === EXPMAX32.U) && (frac =/= 0.U)

  // ---- normalize + round-to-nearest-even + pack into FP16 ----
  // Same construction as BorgFp16Fma's stage 3 (msbPos -> biased exponent
  // -> dropAmt -> guard/sticky/round), specialized to a single already-
  // unpacked operand instead of an alignment-stage sum.
  private val msbPos     = Log2(sig)                        // valid when sig =/= 0
  private val expBiased  = msbPos.zext.asTypeOf(SInt(EW.W)) + eVal - MANT32.S(EW.W) + BIAS16.S(EW.W)
  private val effExp     = Mux(expBiased < 1.S, 1.S(EW.W), expBiased)
  private val dropAmtS   = effExp - (BIAS16 + MANT16).S(EW.W) + MANT32.S(EW.W) - eVal
  // Clamp *before* narrowing to the field's width. A deeply underflowing
  // input (e.g. 1e-10f) drives dropAmtS well past SIG32 (up to ~125) --
  // truncating that straight to a 5-bit field first wraps it (33 becomes 1),
  // producing a bogus nonzero subnormal instead of the correct zero.
  //
  // The clamp ceiling has to be SIG32+1, not SIG32: guardMask/stkMask are
  // pre-truncated to SIG32 bits, so a guard-bit position of exactly SIG32-1
  // (i.e. dropAmt == SIG32) lands on sig's own top bit -- which is the
  // implicit 1 for any normal input, always set -- giving a false "exactly
  // at the rounding boundary" reading no matter how far underflowed the
  // value actually is. Only at dropAmt >= SIG32+1 does the guard-bit
  // position fall strictly outside sig's real bits and read 0, which is the
  // correct answer for anything clamped this far. (Caught by the
  // widen/narrow test's 1e-10f case, which first regressed to 0x380 -- a
  // silent wraparound past the field width -- then to 0x1 -- this
  // off-by-one -- before landing here.)
  private val DROP_CLAMP = SIG32 + 1
  private val dropAmtSat = Mux(dropAmtS < 0.S, 0.S(EW.W), Mux(dropAmtS > DROP_CLAMP.S(EW.W), DROP_CLAMP.S(EW.W), dropAmtS))
  private val dropAmt    = dropAmtSat.asUInt(log2Ceil(SIG32 + 1) - 1, 0)

  private val keep       = sig >> dropAmt
  private val guardMask  = Mux(dropAmt === 0.U, 0.U, (1.U << (dropAmt - 1.U)))(SIG32 - 1, 0)
  private val stkMask    = Mux(dropAmt <= 1.U, 0.U, ((1.U << (dropAmt - 1.U)) - 1.U))(SIG32 - 1, 0)
  private val guard      = (sig & guardMask).orR
  private val sticky     = (sig & stkMask).orR
  private val lsb        = keep(0)
  private val roundUp    = guard && (sticky || lsb)
  private val sigRounded = keep(MANT16, 0) +& roundUp        // MANT16+2 bits, top bit is carry-out

  private val subnorm      = expBiased < 1.S
  private val carry        = sigRounded(MANT16 + 1)
  private val becameNormal = subnorm && sigRounded(MANT16)
  private val baseExp      = Mux(subnorm, 0.S(EW.W), effExp)
  private val finalExpS    = Mux(carry, baseExp + 1.S, Mux(becameNormal, 1.S(EW.W), baseExp))
  private val finalFrac    = Mux(carry, 0.U(MANT16.W), sigRounded(MANT16 - 1, 0))

  private val overflow  = finalExpS >= EXPMAX16.S
  private val expField  = Mux(overflow, EXPMAX16.U(EXP16.W),
                          Mux(finalExpS < 0.S, 0.U(EXP16.W), finalExpS.asUInt(EXP16 - 1, 0)))
  private val fracField = Mux(overflow, 0.U(MANT16.W), finalFrac)

  private val normalOut = Cat(sign, expField, fracField)
  private val zeroOut   = Cat(sign, 0.U((EXP16 + MANT16).W))
  private val infOut    = Cat(sign, EXPMAX16.U(EXP16.W), 0.U(MANT16.W))
  private val nanOut    = Cat(false.B, EXPMAX16.U(EXP16.W), (1 << (MANT16 - 1)).U(MANT16.W))

  io.out := Mux(isNaN, nanOut, Mux(isInf || overflow, infOut, Mux(isZero, zeroOut, normalOut)))
}
