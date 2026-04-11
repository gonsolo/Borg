// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Combinational FP16 → uint6 conversion with floor + clamp.
  *
  * Converts a positive FP16 value in [0.0, 63.0] to a 6-bit unsigned integer.
  * Negative values → 0. Values ≥ 64 → 63.
  * This is pure combinational logic (~15 LUTs per instance).
  */
object Fp16ToUint6 {
  def apply(fp16: UInt): UInt = {
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)

    // Compute floor(abs(fp16_value)) clamped to [0, 63].
    // FP16 normal: value = (1.mant) × 2^(exp−15)
    // For integer part extraction, we pick the right bits of Cat(1, mant).
    val result = Wire(UInt(6.W))
    result := 0.U  // default: exp < 15 → value < 1.0 → floor = 0

    when(!sign) {
      switch(exp) {
        is(15.U) { result := 1.U }                                    // [1.0, 2.0)
        is(16.U) { result := Cat(0.U(4.W), 1.U(1.W), mant(9)) }      // [2.0, 4.0)
        is(17.U) { result := Cat(0.U(3.W), 1.U(1.W), mant(9, 8)) }   // [4.0, 8.0)
        is(18.U) { result := Cat(0.U(2.W), 1.U(1.W), mant(9, 7)) }   // [8.0, 16.0)
        is(19.U) { result := Cat(0.U(1.W), 1.U(1.W), mant(9, 6)) }   // [16.0, 32.0)
        is(20.U) { result := Cat(1.U(1.W), mant(9, 5)) }              // [32.0, 64.0)
      }
      // Clamp: exp ≥ 21 → value ≥ 64 → saturate to 63
      when(exp >= 21.U) { result := 63.U }
    }
    result
  }
}

/** Morton (Z-order) encoding for two 6-bit coordinates.
  *
  * Interleaves bits: y5 x5 y4 x4 y3 x3 y2 x2 y1 x1 y0 x0 → 12-bit index.
  * This is pure wiring — 0 LUTs.
  */
object MortonEncode {
  def apply(x: UInt, y: UInt): UInt = {
    Cat(y(5), x(5), y(4), x(4), y(3), x(3),
        y(2), x(2), y(1), x(1), y(0), x(0))
  }
}
