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
  * Area: LUT values are supplied externally from BRAM (2 EBRs) rather than
  * synthesized as VecInit mux trees.  The interpolation arithmetic (7×6-bit
  * multiply + subtract) is still combinational.  Saves ~40–60 LUTs vs. the
  * previous VecInit combinational ROM.
  *
  * Edge cases (IEEE 754 compliant):
  *   - rcp(±0)        = ±Inf
  *   - rcp(±Inf)      = ±0
  *   - rcp(NaN)       = NaN
  *   - rcp(subnormal) = ±Inf  (treated as ≈0)
  */
class Fp16RcpIO extends Bundle {
  val in  = Input(UInt(16.W))
  // External BRAM LUT data (pre-fetched 1 cycle before by BorgCore)
  val lutVal  = Input(UInt(10.W))   // rcpLut[lutIdx]
  val lutNext = Input(UInt(10.W))   // rcpLut[lutIdx + 1]
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

  // @doc:rcp-interpolation
  val frac    = mant(5, 0)                    // bottom 6 bits → interpolation fraction

  // LUT values come from external BRAM (already fetched by BorgCore)
  val lutVal  = io.lutVal
  val lutNext = io.lutNext

  // delta × frac / 64  (delta is always positive since 1/x is decreasing)
  val delta      = lutVal - lutNext           // max 120, fits 7 bits
  val correction = (delta * frac) >> 6        // 7×6 → 13 bit product, >>6 → 7 bits
  val estMant    = lutVal - correction
  // @doc:end

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
