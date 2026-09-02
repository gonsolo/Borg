// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import borg.link.{BorgLinkTestWrapper, LinkParams}

/** '''The acceptance gate for the chip-to-chip bridge.'''
  *
  * One scenario -- write a tile via MMIO, rasterize it, and let the hardware
  * flusher emit its RGB565 burst -- driven against two DUTs:
  *
  *   1. `BorgTestWrapper`: Borg wired directly, as on the ULX3S today.
  *   2. `BorgLinkTestWrapper`: the same Borg behind `BorgLinkMaster` +
  *      `BorgLinkSlave`, i.e. everything that will straddle the two dies.
  *
  * The burst is the framebuffer content, so requiring it to be '''bit-identical'''
  * is the strongest end-to-end statement available in simulation: the link is
  * transparent to Borg, over both the MMIO path and the gpuMem path, including a
  * 16-word burst write.
  *
  * The scenario is written once against [[HasLegacyBorgMmio]] and polls
  * `data_ready` rather than assuming fixed cycle counts.  That is a no-op against
  * the direct wrapper (which completes writes combinationally) and load-bearing
  * against the linked one, where every access is a link round trip.
  */
object BorgLinkEquivalenceTests extends TestSuite {

  type Dut = Module with HasLegacyBorgMmio

  val config = FloatConfig.FP16
  val cfg    = BorgConfig.Default.copy(fp = FloatConfig.FP16)
  val tileBase = 0x200

  /** Expected pixel colours: R ramps up, G ramps down, B constant, so a dropped
    * or reordered word is visible rather than aliasing onto its neighbour.
    */
  def px(i: Int): (Int, Int, Int, Int) = {
    val r = BorgTests.floatToBits(i / 16.0f, config).toInt
    val g = BorgTests.floatToBits((15 - i) / 16.0f, config).toInt
    val b = BorgTests.floatToBits(0.5f, config).toInt
    val z = 0x4000 + i
    (r, g, b, z)
  }

  // `step` defaults to a plain clock step for every existing caller. Pass
  // `() => mm.step(d)` (see GpuMemReadModel below) when a write's own MMIO
  // completion can be gated behind real shading activity that needs gpuMem
  // service concurrently -- a plain `d.clock.step(1)` during that wait would
  // never answer the pending read, deadlocking the whole pipeline behind it.
  def waitIdle(d: Dut, limit: Int = 6000, step: () => Unit = null): Unit = {
    val doStep = if (step == null) () => d.clock.step(1) else step
    var g = 0
    while (g < limit && !d.io.data_ready.peek().litToBoolean) {
      doStep()
      g += 1
    }
    Predef.assert(g < limit, "timed out waiting for data_ready")
  }

  def rawWrite(d: Dut, addr: Int, data: BigInt, step: () => Unit = null): Unit = {
    val doStep = if (step == null) () => d.clock.step(1) else step
    waitIdle(d, step = doStep)
    d.io.address.poke(addr.U)
    d.io.data_in.poke(data.U)
    d.io.data_write_n.poke(2.U)
    doStep()
    d.io.data_write_n.poke(3.U)
    waitIdle(d, step = doStep)
    doStep()
  }

  def idleStep(d: Dut): Unit = {
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.waccept.poke(false.B)
    d.clock.step(1)
  }

  /** Minimal gpuMem read-only memory model: services single-word (`wlen=1`)
    * reads issued as a level-held `req` (dropped/advanced only the cycle
    * after `ready`, matching every real Borg gpuMem master -- BorgDMA,
    * BorgBinner, the rasterizer, BorgTextureUnit -- see `MemoryController`'s
    * `sIdle`/`sRespond` for the contract this mirrors), from a fixed `mem`
    * map. Any read at an address not in `mem` returns 0 and is recorded so
    * the caller can tell a real miss from a genuinely-zero texel.
    */
  class GpuMemReadModel(mem: Map[Int, Long]) {
    val sIdle :: sWait :: sRespond :: Nil = List(0, 1, 2)
    var state = sIdle
    var addr = 0
    var waitLeft = 0
    var unexpectedAddr: Option[Int] = None
    var readCount = 0

    def step(d: Dut): Unit = {
      d.io.gpuMem.waccept.poke(false.B)
      state match {
        case s if s == sIdle =>
          d.io.gpuMem.ready.poke(false.B)
          if (d.io.gpuMem.req.peek().litToBoolean && !d.io.gpuMem.wr.peek().litToBoolean) {
            addr = d.io.gpuMem.addr.peek().litValue.toInt
            if (!mem.contains(addr) && unexpectedAddr.isEmpty) unexpectedAddr = Some(addr)
            waitLeft = 2
            state = sWait
          }
        case s if s == sWait =>
          d.io.gpuMem.ready.poke(false.B)
          waitLeft -= 1
          if (waitLeft <= 0) state = sRespond
        case _ /* sRespond */ =>
          d.io.gpuMem.data.poke(mem.getOrElse(addr, 0L).U)
          d.io.gpuMem.ready.poke(true.B)
          readCount += 1
          state = sIdle
      }
      d.clock.step(1)
    }
  }

  // --- Texture-read scenario: known texel content planted at texBaseAddr. ---
  // Layout per BorgTextureUnit's own doc comment: word0 (+0) = {G,R},
  // word1 (+4) = {pad,B}.
  val texBaseAddr = 0x400
  val texTileBase = 0x600
  val texR = BorgTests.floatToBits(0.25f, config).toInt
  val texG = BorgTests.floatToBits(0.75f, config).toInt
  val texB = BorgTests.floatToBits(1.0f, config).toInt
  val texMem: Map[Int, Long] = Map(
    texBaseAddr       -> ((BigInt(texG & 0xffff) << 16) | BigInt(texR & 0xffff)).toLong,
    (texBaseAddr | 4) -> (texB & 0xffff).toLong
  )

  /** Second scenario: a real render where every pixel's fragment shader
    * executes an FTEX instruction, driving two real sequential gpuMem reads
    * per pixel through the link -- the actual missing coverage described in
    * this file's class doc: the original acceptance gate only ever exercised
    * the output write burst, never a gpuMem read.
    *
    * The auto-rasterizer's initial pass fetches from BorgRasterRom (see the
    * comment in `runScenario`), whose edge test runs against uniform slots
    * 0-11 (dx0/neg_dy0/dx1/neg_dy1/dx2/neg_dy2/neg_vx0..neg_vy2 -- see
    * BorgRasterRom's own doc). Setting the six multiplier coefficients
    * (uniforms 0-5) to exactly 0x0000 makes every pixel's edge value
    * `0*dpy + 0*dpx = 0` regardless of position (uniforms 6-11, the per-edge
    * reference points, become irrelevant once their multipliers are zero) --
    * and per `isOutside`'s own documented convention (see
    * BorgShaderDispatcher's MSAA coverage comment), zero of either sign reads
    * as inside, not outside, so every one of the tile's 16 pixels shades.
    *
    * Fragment shader at PC=4: `FTEX rd=26, rs1=r6(U=0.0), rs2=r6(V=0.0)`.
    * U=V=0.0 keeps the texel address at exactly `texBaseAddr` (Fp16ToUint8(0)
    * = 0, ClampTexCoord leaves 0 alone, MortonEncode(0,0) = 0), and rd=26
    * lands the fetched (R,G,B) directly in the tile-write ABI registers
    * (r26/r27/r28 -- see BorgShaderDispatcher's "Hardware ABI" comment), so no
    * further shader instructions are needed. Frag Z (r29) is left at its
    * post-reset 0x0000, which beats the tile buffer's cleared far-plane Z
    * (0x7BFF) under the unsigned FP16-magnitude compare, so the depth test
    * passes and the fetched texel really lands in the tile buffer.
    *
    * Returns (burstBaseAddr, 16 RGB565 words, gpuMem reads observed).
    */
  def runTextureScenario(d: Dut): (Int, Seq[Long], Int) = {
    val mm = new GpuMemReadModel(texMem)

    // Every write in this scenario goes through `mm` while it waits for its
    // own MMIO completion, not a plain clock step. This matters here in a
    // way it doesn't in runScenario(): once a pixel's shading genuinely
    // reads real texture data (below), the iterate write's own completion
    // is gated behind that shading finishing, which needs FTEX's gpuMem
    // reads serviced *during* the wait -- a plain `d.clock.step(1)` would
    // never answer the pending read and deadlock the whole pipeline behind
    // it. (Found the hard way: every write up through frag_pc genuinely
    // does complete with a plain step, since nothing downstream needs
    // gpuMem yet; only the first real per-pixel iterate write exposed this.)
    def rw(addr: Int, data: BigInt): Unit = rawWrite(d, addr, data, () => mm.step(d))

    d.reset.poke(true.B)
    d.io.data_write_n.poke(3.U)
    d.io.data_read_n.poke(3.U)
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.waccept.poke(false.B)
    d.io.gpuMem.data.poke(0.U)
    d.clock.step(4)
    d.reset.poke(false.B)
    // Tile-buffer auto-clear, plus time for the link to train.
    for (_ <- 0 until 400) mm.step(d)

    // Flush configuration.
    rw(BorgGpuRegs.flush_fb_base_offset.litValue.toInt, texTileBase)
    rw(BorgGpuRegs.flush_width_offset.litValue.toInt, 5) // log2(32)

    // Texture configuration: base=texBaseAddr, en=1. log2_dim is irrelevant
    // here -- U=V=0.0 always clamps/mortons to index 0 regardless of it.
    val texConfigVal = (1 << 16) | texBaseAddr
    rw(BorgGpuRegs.tex_config_offset.litValue.toInt, texConfigVal)

    // Edge uniforms 0-5 = 0x0000 (see this method's doc comment).
    for (i <- 0 until 6) {
      rw((BorgGpuRegs.uniform_offset.litValue + i * 4).toInt, 0x0000)
    }

    // Fragment shader: FTEX rd=26, U=r6, V=r6, then halt. fragPcReg=0 is a
    // hard "no fragment shader" sentinel (BorgConfig.scala's
    // BORG_IMEM_FRAG_OFFSET doc, BorgShaderDispatcher's
    // `any_inside && io.fragPcReg =/= 0.U` chain condition) -- it must be
    // nonzero for the chain to fire at all, regardless of what word 0 holds,
    // so the fragment shader is placed at word 4, not word 0.
    rw(BorgGpuRegs.control_offset.litValue.toInt, 2) // reset pipeline
    rw(6 * 4, 0x0000) // r6 = 0.0 (U and V operand for FTEX)
    rw(128 + 4 * 4, Instructions.FTEX(6, 6, 26))
    rw(128 + 5 * 4, 0) // halt
    rw(BorgGpuRegs.frag_pc_offset.litValue.toInt, 4)

    // Enqueue tile (tx=0, ty=0).
    rw(BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, (0 << 10) | 0)
    for (_ <- 0 until 5) mm.step(d)

    // Step the iterator through all 16 pixels. Each one runs the setup shader,
    // then (since fragPcReg != 0 and every edge is inside) the FTEX fragment
    // shader -- two real gpuMem reads per pixel, serviced by `mm`.
    for (_ <- 0 until 16) {
      rw(BorgGpuRegs.iter_offset.litValue.toInt, 1)
      for (_ <- 0 until 200) mm.step(d)
    }

    // Catch the single 16-word output burst (write path -- unaffected by mm,
    // which only ever answers reads).
    var guard = 0
    while (!(d.io.gpuMem.wr.peek().litToBoolean &&
             d.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 20000) {
      mm.step(d)
      guard += 1
    }
    Predef.assert(guard < 20000, "flush burst never started")

    val burstAddr  = d.io.gpuMem.addr.peek().litValue.toInt
    val burstWords = scala.collection.mutable.ArrayBuffer[Long]()

    for (w <- 0 until 16) {
      Predef.assert(d.io.gpuMem.wr.peek().litToBoolean, s"wr dropped at burst word $w")
      burstWords += (d.io.gpuMem.wdata.peek().litValue.toLong & 0xffffL)
      if (w < 15) {
        d.io.gpuMem.waccept.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.waccept.poke(false.B)
      }
    }
    d.io.gpuMem.ready.poke(true.B)
    d.clock.step(1)
    d.io.gpuMem.ready.poke(false.B)
    d.clock.step(1)

    Predef.assert(mm.unexpectedAddr.isEmpty,
      s"gpuMem read at unplanted address 0x${mm.unexpectedAddr.get.toHexString}")

    (burstAddr, burstWords.toSeq, mm.readCount)
  }

  /** Run the render and return `(burstBaseAddr, 16 RGB565 words)`. */
  def runScenario(d: Dut): (Int, Seq[Long]) = {
    d.reset.poke(true.B)
    d.io.data_write_n.poke(3.U)
    d.io.data_read_n.poke(3.U)
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.waccept.poke(false.B)
    d.io.gpuMem.data.poke(0.U)
    d.clock.step(4)
    d.reset.poke(false.B)
    // Tile-buffer auto-clear, plus time for the link to train.
    for (_ <- 0 until 400) idleStep(d)

    // (1) 16 tile pixels: CTRL=idx, BZ={B,Z}, RG={R,G} (the RG write commits).
    for (i <- 0 until 16) {
      val (r, g, b, z) = px(i)
      rawWrite(d, BorgGpuRegs.tile_ctrl_offset.litValue.toInt, i & 0xf)
      rawWrite(d, BorgGpuRegs.tile_bz_offset.litValue.toInt,
        (BigInt(b & 0xffff) << 16) | BigInt(z & 0xffff))
      rawWrite(d, BorgGpuRegs.tile_rg_offset.litValue.toInt,
        (BigInt(r & 0xffff) << 16) | BigInt(g & 0xffff))
    }
    d.clock.step(4)

    // (2) Flush configuration.
    rawWrite(d, BorgGpuRegs.flush_fb_base_offset.litValue.toInt, tileBase)
    rawWrite(d, BorgGpuRegs.flush_width_offset.litValue.toInt, 5) // log2(32)

    // (3) The auto-rasterizer's initial edge-test pass always fetches from the
    // permanent BorgRasterRom hardware ROM, never from writable instruction
    // memory (BorgCore.scala: `fetchRast := io.coreTrigger.isRast`, and the
    // dispatcher sets isRast=true for exactly this pass -- see BorgRasterRom's
    // own doc). A prior version of this test wrote ADD instructions to words
    // 0-3 believing they'd force every edge "inside"; those writes were dead
    // code, never fetched. This scenario's color comes entirely from the
    // direct tile-buffer pokes in step (1) above, so whether any pixel tests
    // "inside" is irrelevant here -- frag_pc=0 means no fragment shader chains
    // regardless, and the iterate pass below exists only to drive the tile to
    // completion so the flusher fires. See runTextureScenario() for a
    // scenario where "inside" genuinely matters and is staged correctly.
    rawWrite(d, BorgGpuRegs.control_offset.litValue.toInt, 2) // reset pipeline
    rawWrite(d, 128 + 0 * 4, 0) // halt at word 0 -- no shader body needed
    rawWrite(d, BorgGpuRegs.frag_pc_offset.litValue.toInt, 0)

    // (4) Enqueue tile (tx=0, ty=0).
    rawWrite(d, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, (0 << 10) | 0)
    d.clock.step(5)

    // (5) Step the iterator through all 16 pixels.  No gpuMem traffic happens
    // during these -- Z stays on-chip and the flusher only fires on tile complete.
    for (_ <- 0 until 16) {
      rawWrite(d, BorgGpuRegs.iter_offset.litValue.toInt, 1)
      for (_ <- 0 until 120) idleStep(d)
    }

    // (6) Catch the single 16-word burst.
    var guard = 0
    while (!(d.io.gpuMem.wr.peek().litToBoolean &&
             d.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 20000) {
      idleStep(d)
      guard += 1
    }
    Predef.assert(guard < 20000, "flush burst never started")

    val burstAddr  = d.io.gpuMem.addr.peek().litValue.toInt
    val burstWords = scala.collection.mutable.ArrayBuffer[Long]()

    for (w <- 0 until 16) {
      Predef.assert(d.io.gpuMem.wr.peek().litToBoolean, s"wr dropped at burst word $w")
      burstWords += (d.io.gpuMem.wdata.peek().litValue.toLong & 0xffffL)
      if (w < 15) {
        d.io.gpuMem.waccept.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.waccept.poke(false.B)
      }
    }
    d.io.gpuMem.ready.poke(true.B)
    d.clock.step(1)
    d.io.gpuMem.ready.poke(false.B)
    d.clock.step(1)

    (burstAddr, burstWords.toSeq)
  }

  val tests = Tests {

    utest.test("direct_and_linked_framebuffers_are_bit_identical") {
      var direct: (Int, Seq[Long]) = null
      var linked: (Int, Seq[Long]) = null

      simulate(new BorgTestWrapper(cfg)) { d => direct = runScenario(d) }
      simulate(new BorgLinkTestWrapper(cfg, LinkParams(trainBeats = 8))) { d =>
        linked = runScenario(d)
      }

      println(s"  direct: base 0x${direct._1.toHexString}, ${direct._2.length} words")
      println(s"  linked: base 0x${linked._1.toHexString}, ${linked._2.length} words")

      // The gate itself.
      utest.assert(direct._1 == linked._1)
      utest.assert(direct._2 == linked._2)

      // Independently anchor it: a bug that broke *both* paths identically would
      // otherwise slip through a pure comparison.
      utest.assert(direct._1 == tileBase)
      utest.assert(direct._2.length == 16)
      val expected = (0 until 16).map { w =>
        val (r, g, b, _) = px(w)
        BorgTests.toRgb565(r, g, b).toLong & 0xffffL
      }
      utest.assert(direct._2 == expected)
    }

    utest.test("direct_and_linked_ftex_framebuffers_are_bit_identical") {
      var direct: (Int, Seq[Long], Int) = null
      var linked: (Int, Seq[Long], Int) = null

      simulate(new BorgTestWrapper(cfg)) { d => direct = runTextureScenario(d) }
      simulate(new BorgLinkTestWrapper(cfg, LinkParams(trainBeats = 8))) { d =>
        linked = runTextureScenario(d)
      }

      println(s"  direct: base 0x${direct._1.toHexString}, ${direct._2.length} words, " +
        s"${direct._3} gpuMem reads")
      println(s"  linked: base 0x${linked._1.toHexString}, ${linked._2.length} words, " +
        s"${linked._3} gpuMem reads")

      // The gate itself.
      utest.assert(direct._1 == linked._1)
      utest.assert(direct._2 == linked._2)

      // This scenario's whole point: real gpuMem read traffic actually
      // happened -- two sequential reads (B then RG) per pixel, 16 pixels.
      utest.assert(direct._3 == 32)
      utest.assert(linked._3 == 32)

      // Independently anchor it: a bug that broke *both* paths identically
      // (e.g. the same reference gpuMem model on both sides) would otherwise
      // slip through a pure comparison. Every pixel fetches the same planted
      // texel (U=V=0.0 for all 16), so the whole burst is that one colour.
      utest.assert(direct._1 == texTileBase)
      utest.assert(direct._2.length == 16)
      val expectedWord = BorgTests.toRgb565(texR, texG, texB).toLong & 0xffffL
      utest.assert(direct._2 == Seq.fill(16)(expectedWord))
    }
  }
}
