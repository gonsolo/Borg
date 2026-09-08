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
}
