// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import utest._
import memory.Ecp5PllParams.Ecp5PllParamsOps

/** Step A.1 verification: PLL parameter solver produces correct values
  * for the SDRAM configuration (25 MHz → 125 MHz + 125 MHz@90°).
  */
object Ecp5PllTests extends TestSuite {
  val tests: Tests = Tests {

    test("solve_25_to_125mhz") {
      val p = Ecp5PllParams(
        inHz   = 25000000L,
        out0Hz = 125000000L, out0Deg = 0,
        out1Hz = 125000000L, out1Deg = 90
      )
      val s = p.solved

      // Verify output frequency is exact
      assert(s.fOut == 125000000L)

      // Verify VCO is in range [400 MHz, 800 MHz]
      assert(s.fVco >= 400000000L)
      assert(s.fVco <= 800000000L)

      // Verify fundamental PLL equation: fOut = inHz * feedbackDiv / refclkDiv
      assert(p.inHz * s.feedbackDiv / s.refclkDiv == s.fOut)

      // Verify VCO: fVco = fOut * outputDiv
      assert(s.fOut * s.outputDiv == s.fVco)

      // Secondary1 should produce 125 MHz from VCO
      val sec1Freq = s.fVco / s.sec1Div
      assert(sec1Freq == 125000000L)

      // Phase shift for 90° on secondary1 should be non-zero
      // (secondary phase compensation = div*8 - 8, then + 8*div*90/360 = 2*div)
      assert(s.sec1CPhase > 0 || s.sec1FPhase > 0)

      // Print for human verification
      println(s"PLL solution: refclkDiv=${s.refclkDiv} feedbackDiv=${s.feedbackDiv} " +
        s"outputDiv=${s.outputDiv} fOut=${s.fOut} fVco=${s.fVco}")
      println(s"Primary: cphase=${s.primaryCPhase} fphase=${s.primaryFPhase}")
      println(s"Sec1: div=${s.sec1Div} cphase=${s.sec1CPhase} fphase=${s.sec1FPhase}")
    }

    test("solve_25_to_25mhz_passthrough") {
      val p = Ecp5PllParams(inHz = 25000000L, out0Hz = 25000000L)
      val s = p.solved

      assert(s.fOut == 25000000L)
      assert(s.fVco >= 400000000L)
      assert(s.fVco <= 800000000L)
    }

    test("solve_rejects_impossible_freq") {
      // 2 GHz from 25 MHz should fail — no VCO solution exists
      val threw = try {
        Ecp5PllParams(inHz = 25000000L, out0Hz = 2000000000L).solved
        false
      } catch {
        case e: IllegalArgumentException =>
          assert(e.getMessage.contains("fVco"))
          true
      }
      assert(threw)
    }

    test("solve_with_pixel_clock") {
      // 25 MHz → 125 MHz (0°) + 125 MHz (90°) + 25 MHz (0°) for pixel clock
      val p = Ecp5PllParams(
        inHz   = 25000000L,
        out0Hz = 125000000L, out0Deg = 0,
        out1Hz = 125000000L, out1Deg = 90,
        out2Hz = 25000000L,  out2Deg = 0
      )
      val s = p.solved

      assert(s.fOut == 125000000L)
      val sec2Freq = s.fVco / s.sec2Div
      // Pixel clock should be close to 25 MHz (VCO/div)
      assert(math.abs(sec2Freq - 25000000L) <= 1000000L) // within 1 MHz
    }
  }
}
