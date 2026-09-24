// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** FRCP and FRSQ on an FP32 build keep FP32's exponent range: the LUT core
  * sees only the mantissa, and the exponent is applied around it. */
object FrcpFp32RangeTests extends TestSuite {
  import BorgCoreTestHelpers._

  val tests = Tests {
    utest.test("frcp_and_frsq_cover_the_fp32_exponent_range") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: FRCP/FRSQ over FP32's range ---")
        idleInputs(core)
        resetCore(core)
        writeImem(core, 0, Instructions.FRCP(rs1 = 0, rd = 2))
        writeImem(core, 1, Instructions.FRSQ(rs1 = 0, rd = 3))
        writeImem(core, 2, 0)
        def run(x: Float): (Float, Float) = {
          writeReg(core, 0, floatToBits(x))
          resetCore(core)                        // back to PC 0
          startAndWait(core)
          (bitsToFloat(readReg(core, 2)), bitsToFloat(readReg(core, 3)))
        }
        // Setup determinants and w's live far outside FP16's 6e-5..65504.
        // The LUT core is ~11 bits; one Newton step in the shader doubles it.
        for (x <- Seq(1.0f, 0.75f, 3.0e7f, 1.7e-7f, 6.0e20f, 1.0e-30f, 2.5e37f, 8.0f, 2.0f, 4.0e10f, 2.5e-9f)) {
          val (rcp, rsq) = run(x)
          val eRcp = math.abs(rcp * x - 1.0)
          val eRsq = math.abs(rsq * rsq * x - 1.0)
          println(f"  x=$x%-10.3e 1/x=$rcp%-11.4e (rel err $eRcp%.1e)  1/sqrt(x)=$rsq%-11.4e (rel err ${eRsq / 2}%.1e)")
          utest.assert(eRcp < 1.0 / 512)
          utest.assert(eRsq < 2.0 / 512)
        }
        val (rn, _) = run(-3.0e7f)
        utest.assert(math.abs(rn * -3.0e7f - 1.0) < 1.0 / 512)
        val (r0, s0) = run(0.0f)
        println(s"  x=0: 1/x=$r0 1/sqrt(x)=$s0")
        utest.assert(r0.isPosInfinity && s0.isPosInfinity)
        val (ri, si) = run(Float.PositiveInfinity)
        utest.assert(ri == 0.0f && si == 0.0f)
        val (_, sneg) = run(-4.0f)
        utest.assert(sneg.isNaN)
        println("  PASSED")
      }
    }
  }
}
