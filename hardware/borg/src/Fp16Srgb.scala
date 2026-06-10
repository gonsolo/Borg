// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Combinational FP16 linear→sRGB encode (the whole `linearToSrgb` curve,
  * piecewise `<=0.0031308` branch + `pow(x,1/2.4)` affine, baked into the LUT).
  *
  * sRGB does not factor into a per-binade scaling the way 1/x and 1/sqrt(x) do,
  * so this is a DIRECT fp16→fp16 table indexed by the input's (exponent, top
  * mantissa bits) over the valid domain x∈[0,1]:
  *   idx = (exp-1)·16 + mant[9:6]   for exp∈[1,15]
  * The LUT entry IS the fp16 output; `mant[5:0]` linearly interpolates the two
  * neighbouring fp16-bit values (monotone in x, so the bit patterns are monotone
  * and the integer interpolation is accurate — validated in Python, max abs error
  * 0.0018 ≈ 0.46/255, sub-LSB for 8-bit display).
  *
  * Out of domain: x≤0 / subnormal → 0; x≥2 (exp≥16) → 1.0 (clamp).
  */
class Fp16SrgbIO extends Bundle {
  val in      = Input(UInt(16.W))
  val lutVal  = Input(UInt(16.W)) // srgbLut[idx]    (fp16 output bits)
  val lutNext = Input(UInt(16.W)) // srgbLut[idx+1]
  val out     = Output(UInt(16.W))
}

class Fp16Srgb extends Module {
  val io = IO(new Fp16SrgbIO)

  val sign = io.in(15)
  val exp  = io.in(14, 10)
  val mant = io.in(9, 0)
  val frac = mant(5, 0)

  // Linear interpolation on the fp16 bit patterns (lutNext ≥ lutVal since sRGB is
  // increasing and positive-fp16 bits are monotone in value).
  val delta  = io.lutNext - io.lutVal
  val interp = (io.lutVal +& ((delta * frac) >> 6))(15, 0)

  val one = "h3C00".U(16.W) // fp16 1.0
  io.out := Mux(sign || exp === 0.U, 0.U(16.W),
            Mux(exp >= 16.U, one, interp))
}
