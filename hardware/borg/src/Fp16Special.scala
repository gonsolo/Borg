// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Consolidated FP16 reciprocal / reciprocal-sqrt / linear->sRGB unit.
  *
  * All three ops share the exact same LUT-interpolation core (see
  * `@doc:rcp-interpolation` in the former Fp16Rcp): `correction = (delta *
  * frac) >> 6`, only the direction (subtract for the decreasing rcp/rsq
  * curves, add for the increasing sRGB curve), fraction selection, and the
  * exponent/edge-case assembly differ. Previously three separate modules
  * (Fp16Rcp, Fp16Rsq, Fp16Srgb) each carried their own copy of that
  * interpolation datapath;
  * measured standalone: 6,967 + 7,273 + 11,189 = 25,429 um^2 vs 15,363 um^2
  * consolidated (-39.6%). All three ops execute in the same pipeline slot
  * (busy_counter==3 capture, see BorgLane), so sharing one instance selected
  * by `op` costs nothing in latency -- see BorgLane.computeFp16Special.
  *
  * lutVal/lutNext are carried at 16 bits uniformly; rcp/rsq's actual LUT
  * entries are 10-bit mantissa estimates (zero-extended by the caller into
  * the low 10 bits) since delta=lutVal-lutNext is always non-negative for
  * those two (monotonically decreasing curves) -- promoting to 16 bits
  * before subtracting is exact. sRGB's LUT entries are already full 16-bit
  * fp16 output values.
  */
object Fp16SpecialOp {
  val Rcp  = 0.U(2.W)
  val Rsq  = 1.U(2.W)
  val Srgb = 2.U(2.W)
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

  // --- Shared interpolation core ---
  // @doc:rcp-interpolation
  // rcp/rsq: decreasing (lutVal >= lutNext). srgb: increasing (lutNext >= lutVal).
  val decreasing = isRcp || isRsq
  val delta      = Mux(decreasing, io.lutVal - io.lutNext, io.lutNext - io.lutVal)
  val correction = (delta * frac) >> 6
  val estMant16  = Mux(decreasing, io.lutVal - correction, io.lutVal +& correction)(15, 0)
  val estMant10  = estMant16(9, 0)
  // @doc:end

  // --- rcp: exponent = 29 - exp (clamped), sign preserved ---
  val rcpEstExp = WireDefault(0.U(5.W))
  when(exp <= 29.U) { rcpEstExp := 29.U - exp }
  val rcpNormal = Cat(sign, rcpEstExp, estMant10)
  val rcpOut = MuxCase(rcpNormal, Seq(
    isNaN             -> Cat(sign, "b11111".U(5.W), 1.U(10.W)),  // NaN
    isInf             -> Cat(sign, 0.U(15.W)),                    // +-Inf -> +-0
    isZeroOrSubnormal -> Cat(sign, "b11111".U(5.W), 0.U(10.W))   // +-0/sub -> +-Inf
  ))

  // --- rsq: parity-dependent exponent, always non-negative result ---
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

  // --- srgb: direct fp16 interpolation, clamp outside [0,1] domain ---
  val srgbOne = "h3C00".U(16.W) // fp16 1.0
  val srgbOut = Mux(sign || exp === 0.U, 0.U(16.W), Mux(exp >= 16.U, srgbOne, estMant16))

  io.out := Mux(isRcp, rcpOut, Mux(isRsq, rsqOut, srgbOut))
}
