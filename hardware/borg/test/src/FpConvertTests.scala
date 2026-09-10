// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Tests for the FP16<->FP32 boundary conversion units (`FpConvert.scala`,
  * `feat/fp32-datapath` branch plan item 5).
  *
  * Two independent kinds of check, deliberately not just one:
  *
  *   1. Round-trip identity, swept over every non-NaN FP16 bit pattern:
  *      `narrow(widen(x)) == x`. This needs no external oracle at all -- widen
  *      is exact and narrowing an exactly-FP16-representable value is a
  *      no-op -- so it catches sign/shift/bit-position bugs even if the
  *      hand-derived oracle below happened to share the same mistake.
  *   2. A software oracle (`refWiden`/`refNarrow`, built from first
  *      principles via `Double`/`Float`, not by transcribing the RTL's
  *      bit-twiddling) checked against specific values that only exist on
  *      one side of the boundary: subnormal promotion, round-to-even ties,
  *      overflow to Inf, and underflow to zero.
  */
/** Test-only wrapper chaining widen->narrow inside one module, so the
  * round-trip sweep runs as a single simulation instead of two nested ones
  * (`chisel3.simulator` explicitly disallows nesting `simulate` calls).
  */
class Fp16RoundTripIO extends Bundle {
  val in  = Input(UInt(16.W))
  val out = Output(UInt(16.W))
}
class Fp16RoundTrip extends Module {
  val io = IO(new Fp16RoundTripIO)
  private val widen  = Module(new Fp16ToFp32)
  private val narrow = Module(new Fp32ToFp16)
  widen.io.in  := io.in
  narrow.io.in := widen.io.out
  io.out       := narrow.io.out
}

object FpConvertTests extends TestSuite {

  // --- Software oracle for the widen direction (exact) ---
  // Reconstructs the FP16 bit pattern's true value via Double math, then lets
  // Java's own (correctly-rounded, but here exact since it always fits)
  // double->float narrowing produce the FP32 bits. Independent of the RTL's
  // unpack/renormalize/pack implementation.
  def fp16BitsToDouble(bits: Int): Double = {
    val sign = if ((bits & 0x8000) != 0) -1.0 else 1.0
    val exp  = (bits >> 10) & 0x1f
    val mant = bits & 0x3ff
    if (exp == 0) {
      if (mant == 0) sign * 0.0 else sign * mant * math.pow(2, -24)
    } else if (exp == 31) {
      if (mant == 0) sign * Double.PositiveInfinity else Double.NaN
    } else {
      sign * (1.0 + mant / 1024.0) * math.pow(2, exp - 15)
    }
  }
  def refWiden(bits16: Int): Int =
    java.lang.Float.floatToRawIntBits(fp16BitsToDouble(bits16).toFloat)

  // --- Software oracle for the narrow direction (round-to-nearest-even) ---
  // Built directly from the IEEE round-to-nearest-even definition on the
  // 24-bit (implicit + stored) significand, independent of the RTL.
  def refNarrow(f: Float): Int = {
    val bits  = java.lang.Float.floatToRawIntBits(f)
    val sign16 = (bits >>> 16) & 0x8000
    val expF  = (bits >>> 23) & 0xff
    val mant  = bits & 0x7fffff
    if (expF == 0xff) {
      if (mant == 0) sign16 | 0x7c00 else 0x7e00 // Inf, or canonical NaN (unsigned, matches RTL)
    } else if (expF == 0) {
      sign16 // any FP32 subnormal/zero is far below FP16's smallest subnormal (2^-24)
    } else {
      val e = expF - 127 + 15 // unclamped FP16-biased exponent
      if (e >= 31) sign16 | 0x7c00
      else if (e <= 0) {
        val full  = mant | 0x800000
        val shift = 14 - e
        if (shift >= 25) sign16 // provably rounds to zero (see FpConvertTests header note)
        else {
          val keep      = full >>> shift
          val roundBit  = (full >>> (shift - 1)) & 1
          val stickyBit = (full & ((1 << (shift - 1)) - 1)) != 0
          val roundUp   = (roundBit == 1) && (stickyBit || (keep & 1) == 1)
          sign16 | (keep + (if (roundUp) 1 else 0)) // a carry out of the 10-bit field lands
                                                      // exactly on bit 10 = exp field 1 -- the
                                                      // correct subnormal->normal promotion.
        }
      } else {
        val shift     = 13
        val keep      = mant >>> shift
        val roundBit  = (mant >>> (shift - 1)) & 1
        val stickyBit = (mant & ((1 << (shift - 1)) - 1)) != 0
        val roundUp   = (roundBit == 1) && (stickyBit || (keep & 1) == 1)
        var m   = keep + (if (roundUp) 1 else 0)
        var exp = e
        if (m == 0x400) { m = 0; exp += 1 }
        if (exp >= 31) sign16 | 0x7c00 else sign16 | (exp << 10) | m
      }
    }
  }

  // Batch helpers: one simulator instance reused across every value, not one
  // per value -- each `simulate(new Module)` call costs several seconds of
  // its own (elaboration + backend spin-up), independent of how much work
  // runs inside it, so instantiating per-value turned this suite from
  // seconds into hours before being rewritten this way.
  def widenMany(bitsSeq: Seq[Int]): Seq[Int] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Int]
    simulate(new Fp16ToFp32) { d =>
      for (b <- bitsSeq) {
        d.io.in.poke(b.U)
        d.clock.step(1)
        out += d.io.out.peek().litValue.toInt
      }
    }
    out.toSeq
  }
  def narrowMany(bitsSeq: Seq[Int]): Seq[Int] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Int]
    simulate(new Fp32ToFp16) { d =>
      for (b <- bitsSeq) {
        d.io.in.poke((BigInt(b) & 0xffffffffL).U(32.W))
        d.clock.step(1)
        out += d.io.out.peek().litValue.toInt
      }
    }
    out.toSeq
  }
  def isNaN16(b: Int): Boolean = ((b >> 10) & 0x1f) == 0x1f && (b & 0x3ff) != 0
  def isNaN32(b: Int): Boolean = ((b >> 23) & 0xff) == 0xff && (b & 0x7fffff) != 0

  val tests = Tests {

    // 1. Round-trip identity over every non-NaN FP16 bit pattern, one
    // simulation of the chained wrapper module.
    utest.test("widen_narrow_roundtrip_sweep") {
      val inputs = (0 until 0x10000).filterNot(isNaN16)
      val outputs = scala.collection.mutable.ArrayBuffer.empty[Int]
      simulate(new Fp16RoundTrip) { d =>
        for (b <- inputs) {
          d.io.in.poke(b.U)
          d.clock.step(1)
          outputs += d.io.out.peek().litValue.toInt
        }
      }
      for ((b, back) <- inputs.zip(outputs)) {
        utest.assert(back == b)
      }
      utest.assert(inputs.size == 0x10000 - 2 * 1023) // 2046 NaN encodings excluded (exp=0x1f, mant 1..1023, both signs)
    }

    // 2a. Widen: exact-value spot checks against the Double-based oracle.
    utest.test("widen_spot_checks") {
      val vecs = Seq(
        0x0000, 0x8000, // +-0
        0x3c00, 0xbc00, // +-1.0
        0x4000, 0x3800, // 2.0, 0.5
        0x0001, 0x03ff, // smallest / largest subnormal
        0x0400,         // smallest normal
        0x7bff,         // largest finite (65504)
        0x7c00, 0xfc00, // +-Inf
        0x7e00           // NaN
      )
      val got = widenMany(vecs)
      for ((b, g) <- vecs.zip(got) if b != 0x7e00) {
        val want = refWiden(b)
        utest.assert(g == want)
      }
      // NaN: canonicalized, just check the output is still NaN-shaped.
      utest.assert(isNaN32(got.last))
    }

    // 2b. Narrow: round-to-nearest-even, overflow, underflow, subnormal
    // promotion -- values chosen to only be meaningful on the FP32 side.
    utest.test("narrow_spot_checks") {
      val cases = Seq(
        (0.0f, "zero"),
        (-0.0f, "neg zero"),
        (1.0f, "exact"),
        (1.0f + math.ulp(1.0f) * 4096, "needs rounding, round-down side"),
        (1.0009765625f, "exact tie boundary between two fp16 steps"),
        (65504.0f, "largest finite fp16, exact"),
        (70000.0f, "overflow to +Inf"),
        (-70000.0f, "overflow to -Inf"),
        (1e-10f, "underflow to zero"),
        (6.1e-5f, "rounds into a normal near the subnormal boundary"),
        (3e-8f, "rounds near the zero/smallest-subnormal boundary"),
        (6.097555e-5f, "largest-subnormal boundary, may promote to smallest normal"),
        (Float.PositiveInfinity, "+Inf passthrough"),
        (Float.NegativeInfinity, "-Inf passthrough")
      )
      val bitsSeq = cases.map(c => java.lang.Float.floatToRawIntBits(c._1)) :+
        java.lang.Float.floatToRawIntBits(Float.NaN)
      val got = narrowMany(bitsSeq)
      for (((f, note), g) <- cases.zip(got)) {
        val want = refNarrow(f)
        if (g != want)
          sys.error(s"narrow($f) [$note]: got 0x${g.toHexString} want 0x${want.toHexString}")
      }
      utest.assert(isNaN16(got.last))
    }
  }
}
