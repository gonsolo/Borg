// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._

/** FEXP2 and FLOG2 against Vulkan's precision (exp2: 3 + 2|x| ULP; log2: 3 ULP, or 2^-21
  * absolute in [0.5, 2]) on random and edge-case inputs, and their special values. */
object BorgExpLogTests extends TestSuite {
  def bits(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f)) & 0xFFFFFFFFL
  def flt(b: BigInt): Float = java.lang.Float.intBitsToFloat(b.toInt)
  def ulp(v: Double): Double = math.ulp(v.toFloat).toDouble

  def run(log: Boolean, xs: Seq[Float]): Seq[Float] = {
    var out = Seq.empty[Float]
    simulate(new BorgExpLog) { dut =>
      dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
      dut.io.log.poke(log.B)
      val got = scala.collection.mutable.ArrayBuffer[Float]()
      for (x <- xs ++ Seq.fill(3)(0f)) {
        dut.io.in.valid.poke(true.B); dut.io.in.bits.poke(bits(x).U)
        dut.clock.step(1)
        if (dut.io.out.valid.peek().litToBoolean) got += flt(dut.io.out.bits.peek().litValue)
      }
      out = got.toSeq.take(xs.size)
    }
    out
  }

  val rnd = new scala.util.Random(7)

  val tests = Tests {
    utest.test("flog2_meets_vulkan_precision") {
      val xs = Seq(1f, 2f, 0.5f, 4f, 1024f, 3f, 0.75f, 1.0000001f, 0.99999994f, Float.MaxValue, java.lang.Float.MIN_NORMAL) ++
        Seq.fill(4000)(math.pow(2, rnd.nextDouble() * 250 - 125).toFloat) ++ Seq.fill(4000)((0.5 + rnd.nextDouble() * 1.5).toFloat)
      val got = run(log = true, xs)
      var worst = 0.0
      for ((x, g) <- xs.zip(got)) {
        val ref = math.log(x.toDouble) / math.log(2)
        val err = math.abs(g - ref)
        val ok = if (x >= 0.5f && x <= 2f) err <= math.pow(2, -21) else err <= 3 * ulp(ref)
        if (!ok) println(f"  log2($x%e) = $g%e, expect $ref%e")
        utest.assert(ok)
        if (!(x >= 0.5f && x <= 2f)) worst = worst max err / ulp(ref)
      }
      println(f"  flog2: ${xs.size} inputs, worst ${worst}%.2f ULP outside [0.5, 2]; log2(1) = ${got(0)}, log2(1024) = ${got(4)}")
      utest.assert(got(0) == 0f && got(1) == 1f && got(2) == -1f && got(4) == 10f)
    }

    utest.test("fexp2_meets_vulkan_precision") {
      val xs = Seq(0f, 1f, -1f, 10f, 0.5f, -0.5f, 127f, -126f, 1e-8f, -1e-8f) ++
        Seq.fill(4000)((rnd.nextDouble() * 252 - 126).toFloat) ++ Seq.fill(4000)((rnd.nextDouble() * 2 - 1).toFloat)
      val got = run(log = false, xs)
      var worst = 0.0
      for ((x, g) <- xs.zip(got)) {
        val ref = math.pow(2, x.toDouble)
        val u = math.abs(g - ref) / ulp(ref)
        val ok = u <= 3 + 2 * math.abs(x)
        if (!ok) println(f"  exp2($x%e) = $g%e, expect $ref%e ($u%.2f ULP)")
        utest.assert(ok)
        worst = worst max u
      }
      println(f"  fexp2: ${xs.size} inputs, worst $worst%.2f ULP; exp2(10) = ${got(3)}")
      utest.assert(got(0) == 1f && got(1) == 2f && got(2) == 0.5f && got(3) == 1024f)
    }

    utest.test("special_values") {
      val (inf, ninf, nan) = (Float.PositiveInfinity, Float.NegativeInfinity, Float.NaN)
      val l = run(log = true, Seq(0f, -0f, -1f, inf, ninf, nan, 1e-40f))
      utest.assert(l(0) == ninf && l(1) == ninf && l(2).isNaN && l(3) == inf && l(4).isNaN && l(5).isNaN && l(6) == ninf)
      val e = run(log = false, Seq(inf, ninf, nan, 128f, -150f, 200f))
      utest.assert(e(0) == inf && e(1) == 0f && e(2).isNaN && e(3) == inf && e(4) == 0f && e(5) == inf)
    }
  }
}
