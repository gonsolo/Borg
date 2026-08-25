// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Independent verification of issue #2 (FP16 division exceeds the Vulkan
  * 2.5 ULP bound) against the real BorgCore/Fp16Special RTL via simulation —
  * not a re-transcription of the Chisel to Python, an actual run of the
  * hardware path (LUT ROM in BorgLane + Fp16Special interpolation +
  * BorgCore's FRCP/FMUL instruction execution).
  *
  * Now that the 33-entry rcpLut has landed, these sweeps assert the bound
  * rather than only reporting it, so they double as the regression guard for
  * that table. The printed worst cases are kept: a future table edit that
  * stays under 2.5 ULP but loses margin should still be visible.
  */
object FrcpPrecisionTests extends TestSuite {
  import BorgCoreTests._

  val config = BorgConfig.Default

  /** FP16 bits -> Double, exact (no float rounding in between). */
  def fp16BitsToDouble(b: BigInt): Double = {
    val bits = b.toInt & 0xffff
    val sign = if ((bits >>> 15) != 0) -1.0 else 1.0
    val exp  = (bits >>> 10) & 0x1f
    val mant = bits & 0x3ff
    if (exp == 0) {
      if (mant == 0) sign * 0.0
      else sign * mant.toDouble * math.pow(2.0, -24) // subnormal
    } else if (exp == 31) {
      if (mant == 0) sign * Double.PositiveInfinity else Double.NaN
    } else {
      sign * (1.0 + mant.toDouble / 1024.0) * math.pow(2.0, exp - 15)
    }
  }

  /** ULP of an FP16 value, computed from its own (correctly-rounded) magnitude. */
  def fp16Ulp(golden: Double): Double = {
    if (golden == 0.0) math.pow(2.0, -24)
    else {
      val e = math.floor(math.log(math.abs(golden)) / math.log(2.0)).toInt
      val eClamped = math.max(e, -14) // subnormal floor
      math.pow(2.0, eClamped - 10)
    }
  }

  def ulpError(actual: Double, golden: Double): Double =
    math.abs(actual - golden) / fp16Ulp(golden)

  def frcpViaCore(core: BorgCore, xBits: BigInt): BigInt = {
    resetCore(core)
    writeReg(core, 0, xBits)
    writeImem(core, 0, Instructions.FRCP(rs1 = 0, rd = 2))
    writeImem(core, 1, 0)
    startAndWait(core)
    readReg(core, 2)
  }

  def divViaCore(core: BorgCore, aBits: BigInt, bBits: BigInt): BigInt = {
    // Mirrors borgvk_compiler.c's lower_fdiv: a / b -> a * frcp(b), no refinement.
    resetCore(core)
    writeReg(core, 0, bBits)
    writeImem(core, 0, Instructions.FRCP(rs1 = 0, rd = 2))
    writeImem(core, 1, 0)
    startAndWait(core)
    val rcpB = readReg(core, 2)

    resetCore(core)
    writeReg(core, 0, aBits)
    writeReg(core, 1, rcpB)
    writeImem(core, 0, Instructions.MUL(rs1 = 0, rs2 = 1, rd = 2))
    writeImem(core, 1, 0)
    startAndWait(core)
    readReg(core, 2)
  }

  val tests = Tests {
    utest.test("frcp_ulp_sweep_exp15") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        var worst = 0.0
        var worstMant = 0
        var overCount = 0
        val bound = 2.5
        for (mant <- 0 until 1024) {
          val xBits = BigInt((15 << 10) | mant) // [1.0, 2.0)
          val golden = 1.0 / fp16BitsToDouble(xBits)
          val actual = fp16BitsToDouble(frcpViaCore(core, xBits))
          val err = ulpError(actual, golden)
          if (err > bound) overCount += 1
          if (err > worst) { worst = err; worstMant = mant }
        }
        println(f"[frcp exp15] worst=$worst%.3f ULP at mant=$worstMant over${bound}=$overCount/1024")
        // This was report-only while it was verifying an external claim about
        // hardware that did not yet meet the bound. With the 33-entry table it
        // does, so the claim becomes an assertion: 2.555 -> 0.955 ULP here.
        utest.assert(overCount == 0)
        utest.assert(worst <= bound)
      }
    }

    utest.test("frcp_ulp_sweep_exp10_and_exp20") {
      // Cross-check that the ULP-error pattern is exponent-invariant (same
      // mantissa interpolation logic regardless of binade), to justify
      // sweeping one binade instead of all 57,346 finite FP16 inputs.
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        for (expField <- Seq(10, 20)) {
          var worst = 0.0
          val bound = 2.5
          var overCount = 0
          for (mant <- 0 until 1024) {
            val xBits = BigInt((expField << 10) | mant)
            val golden = 1.0 / fp16BitsToDouble(xBits)
            val actual = fp16BitsToDouble(frcpViaCore(core, xBits))
            val err = ulpError(actual, golden)
            if (err > bound) overCount += 1
            if (err > worst) worst = err
          }
          println(f"[frcp exp=$expField] worst=$worst%.3f ULP over${bound}=$overCount/1024")
          utest.assert(overCount == 0)
          utest.assert(worst <= bound)
        }
      }
    }

    utest.test("fdiv_ulp_sample") {
      // Mirrors the issue's methodology: fixed set of numerators, sweep
      // divisors across one binade, exactly as borgvk_compiler.c lowers
      // OpFDiv (FRCP then FMUL, no refinement).
      //
      // The numerators are a fixed eight, so this worst case is a floor, not
      // a maximum. The exhaustive figure lives in BorgLutTables' comment: all
      // 1,048,576 mantissa pairs, 3.295 ULP before and 2.245 ULP after.
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        val numerators = Seq(1.0, 1.5, 2.0, 3.0, 7.0, -1.0, 0.5, 100.0)
          .map(f => floatToFp16Bits(f.toFloat))
        var worst = 0.0
        val bound = 2.5
        var overCount = 0
        var total = 0
        for (mant <- 0 until 1024) {
          val bBits = BigInt((15 << 10) | mant)
          val bVal = fp16BitsToDouble(bBits)
          for (aBits <- numerators) {
            val aVal = fp16BitsToDouble(aBits)
            val golden = aVal / bVal
            val actual = fp16BitsToDouble(divViaCore(core, aBits, bBits))
            val err = ulpError(actual, golden)
            total += 1
            if (err > bound) overCount += 1
            if (err > worst) worst = err
          }
        }
        println(f"[fdiv sample] worst=$worst%.3f ULP over${bound}=$overCount/$total")
        utest.assert(overCount == 0)
        utest.assert(worst <= bound)
      }
    }
  }
}
