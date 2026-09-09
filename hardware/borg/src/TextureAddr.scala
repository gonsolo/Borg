// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Combinational FP16 → uint8 conversion with floor + clamp.
  *
  * Converts a positive FP16 value in [0.0, 255.0] to an 8-bit unsigned integer.
  * Negative values → 0. Values ≥ 256 → 255.
  * This is pure combinational logic (~20 LUTs per instance).
  */
object Fp16ToUint8 {
  def apply(fp16: UInt): UInt = {
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)

    // Compute floor(abs(fp16_value)) clamped to [0, 255].
    // FP16 normal: value = (1.mant) × 2^(exp−15)
    // For integer part extraction, we pick the right bits of Cat(1, mant).
    val result = Wire(UInt(8.W))
    result := 0.U  // default: exp < 15 → value < 1.0 → floor = 0

    when(!sign) {
      switch(exp) {
        is(15.U) { result := 1.U }                                    // [1.0, 2.0)
        is(16.U) { result := Cat(0.U(6.W), 1.U(1.W), mant(9)) }      // [2.0, 4.0)
        is(17.U) { result := Cat(0.U(5.W), 1.U(1.W), mant(9, 8)) }   // [4.0, 8.0)
        is(18.U) { result := Cat(0.U(4.W), 1.U(1.W), mant(9, 7)) }   // [8.0, 16.0)
        is(19.U) { result := Cat(0.U(3.W), 1.U(1.W), mant(9, 6)) }   // [16.0, 32.0)
        is(20.U) { result := Cat(0.U(2.W), 1.U(1.W), mant(9, 5)) }   // [32.0, 64.0)
        is(21.U) { result := Cat(0.U(1.W), 1.U(1.W), mant(9, 4)) }   // [64.0, 128.0)
        is(22.U) { result := Cat(1.U(1.W), mant(9, 3)) }              // [128.0, 256.0)
      }
      // Clamp: exp ≥ 23 → value ≥ 256 → saturate to 255
      when(exp >= 23.U) { result := 255.U }
    }
    result
  }
}

/** FP16 -> unsigned 8.8 fixed point, for texture coordinates.
  *
  * Returns the integer texel index in bits 15:8 and the fraction in 7:0, so
  * one conversion yields both halves a bilinear tap needs. Deriving them
  * separately -- [[Fp16ToUint8]] for the integer and a second shifter for the
  * fraction -- would be two chances for the two to disagree at a boundary,
  * which is exactly where filtering artifacts show up.
  *
  * Same derivation as ColorQuantize.quantize8: a normal FP16 is
  * `SIG * 2^(exp-25)` with SIG = 1024+mant, so `value * 256 = SIG *
  * 2^(exp-17)` -- a left shift for exp >= 17 and a right shift below it.
  * Unlike quantize8 the shift crosses zero, so both directions are needed.
  *
  * Negative and zero/subnormal inputs give 0; anything >= 256.0 saturates,
  * matching Fp16ToUint8's clamping so the two never disagree about range.
  */
object Fp16ToFixed88 {
  def apply(fp16: UInt): UInt = {
    require(fp16.getWidth == 16)
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val sig  = Cat(1.U(1.W), mant)                  // 11 bits, [1024, 2047]

    // Widened to the output width BEFORE shifting: `sig` is 11 bits, so a
    // right shift of it stays 11 bits and slicing (15,0) is out of range.
    val sigWide   = Cat(0.U(5.W), sig)              // 16 bits
    val shiftUp   = (exp - 17.U)(4, 0)
    val shiftDown = (17.U - exp)(4, 0)
    val up   = (sigWide << shiftUp)(15, 0)
    val down = (sigWide >> shiftDown)(15, 0)

    val magnitude = Mux(exp >= 17.U, up, down)
    MuxCase(magnitude, Seq(
      (sign)             -> 0.U(16.W),              // negative clamps to 0
      (exp === 0.U)      -> 0.U(16.W),              // zero / subnormal
      (exp >= 23.U)      -> "hFF00".U(16.W)         // >= 256.0 saturates
    ))
  }
}

/** Keep Fp16ToUint6 for backward compatibility. */
object Fp16ToUint6 {
  def apply(fp16: UInt): UInt = {
    Fp16ToUint8(fp16)(5, 0)  // Just use lower 6 bits
  }
}

/** Clamp a raw Fp16ToUint8 texel coordinate to the last valid row/column of a
  * `2^log2Dim`-texel-wide texture, given as the `tex_config_log2_dim` MMIO
  * field (0 disables clamping, matching the legacy path's existing convention).
  *
  * UV=1.0 at a triangle's far edge/vertex legitimately interpolates to
  * exactly the texture's width in texel space (e.g. 64.0 for a 64-wide
  * texture) rather than 63.999... — Fp16ToUint8 floors that to 64, one past
  * the last valid index (0-63). Left unclamped, MortonEncode sets a bit the
  * 64-wide addressing was never meant to carry, landing the read in
  * unpopulated texture memory and returning black. This was already fixed
  * for the legacy autonomous texture path (Borg.scala) but not carried over
  * when the FTEX-inline path (Step 34.5) was added — hence a shared helper,
  * so a future new call site can't independently forget it. */
object ClampTexCoord {
  def apply(raw: UInt, log2Dim: UInt): UInt = {
    val max = Mux(log2Dim === 0.U, 255.U(8.W), ((1.U << log2Dim) - 1.U)(7, 0))
    Mux(raw > max, max, raw)
  }
}

/** Morton (Z-order) encoding for two 8-bit coordinates.
  *
  * Interleaves bits: y7 x7 y6 x6 ... y1 x1 y0 x0 → 16-bit index.
  * This is pure wiring — 0 LUTs.
  */
object MortonEncode {
  def apply(x: UInt, y: UInt): UInt = {
    Cat(y(7), x(7), y(6), x(6), y(5), x(5), y(4), x(4),
        y(3), x(3), y(2), x(2), y(1), x(1), y(0), x(0))
  }
}
