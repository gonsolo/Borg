// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import hardfloat._

/** Co-simulation harness: drives BorgFp16Fma and HardFloat MulAddRecFN with the
  * SAME a/b/c/negate and exposes both outputs.  Removes any doubt about whether
  * the BigInt oracle matches HardFloat — this compares the two RTL units directly.
  */
class FmaCompareIO(cfg: BorgConfig) extends Bundle {
  val a = Input(UInt(cfg.totalBits.W))
  val b = Input(UInt(cfg.totalBits.W))
  val c = Input(UInt(cfg.totalBits.W))
  val negate = Input(Bool())
  val outCustom = Output(UInt(cfg.totalBits.W))
  val outHf     = Output(UInt(cfg.totalBits.W))
}

class FmaCompare(cfg: BorgConfig) extends Module {
  val io = IO(new FmaCompareIO(cfg))
  // Custom unit (one internal pipeline register; pipeEn held high).
  val cust = Module(new BorgFp16Fma(cfg))
  cust.io.a := io.a; cust.io.b := io.b; cust.io.c := io.c
  cust.io.negate := io.negate; cust.io.pipeEn := true.B
  io.outCustom := cust.io.out

  // HardFloat reference: op(1)=negate product.  Computes (negate?-(a*b):a*b)+c.
  val hf = Module(new MulAddRecFN(cfg.exp, cfg.sig))
  hf.io.op := Mux(io.negate, 2.U, 0.U)
  hf.io.a  := recFNFromFN(cfg.exp, cfg.sig, io.a)
  hf.io.b  := recFNFromFN(cfg.exp, cfg.sig, io.b)
  hf.io.c  := recFNFromFN(cfg.exp, cfg.sig, io.c)
  hf.io.roundingMode := 0.U
  hf.io.detectTininess := 1.U
  hf.io.valid := true.B
  // Register HF output one cycle to align with the custom unit's pipe register.
  io.outHf := RegNext(fNFromRecFN(cfg.exp, cfg.sig, hf.io.out))
}

/** Phase 1: Standalone verification of BorgFp16Fma vs an exact-arithmetic oracle.
  *
  * The oracle computes `(negate ? -(a*b) : a*b) + c` EXACTLY in BigInt, then
  * rounds once to FP16 round-to-nearest-even.  This is the true single-rounded
  * FMA — bit-identical to Berkeley HardFloat's `MulAddRecFN` (mode 0) over the
  * GPU's domain — so matching the oracle proves the drop-in replacement is safe.
  *
  * Coverage: structured boundary cases (0, ±1, powers of two, max/min normal,
  * subnormals, cancellation) + seeded random sampling across the normal range.
  * Inf/NaN inputs (exp==31) are excluded (out of GPU domain).
  */
object BorgFp16FmaTests extends TestSuite {

  // ---- exact FP16 FMA oracle (BigInt) ----------------------------------------

  /** Unpack FP16 bits → (sign:+1/-1, sigInt, exp2) with value = sign*sigInt*2^exp2. */
  private def unpack(bits: Int): (Int, BigInt, Int) = {
    val s = (bits >> 15) & 1
    val e = (bits >> 10) & 0x1f
    val f = bits & 0x3ff
    val sign = if (s == 1) -1 else 1
    if (e == 0) (sign, BigInt(f), -24)                 // zero (f=0) or subnormal
    else (sign, BigInt(1024 + f), e - 25)              // normal: (1.f)<<10, exp e-15-10
  }

  /** Round real value (mag*2^exp2, mag>=0) to FP16 RNE, with sign. */
  private def roundToFp16(sign: Int, mag: BigInt, exp2: Int): Int = {
    val sbit = if (sign < 0) 0x8000 else 0
    if (mag == 0) return sbit
    val nbits = mag.bitLength
    val msbExpUnb = exp2 + nbits - 1          // unbiased base-2 exponent of the MSB
    val biased = msbExpUnb + 15
    val effBiased = math.max(biased, 1)        // subnormals anchor at exp 1
    val outLSBexp2 = effBiased - 15 - 10       // weight of frac bit0
    val dropBits = outLSBexp2 - exp2
    val sigRounded: BigInt =
      if (dropBits <= 0) mag << (-dropBits)    // exact, room to spare
      else {
        val kept = mag >> dropBits
        val rem  = mag - (kept << dropBits)
        val half = BigInt(1) << (dropBits - 1)
        if (rem > half) kept + 1
        else if (rem < half) kept
        else kept + (kept & 1)                 // tie → round to even
      }
    if (biased <= 0) {
      // subnormal (or rounded up to smallest normal)
      if (sigRounded >= 1024) sbit | (1 << 10)            // became normal, frac 0
      else sbit | sigRounded.toInt
    } else {
      if (sigRounded >= 2048) {                            // rounding carried out
        val exp = biased + 1
        if (exp >= 31) sbit | (31 << 10) else sbit | (exp << 10)
      } else {
        if (biased >= 31) sbit | (31 << 10)                // overflow → Inf
        else sbit | (biased << 10) | (sigRounded.toInt - 1024)
      }
    }
  }

  private def isInf(x: Int)  = ((x >> 10) & 0x1f) == 0x1f && (x & 0x3ff) == 0
  private def isNaN(x: Int)  = ((x >> 10) & 0x1f) == 0x1f && (x & 0x3ff) != 0
  private def isZeroV(x: Int) = ((x >> 10) & 0x1f) == 0 && (x & 0x3ff) == 0

  /** True single-rounded FP16 FMA → FP16 bits (IEEE Inf/NaN rules, matching HardFloat). */
  private def trueFma(a: Int, b: Int, c: Int, negate: Boolean): Int = {
    def sgn(x: Int) = if (((x >> 15) & 1) == 1) -1 else 1
    val prodNeg = (sgn(a) * sgn(b) * (if (negate) -1 else 1)) < 0
    val cNeg = sgn(c) < 0
    val anyNaN = isNaN(a) || isNaN(b) || isNaN(c)
    val infTimesZero = (isInf(a) && isZeroV(b)) || (isZeroV(a) && isInf(b))
    val prodInf = (isInf(a) || isInf(b)) && !infTimesZero
    val cInfV = isInf(c)
    val infMinusInf = prodInf && cInfV && (prodNeg != cNeg)
    if (anyNaN || infTimesZero || infMinusInf) return 0x7E00
    if (prodInf) return (if (prodNeg) 0x8000 else 0) | 0x7C00
    if (cInfV) return (if (cNeg) 0x8000 else 0) | 0x7C00

    val (sa, sigA, ea) = unpack(a)
    val (sb, sigB, eb) = unpack(b)
    val (sc, sigC, ec) = unpack(c)
    val signP = sa * sb * (if (negate) -1 else 1)
    val sigP  = sigA * sigB
    val expP  = ea + eb
    // align product and c to a common exponent, sum exactly
    val common = math.min(expP, ec)
    val pv = BigInt(signP) * (sigP << (expP - common))
    val cv = BigInt(sc)    * (sigC << (ec - common))
    val sum = pv + cv
    val sign = if (sum < 0) -1 else 1
    roundToFp16(sign, sum.abs, common)
  }

  // ---- driving the DUT --------------------------------------------------------

  private def eval(dut: BorgFp16Fma, a: Int, b: Int, c: Int, negate: Boolean): Int = {
    dut.io.a.poke(a.U)
    dut.io.b.poke(b.U)
    dut.io.c.poke(c.U)
    dut.io.negate.poke(negate.B)
    dut.io.pipeEn.poke(true.B)
    dut.clock.step(1)        // capture stage1 → pipeline register
    dut.io.out.peek().litValue.toInt & 0xffff
  }

  /** Is this FP16 bit pattern a special (Inf/NaN)?  Excluded from the domain. */
  private def isSpecial(bits: Int): Boolean = ((bits >> 10) & 0x1f) == 0x1f

  private def fmt(bits: Int): String = f"0x$bits%04x"

  // A spread of representative FP16 values (both signs) for structured testing.
  private val structured: Seq[Int] = {
    val pos = Seq(
      0x0000, // +0
      0x0001, // smallest subnormal
      0x03ff, // largest subnormal
      0x0400, // smallest normal
      0x3c00, // 1.0
      0x3800, // 0.5
      0x4000, // 2.0
      0x4200, // 3.0
      0x4400, // 4.0
      0x3555, // ~0.333
      0x7bff, // max normal 65504
      0x0200, // a subnormal
      0x1400, // small normal
      0x6000, // large-ish
      0x4900  // 10.0
    )
    pos ++ pos.map(_ ^ 0x8000)   // add negatives
  }

  val tests = Tests {

    // DEFINITIVE: compare BorgFp16Fma directly against HardFloat RTL (not the oracle).
    utest.test("vs_hardfloat") {
      simulate(new FmaCompare(BorgConfig.Default)) { dut =>
        dut.clock.step(1)  // prime the RegNext alignment
        val rng = new scala.util.Random(0x5A5A5A)
        var fails = 0
        var n = 0
        val N = 30000
        // Mix render-realistic and full-range patterns.
        def gen(): Int = {
          if (rng.nextBoolean()) {
            val sign = rng.nextInt(2) << 15; val e = 5 + rng.nextInt(21); sign | (e << 10) | rng.nextInt(0x400)
          } else rng.nextInt(0x10000)
        }
        while (n < N) {
          val a = gen(); val b = gen(); val c = gen(); val neg = rng.nextBoolean()
          dut.io.a.poke(a.U); dut.io.b.poke(b.U); dut.io.c.poke(c.U); dut.io.negate.poke(neg.B)
          dut.clock.step(1)
          val gotC = dut.io.outCustom.peek().litValue.toInt & 0xffff
          val gotH = dut.io.outHf.peek().litValue.toInt & 0xffff
          if (gotC != gotH) {
            if (fails < 30) println(s"DIVERGE a=${fmt(a)} b=${fmt(b)} c=${fmt(c)} neg=$neg : custom=${fmt(gotC)} hf=${fmt(gotH)}")
            fails += 1
          }
          n += 1
        }
        if (fails > 0) println(s"vs_hardfloat: $fails / $N diverge")
        utest.assert(fails == 0)
      }
    }

    utest.test("structured_cases") {
      simulate(new BorgFp16Fma()) { dut =>
        var fails = 0
        for (a <- structured; b <- structured; c <- structured; neg <- Seq(false, true)) {
          val got = eval(dut, a, b, c, neg)
          val exp = trueFma(a, b, c, neg)
          if (got != exp && fails < 20) {
            println(s"MISMATCH a=${fmt(a)} b=${fmt(b)} c=${fmt(c)} neg=$neg : got=${fmt(got)} exp=${fmt(exp)}")
            fails += 1
          } else if (got != exp) fails += 1
        }
        utest.assert(fails == 0)
      }
    }

    utest.test("random_sample") {
      simulate(new BorgFp16Fma()) { dut =>
        val rng = new scala.util.Random(0xB0B5EED)
        var fails = 0
        var n = 0
        val N = 20000
        while (n < N) {
          val a = rng.nextInt(0x10000)
          val b = rng.nextInt(0x10000)
          val c = rng.nextInt(0x10000)
          if (!isSpecial(a) && !isSpecial(b) && !isSpecial(c)) {
            val neg = rng.nextBoolean()
            val got = eval(dut, a, b, c, neg)
            val exp = trueFma(a, b, c, neg)
            if (got != exp) {
              if (fails < 20)
                println(s"MISMATCH a=${fmt(a)} b=${fmt(b)} c=${fmt(c)} neg=$neg : got=${fmt(got)} exp=${fmt(exp)}")
              fails += 1
            }
            n += 1
          }
        }
        if (fails > 0) println(s"random_sample: $fails / $N mismatches")
        utest.assert(fails == 0)
      }
    }

    // Render-realistic ranges: normals near the values the shaders actually use
    // (coordinates ~0..512, colors 0..1, intermediates near 1.0).  Uniform-over-
    // all-bit-patterns under-samples this; bias the exponent field to [5,25].
    utest.test("render_realistic") {
      simulate(new BorgFp16Fma()) { dut =>
        val rng = new scala.util.Random(0x12345)
        def realistic(): Int = {
          val sign = rng.nextInt(2) << 15
          val e = 5 + rng.nextInt(21)        // exp field 5..25  (~2^-10 .. 2^10)
          val f = rng.nextInt(0x400)
          sign | (e << 10) | f
        }
        var fails = 0
        val N = 40000
        for (_ <- 0 until N) {
          val a = realistic(); val b = realistic(); val c = realistic()
          val neg = rng.nextBoolean()
          val got = eval(dut, a, b, c, neg)
          val exp = trueFma(a, b, c, neg)
          if (got != exp) {
            if (fails < 30)
              println(s"MISMATCH a=${fmt(a)} b=${fmt(b)} c=${fmt(c)} neg=$neg : got=${fmt(got)} exp=${fmt(exp)}")
            fails += 1
          }
        }
        if (fails > 0) println(s"render_realistic: $fails / $N mismatches")
        utest.assert(fails == 0)
      }
    }

    // Inf/NaN inputs + overflow: edge functions overflow FP16 to ±Inf, which
    // must then propagate as proper Inf arithmetic (NOT blanket NaN) for the
    // rasterizer's sign-based inside/outside test.
    utest.test("inf_and_overflow") {
      simulate(new BorgFp16Fma()) { dut =>
        val rng = new scala.util.Random(0xF00D)
        // Pool: ±Inf, large normals (whose products overflow), zeros, mid normals.
        val pool = Seq(
          0x7c00, 0xfc00,                 // ±Inf
          0x7bff, 0xfbff,                 // ±max normal 65504
          0x7800, 0xf800,                 // ±32768 (product overflows)
          0x6000, 0xe000,                 // ±large
          0x0000, 0x8000,                 // ±0
          0x3c00, 0xbc00,                 // ±1
          0x4900, 0x5640                  // 10, 100
        )
        var fails = 0
        for (a <- pool; b <- pool; c <- pool; neg <- Seq(false, true)) {
          val got = eval(dut, a, b, c, neg)
          val exp = trueFma(a, b, c, neg)
          if (got != exp) {
            if (fails < 30) println(s"MISMATCH a=${fmt(a)} b=${fmt(b)} c=${fmt(c)} neg=$neg : got=${fmt(got)} exp=${fmt(exp)}")
            fails += 1
          }
        }
        if (fails > 0) println(s"inf_and_overflow: $fails mismatches")
        utest.assert(fails == 0)
      }
    }

    // Pipeline-timing test: in the real core pipeEn is a 1-cycle PULSE, with the
    // result read on a later cycle while inputs may change.  Verify the held
    // result survives input changes after the capture edge.
    utest.test("pipeline_hold") {
      simulate(new BorgFp16Fma()) { dut =>
        val rng = new scala.util.Random(0xABCDE)
        var fails = 0
        for (_ <- 0 until 5000) {
          val a = rng.nextInt(0x10000); val b = rng.nextInt(0x10000); val c = rng.nextInt(0x10000)
          if (!isSpecial(a) && !isSpecial(b) && !isSpecial(c)) {
            // Capture cycle: inputs valid, pipeEn pulses high.
            dut.io.a.poke(a.U); dut.io.b.poke(b.U); dut.io.c.poke(c.U)
            dut.io.negate.poke(false.B); dut.io.pipeEn.poke(true.B)
            dut.clock.step(1)
            // Subsequent cycles: pipeEn low, inputs change to garbage; held result must stand.
            dut.io.a.poke(0x7777.U); dut.io.b.poke(0x1234.U); dut.io.c.poke(0x4321.U)
            dut.io.pipeEn.poke(false.B)
            dut.clock.step(1)
            val got = dut.io.out.peek().litValue.toInt & 0xffff
            val exp = trueFma(a, b, c, false)
            if (got != exp) {
              if (fails < 20) println(s"HOLD MISMATCH a=${fmt(a)} b=${fmt(b)} c=${fmt(c)} : got=${fmt(got)} exp=${fmt(exp)}")
              fails += 1
            }
          }
        }
        if (fails > 0) println(s"pipeline_hold: $fails mismatches")
        utest.assert(fails == 0)
      }
    }
  }
}
