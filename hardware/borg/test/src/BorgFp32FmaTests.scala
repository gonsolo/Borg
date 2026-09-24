// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** The FP32 FMA against java.lang.Math.fma, which rounds once, to nearest
  * even: what SPIR-V needs of OpFAdd, OpFMul (computed as 1*a+b and a*b+0)
  * and fma. Random bit patterns, near-cancelling sums, and every triple of an
  * edge-value set (zeros, infinities, NaN, the normal and subnormal
  * boundaries, ulp neighbours). Bit-exact, with Vulkan's allowances only: any
  * NaN for a NaN, and a subnormal result may be flushed to a zero. A signed-
  * zero difference is counted separately (signed zeros need
  * SignedZeroInfNanPreserve, which Borg does not report). */
object BorgFp32FmaTests extends TestSuite {
  def f2b(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  def b2f(b: Long): Float = java.lang.Float.intBitsToFloat(b.toInt)

  def check(cfg: BorgConfig, triples: Seq[(Float, Float, Float)]): (Int, Int, Int) = {
    var bad = 0; var zeroSign = 0; var flushed = 0
    simulate(new BorgFp16Fma(cfg)) { d =>
      d.io.negate.poke(false.B); d.io.pipeEn1.poke(true.B); d.io.pipeEn2.poke(true.B)
      for ((a, b, c) <- triples) {
        d.io.a.poke(f2b(a).U); d.io.b.poke(f2b(b).U); d.io.c.poke(f2b(c).U)
        d.clock.step(cfg.fmaStages + 2)
        val got = d.io.out.peek().litValue.toLong
        val exp = Math.fma(a, b, c)
        val g = b2f(got)
        val ok =
          if (exp.isNaN) g.isNaN
          else if (got == f2b(exp)) true
          else if (g == 0f && exp == 0f) { zeroSign += 1; true }
          else if (g == 0f && math.abs(exp) < java.lang.Float.MIN_NORMAL) { flushed += 1; true }
          else false
        if (!ok) {
          if (bad < 8) println(f"  MISMATCH fma($a%s, $b%s, $c%s) = $g%s (0x$got%08x), expected $exp%s (0x${f2b(exp)}%08x)")
          bad += 1
        }
      }
    }
    (bad, zeroSign, flushed)
  }

  val edges: Seq[Float] = Seq(0f, -0f, 1f, -1f, Float.PositiveInfinity, Float.NegativeInfinity, Float.NaN,
    Float.MaxValue, -Float.MaxValue, java.lang.Float.MIN_NORMAL, -java.lang.Float.MIN_NORMAL,
    Float.MinPositiveValue, 3e-39f, java.lang.Math.nextUp(1f), java.lang.Math.nextDown(1f),
    1.5f, 3.4e38f, 1e-20f, -7.25f)

  def randomTriples(n: Int, seed: Int): Seq[(Float, Float, Float)] = {
    val r = new scala.util.Random(seed)
    def any = b2f(r.nextInt().toLong & 0xFFFFFFFFL)
    def normal(e: Int) = (r.nextFloat() + 0.5f) * math.pow(2, e).toFloat * (if (r.nextBoolean()) 1 else -1)
    (0 until n).map { i =>
      i % 3 match {
        case 0 => (any, any, any)
        case 1 => val e = r.nextInt(40) - 20; (normal(e), normal(r.nextInt(6) - 3), normal(e))         // near cancellation
        case _ => val a = normal(r.nextInt(60) - 30); val b = normal(r.nextInt(60) - 30); (a, b, -a * b * (1 + r.nextFloat() * 1e-6f))
      }
    }
  }

  val tests = Tests {
    utest.test("fp32_fma_is_correctly_rounded") {
      for (cfg <- Seq(BorgConfig.Test, BorgConfig.Wafer)) {
        val vectors = randomTriples(6000, 1) ++ (for (a <- edges; b <- edges; c <- edges) yield (a, b, c))
        val (bad, zs, fl) = check(cfg, vectors)
        println(s"  fmaStages ${cfg.fmaStages}: ${vectors.size} vectors, $bad wrong; $zs signed-zero only, $fl subnormals flushed")
        utest.assert(bad == 0)
      }
    }
  }
}
