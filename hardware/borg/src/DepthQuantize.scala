// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Float <-> UNORM16 depth for D16_UNORM, which Vulkan mandates. Shifts and rounding, no
  * multiplier, like [[ColorQuantize]]. Depth outside [0,1] clamps.
  */
object DepthQuantize {

  /** FP16 -> UNORM16, round(value * 65536) = SIG * 2^(exp-9): an exact left shift for exp >= 9
    * (at most 65504), a rounding right shift below.
    */
  def quantize16(fp16: UInt): UInt = {
    require(fp16.getWidth == 16)
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val sig  = Cat(1.U(1.W), mant) // 11 bits, implicit leading one, in [1024,2047]

    val isZeroOrSubnormal = exp === 0.U

    // Each branch wraps outside its own exponent range; normalResult picks the valid one.
    val leftShift  = (exp - 9.U)(3, 0)
    val leftResult = (sig << leftShift)(15, 0)

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

  /** UNORM16 -> FP16 by normalizing u16 = value * 65536. Values below 4 would be FP16 subnormals
    * and flush to zero, like Fp16Fp32.
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
  /** FP32 -> UNORM16, exactly round(value * 65535) = (SIG*65536 - SIG) >> (150 - exp). */
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

  /** UNORM16 -> FP32 as Cat(u, u) * 2^-32 ~ u/65535, close enough that quantize16Fp32 returns u. */
  def dequantize16Fp32(u16: UInt): UInt = {
    require(u16.getWidth == 16)
    val n   = Cat(u16, u16)                               // u * 65537, 32 bits
    val msb = Log2(n)
    val expField  = (msb +& 95.U)(7, 0)                   // msb - 32 + 127
    val mantField = (n << (31.U - msb))(30, 8)            // 23 bits below the leading one
    Mux(u16 === 0.U, 0.U(32.W), Cat(0.U(1.W), expField, mantField))
  }
}
