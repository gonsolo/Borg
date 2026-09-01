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

  def waitIdle(d: Dut, limit: Int = 6000): Unit = {
    var g = 0
    while (g < limit && !d.io.data_ready.peek().litToBoolean) {
      d.clock.step(1)
      g += 1
    }
    Predef.assert(g < limit, "timed out waiting for data_ready")
  }

  def rawWrite(d: Dut, addr: Int, data: BigInt): Unit = {
    waitIdle(d)
    d.io.address.poke(addr.U)
    d.io.data_in.poke(data.U)
    d.io.data_write_n.poke(2.U)
    d.clock.step(1)
    d.io.data_write_n.poke(3.U)
    waitIdle(d)
    d.clock.step(1)
  }

  def idleStep(d: Dut): Unit = {
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.waccept.poke(false.B)
    d.clock.step(1)
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

    // (3) Minimal shader: r0=r1=r2=1.0 so every edge tests inside, then halt.
    rawWrite(d, BorgGpuRegs.control_offset.litValue.toInt, 2) // reset pipeline
    rawWrite(d, 7 * 4, 0x3c00) // r7 = 1.0
    rawWrite(d, 6 * 4, 0x0000) // r6 = 0.0
    rawWrite(d, 128 + 0 * 4, Instructions.ADD(7, 6, 0))
    rawWrite(d, 128 + 1 * 4, Instructions.ADD(7, 6, 1))
    rawWrite(d, 128 + 2 * 4, Instructions.ADD(7, 6, 2))
    rawWrite(d, 128 + 3 * 4, 0) // halt -- no separate fragment shader
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
  }
}
