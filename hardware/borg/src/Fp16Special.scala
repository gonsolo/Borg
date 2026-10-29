// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FP16 reciprocal and reciprocal square root: a LUT and linear interpolation,
  * `correction = (delta * frac) >> 6`, shared by both ops (one instance, selected by `op`).
  * The 10-bit LUT entries arrive zero-extended to 16 bits; delta = lutVal - lutNext is never
  * negative for these decreasing curves.
  */
object Fp16SpecialOp {
  val Rcp  = 0.U(2.W)
  val Rsq  = 1.U(2.W)
}

class Fp16SpecialIO extends Bundle {
  val in      = Input(UInt(16.W))
  val lutVal  = Input(UInt(16.W))
  val lutNext = Input(UInt(16.W))
  val op      = Input(UInt(2.W))
  val out     = Output(UInt(16.W))
}

class Fp16Special extends Module {
  val io = IO(new Fp16SpecialIO)

  val sign = io.in(15)
  val exp  = io.in(14, 10)
  val mant = io.in(9, 0)

  val isRcp  = io.op === Fp16SpecialOp.Rcp
  val isRsq  = io.op === Fp16SpecialOp.Rsq

  // rcp uses a 5-bit fraction. Pre-double it to reuse the shared >>6 datapath.
  val frac = Mux(isRcp, Cat(mant(4, 0), 0.U(1.W)), mant(5, 0))

  val isZeroOrSubnormal = exp === 0.U
  val isInfOrNaN        = exp === 31.U
  val isNaN             = isInfOrNaN && mant =/= 0.U
  val isInf             = isInfOrNaN && mant === 0.U
  val isNeg             = sign && !isZeroOrSubnormal // rsqrt(neg) = NaN

  // @doc:rcp-interpolation
  val delta      = io.lutVal - io.lutNext
  val correction = (delta * frac) >> 6
  val estMant16  = (io.lutVal - correction)(15, 0)
  val estMant10  = estMant16(9, 0)
  // @doc:end

  // rcp: exponent 29 - exp (clamped), sign preserved.
  val rcpEstExp = WireDefault(0.U(5.W))
  when(exp <= 29.U) { rcpEstExp := 29.U - exp }
  val rcpNormal = Cat(sign, rcpEstExp, estMant10)
  val rcpOut = MuxCase(rcpNormal, Seq(
    isNaN             -> Cat(sign, "b11111".U(5.W), 1.U(10.W)),  // NaN
    isInf             -> Cat(sign, 0.U(15.W)),                    // +-Inf -> +-0
    isZeroOrSubnormal -> Cat(sign, "b11111".U(5.W), 0.U(10.W))   // +-0/sub -> +-Inf
  ))

  // rsq: the exponent depends on its parity; the result is never negative.
  val parity = !exp(0)
  val mzero  = mant === 0.U
  val rsqExp = Mux(parity,
    (44.U - exp) >> 1,
    Mux(mzero, (45.U - exp) >> 1, (43.U - exp) >> 1))
  val rsqEstMant = Mux(!parity && mzero, 0.U(10.W), estMant10)
  val rsqNormal = Cat(0.U(1.W), rsqExp(4, 0), rsqEstMant)
  val rsqOut = MuxCase(rsqNormal, Seq(
    (isNaN || isNeg)  -> Cat(0.U(1.W), "b11111".U(5.W), 1.U(10.W)), // NaN
    isInf             -> Cat(0.U(1.W), 0.U(15.W)),                   // rsqrt(+Inf)=+0
    isZeroOrSubnormal -> Cat(0.U(1.W), "b11111".U(5.W), 0.U(10.W))   // rsqrt(+0)=+Inf
  ))

  io.out := Mux(isRcp, rcpOut, rsqOut)
}
