// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** FP32 coverage for the dispatcher's one genuinely width-dependent step:
  * the FP32 -> FP16 narrowing on the way into the tile buffer.
  *
  * BorgShaderDispatcherTests is pinned to FP16, which left the question of
  * what an FP32 build does here untested. The answer turns out to be
  * narrower than "the dispatcher needs FP32 coverage": TileWriteIO and
  * TileReadIO are 16-bit at every config (`new TileReadIO(16, cfg.samples)`),
  * so the tile buffer is FP16-native by design -- the FP32 plan's explicit
  * choice, "FP32 fragment output -> FP16 narrow before colour write". Blend
  * operates on 8-bit quantised values, the stencil plane is 8-bit, and the
  * depth compare is on 16-bit Z. None of those change with `cfg.fp`.
  *
  * What DOES change is BorgShaderDispatcher's `fragNarrow`:
  *
  *     def fragNarrow(d: UInt) =
  *       if (config.totalBits > 16) Fp16Fp32.narrow(d) else d(15, 0)
  *
  * and its comment names the failure mode precisely -- "narrow() rounds it to
  * the nearest FP16 value; a raw (15,0)" truncation would not. That is the
  * property worth pinning, and it is invisible to every FP16 test because at
  * FP16 the function is the identity.
  *
  * So this file deliberately does NOT re-test blend/stencil/MSAA at FP32:
  * those paths are bit-identical across configs by construction. It tests the
  * one place the config actually reaches.
  */
object BorgShaderDispatcherFp32Tests extends TestSuite {
  import BorgShaderDispatcherTestHelpers._

  private val FP32_BASE = BorgConfig.Default.copy(fp = FloatConfig.FP32, samples = 1)

  private def fp32Bits(f: Float): Long =
    java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL

  /** The reference narrowing: the JDK's own IEEE FP32 -> FP16 conversion.
    *
    * Deliberately NOT hand-rolled. The first version of this test computed
    * the expectation with math.round(), which rounds halves AWAY from zero,
    * and promptly disagreed with the hardware on 1.5004883 -- whose FP16
    * mantissa is exactly 512.5, a tie. IEEE (and Fp16Fp32.narrow) rounds
    * half to EVEN, giving 512; math.round gave 513. The hardware was right
    * and the reference was wrong, which is the whole hazard of writing a
    * second implementation to check the first. Float.floatToFloat16 (Java
    * 20+) is the spec conversion, so there is nothing left to get wrong.
    */
  private def refNarrow(f: Float): Int =
    java.lang.Float.floatToFloat16(f) & 0xffff

  val tests = Tests {

    utest.test("FP32 fragment colour is ROUNDED to FP16, not truncated") {
      // Each value is chosen so round-to-nearest and truncate-toward-zero
      // give DIFFERENT FP16 results: the bits below the FP16 mantissa are
      // above the halfway point, so rounding carries into the last kept bit
      // and truncation does not. If fragNarrow ever became `d(15, 0)` or a
      // plain mantissa chop, every one of these lands one ULP low.
      val cases = Seq(1.0009765f, 2.001953f, 0.50048828f, 1.5004883f, 3.0009766f)

      simulate(new BorgShaderDispatcher(FP32_BASE)) { d =>
        pokeIdle(d)
        // pokeIdle leaves tileRead.z at FP16_MAX_DEPTH, which is correct here:
        // Z stays FP16 at every config, so the depth test is unaffected by fp.
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        var checked = 0
        for (f <- cases) {
          val srcBits = fp32Bits(f)
          val expected = refNarrow(f)
          val truncated = (srcBits & 0xffff).toInt
          // The premise of the test: these must differ, or it proves nothing.
          utest.assert(expected != truncated)

          val w = runPixelFull(d,
            fragZ = 0x3000, oldZ = 0x4000,
            compareOp = CMP_LESS, writeEn = true,
            srcRgb = (srcBits.toInt, srcBits.toInt, srcBits.toInt))

          println(f"  $f%.7f: fp32=0x${srcBits}%08x -> tile=0x${w.r}%04x " +
                  f"(round=0x${expected}%04x, truncate would be 0x${truncated}%04x)")
          utest.assert(w.en)
          utest.assert(w.r == expected)
          utest.assert(w.g == expected)
          utest.assert(w.b == expected)
          checked += 1
        }
        utest.assert(checked == cases.length)
        println(s"  PASSED -- $checked values rounded, none truncated")
      }
    }

    utest.test("FP32 build still drives the tile write at all") {
      // A blunt smoke check that an FP32 dispatcher reaches sTileWrite with
      // the fragment enabled -- the elaboration suites prove it builds, this
      // proves it runs a pixel end to end.
      simulate(new BorgShaderDispatcher(FP32_BASE)) { d =>
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)
        val one = fp32Bits(1.0f).toInt
        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true, srcRgb = (one, one, one))
        utest.assert(w.en)
        utest.assert(w.r == refNarrow(1.0f))
        println(f"  FP32 pixel reached the tile write, colour=0x${w.r}%04x")
      }
    }
  }
}
