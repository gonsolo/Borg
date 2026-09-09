// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import utest._

/** Elaboration-only checks for the optional blend path (Step 50 item 9).
  *
  * Same rationale as [[BorgDepthFlushElabTests]]: the expensive integration
  * bugs for an optional, config-gated feature are unconnected ports, width
  * mismatches and `require`s that never fire, and all of them surface at
  * elaboration in seconds rather than needing a full-pipeline simulation.
  *
  * Behaviour lives in [[BorgBlendTests]] (the equation) and
  * [[BorgShaderDispatcherTests]] (the tile-write integration), both of which
  * run against a simulator.
  */
object BorgBlendElabTests extends TestSuite {

  private def elaborate(cfg: BorgConfig): String =
    circt.stage.ChiselStage.emitCHIRRTL(new Borg(cfg))

  val tests = Tests {

    utest.test("Borg elaborates with hasBlend enabled") {
      // The chain BorgConfig.hasBlend -> BorgRasterizer.blendCfg ->
      // BorgShaderDispatcher.blendCfg -> Borg.scala's BLEND_CFG/BLEND_CONST
      // decode. An unconnected Option port anywhere in it fails here.
      val chirrtl = elaborate(BorgConfig.Default.copy(hasBlend = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasBlend=true) elaborated cleanly")
    }

    utest.test("disabled build instantiates no blend hardware") {
      // The claim BorgConfig.hasBlend's doc makes -- that a disabled build is
      // bit-identical, not merely equivalent -- checked structurally: no
      // blendCfg port and no frag_a register anywhere in the emitted design.
      val off = elaborate(BorgConfig.Default)
      val on  = elaborate(BorgConfig.Default.copy(hasBlend = true))
      utest.assert(!off.contains("blendCfg"))
      utest.assert(!off.contains("frag_a"))
      utest.assert(on.contains("blendCfg"))
      utest.assert(on.contains("frag_a"))
      println("  disabled: no blendCfg/frag_a; enabled: both present")
    }

    utest.test("hasBlend with MSAA is a build error, not silent wrongness") {
      // The destination colour is per-sample but TileWriteIO broadcasts one
      // blended result to every covered sample, which would be wrong on any
      // partially-covered edge pixel. Must fail loudly.
      val thrown =
        try { elaborate(BorgConfig.Default.copy(hasBlend = true, samples = 4)); false }
        catch { case _: Throwable => true }
      utest.assert(thrown)
      println("  hasBlend + samples=4 correctly rejected")
    }

    utest.test("blend and depth flush coexist") {
      // Both are optional tile-path features touching the same modules;
      // enabling one must not have quietly claimed something the other needs.
      val chirrtl = elaborate(BorgConfig.Default.copy(hasBlend = true, hasDepthFlush = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasBlend=true, hasDepthFlush=true) elaborated cleanly")
    }
  }
}
