// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgShaderDispatcherTestHelpers {

  val config = FloatConfig.FP16

  /** The datapath every test in this file is written against: FP16, single
    * sample, no optional tile-path hardware. Was spelled `BorgConfig.Default`
    * at each call site, which is how 13 basic dispatcher tests silently
    * became FP32 + 4x MSAA when the default moved on 2026-09-15 -- MSAA
    * changes the coverage/tile-write semantics these tests assert on, so
    * they failed for a reason that had nothing to do with what they test.
    * hasBlend/hasStencil/hasBilinear are pinned false for the same reason,
    * repeated 2026-09-15 when Default turned those three on too: MSAA below
    * is BASE.copy(samples = 4) and needPerSample (BorgShaderDispatcher) is
    * `samples > 1 && (hasBlend || hasStencil)` -- an unpinned BASE would have
    * made "plain MSAA" silently take the serialized per-sample write path,
    * and BLEND/STENCIL/MSAA_BLEND below would stop isolating what they name
    * since BASE would already carry them. Named once, here.
    */
  val BASE = BorgConfig.Default.copy(
    fp = FloatConfig.FP16, samples = 1,
    hasBlend = false, hasStencil = false, hasBilinear = false)

  // FP16 constants
  val FP16_POS_ONE = 0x3C00  // +1.0
  val FP16_NEG_ONE = 0xBC00  // -1.0
  val FP16_NEG_TWO = 0xC000  // -2.0
  val FP16_NEG_ZERO = 0x8000 // -0.0  ← MUST count as inside

  // Phase enum values (matches Enum(8) in BorgShaderDispatcher, Step 25.5C)
  val PHASE_IDLE       = 0
  val PHASE_RAST       = 1
  val PHASE_FRAG       = 2
  // sTexFetch (legacy autonomous fetch) removed -- texturing is FTEX-inline
  // only now, so the remaining phases shift down by one.
  val PHASE_Z_READ     = 3
  val PHASE_Z_WAIT1    = 4
  val PHASE_Z_WAIT2    = 5
  val PHASE_TILE_WRITE = 6

  // FP16 max depth for tile buffer clear value
  val FP16_MAX_DEPTH = 0x7BFF

  // VkCompareOp encoding (Step 50 item 11) -- deliberately identical to the
  // Vulkan enum so the driver can pass VkCompareOp through untranslated.
  val CMP_NEVER            = 0
  val CMP_LESS             = 1
  val CMP_EQUAL            = 2
  val CMP_LESS_OR_EQUAL    = 3
  val CMP_GREATER          = 4
  val CMP_NOT_EQUAL        = 5
  val CMP_GREATER_OR_EQUAL = 6
  val CMP_ALWAYS           = 7

  /** Set all inputs to safe idle defaults (no clock step). */
  def pokeIdle(d: BorgShaderDispatcher): Unit = {
    d.io.pixelReady.poke(false.B)
    d.io.shaderTileIndex(0).poke(0.U)
    d.io.pipeWrite(0).en.poke(false.B)
    d.io.pipeWrite(0).addr.poke(0.U)
    d.io.pipeWrite(0).data.poke(0.U)
    d.io.coreStatus.running.poke(false.B)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.fragPcReg.poke(0.U)
    d.io.texConfig.en.poke(false.B)
    d.io.texConfig.mortonIndex.poke(0.U)
    d.io.texConfig.baseAddr.poke(0.U)
    d.io.gpuMem.data.poke(0.U)
    d.io.gpuMem.ready.poke(false.B)
    // Step 50 item 11: configurable depth state.  The defaults reproduce the
    // historical hardcoded behaviour (LESS, depth writes on) so every
    // pre-existing test in this file keeps its original meaning.
    d.io.depthCompareOp.poke(CMP_LESS.U)
    d.io.depthWriteEn.poke(true.B); d.io.depthUnorm.poke(true.B)
    // Step 50 item 9: blending off by default (only present in a hasBlend
    // build), so every pre-existing test keeps the unconditional-overwrite
    // behaviour it was written against.
    d.io.blendCfg.foreach { b =>
      b.enable.poke(false.B)
      b.srcColorFactor.poke(0.U); b.dstColorFactor.poke(0.U); b.colorOp.poke(0.U)
      b.srcAlphaFactor.poke(0.U); b.dstAlphaFactor.poke(0.U); b.alphaOp.poke(0.U)
      b.constant.r.poke(0.U); b.constant.g.poke(0.U)
      b.constant.b.poke(0.U); b.constant.a.poke(0.U)
      b.colorWriteMask.poke(0xF.U)
    }
    // Step 50 item 10: stencil off by default, same reasoning.
    d.io.stencilCfg.foreach { st =>
      st.enable.poke(false.B)
      for (f <- Seq(st.front, st.back)) {
        f.compareOp.poke(0.U); f.failOp.poke(0.U)
        f.passOp.poke(0.U); f.depthFailOp.poke(0.U)
        f.compareMask.poke(0xFF.U); f.writeMask.poke(0xFF.U); f.reference.poke(0.U)
      }
    }
    // frontFacing selects cfg.front vs cfg.back in BorgStencil.evaluate
    // (BorgStencil.scala:135) and is never driven by anything else in this
    // standalone-dispatcher harness (it's real per-triangle hardware fed by
    // BorgSequencer in the full pipeline, absent here). Left unpoked it
    // stays at its 0/false reset value, silently selecting the .back half
    // -- which pokeFrontFace() below never configures -- so every stencil
    // op quietly no-ops instead of running the .front op the test actually
    // set up. Default true (front-facing) to match Borg.scala's own idle
    // default (`Mux(s.io.busy, s.io.frontFacingOverride, true.B)`), which
    // is what every existing pokeFrontFace-based test already assumes.
    d.io.frontFacing.foreach(_.poke(true.B))
    d.io.stencilRead.foreach(_.foreach(_.poke(0.U)))
    // Step 50: scissor. The rectangle test itself lives in BorgRasterizer
    // (that is where the screen coordinates are); the dispatcher just takes
    // the per-lane verdict, so "everything passes" is the neutral default
    // every pre-existing test was written against.
    d.io.scissorPass.foreach(_.poke(true.B))
    d.io.sampleCfg.mask.poke(((1 << d.cfg.samples) - 1).U)
    d.io.sampleCfg.alphaToCov.poke(false.B); d.io.sampleCfg.shaderMask.poke(false.B); d.io.sampleCfg.single.poke(false.B)
    // No ZTEST in flight: every pre-existing test runs the late tests.
    d.io.zTestReq.poke(false.B)
    // Destination alpha: opaque, which is what the hardware behaved as
    // before the plane existed -- so every pre-existing blend test keeps its
    // original meaning.
    d.io.alphaRead.foreach(_.foreach(_.poke(0xFF.U)))
    // Step 25.5C: tile read port — provide max depth so depth test passes.
    // Per-sample since MSAA: every sample starts at the far plane.
    d.io.tileRead.data.foreach { s =>
      s.r.poke(0.U)
      s.g.poke(0.U)
      s.b.poke(0.U)
      s.z.poke(FP16_MAX_DEPTH.U)
    }
  }

  /** Step through the 3 depth-test wait states (sZRead → sZWait1 → sZWait2 → sTileWrite). */
  def stepThroughDepthTest(d: BorgShaderDispatcher): Unit = {
    d.clock.step(3)  // sZRead → sZWait1 → sZWait2 → sTileWrite
  }

  /** Fire pixelReady for one cycle, then restore idle.
    * Optionally sets fragPcReg before the pulse.
    */
  def firePixelReady(d: BorgShaderDispatcher, fragPc: Int = 13, tileIdx: Int = 5): Unit = {
    d.io.pixelReady.poke(true.B)
    d.io.fragPcReg.poke(fragPc.U)
    d.io.shaderTileIndex(0).poke(tileIdx.U)
    d.clock.step(1)
    d.io.pixelReady.poke(false.B)
  }

  /** Simulate a complete shader execution: autoRunPending → running → halt. */
  def simulateShaderRun(d: BorgShaderDispatcher, cycles: Int = 3): Unit = {
    d.io.coreStatus.autoRunPending.poke(true.B)
    d.clock.step(1)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.coreStatus.running.poke(true.B)
    d.clock.step(cycles)
    d.io.coreStatus.running.poke(false.B)
    d.clock.step(1)
  }

  /** Write a single edge-function result via pipeline snoop. */
  def pokeEdge(d: BorgShaderDispatcher, reg: Int, value: Int): Unit = {
    d.io.pipeWrite(0).en.poke(true.B)
    d.io.pipeWrite(0).addr.poke(reg.U)
    d.io.pipeWrite(0).data.poke(value.U)
    d.clock.step(1)
    d.io.pipeWrite(0).en.poke(false.B)
  }

  /** Drive one complete inside-pixel through sRast → sFrag → depth test →
    * sTileWrite, and report what the tile buffer was told to do.
    *
    * Returns `(tileWrite.en, tileWrite.data.z)` sampled in sTileWrite.  The
    * FSM lands back in sIdle afterwards, so a single simulator instance can
    * run many pixels back to back -- which is what makes the 24-case
    * compare-op sweep below affordable (one `simulate` block, not 24).
    */
  def runPixel(
      d: BorgShaderDispatcher,
      fragZ: Int,
      oldZ: Int,
      compareOp: Int,
      writeEn: Boolean,
      tileIdx: Int = 7
  ): (Boolean, Int) = {
    val w = runPixelFull(d, fragZ, oldZ, compareOp, writeEn, tileIdx = tileIdx)
    (w.en, w.z)
  }

  /** What the tile buffer was told to write. `stencilEn`/`stencil` are
    * meaningful only in a hasStencil build; elsewhere they read as
    * false/0, matching a build with no stencil plane. */
  case class TileWrite(en: Boolean, r: Int, g: Int, b: Int, z: Int,
                       stencilEn: Boolean = false, stencil: Int = 0,
                       alpha: Int = 0xFF, alphaMask: Boolean = false)

  /** [[runPixel]] with the colour operands exposed, for the blend tests:
    * `srcRgb` is what the fragment shader writes to r26/27/28, `dstRgb` what
    * the tile buffer already holds, and `fragA` the optional r24 alpha
    * output (a hasBlend build only).
    */
  def runPixelFull(
      d: BorgShaderDispatcher,
      fragZ: Int,
      oldZ: Int,
      compareOp: Int,
      writeEn: Boolean,
      srcRgb: (Int, Int, Int) = (0x1111, 0x2222, 0x3333),
      dstRgb: (Int, Int, Int) = (0, 0, 0),
      fragA: Option[Int] = None,
      storedStencil: Int = 0,
      dstAlpha: Int = 0xFF,
      tileIdx: Int = 7
  ): TileWrite = {
    d.io.stencilRead.foreach(_.foreach(_.poke(storedStencil.U)))
    d.io.alphaRead.foreach(_.foreach(_.poke(dstAlpha.U)))
    d.io.depthCompareOp.poke(compareOp.U)
    d.io.depthWriteEn.poke(writeEn.B)
    d.io.tileRead.data.foreach { s =>
      s.z.poke(oldZ.U); s.r.poke(dstRgb._1.U); s.g.poke(dstRgb._2.U); s.b.poke(dstRgb._3.U)
    }

    firePixelReady(d, fragPc = 13, tileIdx = tileIdx)
    d.io.fragPcReg.poke(13.U)

    // Rast shader: all edges inside.
    d.io.coreStatus.autoRunPending.poke(true.B)
    d.clock.step(1)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.coreStatus.running.poke(true.B)
    pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
    d.io.coreStatus.running.poke(false.B)
    d.clock.step(1)

    // Frag shader: RGB plus the depth we want tested.
    d.io.coreStatus.autoRunPending.poke(true.B)
    d.clock.step(1)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.coreStatus.running.poke(true.B)
    val fragWrites =
      fragA.map(a => Seq((24, a))).getOrElse(Seq.empty) ++
      Seq((26, srcRgb._1), (27, srcRgb._2), (28, srcRgb._3), (29, fragZ))
    for ((reg, value) <- fragWrites) {
      d.io.pipeWrite(0).en.poke(true.B)
      d.io.pipeWrite(0).addr.poke(reg.U)
      d.io.pipeWrite(0).data.poke(value.U)
      d.clock.step(1)
    }
    d.io.pipeWrite(0).en.poke(false.B)
    d.io.coreStatus.running.poke(false.B)
    d.clock.step(1)

    utest.assert(d.io.phase.peek().litValue.toInt == PHASE_Z_READ)
    stepThroughDepthTest(d)
    utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)

    val w = TileWrite(
      d.io.tileWrite.en.peek().litToBoolean,
      d.io.tileWrite.data.r.peek().litValue.toInt,
      d.io.tileWrite.data.g.peek().litValue.toInt,
      d.io.tileWrite.data.b.peek().litValue.toInt,
      d.io.tileWrite.data.z.peek().litValue.toInt,
      d.io.stencilWriteMask.map(_.peek().litValue != 0).getOrElse(false),
      d.io.stencilWrite.map(_.peek().litValue.toInt).getOrElse(0),
      d.io.alphaWrite.map(_.peek().litValue.toInt).getOrElse(0xFF),
      d.io.alphaWriteMask.map(_.peek().litToBoolean).getOrElse(false))

    d.clock.step(1)  // sTileWrite → sIdle, ready for the next pixel
    w
  }

  /** Write all three edge values and check resulting insideFlag. */
  def pokeAllEdges(d: BorgShaderDispatcher, e0: Int, e1: Int, e2: Int): Unit = {
    pokeEdge(d, 0, e0)
    pokeEdge(d, 1, e1)
    pokeEdge(d, 2, e2)
  }


    // =========================================================================

    // Basic FSM: sRast → sIdle for outside pixels

    // =========================================================================

    // =========================================================================

    // sRast → sFrag → sTileWrite (no texturing)

    // =========================================================================

    // =========================================================================

    // stall held across all phases

    // =========================================================================

    // =========================================================================

    // fragPcReg=0 disables chaining even for inside pixels

    // =========================================================================

    // =========================================================================

    // FP16 negative-zero must count as INSIDE

    // =========================================================================

    // =========================================================================

    // shaderTileIndex forwarded correctly to sTileWrite

    // =========================================================================

    // =========================================================================

    // phase output is observable at every FSM step (debugability contract)

    // =========================================================================

    // =========================================================================

    // Step 25.3f: inside_flag guard — tileWrite.en stays low for outside pixels

    // =========================================================================

    //

    // Currently the FSM routes outside pixels sRast→sIdle and they never reach

    // sTileWrite.  Step 25.4d will change this (autonomous iteration processes

    // every pixel).  This test simulates a hypothetical sTileWrite entry with

    // outside edges and verifies that tileWrite.en stays low.

    //

    // Implementation note: we cannot directly force phase=sTileWrite from the

    // outside — the FSM only reaches it after sFrag.  We therefore test the

    // guard by running a full inside→frag path, then checking that the SAME

    // logic also blocks the write when inside_flag is false.  The most robust

    // approach is a separate outside-pixel run where we intercept at sFrag and

    // corrupt inside_flag by poking a negative edge DURING sFrag execution.

    //

    // Simpler alternative used here: run with all edges outside from the

    // start.  The FSM takes sRast→sIdle, so tileWrite.en must never assert.

    // Then separately test that inside pixels DO produce tileWrite.en=true

    // (regression check on the guard itself).

    // =========================================================================

    // discard: r25 hardware-ABI kill flag blocks tile write-back

    // =========================================================================

    // =========================================================================

    // discard: r25 write of exactly 0 must NOT discard (only nonzero kills)

    // =========================================================================

    // =========================================================================

    // Step 50.2: 4× MSAA per-sample coverage

    // =========================================================================

    //

    // The whole point of per-sample coverage is that a pixel STRADDLING a

    // triangle edge is partially covered — some samples in, some out.  A

    // pixel-centre-only test can never produce that, so these tests drive edge

    // values whose sign differs from the sign of (edge + sample offset).

    //

    // Sample s's edge value is e + δ_s, tested as e >= -δ_s.  With the standard

    // Vulkan/D3D 4× positions, δ_s2 = -δ_s1 and δ_s3 = -δ_s0, so the hardware

    // derives all four thresholds from the two base deltas per edge.

    // Pinned to FP16 explicitly. These were BorgConfig.Default.copy(...),
    // which silently became FP32 when the default switched on 2026-09-15 --
    // while every expectation in this file is a bit-exact FP16 constant
    // (FP16_ONE = 0x3C00, the f16()/f16ToFloat() helpers, the tile colour
    // comparisons), and `config` above is FP16 too. That left one file
    // driving two different datapaths with one set of patterns.
    //
    // KNOWN GAP: this means the FP32 dispatcher path -- blend, stencil and
    // the per-sample MSAA tile writes at FP32 -- has no simulation coverage,
    // on what is now the default config. Converting this file's constants to
    // FP32 is a real rewrite rather than a helper swap; tracked separately.
    val MSAA = BASE.copy(samples = 4)

    /** FP16 bits for a float (test-side reference, finite normals only). */

    def f16(f: Float): Int = {
      val h = java.lang.Float.floatToIntBits(f)
      val sign = (h >>> 16) & 0x8000
      val expF = ((h >>> 23) & 0xff) - 127 + 15
      val mantF = h & 0x7fffff
      if (f == 0.0f) sign
      else if (expF <= 0) sign
      else if (expF >= 0x1f) sign | 0x7bff
      else sign | (expF << 10) | (mantF >> 13)
    }

    /** Inverse of [[f16]] — used by the blend tests, which care about the
      * numeric colour rather than an exact bit pattern (the blend path
      * round-trips through UNORM8, so the FP16 that comes back is the nearest
      * representable value, not the one that went in). */

    def f16ToFloat(bits: Int): Float = {
      val exp  = (bits >> 10) & 0x1f
      val mant = bits & 0x3ff
      val mag =
        if (exp == 0) mant.toFloat / (1 << 24)
        else (1.0f + mant.toFloat / 1024.0f) * math.pow(2.0, exp - 15).toFloat
      if ((bits & 0x8000) != 0) -mag else mag
    }

    /** Drive the two base deltas for all three edges. */

    def pokeCovDelta(d: BorgShaderDispatcher, d0: Int, d1: Int): Unit =
      for (e <- 0 until 3) {
        d.io.covDelta.get(e)(0).poke(d0.U)
        d.io.covDelta.get(e)(1).poke(d1.U)
      }

    // =========================================================================

    // Step 50 item 11: configurable depth compare op + depthWriteEnable

    //

    // Vulkan requires all 8 VkCompareOp values and an independent

    // depthWriteEnable; the hardware previously hardcoded LESS with an

    // unconditional write, so a pipeline asking for anything else rendered

    // silently wrong rather than failing.

    // =========================================================================

    // =========================================================================

    // Step 50 item 9: blending wired into the tile-write path

    //

    // BorgBlendTests covers the blend equation itself exhaustively. What is

    // tested here is the integration: that the destination colour really is

    // the tile buffer's stored colour (not a stale or zero read), that r24

    // reaches the blend unit as source alpha, and that a disabled blend is

    // bit-exact rather than merely close.

    // =========================================================================

    val BLEND = BASE.copy(hasBlend = true)

    val FP16_ONE  = 0x3C00

    val FP16_HALF = 0x3800

    val FP16_ZERO = 0x0000

    // =========================================================================

    // Step 50 item 10: stencil wired into the tile-write path

    //

    // BorgStencilTests covers the test/op logic exhaustively. What is tested

    // here is that the dispatcher drives it with the right operands and, in

    // particular, that stencilWriteEn is genuinely independent of

    // tileWrite.en -- the buffer must advance on fragments that are killed.

    // =========================================================================

    val STENCIL = BASE.copy(hasStencil = true)

    def pokeFrontFace(d: BorgShaderDispatcher, compareOp: Int, failOp: Int,
                      passOp: Int, depthFailOp: Int, reference: Int,
                      compareMask: Int = 0xFF, writeMask: Int = 0xFF): Unit = {
      val f = d.io.stencilCfg.get.front
      f.compareOp.poke(compareOp.U); f.failOp.poke(failOp.U)
      f.passOp.poke(passOp.U); f.depthFailOp.poke(depthFailOp.U)
      f.compareMask.poke(compareMask.U); f.writeMask.poke(writeMask.U)
      f.reference.poke(reference.U)
    }

    // =========================================================================

    // Step 50: colorWriteMask and the scissor verdict

    // =========================================================================

    // =========================================================================

    // Step 50 item 9, second half: real destination alpha

    // =========================================================================

    // =========================================================================

    // Step 50: per-sample tile writes at 4x MSAA

    //

    // Vulkan's framebufferColorSampleCounts must include VK_SAMPLE_COUNT_4_BIT,

    // so "blending/stencil/depthWriteEnable only work at 1x" was a conformance

    // hole rather than a configuration preference. sTileWrite now issues one

    // write per sample with a one-hot coverage mask instead of broadcasting

    // one shared result.

    // =========================================================================

    val MSAA_BLEND = BASE.copy(samples = 4, hasBlend = true, hasStencil = true)

}
