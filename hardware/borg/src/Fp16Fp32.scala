// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FP16 <-> FP32 boundary conversion, for the spots that deliberately stay
  * FP16-native even when cfg.fp = FloatConfig.FP32 (rasterizer coordinate
  * generation, texture sampling, Fp16Special) but need to hand a value to or
  * from the general (now FP32) shader ALU. See the feat/fp32-datapath
  * branch's plan doc for the boundary list.
  */
object Fp16Fp32 {
  /** FP16 -> FP32 widen. Exact for zero and normal FP16 inputs -- the only
    * values any current caller (BorgLane's pixelToFP16Half pixel-center
    * generation; a future texture-sample-output widen) ever produces.
    * Subnormal FP16 inputs flush to a correctly-signed zero rather than
    * being renormalized -- a common, defensible GPU simplification, not
    * exercised by any current call site (pixel coordinates and texel
    * colours never produce FP16 subnormals in this pipeline). Inf/NaN pass
    * through as FP32 Inf/NaN (exponent all-ones); NaN-ness is preserved but
    * the payload is zero-extended rather than IEEE-repositioned.
    */
  def widen(h: UInt): UInt = {
    require(h.getWidth == 16, s"Fp16Fp32.widen expects a 16-bit FP16 pattern, got ${h.getWidth}")
    val sign   = h(15)
    val exp16  = h(14, 10)
    val mant16 = h(9, 0)
    val isZeroOrSubnormal = exp16 === 0.U
    val isInfNan          = exp16 === 31.U
    val exp32 = MuxCase(exp16 +& 112.U, Seq(   // 112 = 127 (FP32 bias) - 15 (FP16 bias)
      isZeroOrSubnormal -> 0.U,
      isInfNan          -> 255.U
    ))
    val mant32 = Mux(isZeroOrSubnormal, 0.U(23.W), Cat(mant16, 0.U(13.W)))
    Cat(sign, exp32(7, 0), mant32)
  }

  /** FP32 -> FP16 narrow, round-to-nearest-even. Used at boundaries where a
    * value computed on the general (FP32) ALU needs to become a real FP16
    * value for FP16-native hardware to consume (e.g. Fp16Special's rcp/
    * rsqrt/sRGB input). Correctly rounds the normal range (guard/sticky/
    * round-to-even on the dropped 13 mantissa bits, matching this
    * codebase's existing f2i idiom for the exponent unbias: `expF.zext -
    * bias.S`), saturates overflow (exponent too large for FP16) to +/-Inf,
    * and flushes underflow -- including FP16's own subnormal band -- to a
    * correctly-signed zero. The flush-to-zero choice mirrors widen()'s
    * simplification above, for the same reason: not exercised by any
    * current caller's real value range (reciprocal/rsqrt/sRGB operands,
    * pixel coordinates, and colours all stay well inside FP16's normal
    * range). Inf/NaN pass through; NaN-ness is preserved, not the payload.
    */
  def narrow(f: UInt): UInt = {
    require(f.getWidth == 32, s"Fp16Fp32.narrow expects a 32-bit FP32 pattern, got ${f.getWidth}")
    val sign   = f(31)
    val exp32  = f(30, 23)
    val mant32 = f(22, 0)

    val isZero   = exp32 === 0.U
    val isInfNan = exp32 === 255.U

    val eUnbiased = exp32.zext - 127.S
    val underflow = eUnbiased < (-14).S
    val overflow  = eUnbiased > 15.S
    val exp16Normal = (eUnbiased + 15.S).asUInt(4, 0)

    // Round-to-nearest-even: keep the top 10 bits of the 23-bit mantissa;
    // guard = first dropped bit, sticky = OR of the rest, tie-to-even on
    // the kept LSB.
    val keep    = mant32(22, 13)
    val guard   = mant32(12)
    val sticky  = mant32(11, 0).orR
    val roundUp = guard && (sticky || keep(0))
    val mantSum = keep +& roundUp
    val mantissaCarry   = mantSum(10)
    val mantissaRounded = Mux(mantissaCarry, 0.U(10.W), mantSum(9, 0))
    // A carry here means the mantissa rounded up to 1.0 -- bumping the
    // exponent by one correctly reaches Inf's bit pattern (exp=31,
    // mant=0) in the one case that lands exactly on FP16's max exponent.
    val exp16Rounded = (exp16Normal +& mantissaCarry.asUInt)(4, 0)

    val exp16 = MuxCase(exp16Rounded, Seq(
      isZero    -> 0.U,
      isInfNan  -> 31.U,
      underflow -> 0.U,
      overflow  -> 31.U
    ))
    val mant16 = MuxCase(mantissaRounded, Seq(
      isZero    -> 0.U,
      isInfNan  -> Mux(mant32 =/= 0.U, 1.U(10.W), 0.U(10.W)),
      underflow -> 0.U,
      overflow  -> 0.U
    ))
    Cat(sign, exp16, mant16)
  }
}
