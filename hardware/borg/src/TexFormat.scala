// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Texel formats the sampler decodes (docs/B2_texture_unit.md): every format
  * Vulkan 1.0 requires SAMPLED_IMAGE support for, plus the 16-bit normalized
  * ones. One table drives the hardware decoder and the tests' reference, so
  * the two cannot disagree about a layout.
  *
  * A format is its size in bytes and up to four channels, each a bit field
  * of the little-endian texel (offset, width; width 0 = absent) with one
  * numeric type. Absent channels read as 0, alpha as 1. BGRA orders and the
  * packed formats are just different offsets.
  */
object TexFormat {
  // Numeric types.
  val UNORM = 0; val SNORM = 1; val UINT = 2; val SINT = 3; val SFLOAT = 4
  val SRGB = 5          // UNORM, with R, G, B converted from sRGB
  val UFLOAT = 6        // unsigned small floats: 5-bit exponent, 6/5-bit mantissa (B10G11R11)
  val SHAREDEXP = 7     // E5B9G9R9: a 9-bit mantissa per channel, exponent in bits 31:27

  case class Fmt(code: Int, name: String, bytes: Int, ch: Seq[(Int, Int)], kind: Int) {
    require(ch.size == 4)
    def isInt: Boolean = kind == UINT || kind == SINT
  }
  private val kinds = Map("UNORM" -> UNORM, "SNORM" -> SNORM, "UINT" -> UINT, "SINT" -> SINT, "SFLOAT" -> SFLOAT,
    "SRGB" -> SRGB, "UFLOAT" -> UFLOAT, "SHAREDEXP" -> SHAREDEXP)
  val all: Seq[Fmt] = TexFormatTable.rows.map { case (code, name, bytes, kind, ch) => Fmt(code, name, bytes, ch, kinds(kind)) }
  def byName(n: String): Fmt = all.find(_.name == n).getOrElse(throw new NoSuchElementException(n))
  val Bits = 6                                    // format code width
  require(all.size < (1 << Bits))

  // --- Hardware -----------------------------------------------------------

  /** The table as ROM lookups by format code. */
  class Info extends Bundle {
    val bytes = UInt(5.W)
    val kind  = UInt(3.W)
    val off   = Vec(4, UInt(7.W))
    val bits  = Vec(4, UInt(6.W))    // field widths (not `width`: Data has one)
  }
  def info(code: UInt): Info = {
    val w = Wire(new Info)
    w := 0.U.asTypeOf(w)
    for (fm <- all) when(code === fm.code.U) {
      w.bytes := fm.bytes.U; w.kind := fm.kind.U
      for (c <- 0 until 4) { w.off(c) := fm.ch(c)._1.U; w.bits(c) := fm.ch(c)._2.U }
    }
    w
  }

  /** v / (2^b - 1) as FP32, for 1 <= b <= 24: 0.vvvv... in binary, so the
    * mantissa is v's bits repeated -- no division. Truncated, not rounded
    * (within half an FP32 ULP of exact, far below any format's precision). */
  def unormToFp32(v: UInt, b: UInt): UInt = {
    val v24 = v(23, 0)
    // v's bits repeated from the top of a 48-bit window: 0.vvvv.
    val rep = VecInit((1 to 24).map { bw =>
      val bits = v24(bw - 1, 0)
      val n = (48 + bw - 1) / bw
      Cat(Seq.fill(n)(bits))(n * bw - 1, n * bw - 48)
    })(b - 1.U)
    val lz = PriorityEncoder(Reverse(rep))               // leading zeros of 0.rep
    val norm = (rep << lz)(47, 0)                        // 1.xxxx at bit 47
    val one  = v24 === ((1.U(25.W) << b) - 1.U)          // all ones: exactly 1.0
    val exp  = (126.U(8.W) - lz)(7, 0)                        // 0.1xxx = 2^-1 -> 126
    Mux(v24 === 0.U, 0.U(32.W),
      Mux(one, "h3F800000".U(32.W), Cat(0.U(1.W), exp, norm(46, 24))))
  }

  /** max(v / (2^(b-1) - 1), -1) as FP32, v a b-bit two's-complement value. */
  def snormToFp32(v: UInt, b: UInt): UInt = {
    val neg = (v >> (b - 1.U))(0)
    val mag = Mux(neg, ((1.U(33.W) << b) - v(31, 0))(31, 0), v)   // |v|
    val magBits = b - 1.U
    val isMin = neg && mag === (1.U(33.W) << magBits)(31, 0)      // -2^(b-1): exactly -1
    val u = unormToFp32(mag, magBits)
    Mux(isMin, "hBF800000".U(32.W), Cat(neg && u =/= 0.U, u(30, 0)))
  }

  /** Unsigned small float (5-bit exponent, bias 15, mBits mantissa, no sign)
    * to FP32. */
  def ufloatToFp32(v: UInt, mBits: Int): UInt = {
    val e = v(mBits + 4, mBits); val m = v(mBits - 1, 0)
    val mant = Cat(m, 0.U((23 - mBits).W))
    Mux(e === 0.U, Mux(m === 0.U, 0.U(32.W), {          // denormal: m * 2^(-14 - mBits)
        val lz = PriorityEncoder(Reverse(m))
        val sh = (m << (lz +& 1.U))(mBits - 1, 0)
        Cat(0.U(1.W), (112.U(8.W) - lz)(7, 0), sh, 0.U((23 - mBits).W))
      }),
      Mux(e === 31.U, Cat(0.U(1.W), "hFF".U(8.W), mant), Cat(0.U(1.W), (e +& 112.U)(7, 0), mant)))
  }

  /** An unsigned integer (up to 24 bits) times 2^scale, as FP32. */
  def uintScaledToFp32(v: UInt, scale: SInt): UInt = {
    val v24 = v(23, 0)
    val lz = PriorityEncoder(Reverse(v24))
    val norm = (v24 << lz)(23, 0)                        // 1.xxx at bit 23
    val exp = (127 + 23).S - lz.zext + scale
    Mux(v24 === 0.U, 0.U(32.W), Cat(0.U(1.W), exp.asUInt(7, 0), norm(22, 0)))
  }

  /** sRGB -> linear for an 8-bit value, as FP32 bits (a 256-entry ROM). */
  val srgbToLinear: Seq[Long] = (0 until 256).map { i =>
    val c = i / 255.0
    val l = if (c <= 0.04045) c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)
    java.lang.Float.floatToRawIntBits(l.toFloat).toLong & 0xFFFFFFFFL
  }
}
