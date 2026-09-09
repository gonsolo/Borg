// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import utest._

/** Elaboration-only checks for the optional tile-path features:
  * `hasDepthFlush` (item 14), `hasBlend` (item 9) and `hasStencil` (item 10).
  *
  * These deliberately do NOT run a simulator: they call ChiselStage directly,
  * so they catch the whole class of integration bugs that show up at
  * elaboration -- an unconnected Option port, a width mismatch, a `require`
  * that fires, a switch/Enum misuse -- in seconds rather than minutes. That
  * matters here because the full-pipeline simulation suites are currently
  * unrunnable on this machine (BorgSequencerTests sat 3461s without
  * completing a single test), so an elaboration gate is the cheapest real
  * coverage available for top-level wiring.
  *
  * The three features are checked together rather than in a file each: they
  * touch the same modules (BorgTileBuffer, BorgShaderDispatcher,
  * BorgRasterizer, Borg's register decode), so the interesting cases are as
  * much about them coexisting as about each one alone.
  *
  * Behavioural verification lives in the suites that do run:
  * [[BorgTileFlusherTests]] (the depth burst), [[BorgBlendTests]] and
  * [[BorgStencilTests]] (the equations), and [[BorgShaderDispatcherTests]]
  * (the tile-write integration for all three).
  */
object BorgTilePathElabTests extends TestSuite {

  // emitSystemVerilog, NOT emitCHIRRTL. CHIRRTL emission stops before
  // firtool's lowering, so it happily accepts a design with an undriven sink
  // -- which is exactly how a `not fully initialized` error on a newly added
  // IO field reached the golden render instead of failing here. Going all the
  // way to SystemVerilog costs a few seconds per config and closes that hole.
  private def elaborate(cfg: BorgConfig): String =
    circt.stage.ChiselStage.emitSystemVerilog(new Borg(cfg))

  val tests = Tests {

    // --- Each feature elaborates on its own ---------------------------------

    utest.test("Borg elaborates with hasDepthFlush enabled") {
      // The integration path: BorgConfig knob -> BorgTileFlusher constructor
      // -> depthBase/depthEn ports -> Borg.scala's FLUSH_ZB_BASE decode.
      // Any unconnected/mismatched port in that chain fails right here.
      val chirrtl = elaborate(BorgConfig.Default.copy(hasDepthFlush = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasDepthFlush=true) elaborated cleanly")
    }

    utest.test("Borg elaborates with hasBlend enabled") {
      // BorgConfig.hasBlend -> BorgRasterizer.blendCfg ->
      // BorgShaderDispatcher.blendCfg -> Borg.scala's BLEND_CFG/BLEND_CONST
      // decode.
      val chirrtl = elaborate(BorgConfig.Default.copy(hasBlend = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasBlend=true) elaborated cleanly")
    }

    utest.test("Borg elaborates with hasStencil enabled") {
      // Adds a memory as well as ports: BorgTileBuffer's stencil plane plus
      // the read/write/clear paths muxed alongside the colour plane.
      val chirrtl = elaborate(BorgConfig.Default.copy(hasStencil = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasStencil=true) elaborated cleanly")
    }

    utest.test("Borg elaborates unchanged with everything disabled") {
      val chirrtl = elaborate(BorgConfig.Default)
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(default) elaborated cleanly")
    }

    // --- A disabled build really carries no hardware ------------------------

    utest.test("disabled build instantiates no optional tile-path hardware") {
      // The claim each knob's doc comment makes -- bit-identical, not merely
      // equivalent -- checked structurally against the emitted CHIRRTL rather
      // than taken on trust. `depthBase`/`blendCfg`/`stencilRead` are port
      // names; `zVec`/`frag_a`/`stencilMems` are the registers and memory.
      val off = elaborate(BorgConfig.Default)
      utest.assert(!off.contains("depthBase"))
      utest.assert(!off.contains("zVec"))
      utest.assert(!off.contains("blendCfg"))
      utest.assert(!off.contains("frag_a"))
      utest.assert(!off.contains("stencilRead"))
      utest.assert(!off.contains("stencilMems"))
      println("  default build: none of the six markers present")

      val on = elaborate(BorgConfig.Default.copy(
        hasDepthFlush = true, hasBlend = true, hasStencil = true))
      utest.assert(on.contains("depthBase"))
      utest.assert(on.contains("zVec"))
      utest.assert(on.contains("blendCfg"))
      utest.assert(on.contains("frag_a"))
      utest.assert(on.contains("stencilRead"))
      utest.assert(on.contains("stencilMems"))
      println("  all-enabled build: all six present")
    }

    // --- MSAA is rejected, not approximated ---------------------------------

    utest.test("hasDepthFlush with MSAA is a build error, not silent wrongness") {
      // BorgTileFlusher.require: averaging samples is the wrong resolve for
      // depth, so the unsupported combination must fail loudly.
      val thrown =
        try { elaborate(BorgConfig.Default.copy(hasDepthFlush = true, samples = 4)); false }
        catch { case _: Throwable => true }
      utest.assert(thrown)
      println("  hasDepthFlush + samples=4 correctly rejected")
    }

    utest.test("blend and stencil elaborate at 4x MSAA") {
      // These used to be a build error: TileWriteIO broadcasts one `data` to
      // every covered sample, so blending against sample 0's destination and
      // sharing one stencil write was wrong on any partially covered edge
      // pixel. sTileWrite now serializes over samples instead, which matters
      // because Vulkan's framebufferColorSampleCounts must include 4 -- so
      // "blending only works at 1x" was a conformance hole, not a
      // configuration preference.
      for (cfg <- Seq(
             BorgConfig.Default.copy(hasBlend = true, samples = 4),
             BorgConfig.Default.copy(hasStencil = true, samples = 4),
             BorgConfig.Default.copy(hasBlend = true, hasStencil = true, samples = 4)))
        utest.assert(elaborate(cfg).nonEmpty)
      println("  hasBlend / hasStencil / both at samples=4 all elaborate")
    }

    utest.test("the serialized write path exists only where it is needed") {
      // needPerSample is samples>1 AND (hasBlend || hasStencil): plain 4x MSAA
      // keeps the single-cycle broadcast write, so the existing MSAA config
      // pays none of the extra cycles.
      val plainMsaa = elaborate(BorgConfig.Default.copy(samples = 4))
      utest.assert(!plainMsaa.contains("sampleCtr"))
      val blendMsaa = elaborate(BorgConfig.Default.copy(hasBlend = true, samples = 4))
      utest.assert(blendMsaa.contains("sampleCtr"))
      // And a single-sample build never needs it regardless of features.
      val single = elaborate(BorgConfig.Default.copy(hasBlend = true, hasStencil = true))
      utest.assert(!single.contains("sampleCtr"))
      println("  sampleCtr present only for MSAA + blend/stencil")
    }

    // --- And they coexist ---------------------------------------------------

    utest.test("all three optional tile-path features coexist") {
      // They touch the same modules; enabling one must not have quietly
      // claimed something another needs.
      val chirrtl = elaborate(BorgConfig.Default.copy(
        hasDepthFlush = true, hasBlend = true, hasStencil = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(depthFlush + blend + stencil) elaborated cleanly")
    }
  }
}
