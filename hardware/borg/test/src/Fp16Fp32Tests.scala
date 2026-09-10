// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Tests for `Fp16Fp32.widen`/`narrow` (`Fp16Fp32.scala`), the FP32<->FP16
  * boundary conversions wired into `BorgCore.widenTexel` (texture-sample
  * output), `BorgLane.computeFp16Special` (Fp16Special's narrow-in/
  * widen-out), and `BorgShaderDispatcher.fragNarrow` (the tile-buffer write
  * boundary) -- commits a5953467 and 6919f1b4, item 5 of the
  * `feat/fp32-datapath` branch plan. No dedicated test existed for the
  * conversion functions themselves before this file; only whatever those
  * three call sites happen to exercise indirectly.
  *
  * `widen`/`narrow` deliberately FLUSH FP16 subnormals (and FP32 values that
  * would need to round into FP16's subnormal band) to a signed zero rather
  * than handling them exactly -- documented in `Fp16Fp32.scala` as a
  * defensible simplification since no current caller's real value range
  * (pixel coordinates, texel colours, rcp/rsqrt/sRGB operands) ever produces
  * one. The oracles below encode that same documented contract, not naive
  * IEEE-754 semantics, so a mismatch here means the RTL diverged from its
  * own documented behaviour, not that it lacks textbook precision.
  */
object Fp16Fp32Tests extends TestSuite {

  // --- widen oracle: exact widen for zero/normal/inf/nan, signed-zero flush for subnormal ---
  def fp16BitsToDouble(bits: Int): Double = {
    val sign = if ((bits & 0x8000) != 0) -1.0 else 1.0
    val exp  = (bits >> 10) & 0x1f
    val mant = bits & 0x3ff
    if (exp == 31) { if (mant == 0) sign * Double.PositiveInfinity else Double.NaN }
    else sign * (1.0 + mant / 1024.0) * math.pow(2, exp - 15) // exp==0 (zero) handled by the caller
  }
  def refWidenFlush(bits16: Int): Int = {
    val exp = (bits16 >> 10) & 0x1f
    if (exp == 0) (bits16 & 0x8000) << 16 // zero or subnormal -> signed zero
    else java.lang.Float.floatToRawIntBits(fp16BitsToDouble(bits16).toFloat)
  }

  // --- narrow oracle: a careful, from-scratch transcription of Fp16Fp32.narrow's
  // documented algorithm (round-to-nearest-even in range, flush underflow --
  // including the subnormal band -- to zero, saturate overflow to Inf).
  // Independent enough to catch a transcription slip in the RTL: written from
  // the docstring's description, not by reading the RTL's Chisel line by line.
  def refNarrowFlush(f: Float): Int = {
    val bits   = java.lang.Float.floatToRawIntBits(f)
    val sign16 = (bits >>> 16) & 0x8000
    val expF   = (bits >>> 23) & 0xff
    val mant32 = bits & 0x7fffff
    if (expF == 0xff) {
      if (mant32 == 0) sign16 | 0x7c00 else sign16 | 0x7c01 // Inf, or NaN with a 1-bit payload marker
    } else if (expF == 0) {
      sign16 // FP32 zero or subnormal -> signed zero
    } else {
      val eUnbiased = expF - 127
      if (eUnbiased < -14) sign16                 // underflow, incl. the fp16 subnormal band -> zero
      else if (eUnbiased > 15) sign16 | 0x7c00     // overflow -> Inf
      else {
        val keep      = mant32 >>> 13
        val guard     = (mant32 >>> 12) & 1
        val sticky    = (mant32 & 0xfff) != 0
        val roundUp   = (guard == 1) && (sticky || (keep & 1) == 1)
        val mantSum   = keep + (if (roundUp) 1 else 0)
        val carry     = (mantSum & 0x400) != 0
        val mant16    = if (carry) 0 else mantSum & 0x3ff
        val exp16     = (eUnbiased + 15 + (if (carry) 1 else 0)) & 0x1f
        sign16 | (exp16 << 10) | mant16
      }
    }
  }

  def isNaN16(b: Int): Boolean = ((b >> 10) & 0x1f) == 0x1f && (b & 0x3ff) != 0
  def isSubnormal16(b: Int): Boolean = ((b >> 10) & 0x1f) == 0 && (b & 0x3ff) != 0

  // Test-only wrapper so the round-trip sweep runs as one simulation.
  class RoundTripIO extends Bundle {
    val in  = Input(UInt(16.W))
    val out = Output(UInt(16.W))
  }
  class RoundTrip extends Module {
    val io = IO(new RoundTripIO)
    io.out := Fp16Fp32.narrow(Fp16Fp32.widen(io.in))
  }

  class WidenIO extends Bundle {
    val in  = Input(UInt(16.W))
    val out = Output(UInt(32.W))
  }
  class WidenWrap extends Module {
    val io = IO(new WidenIO)
    io.out := Fp16Fp32.widen(io.in)
  }
  class NarrowIO extends Bundle {
    val in  = Input(UInt(32.W))
    val out = Output(UInt(16.W))
  }
  class NarrowWrap extends Module {
    val io = IO(new NarrowIO)
    io.out := Fp16Fp32.narrow(io.in)
  }

  val tests = Tests {

    // 1. Round-trip: normal/zero/inf reproduce exactly; subnormal inputs
    // deliberately flush to a signed zero (the documented FTZ behaviour),
    // checked explicitly rather than skipped.
    utest.test("widen_narrow_roundtrip_sweep") {
      val allBits = (0 until 0x10000).filterNot(isNaN16)
      val outputs = scala.collection.mutable.ArrayBuffer.empty[Int]
      simulate(new RoundTrip) { d =>
        for (b <- allBits) {
          d.io.in.poke(b.U)
          d.clock.step(1)
          outputs += d.io.out.peek().litValue.toInt
        }
      }
      for ((b, back) <- allBits.zip(outputs)) {
        val want = if (isSubnormal16(b)) (b & 0x8000) else b
        if (back != want)
          sys.error(s"roundtrip(0x${b.toHexString}): got 0x${back.toHexString} want 0x${want.toHexString}")
      }
    }

    // 2a. Widen spot checks against the oracle.
    utest.test("widen_spot_checks") {
      val vecs = Seq(
        0x0000, 0x8000,           // +-0
        0x3c00, 0xbc00,           // +-1.0
        0x4000, 0x3800,           // 2.0, 0.5
        0x0001, 0x03ff, 0x83ff,   // subnormal -> must flush to signed zero
        0x0400,                   // smallest normal
        0x7bff,                   // largest finite (65504)
        0x7c00, 0xfc00            // +-Inf
      )
      val outputs = scala.collection.mutable.ArrayBuffer.empty[Int]
      simulate(new WidenWrap) { d =>
        for (b <- vecs) {
          d.io.in.poke(b.U)
          d.clock.step(1)
          outputs += d.io.out.peek().litValue.toInt
        }
      }
      for ((b, g) <- vecs.zip(outputs)) {
        val want = refWidenFlush(b)
        if (g != want)
          sys.error(s"widen(0x${b.toHexString}): got 0x${g.toHexString} want 0x${want.toHexString}")
      }
    }

    // 2b. Narrow spot checks: round-to-nearest-even in range, overflow to
    // Inf, underflow (incl. the fp16 subnormal band) flushed to zero.
    utest.test("narrow_spot_checks") {
      val cases = Seq(
        (0.0f, "zero"), (-0.0f, "neg zero"), (1.0f, "exact"),
        (1.0f + math.ulp(1.0f) * 4096, "needs rounding, round-down side"),
        (1.0009765625f, "exact tie boundary between two fp16 steps"),
        (65504.0f, "largest finite fp16, exact"),
        (70000.0f, "overflow to +Inf"), (-70000.0f, "overflow to -Inf"),
        (1e-10f, "deep underflow to zero"),
        (6.1e-5f, "normal, near the (skipped) subnormal boundary"),
        (3e-8f, "would need an fp16 subnormal -> flushed to zero"),
        (Float.PositiveInfinity, "+Inf passthrough"),
        (Float.NegativeInfinity, "-Inf passthrough")
      )
      val outputs = scala.collection.mutable.ArrayBuffer.empty[Int]
      simulate(new NarrowWrap) { d =>
        for ((f, _) <- cases) {
          val bits = BigInt(java.lang.Float.floatToRawIntBits(f)) & 0xffffffffL
          d.io.in.poke(bits.U(32.W))
          d.clock.step(1)
          outputs += d.io.out.peek().litValue.toInt
        }
      }
      for (((f, note), g) <- cases.zip(outputs)) {
        val want = refNarrowFlush(f)
        if (g != want)
          sys.error(s"narrow($f) [$note]: got 0x${g.toHexString} want 0x${want.toHexString}")
      }
    }
  }
}
