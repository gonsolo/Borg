// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Combinational FP16 reciprocal square root (1/sqrt(x)) using a 34-entry LUT
  * (two parity regions of 17) with linear interpolation — the companion of
  * [[Fp16Rcp]] for `normalize`.
  *
  * Reciprocal-sqrt halves the exponent, so the mantissa range that must be
  * inverse-sqrt'd depends on the parity of the unbiased exponent E = exp-15:
  *   - E even (exp odd) : value = 2/sqrt(1+m/1024)         in (1.414, 2]
  *   - E odd  (exp even): value = 2/sqrt(2·(1+m/1024))     in (1.0, 1.414]
  * The LUT stores the normalized result mantissa for each region (left edge of
  * each of 16 mantissa intervals + 1 endpoint); `mant[5:0]` interpolates.
  *
  * Exponent (unbiased): E even → -E/2-1 (or -E/2 at the exact power-of-two
  * boundary m==0); E odd → -(E+1)/2.  Accuracy ~9.5 bits (validated in Python
  * against all normal positive fp16 inputs; max rel err 0.14%).
  *
  * The two LUT words (lut[idx], lut[idx+1]) are pre-fetched by BorgCore from
  * BRAM (parity already folded into `idx`); only the interpolation + exponent
  * arithmetic is here.
  *
  * Edge cases: rsqrt(+0)=+Inf, rsqrt(+Inf)=+0, rsqrt(NaN)=NaN, negative→NaN.
  */
class Fp16RsqIO extends Bundle {
  val in      = Input(UInt(16.W))
  val lutVal  = Input(UInt(10.W)) // frsqLut[idx]   (idx folds in the parity region)
  val lutNext = Input(UInt(10.W)) // frsqLut[idx+1]
  val out     = Output(UInt(16.W))
}

class Fp16Rsq extends Module {
  val io = IO(new Fp16RsqIO)

  val sign = io.in(15)
  val exp  = io.in(14, 10)
  val mant = io.in(9, 0)

  val isZeroOrSubnormal = exp === 0.U
  val isInfOrNaN        = exp === 31.U
  val isNaN             = isInfOrNaN && mant =/= 0.U
  val isInf             = isInfOrNaN && mant === 0.U
  val isNeg             = sign && !isZeroOrSubnormal // rsqrt(neg) = NaN

  // parity p: 0 when E=exp-15 is even (exp odd), 1 when E odd (exp even).
  val parity = !exp(0)

  // --- Interpolate the result mantissa (rsqrt is decreasing → lutVal ≥ lutNext) ---
  val frac       = mant(5, 0)
  val delta      = io.lutVal - io.lutNext
  val correction = (delta * frac) >> 6
  val estMantRaw = io.lutVal - correction

  // --- Result exponent (biased), per the parity derivation ---
  //   p=0 (exp odd) : rexp = (43-exp)/2   (m≠0) or (45-exp)/2 (m==0 boundary)
  //   p=1 (exp even): rexp = (44-exp)/2
  // exp ∈ [1,30] keeps every numerator non-negative and even, so >>1 is exact.
  val mzero = mant === 0.U
  val rexp = Mux(parity,
    (44.U - exp) >> 1,
    Mux(mzero, (45.U - exp) >> 1, (43.U - exp) >> 1))
  val estMant = Mux(!parity && mzero, 0.U(10.W), estMantRaw)

  val normal = Cat(0.U(1.W), rexp(4, 0), estMant)

  io.out := MuxCase(normal, Seq(
    (isNaN || isNeg)  -> Cat(0.U(1.W), "b11111".U(5.W), 1.U(10.W)), // NaN
    isInf             -> Cat(0.U(1.W), 0.U(15.W)),                   // rsqrt(+Inf)=+0
    isZeroOrSubnormal -> Cat(0.U(1.W), "b11111".U(5.W), 0.U(10.W))   // rsqrt(+0)=+Inf
  ))
}
