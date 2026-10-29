// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The bilinear filter's operands: base texel, sub-texel position and the wrap rule. */
class BilinearIO extends Bundle {
  val enable  = Input(Bool())      // runtime VK_FILTER_LINEAR
  val u8      = Input(UInt(8.W))   // base texel column (already clamped)
  val v8      = Input(UInt(8.W))   // base texel row
  val fracU   = Input(UInt(8.W))   // sub-texel position, 0..255 -> [0,1)
  val fracV   = Input(UInt(8.W))
  val log2Dim = Input(UInt(4.W))   // for wrapping u8+1 / v8+1
  // The +1 neighbours wrap by the same VkSamplerAddressMode as the base coordinate.
  val addrModeU = Input(UInt(2.W))
  val addrModeV = Input(UInt(2.W))
  val border    = Input(UInt(2.W))   // VkBorderColor
}

/** UNORM8 bilinear filtering (`VK_FILTER_LINEAR`), weighted with [[BorgBlend]]'s exact
  * round(a*b/255). The Vulkan half-texel offset belongs to the coordinate generation.
  */
object TexFilter {

  /** `a*(255-f) + b*f`: never exceeds 255; f = 255 gives exactly `b`. */
  def lerp8(a: UInt, b: UInt, f: UInt): UInt = {
    require(a.getWidth == 8 && b.getWidth == 8 && f.getWidth == 8)
    (BorgBlend.mul255(a, (255.U(8.W) - f)) +& BorgBlend.mul255(b, f))(7, 0)
  }

  /** Taps ordered (u,v), (u+1,v), (u,v+1), (u+1,v+1). */
  def bilinear(taps: Seq[UInt], fracU: UInt, fracV: UInt): UInt = {
    require(taps.length == 4)
    val top    = lerp8(taps(0), taps(1), fracU)
    val bottom = lerp8(taps(2), taps(3), fracU)
    lerp8(top, bottom, fracV)
  }
}
