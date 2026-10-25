// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** sRGB attachments in the tile path (docs/B8_isa_v2.md, "sRGB attachments"): the tile loader
  * decodes 8-bit sRGB to linear FP16, the flusher encodes linear FP16 to 8-bit sRGB. */
object SrgbCodec {
  private def srgb(l: Double) = if (l <= 0.0031308) 12.92 * l else 1.055 * math.pow(l, 1 / 2.4) - 0.055
  private def linear(c: Double) = if (c <= 0.04045) c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)
  private def half(d: Double): Int = java.lang.Float.floatToFloat16(d.toFloat) & 0xFFFF

  /** FP16 bits of each code's linear value. */
  val decodeTable: Seq[Int] = (0 until 256).map(c => half(linear(c / 255.0)))
  /** The smallest FP16 value that rounds to code k (k = 1..255); code 256 never matches. */
  val boundary: Seq[Int] = (0 to 256).map { k =>
    if (k == 0) 0 else if (k == 256) 0x7C00
    else (0 until 0x7C00).find(h => math.round(srgb(java.lang.Float.float16ToFloat(h.toShort).toDouble) * 255) >= k).get
  }

  /** `piped`: the table read sits behind a register (block RAM on the FPGA), one cycle late. */
  def decode(c: UInt, piped: Boolean = false): UInt = {
    val d = VecInit(decodeTable.map(_.U(16.W)))(c)
    if (piped) RegNext(d) else d
  }

  /** Round-to-nearest sRGB code of a linear FP16 value, clamped to [0, 1]: FSRGB's interpolated
    * table, then one step either way against the exact code boundaries. `piped`: both table
    * reads sit behind registers (block RAM on the FPGA), the code arrives two cycles late. */
  def encode(h: UInt, piped: Boolean = false): UInt = {
    def stage(x: UInt): UInt = if (piped) RegNext(x) else x
    val lut = VecInit(BorgLutTables.srgbLut.map(_.U(16.W)))
    val idx = Cat((h(14, 10) - 1.U)(3, 0), h(9, 6))
    val (v, n, h1) = (stage(lut(idx)), stage(lut((idx +& 1.U)(7, 0))), stage(h))
    val est = (v +& (((n - v) * h1(5, 0)) >> 6))(15, 0)
    val guess = ColorQuantize.quantize8(Mux(h1(14, 10) >= 16.U, "h3C00".U(16.W), est))
    val bound = VecInit(boundary.map(_.U(16.W)))
    val (lo, hi, g, h2) = (stage(bound(guess)), stage(bound(guess +& 1.U)), stage(guess), stage(h1))
    val mag = h2(14, 0)
    val code = Mux(mag < lo, g - 1.U, Mux(mag >= hi, g + 1.U, g))
    Mux(h2(15) || h2(14, 10) === 0.U, 0.U(8.W), Mux(mag >= "h3C00".U, 255.U(8.W), code(7, 0)))
  }
}
