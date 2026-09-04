// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FP16 <-> UNORM8 color quantization for the tile buffer's optional narrow
  * color storage (`BorgConfig.tileColorBits`).
  *
  * Both directions are pure bit manipulation -- a variable shift plus a
  * round/clamp, not a multiplier -- since a full FP16 multiply-by-255 unit
  * would cost more silicon than the storage narrowing it would be paying for.
  * Inputs are assumed to be shaded color in [0,1]; anything outside that
  * range (a buggy shader, or FP16 -0.0/NaN) is clamped rather than wrapped.
  *
  * @doc:color-quantize
  */
object ColorQuantize {

  /** FP16(assumed in [0,1]) -> UNORM8, round-to-nearest.
    *
    * A normal FP16 value is `1.mant x 2^(exp-15)`, mant 10 bits, implicit
    * leading 1 (so the 11-bit significand SIG = 1024 + mant, an integer in
    * [1024, 2047)). We want `round(value * 255)`; using 256 instead of 255
    * turns the multiply into a pure shift (255/256 error is under 0.4% of
    * one UNORM8 step, negligible for a deliberately lossy color path):
    *
    *   value * 256 = SIG * 2^(exp-15-10+8) = SIG * 2^(exp-17)
    *
    * exp is in [1,14] for a normal value < 1 (exp=15 means >=1.0, clamped
    * separately below), so exp-17 is always negative: this is SIG right-
    * shifted by (17-exp), 3..16 bits, with the bit just below the shift
    * point used to round rather than truncate.
    */
  def quantize8(fp16: UInt): UInt = {
    require(fp16.getWidth == 16)
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val sig  = Cat(1.U(1.W), mant) // 11 bits, implicit leading one, in [1024,2047]

    val isZeroOrSubnormal = exp === 0.U

    // Right-shift amount N = 17-exp, always in [3,16] for exp in [1,14] (the
    // only range reaching here once zero/subnormal and >=1.0 are muxed off
    // below). Round-to-nearest via a standard "add half, then shift" bias
    // rather than truncating: result = (sig + 2^(N-1)) >> N.
    val n = (17.U(5.W) - exp)(4, 0)
    val roundBias = (1.U(16.W) << (n - 1.U))(15, 0)
    val sigPlusBias = sig +& roundBias // 11-bit + up to 16-bit -> needs 17 bits, Chisel infers it
    val rounded = (sigPlusBias >> n)(8, 0) // shifted result fits in 9 bits (may carry to 256)

    val normalResult = Mux(rounded > 255.U, 255.U(8.W), rounded(7, 0))

    MuxCase(normalResult, Seq(
      (sign)                 -> 0.U(8.W), // negative clamps to 0
      (isZeroOrSubnormal)    -> 0.U(8.W),
      (!sign && exp >= 15.U) -> 255.U(8.W) // >=1.0 clamps to 255
    ))
  }

  /** UNORM8 -> FP16, exact up to FP16's own rounding.
    *
    * u/255 is approximated as u/256 * (256/255) via the standard "replicate
    * the byte" trick: numerator = (u<<8)|u exactly equals u*257 (no carry,
    * u<256), and u*257/65536 differs from the true u/255 by a factor of
    * 65535/65536 (~1.5e-5 relative) -- far below FP16's own ~2^-10 mantissa
    * resolution, so this rounds to the correctly-rounded FP16 result in the
    * overwhelming majority of cases and at most 1 ULP off in rare corners.
    * Fully acceptable for a path whose entire point is trading precision
    * for area.
    *
    * numerator is then just an integer needing float-normalization (leading-
    * one detect + shift), the same shape as BorgLane's existing i2f integer-
    * to-FP16 conversion, with a fixed -16 exponent bias for the implicit
    * /65536 scale.
    */
  def dequantize8(u8: UInt): UInt = {
    require(u8.getWidth == 8)
    val numerator = Cat(u8, u8) // 16 bits, exactly u*257

    val msb = Log2(numerator) // position of the leading 1, 0 when numerator==0
    // value = numerator * 2^-16 = 1.frac * 2^(msb-16); FP16 exponent field
    // (bias 15) is therefore msb-16+15 = msb-1, only valid while msb>=1.
    val expField  = (msb.zext - 1.S)(4, 0).asUInt
    // Mantissa: the 10 bits below the leading one, shifted up to a fixed
    // field width regardless of where the leading one landed.
    val shiftUp   = (15.U(4.W) - msb(3, 0))
    val mantField = (numerator << shiftUp)(14, 5) // 10 bits below the (now bit-15) leading one

    Mux(numerator === 0.U, 0.U(16.W), Cat(0.U(1.W), expField, mantField))
  }
}
