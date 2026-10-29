// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FP16 <-> FP32 conversion where FP16-native units (rasterizer coordinates, texture
  * sampling, Fp16Special) meet the FP32 shader ALU.
  */
object Fp16Fp32 {
  /** FP16 -> FP32 widen, exact for zero and normals. Subnormals flush to signed zero
    * (no caller produces them); Inf/NaN stay Inf/NaN, the payload is not repositioned.
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

  /** FP32 -> FP16 narrow, round-to-nearest-even. Overflow saturates to +/-Inf, underflow
    * (including the FP16 subnormal band) flushes to signed zero; no caller's operands
    * leave the FP16 normal range. Inf/NaN pass through, not the NaN payload.
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

    // Keep the top 10 mantissa bits; guard, sticky, ties to even.
    val keep    = mant32(22, 13)
    val guard   = mant32(12)
    val sticky  = mant32(11, 0).orR
    val roundUp = guard && (sticky || keep(0))
    val mantSum = keep +& roundUp
    val mantissaCarry   = mantSum(10)
    val mantissaRounded = Mux(mantissaCarry, 0.U(10.W), mantSum(9, 0))
    // A carry bumps the exponent, which correctly reaches Inf at FP16's max exponent.
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
