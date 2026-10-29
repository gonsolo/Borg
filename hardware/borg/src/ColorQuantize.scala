// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FP16 <-> UNORM8 colour quantization: shifts and a subtract, no multiplier. Values outside
  * [0,1] (including -0.0 and NaN) clamp rather than wrap. */
object ColorQuantize {

  /** FP16 -> UNORM8, exactly round(value * 255), with SIG = 1024 + mant:
    *
    *   value * 255 = SIG * 255 * 2^(exp-25) = (SIG*256 - SIG) >> (25-exp)
    *
    * Exact inverse of dequantize8, so a colour the tile buffer rewrites does not drift.
    */
  def quantize8(fp16: UInt): UInt = {
    require(fp16.getWidth == 16)
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val sig  = Cat(1.U(1.W), mant)

    val isZeroOrSubnormal = exp === 0.U

    // N = 25-exp in [11,24] for the exp range left after the clamps; rounds to nearest.
    val sig255 = (sig << 8) - sig
    val n = (25.U(5.W) - exp)(4, 0)
    val roundBias = (1.U(24.W) << (n - 1.U))(23, 0)
    val rounded = ((sig255 +& roundBias) >> n)(8, 0)  // may carry to 256

    val normalResult = Mux(rounded > 255.U, 255.U(8.W), rounded(7, 0))

    MuxCase(normalResult, Seq(
      (sign)                 -> 0.U(8.W),
      (isZeroOrSubnormal)    -> 0.U(8.W),
      (!sign && exp >= 15.U) -> 255.U(8.W)
    ))
  }

  /** UNORM8 -> FP16 as u*257 / 65536, the replicated byte: within 1.5e-5 of u/255, at most one
    * FP16 ulp off. */
  def dequantize8(u8: UInt): UInt = {
    require(u8.getWidth == 8)
    val numerator = Cat(u8, u8)

    val msb = Log2(numerator)
    // value = 1.frac * 2^(msb-16): exponent field msb-1.
    val expField  = (msb.zext - 1.S)(4, 0).asUInt
    val shiftUp   = (15.U(4.W) - msb(3, 0))
    val mantField = (numerator << shiftUp)(14, 5) // the 10 bits below the leading one

    Mux(numerator === 0.U, 0.U(16.W), Cat(0.U(1.W), expField, mantField))
  }
}
