// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Combinational FP16 reciprocal unit using a 16-entry LUT with linear
  * interpolation.
  *
  * Accuracy: ~10-bit mantissa (matching FP16 precision) without any
  * Newton-Raphson iteration.  For the rare case where sub-ULP accuracy
  * is needed, a single NR step can be added in the shader:
  * {{{
  *   y = frcp(x)
  *   y = y * (2.0 - x * y)   // one FMUL + FMADD
  * }}}
  *
  * Area: 17 × 10-bit LUT (170 bits) + one 7×6-bit multiply for
  * interpolation.  No dedicated multiplier for NR.
  *
  * Edge cases (IEEE 754 compliant):
  *   - rcp(±0)        = ±Inf
  *   - rcp(±Inf)      = ±0
  *   - rcp(NaN)       = NaN
  *   - rcp(subnormal) = ±Inf  (treated as ≈0)
  */
class Fp16RcpIO extends Bundle {
  val in  = Input(UInt(16.W))
  val out = Output(UInt(16.W))
}

class Fp16Rcp extends Module {
  val io = IO(new Fp16RcpIO)

  // --- Field extraction ---
  val sign = io.in(15)
  val exp  = io.in(14, 10)
  val mant = io.in(9, 0)

  // --- Edge-case detection ---
  val isZeroOrSubnormal = exp === 0.U
  val isInfOrNaN        = exp === 31.U
  val isNaN             = isInfOrNaN && mant =/= 0.U
  val isInf             = isInfOrNaN && mant === 0.U

  // --- 17-entry LUT: mant_out = round((2 / (1 + i/16) - 1) * 1024) ---
  // Uses MuxLookup (mux tree) instead of VecInit to avoid SystemVerilog
  // array-literal syntax that yosys cannot parse.
  private val lutData = Seq(
    1023, 904, 796, 701, 614, 536, 465, 401,
     341, 287, 236, 190, 146, 106,  68,  33, 0
  )

  val lutIdx  = mant(9, 6)                    // top 4 bits → table index (0–15)
  val frac    = mant(5, 0)                    // bottom 6 bits → interpolation fraction

  val lutVal  = MuxLookup(lutIdx, 0.U(10.W))(
    (0 until 16).map(i => i.U -> lutData(i).U(10.W))
  )
  val lutNext = MuxLookup(lutIdx, 0.U(10.W))(
    (0 until 16).map(i => i.U -> lutData(i + 1).U(10.W))
  )

  // delta × frac / 64  (delta is always positive since 1/x is decreasing)
  val delta      = lutVal - lutNext           // max 120, fits 7 bits
  val correction = (delta * frac) >> 6        // 7×6 → 13 bit product, >>6 → 7 bits
  val estMant    = lutVal - correction

  // --- Reciprocal exponent: 29 − exp (with clamping) ---
  val estExp = WireDefault(0.U(5.W))
  when(exp <= 29.U) {
    estExp := 29.U - exp
  }
  // exp > 29 → estExp stays 0 (flush toward zero for very large inputs)

  // --- Assemble result with edge-case overrides ---
  val normal = Cat(sign, estExp, estMant)

  io.out := MuxCase(normal, Seq(
    isNaN             -> Cat(sign, "b11111".U(5.W), 1.U(10.W)),  // NaN
    isInf             -> Cat(sign, 0.U(15.W)),                    // ±Inf → ±0
    isZeroOrSubnormal -> Cat(sign, "b11111".U(5.W), 0.U(10.W))   // ±0/sub → ±Inf
  ))
}
