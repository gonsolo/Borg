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

  // BorgConfig.Default turns hasDepthFlush on (it is part of the Vulkan
  // feature set every target ships), so "nothing optional" has to be spelled
  // out: these tests are about the knobs themselves, not the shipped set.
  private val Bare = BorgConfig.Test.copy(
    hasDepthFlush = false, hasBlend = false, hasStencil = false, hasBilinear = false)

  val tests = Tests {

    // --- Each feature elaborates on its own ---------------------------------

    utest.test("Borg elaborates with hasDepthFlush enabled") {
      // The integration path: BorgConfig knob -> BorgTileFlusher constructor
      // -> depthBase/depthEn ports -> Borg.scala's FLUSH_ZB_BASE decode.
      // Any unconnected/mismatched port in that chain fails right here.
      val chirrtl = elaborate(BorgConfig.Test.copy(hasDepthFlush = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasDepthFlush=true) elaborated cleanly")
    }

    utest.test("Borg elaborates with hasBlend enabled") {
      // BorgConfig.hasBlend -> BorgRasterizer.blendCfg ->
      // BorgShaderDispatcher.blendCfg -> Borg.scala's BLEND_CFG/BLEND_CONST
      // decode.
      val chirrtl = elaborate(BorgConfig.Test.copy(hasBlend = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasBlend=true) elaborated cleanly")
    }

    utest.test("Borg elaborates with hasStencil enabled") {
      // Adds a memory as well as ports: BorgTileBuffer's stencil plane plus
      // the read/write/clear paths muxed alongside the colour plane.
      val chirrtl = elaborate(BorgConfig.Test.copy(hasStencil = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasStencil=true) elaborated cleanly")
    }

    utest.test("Borg elaborates with msaaMultiPass enabled") {
      // The whole tile path end to end in multi-pass storage: BorgTileBuffer
      // collapses to one live plane plus an accumulator, BorgTileSequencer
      // gains its pass loop, and Borg wires the TilePassIO between them. An
      // unconnected Option port anywhere on that path fails here.
      val cfg = BorgConfig.Default.copy(
        samples = 4, hasBlend = true, hasStencil = true,
        tileColorBits = 8, msaaMultiPass = true, maxBinTiles = 64)
      val sv = elaborate(cfg)
      // One colour plane and one accumulator, not four planes.
      assert(sv.contains("accumMem"))
      assert(sv.contains("rgbzMems_0_ext"))
      assert(!sv.contains("rgbzMems_3_ext"))
      // Stencil and alpha collapse the same way -- neither is ever flushed,
      // so a pass's values are purely transient.
      assert(!sv.contains("stencilMems_3_ext"))
      assert(!sv.contains("alphaMems_3_ext"))
      println("  msaaMultiPass: 1 colour plane + accumulator, no sample 1-3 planes")
    }

    utest.test("msaaMultiPass requires quantized tile colour") {
      // The accumulator averages STORED integers; averaging FP16 bit patterns
      // would not average the colours they denote, so the combination is
      // refused at elaboration rather than producing quietly wrong pixels.
      val bad = BorgConfig.Default.copy(
        samples = 4, tileColorBits = 16, msaaMultiPass = true, maxBinTiles = 64)
      val threw = try { elaborate(bad); false } catch { case _: Throwable => true }
      assert(threw)
      println("  msaaMultiPass + tileColorBits=16 correctly refused")
    }

    utest.test("Borg elaborates with every optional feature disabled") {
      val chirrtl = elaborate(Bare)
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(bare) elaborated cleanly")
    }

    // --- A disabled build really carries no hardware ------------------------

    utest.test("disabled build instantiates no optional tile-path hardware") {
      // The claim each knob's doc comment makes -- bit-identical, not merely
      // equivalent -- checked structurally against the emitted CHIRRTL rather
      // than taken on trust. `depthBase`/`blendCfg`/`stencilRead` are port
      // names; `zVec`/`frag_a`/`stencilMems` are the registers and memory.
      val off = elaborate(Bare)
      utest.assert(!off.contains("depthBase"))
      utest.assert(!off.contains("zVec"))
      utest.assert(!off.contains("blendCfg"))
      utest.assert(!off.contains("frag_a"))
      utest.assert(!off.contains("stencilRead"))
      utest.assert(!off.contains("stencilMems"))
      println("  default build: none of the six markers present")

      val on = elaborate(BorgConfig.Test.copy(
        hasDepthFlush = true, hasBlend = true, hasStencil = true))
      utest.assert(on.contains("depthBase"))
      utest.assert(on.contains("zVec"))
      utest.assert(on.contains("blendCfg"))
      utest.assert(on.contains("frag_a"))
      utest.assert(on.contains("stencilRead"))
      utest.assert(on.contains("stencilMems"))
      println("  all-enabled build: all six present")
    }

    // --- MSAA depth resolves to sample zero, not an average -----------------

    utest.test("hasDepthFlush elaborates at MSAA, resolving to sample zero") {
      // This combination was a build error until 2026-09-15, on the grounds
      // that "which sample's depth wins" was an unmade decision. It isn't:
      // VK_KHR_depth_stencil_resolve defines SAMPLE_ZERO/AVERAGE/MIN/MAX,
      // every Mesa driver advertises SAMPLE_ZERO, and v3dv -- the tile-based
      // renderer this design is closest to -- supports ONLY SAMPLE_ZERO for
      // depth. BorgTileFlusher's sFill already stages io.read.data(0).z, and
      // `data` is indexed by sample, so the conformant result was what the
      // RTL computed all along; only the require() disagreed.
      val chirrtl = elaborate(BorgConfig.Test.copy(hasDepthFlush = true, samples = 4))
      utest.assert(chirrtl.nonEmpty)
      println("  hasDepthFlush + samples=4 elaborates (sample-zero resolve)")
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
             BorgConfig.Test.copy(hasBlend = true, samples = 4),
             BorgConfig.Test.copy(hasStencil = true, samples = 4),
             BorgConfig.Test.copy(hasBlend = true, hasStencil = true, samples = 4)))
        utest.assert(elaborate(cfg).nonEmpty)
      println("  hasBlend / hasStencil / both at samples=4 all elaborate")
    }

    utest.test("the serialized write path exists only where it is needed") {
      // needPerSample is samples>1 AND (hasBlend || hasStencil). Isolate the
      // knob against Bare (no optional tile-path hardware) rather than
      // BorgConfig.Test: Test IS Default's shape, and Default has had
      // hasBlend/hasStencil on unconditionally since 2026-09-15 (Vulkan
      // conformance, not a per-target choice) -- so "plain 4x MSAA is
      // cheap" stopped being a true claim about anything actually shipped
      // the same day it was written. Every shipped 4x MSAA build now pays
      // the serialized write; that is asserted below too, not hidden.
      val bareMsaa = elaborate(Bare.copy(samples = 4))
      utest.assert(!bareMsaa.contains("sampleCtr"))
      val blendMsaa = elaborate(Bare.copy(hasBlend = true, samples = 4))
      utest.assert(blendMsaa.contains("sampleCtr"))
      val shippedMsaa = elaborate(BorgConfig.Test.copy(samples = 4))
      utest.assert(shippedMsaa.contains("sampleCtr"))
      // And a single-sample build never needs it regardless of features.
      val single = elaborate(Bare.copy(hasBlend = true, hasStencil = true, samples = 1))
      utest.assert(!single.contains("sampleCtr"))
      println("  sampleCtr present only for MSAA + blend/stencil -- which the shipped Test/Default config now always is")
    }

    // --- The extended ISA is gated too --------------------------------------

    utest.test("a build without the extended ISA carries none of it") {
      // These gate instructions, not fixed-function state, so unlike the
      // knobs above they default TRUE -- silently dropping an opcode a
      // compiler already emitted would execute as something else rather than
      // fail. Turning them off is an explicit smaller-ISA decision, and this
      // checks it actually removes the hardware rather than just the decode.
      val off = elaborate(BorgConfig.Test.copy(
        hasMemoryOps = false, hasControlFlow = false))
      utest.assert(!off.contains("execStack"))
      utest.assert(!off.contains("memRdReg"))
      println("  no exec stack, no load/store state")

      val on = elaborate(BorgConfig.Test)
      utest.assert(on.contains("execStack"))
      utest.assert(on.contains("memRdReg"))
      println("  default build has both")
    }

    utest.test("each extended-ISA half can be dropped on its own") {
      // The point of two knobs rather than one: the wafer.space tradeoff can
      // be measured at finer grain than all-or-nothing.
      utest.assert(elaborate(BorgConfig.Test.copy(hasMemoryOps = false)).nonEmpty)
      utest.assert(elaborate(BorgConfig.Test.copy(hasControlFlow = false)).nonEmpty)
      println("  memory-only and control-flow-only builds both elaborate")
    }

    // --- And they coexist ---------------------------------------------------

    utest.test("Borg elaborates with hasBilinear enabled") {
      // BorgConfig.hasBilinear -> BorgTextureUnit's optional BilinearIO ->
      // BorgShaderDispatcher's fraction derivation -> BorgRasterizer ->
      // Borg's SAMPLER_CFG decode. Adding a port to a chain that long is
      // exactly how an undriven sink gets in.
      val chirrtl = elaborate(BorgConfig.Test.copy(hasBilinear = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(hasBilinear=true) elaborated cleanly")
    }

    utest.test("all three optional tile-path features coexist") {
      // They touch the same modules; enabling one must not have quietly
      // claimed something another needs.
      val chirrtl = elaborate(BorgConfig.Test.copy(
        hasDepthFlush = true, hasBlend = true, hasStencil = true, hasBilinear = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(depthFlush + blend + stencil + bilinear) elaborated cleanly")
    }
  }
}
