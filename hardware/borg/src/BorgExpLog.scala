// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FEXP2 and FLOG2 at FP32 (docs/B8_isa_v2.md, "Special functions"): 128 segments per function,
  * each a quadratic in the segment's fraction u = d / 2^17, fixed point with 30 fraction bits.
  * Two cycles from `in` to `out`; denormal inputs and results flush to zero.
  */
object BorgExpLog {
  val Segs = 128
  val Frac = 30
  /** a0, a1, a2 of each segment: exact at u = 0, interpolating at u = 1/2 and u = 1. */
  def coeffs(f: Double => Double): Seq[(BigInt, BigInt, BigInt)] = (0 until Segs).map { i =>
    val g = (u: Double) => f((i + u) / Segs)
    val (a0, a, b) = (g(0), g(0.5) - g(0), g(1) - g(0))
    val (a1, a2) = (4 * a - b, 2 * b - 4 * a)
    def q(v: Double) = BigInt(math.round(v * (1L << Frac)))
    (q(a0), q(a1), q(a2))
  }
  val log2Table = coeffs(t => math.log(1 + t) / math.log(2))
  val exp2Table = coeffs(t => math.pow(2, t))
}

class BorgExpLogIO extends Bundle {
  val in  = Flipped(Valid(UInt(32.W)))
  val log = Input(Bool())                   // FLOG2, else FEXP2
  val out = Valid(UInt(32.W))
}

class BorgExpLog extends Module {
  import BorgExpLog._
  val io = IO(new BorgExpLogIO)
  private def rom(t: Seq[(BigInt, BigInt, BigInt)], k: Int) =
    VecInit(t.map(c => (k match { case 0 => c._1; case 1 => c._2; case _ => c._3 }).S(34.W)))

  val x = io.in.bits
  val (s, e, f) = (x(31), x(30, 23), x(22, 0))
  val nan = e === 255.U && f =/= 0.U
  val inf = e === 255.U && f === 0.U

  // FLOG2: x = 2^(e - 127) * (1 + f / 2^23); index from f's top 7 bits.
  val logIdx = f(22, 16); val logD = Cat(f(15, 0), 0.U(1.W))
  // FEXP2: x as fixed point with 24 fraction bits, n = floor(x), the fraction indexes the table.
  val sh = e.zext - 126.S
  val mant = Cat(1.U(1.W), f)
  val rsh = (-sh).asUInt
  val mag = Mux(sh >= 0.S, (mant << sh(2, 0)).pad(32), mant >> Mux(rsh > 31.U, 31.U, rsh(4, 0)))
  val fx = Mux(s, -(mag.zext), mag.zext)
  val n = fx >> 24
  val fr = fx.asUInt(23, 0)
  val idx = Mux(io.log, logIdx, fr(23, 17))
  val d = Mux(io.log, logD, fr(16, 0))
  val (a0, a1, a2) = (Mux(io.log, rom(log2Table, 0)(idx), rom(exp2Table, 0)(idx)),
                      Mux(io.log, rom(log2Table, 1)(idx), rom(exp2Table, 1)(idx)),
                      Mux(io.log, rom(log2Table, 2)(idx), rom(exp2Table, 2)(idx)))
  val dd = ((d * d) >> 17).asUInt
  val t1 = (a1 * d.zext) >> 17
  val t2 = (a2 * dd.zext) >> 17

  // Special results, else stage 2 assembles the value.
  val big = e >= 134.U                      // |x| >= 128
  val tiny = e < 101.U                      // |x| < 2^-26: 2^x rounds to 1
  val logSpecial = Mux(nan, x | 0x00400000.U, Mux(s && e =/= 0.U, 0x7FC00000.U,
                   Mux(e === 0.U, 0xFF800000L.U, Mux(inf, x, 0.U))))
  val expSpecial = Mux(nan, x | 0x00400000.U, Mux(inf || big, Mux(s, 0.U, 0x7F800000.U), 0x3F800000.U))
  val special = Mux(io.log, nan || inf || s || e === 0.U, nan || inf || big || tiny)

  val v1 = RegNext(io.in.valid, false.B)
  val log1 = RegNext(io.log); val special1 = RegNext(special); val specVal1 = RegNext(Mux(io.log, logSpecial, expSpecial))
  val sum1 = RegNext(a0 +& t1 +& t2)
  val e1 = RegNext(e.zext - 127.S); val n1 = RegNext(n)

  // Stage 2. FLOG2: (e - 127) + log2(1 + f), a signed fixed-point value, normalised and rounded.
  val r = (e1 << Frac) +& sum1
  val rNeg = r < 0.S
  val rMag = Mux(rNeg, -r, r).asUInt(Frac + 8, 0)
  val lz = PriorityEncoder(Reverse(rMag))
  val norm = (rMag << lz)(Frac + 8, 0)                 // leading one at bit Frac + 8
  def round(m: UInt, keep: Int): (UInt, Bool) = {     // RNE of m to `keep` bits below its top bit
    val drop = m.getWidth - 1 - keep
    val kept = m(m.getWidth - 1, drop)
    val g = m(drop - 1); val st = m(drop - 2, 0).orR
    val up = g && (st || kept(0))
    val res = kept +& up
    (res, res(keep + 1))
  }
  val (lm, lCarry) = round(norm, 23)
  val lExp = (8 + 127).U(8.W) - lz + lCarry      // the leading one at bit Frac is 2^0
  val logVal = Mux(rMag === 0.U, 0.U, Cat(rNeg, lExp(7, 0), Mux(lCarry, lm(23, 1), lm(22, 0))))
  // FEXP2: 2^frac in [1, 2) has its leading one at bit Frac; n moves the exponent.
  val p = sum1.asUInt(Frac, 0)
  val (pm, pCarry) = round(p, 23)
  val xExp = n1 + 127.S + pCarry.zext
  val expVal = Mux(xExp <= 0.S, 0.U, Mux(xExp >= 255.S, 0x7F800000.U,
               Cat(0.U(1.W), xExp.asUInt(7, 0), Mux(pCarry, pm(23, 1), pm(22, 0)))))

  io.out.valid := RegNext(v1, false.B)
  io.out.bits  := RegNext(Mux(special1, specVal1, Mux(log1, logVal, expVal)))
}
