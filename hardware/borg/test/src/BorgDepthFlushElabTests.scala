// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import utest._

/** Elaboration-only checks for the optional depth-attachment flush path
  * (Step 50 item 14).
  *
  * These deliberately do NOT run a simulator: they call ChiselStage directly,
  * so they catch the whole class of integration bugs that show up at
  * elaboration -- an unconnected port, a width mismatch, a `require` that
  * fires, a switch/Enum misuse -- in seconds rather than minutes. That
  * matters here because the full-pipeline simulation suites are currently
  * unrunnable on this machine (BorgSequencerTests sat 3461s without
  * completing a single test), so an elaboration gate is the cheapest real
  * coverage available for top-level wiring.
  *
  * Behavioural verification of the burst itself lives in
  * [[BorgTileFlusherTests]], which does run.
  */
object BorgDepthFlushElabTests extends TestSuite {

  private def elaborate(cfg: BorgConfig): String =
    circt.stage.ChiselStage.emitCHIRRTL(new Borg(cfg))

  val tests = Tests {

    utest.test("Borg elaborates with hasDepthFlush enabled") {
      // The integration path: BorgConfig knob -> BorgTileFlusher constructor
      // -> depthBase/depthEn ports -> Borg.scala's FLUSH_ZB_BASE decode.
      // Any unconnected/mismatched port in that chain fails right here.
      val chirrtl = elaborate(BorgConfig.Default.copy(hasDepthFlush = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasDepthFlush=true) elaborated cleanly")
    }

    utest.test("Borg elaborates unchanged with hasDepthFlush disabled") {
      val chirrtl = elaborate(BorgConfig.Default)
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasDepthFlush=false) elaborated cleanly")
    }

    utest.test("disabled build instantiates no depth-flush hardware") {
      // The claim made in BorgTileFlusher's doc comment: a disabled build
      // carries no zVec and no depth ports. Checked structurally against the
      // emitted CHIRRTL rather than taken on trust -- `depthBase`/`depthEn`
      // are the port names, and zVec shows up as a `zVec` register.
      val off = elaborate(BorgConfig.Default)
      val on  = elaborate(BorgConfig.Default.copy(hasDepthFlush = true))
      utest.assert(!off.contains("depthBase"))
      utest.assert(!off.contains("zVec"))
      utest.assert(on.contains("depthBase"))
      utest.assert(on.contains("zVec"))
      println("  disabled: no depthBase/zVec; enabled: both present")
    }

    utest.test("hasDepthFlush with MSAA is a build error, not silent wrongness") {
      // BorgTileFlusher.require: averaging samples is the wrong resolve for
      // depth, so the unsupported combination must fail loudly.
      val thrown =
        try { elaborate(BorgConfig.Default.copy(hasDepthFlush = true, samples = 4)); false }
        catch { case _: Throwable => true }
      utest.assert(thrown)
      println("  hasDepthFlush + samples=4 correctly rejected")
    }
  }
}
