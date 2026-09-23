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

/** FP16 -> floor(value) as an 8-bit two's-complement texel coordinate,
  * PRESERVING sign -- unlike [[Fp16ToUint8]]/[[Fp16ToFixed88]], which both
  * flatten negative inputs to 0 (correct for those two: everywhere they're
  * used, a negative value belongs at the near edge/zero). This exists
  * specifically to feed [[TexAddressMode]]'s REPEAT/MIRRORED_REPEAT wrap,
  * which needs the true negative integer to fold correctly -- see
  * `TexAddressMode`'s own comment for why UV<0 previously could not wrap.
  *
  * Derivation: compute the UNSIGNED 8.8 magnitude of abs(fp16) via
  * [[Fp16ToFixed88]], then two's-complement-negate the whole 8.8 value when
  * the input was negative. Negating a fixed-point value this way computes
  * floor() automatically -- e.g. abs(0.3)'s 8.8 magnitude is (0, ~77/256);
  * negating the 16-bit pattern gives integer byte 0xFF = -1 = floor(-0.3),
  * not 0 = trunc(-0.3) -- which matters at the wrap boundary (UV=-0.3 must
  * land on a texture's LAST texel under REPEAT, not fail to wrap at all).
  *
  * Same magnitude-saturation bound as Fp16ToFixed88: an input whose
  * magnitude already saturates that conversion (>= 256.0) does not produce
  * a meaningfully-wrapped result here either -- acceptable for the same
  * reason the existing positive-overflow path accepts it: realistic UV
  * overflow from a single triangle's interpolation is nowhere near that
  * range.
  */
object Fp16ToSignedTexCoord {
  def apply(fp16: UInt): UInt = {
    require(fp16.getWidth == 16)
    val sign      = fp16(15)
    val absFp16   = Cat(0.U(1.W), fp16(14, 0))       // clear sign -> abs(value)
    val mag88     = Fp16ToFixed88(absFp16)            // unsigned magnitude, 16-bit 8.8
    val negated   = (~mag88 + 1.U)(15, 0)
    val signed88  = Mux(sign, negated, mag88)
    signed88(15, 8)                                    // integer half, two's complement
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

/** `VkSamplerAddressMode` -- what happens to a texel coordinate outside the
  * texture.
  *
  * All four of these are core Vulkan (only MIRROR_CLAMP_TO_EDGE needs an
  * extension), and Borg implemented exactly one of them: clamp. A tiling
  * texture -- the thing REPEAT exists for -- was simply not expressible.
  *
  * Textures are power-of-two sized (the size arrives as `log2Dim`), which is
  * what makes REPEAT and MIRRORED_REPEAT free: they are bit masking and a
  * conditional inversion, not a modulo.
  *
  * == Negative UV ==
  *
  * `raw` may be the two's-complement bit pattern of a NEGATIVE texel
  * coordinate (from [[Fp16ToSignedTexCoord]]), not just a plain unsigned
  * index -- REPEAT/MIRRORED_REPEAT's bit-masking (`raw & (dim-1)`) computes
  * the correct value mod a power-of-two dim for either sign with no extra
  * logic, which is what makes both free of an actual modulo. CLAMP_TO_EDGE/
  * CLAMP_TO_BORDER can't reuse that trick -- a masked bit pattern can't be
  * told apart from "legitimately large positive" by magnitude alone across
  * this function's several caller-dependent widths of `raw` -- so callers
  * with a possibly-negative coordinate pass the real sign explicitly via
  * `negative`, used only for the clamp/border decision. Callers whose `raw`
  * is always non-negative (e.g. a wrapped base coordinate plus a one-texel
  * neighbour offset) leave it at the default and get the historical
  * behaviour unchanged.
  */
object TexAddressMode {
  val REPEAT           = 0
  val MIRRORED_REPEAT  = 1
  val CLAMP_TO_EDGE    = 2
  val CLAMP_TO_BORDER  = 3

  /** @param negative true if the source coordinate was actually negative
    *   (e.g. the FP16 sign bit) -- NOT derived from `raw`'s own bit pattern,
    *   since `raw` is a different width at different call sites and a wide
    *   positive value (a wrapped coordinate plus a neighbour offset) must
    *   never be misread as a small negative one. Defaults to false.B, the
    *   historical behaviour, for every call site that never passes a
    *   negative coordinate.
    * @return (wrapped coordinate, true if the sample falls on the border)
    *
    * `isBorder` is only ever set by CLAMP_TO_BORDER; every other mode maps
    * an out-of-range coordinate onto a real texel.
    */
  def apply(raw: UInt, log2Dim: UInt, mode: UInt, negative: Bool = false.B): (UInt, Bool) = {
    // log2Dim == 0 keeps its historical meaning of "sizing unknown, don't
    // clamp" rather than being read as a 1x1 texture -- every existing call
    // site relies on that, so wrapping must not change it.
    val unsized = log2Dim === 0.U
    val dim     = (1.U(9.W) << log2Dim)(8, 0)          // up to 256
    val maxIdx  = Mux(unsized, 255.U(8.W), (dim - 1.U)(7, 0))

    // Sign-agnostic: correct for a negative raw's two's-complement bit
    // pattern too, for any power-of-two dim -- see the class doc.
    val repeated = raw & maxIdx                         // power-of-two modulo

    // Mirror: fold within a 2*dim period, so the texture reverses each
    // repeat instead of jumping back to zero.
    val period   = (dim << 1)(9, 0)
    val phase    = (raw & (period - 1.U))(8, 0)
    val mirrored = Mux(phase >= dim, (period - 1.U - phase)(7, 0), phase(7, 0))

    // Real sign semantics for clamp/border: negative clamps to the near
    // edge (texel 0), NOT `raw`'s (possibly huge, two's-complement-encoded)
    // unsigned bit value -- that distinction is exactly what `negative`
    // exists to carry in from the caller. Gated by `unsized` first, same as
    // maxIdx above: log2Dim==0 means don't touch raw AT ALL, negative
    // included, or the "every existing call site relies on it" invariant
    // above breaks for exactly the caller this parameter was added for.
    val positiveOverflow = !negative && raw > maxIdx
    val clamped = Mux(unsized, raw, Mux(negative, 0.U(8.W), Mux(positiveOverflow, maxIdx, raw)))
    val outside = !unsized && (negative || positiveOverflow)

    val coord = MuxLookup(mode, clamped)(Seq(
      REPEAT.U          -> Mux(unsized, clamped, repeated),
      MIRRORED_REPEAT.U -> Mux(unsized, clamped, mirrored),
      CLAMP_TO_EDGE.U   -> clamped,
      CLAMP_TO_BORDER.U -> clamped   // the coordinate is unused when isBorder
    ))
    (coord, mode === CLAMP_TO_BORDER.U && outside)
  }
}

/** `VkBorderColor`, for CLAMP_TO_BORDER.
  *
  * The float and int variants of each colour collapse to the same stored
  * value here, so two bits cover the whole core enum. CUSTOM belongs to
  * VK_EXT_custom_border_color and needs a colour register, not an enum.
  */
object BorderColor {
  val TRANSPARENT_BLACK = 0
  val OPAQUE_BLACK      = 1
  val OPAQUE_WHITE      = 2

  /** UNORM8 RGB for the selected border. Transparent and opaque black share
    * it; they differ only in [[a8]]. */
  def rgb8(sel: UInt): UInt = Mux(sel === OPAQUE_WHITE.U, 255.U(8.W), 0.U(8.W))
  /** UNORM8 alpha for the selected border: 0 for transparent black, 1.0 for
    * both opaque colours. */
  def a8(sel: UInt): UInt = Mux(sel === TRANSPARENT_BLACK.U, 0.U(8.W), 255.U(8.W))
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
