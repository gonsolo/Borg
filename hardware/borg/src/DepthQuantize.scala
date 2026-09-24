// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FP16 <-> UNORM16 depth quantization, for `D16_UNORM` support (Step 50
  * item 14 -- see docs/A0_roadmap.md): Vulkan mandates `D16_UNORM` support
  * unconditionally, no OR-choice unlike the 24/32-bit depth tiers, and
  * Borg's Z storage is FP16-native throughout with no UNORM16 conversion
  * path today.
  *
  * Same shape as [[ColorQuantize]] (pure bit manipulation, a variable shift
  * plus a round/clamp, no multiplier), verified standalone before ever being
  * wired into `BorgTileBuffer` -- see that object's own doc comment for the
  * area rationale. The one real structural difference from `ColorQuantize`:
  * UNORM16 asks for 16 fractional bits versus UNORM8's 8, which pushes the
  * quantize direction's shift amount past zero (a left shift for values
  * near 1.0, not always a right shift like quantize8) -- see quantize16's
  * own comment for the derivation.
  *
  * Inputs are assumed to be depth in [0,1] (Borg's existing NDC-like Z
  * convention); anything outside that range clamps rather than wraps, same
  * as ColorQuantize.
  */
object DepthQuantize {

  /** FP16(assumed in [0,1]) -> UNORM16, round-to-nearest.
    *
    * A normal FP16 value is `1.mant x 2^(exp-15)`, mant 10 bits, implicit
    * leading 1 (11-bit significand SIG = 1024 + mant, in [1024, 2047)).
    * We want `round(value * 65536)`:
    *
    *   value * 65536 = SIG * 2^(exp-15-10+16) = SIG * 2^(exp-9)
    *
    * Unlike quantize8 (whose analogous shift, exp-17, is always negative
    * for exp in [1,14]), exp-9 spans both signs over that same range:
    * exp>=9 needs a LEFT shift (exact -- SIG<<5 tops out at 65504 for
    * exp=14, the largest normal exponent below 1.0, never overflowing 16
    * bits, so no clamp needed on that branch), exp<9 needs a RIGHT shift
    * with the same "add half, then shift" round-to-nearest bias quantize8
    * uses.
    */
  def quantize16(fp16: UInt): UInt = {
    require(fp16.getWidth == 16)
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val sig  = Cat(1.U(1.W), mant) // 11 bits, implicit leading one, in [1024,2047]

    val isZeroOrSubnormal = exp === 0.U

    // Left-shift branch (exp>=9): exact, no rounding. Garbage (wrapped
    // UInt subtraction) when exp<9, but that's fine -- normalResult's mux
    // below only selects it when exp>=9, where exp-9 is a valid unsigned
    // shift amount in [0,5].
    val leftShift  = (exp - 9.U)(3, 0)
    val leftResult = (sig << leftShift)(15, 0)

    // Right-shift branch (exp<9): n = 9-exp, valid range [1,8] there.
    // Garbage (wrapped) when exp>=9, discarded the same way.
    val n = (9.U(5.W) - exp)(4, 0)
    val roundBias   = (1.U(16.W) << (n - 1.U))(15, 0)
    val sigPlusBias = sig +& roundBias
    val rightResult = (sigPlusBias >> n)(15, 0)

    val normalResult = Mux(exp >= 9.U, leftResult, rightResult)

    MuxCase(normalResult, Seq(
      sign                    -> 0.U(16.W), // negative clamps to 0
      isZeroOrSubnormal       -> 0.U(16.W),
      (!sign && exp >= 15.U)  -> 65535.U(16.W) // >=1.0 clamps to 65535
    ))
  }

  /** UNORM16 -> FP16, exact up to FP16's own rounding (and, near zero,
    * flushed rather than exact -- see below).
    *
    * u16 is already exactly the integer `value * 65536` (no byte-
    * replication scaling trick needed here, unlike dequantize8 widening an
    * 8-bit input): normalize it directly (leading-one detect + shift), the
    * same shape as dequantize8's own normalization once its numerator is
    * computed -- both operate on a 16-bit numerator representing
    * `value * 65536`, so the msb/expField/mantField derivation is
    * structurally identical.
    *
    * u16 values below 4 (a value under 4/65536, i.e. below FP16's smallest
    * *normal* magnitude at this exponent range) would need a subnormal FP16
    * encoding (exp field 0) to represent exactly; flushing them to a clean
    * zero instead is the same defensible, not-exercised-by-real-depth-data
    * simplification this branch already applies to FP16 subnormals
    * elsewhere (see Fp16Fp32.widen/narrow). dequantize8 never hits this
    * case by construction (its numerator is u*257, whose minimum nonzero
    * value already normalizes cleanly), which is why it doesn't need the
    * same guard.
    */
  def dequantize16(u16: UInt): UInt = {
    require(u16.getWidth == 16)
    val numerator = u16

    val msb = Log2(numerator) // 0 when numerator==0
    val expField  = (msb.zext - 1.S)(4, 0).asUInt
    val shiftUp   = (15.U(4.W) - msb(3, 0))
    val mantField = (numerator << shiftUp)(14, 5)

    Mux(numerator < 4.U, 0.U(16.W), Cat(0.U(1.W), expField, mantField))
  }
  /** FP32 (assumed in [0,1]) -> UNORM16, exactly round(value * 65535).
    *
    * For FP32 tile depth (BorgConfig.tileDepthBits = 32) storing into a
    * D16_UNORM attachment. value = SIG * 2^(exp-150) with SIG the 24-bit
    * significand, so value * 65535 = (SIG*65536 - SIG) >> (150 - exp): a
    * subtract and a rounding shift, no multiplier -- the same trick as
    * ColorQuantize.quantize8. A shift of 41 or more leaves nothing (the
    * product is below 2^40), and values at or above 1.0 clamp to 65535.
    */
  def quantize16Fp32(fp32: UInt): UInt = {
    require(fp32.getWidth == 32)
    val sign = fp32(31)
    val exp  = fp32(30, 23)
    val sig  = Cat(1.U(1.W), fp32(22, 0))                 // 24 bits
    val t    = (sig << 16) - sig                          // SIG * 65535, < 2^40
    val n    = 150.U(9.W) - exp                           // >= 24 for exp <= 126
    val nSm  = n(5, 0)
    val rounded = ((t +& (1.U(41.W) << (nSm - 1.U))) >> nSm)(16, 0)
    val normal  = Mux(n > 40.U, 0.U(16.W), Mux(rounded > 65535.U, 65535.U(16.W), rounded(15, 0)))
    MuxCase(normal, Seq(
      sign            -> 0.U(16.W),
      (exp === 0.U)   -> 0.U(16.W),
      (exp >= 127.U)  -> 65535.U(16.W)))
  }

  /** UNORM16 -> FP32, close enough to u/65535 that quantize16Fp32 returns u.
    *
    * u/65535 is approximated as Cat(u, u) * 2^-32 = u*65537/2^32 (off by a
    * factor of 1 - 2^-32), normalized and truncated to FP32's 24 significant
    * bits. The total error is under 2^-24 relative, far inside the half-step
    * rounding margin of the way back, so every D16 value survives a load and
    * store unchanged.
    */
  def dequantize16Fp32(u16: UInt): UInt = {
    require(u16.getWidth == 16)
    val n   = Cat(u16, u16)                               // u * 65537, 32 bits
    val msb = Log2(n)
    val expField  = (msb +& 95.U)(7, 0)                   // msb - 32 + 127
    val mantField = (n << (31.U - msb))(30, 8)            // 23 bits below the leading one
    Mux(u16 === 0.U, 0.U(32.W), Cat(0.U(1.W), expField, mantField))
  }
}
