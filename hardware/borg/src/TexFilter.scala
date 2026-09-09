// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Operands the bilinear path needs that a Morton index cannot carry.
  *
  * The nearest-neighbour path is handed a single pre-encoded `mortonIndex`,
  * which is enough to name one texel and nothing else. Filtering needs the
  * base texel, the sub-texel position, and the texture size to clamp the +1
  * neighbours against -- so those travel on their own port rather than being
  * bolted onto TexConfigIO, which is shared with call sites that have no
  * filtering concept.
  */
class BilinearIO extends Bundle {
  val enable  = Input(Bool())      // runtime VK_FILTER_LINEAR
  val u8      = Input(UInt(8.W))   // base texel column (already clamped)
  val v8      = Input(UInt(8.W))   // base texel row
  val fracU   = Input(UInt(8.W))   // sub-texel position, 0..255 -> [0,1)
  val fracV   = Input(UInt(8.W))
  val log2Dim = Input(UInt(4.W))   // for clamping u8+1 / v8+1
}

/** Bilinear texture filtering (`VK_FILTER_LINEAR`).
  *
  * Core Vulkan: no feature bit gates linear filtering, and Borg sampled
  * nearest-neighbour only -- one texel, no weights.
  *
  * == Why the weighting is UNORM8 ==
  *
  * Same argument as [[BorgBlend]], and it reuses that unit's exact
  * `round(a*b/255)` identity rather than a second rounding scheme. Texels
  * reach DRAM as FP16 but originate as UNORM8 (borgvk's upload path converts
  * an application's `R8G8B8A8_UNORM` image), so quantizing each tap back to
  * UNORM8 for the weighting is lossless for the content that actually exists,
  * and the result feeds a UNORM8 framebuffer. An FP16 lerp would cost four
  * multipliers per channel to preserve precision the source never had.
  *
  * == The half-texel question, flagged not silently decided ==
  *
  * Vulkan samples at `(u*W - 0.5, v*H - 0.5)`, so a texel's centre sits at
  * fraction 0. Borg's existing nearest path applies no such offset -- the
  * shader hands over texel-space coordinates and they are floored. This
  * implementation keeps that convention deliberately: changing where the
  * sample lands would move every existing render, and doing that in the same
  * change that adds filtering would make a regression impossible to
  * attribute. The offset belongs in the coordinate generation, is a
  * half-texel shift in the filtered result, and wants its own change with
  * its own golden-image comparison.
  */
object TexFilter {

  /** `a + (b - a) * f`, in UNORM8 with an 8-bit weight.
    *
    * Expressed as `a*(255-f) + b*f` so it reuses BorgBlend's exact
    * `round(a*b/255)` -- the two never round differently, and the sum cannot
    * exceed 255 so no saturation is needed.
    *
    * f = 0 gives exactly `a`; f = 255 gives exactly `b`. The true fraction
    * 255/256 therefore maps to "all of b", a bias of at most 1/256 of one
    * texel step, which is standard for 8-bit filter weights.
    */
  def lerp8(a: UInt, b: UInt, f: UInt): UInt = {
    require(a.getWidth == 8 && b.getWidth == 8 && f.getWidth == 8)
    (BorgBlend.mul255(a, (255.U(8.W) - f)) +& BorgBlend.mul255(b, f))(7, 0)
  }

  /** One channel of the 2x2 filter: lerp along u, then between those in v.
    *
    * Taps are ordered (u,v), (u+1,v), (u,v+1), (u+1,v+1).
    */
  def bilinear(taps: Seq[UInt], fracU: UInt, fracV: UInt): UInt = {
    require(taps.length == 4)
    val top    = lerp8(taps(0), taps(1), fracU)
    val bottom = lerp8(taps(2), taps(3), fracU)
    lerp8(top, bottom, fracV)
  }
}
