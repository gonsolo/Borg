// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import borg.{BorgConfig, BorgGpuRegs, FloatConfig, Instructions}

/** Rung A's acceptance gate at the SoC level -- the counterpart to
  * `borg.BorgLinkEquivalenceTests`, but through `Peripherals`'s real address
  * decode and `SoCLogic.wireBorgLoopback()` instead of driving `Borg`/the link
  * directly. This is what's actually new here: confirming `Peripherals`
  * correctly selects `linkOpt` under [[BorgLoopback]] and that
  * `wireBorgLoopback()`'s pin wiring is correct, on top of a link whose own
  * correctness `BorgLinkEquivalenceTests` already established.
  *
  * Same scenario as that gate: write a 16-pixel tile via MMIO, rasterize it,
  * let the hardware flusher emit its RGB565 burst, and require the result be
  * bit-identical between `BorgDirect` and `BorgLoopback`.
  */
object PeripheralsLoopbackEquivalenceTests extends TestSuite {

  val config = FloatConfig.FP16
  val cfg    = BorgConfig.Default.copy(fp = FloatConfig.FP16)
  val tileBase = 0x200

  // addr[11:10] = PERI_BORG (3), addr[9:0] = Borg's own register offset.
  def borgAddr(r: Int): Int = (3 << 10) | (r & 0x3ff)

  /** FP16 bit pattern for a float -- local copy of `borg.BorgTests.floatToBits`'s
    * FP16 branch (test-only code, not shared across mill modules).
    */
  def floatToBits16(f: Float): Int = {
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits >>> 31) << 15
    var exp = ((bits >>> 23) & 0xff) - 127 + 15
    var sig = (bits >>> 13) & 0x3ff
    if (exp <= 0) { exp = 0; sig = 0 }
    else if (exp >= 31) { exp = 31; sig = 0x3ff }
    sign | (exp << 10) | sig
  }

  /** Verbatim copy of `borg.BorgTests.fp16ToUnorm`/`toRgb565` -- the fixed-point
    * quantization `BorgTileFlusher` actually implements in hardware, not a
    * general float conversion, so this must match bit-for-bit rather than be
    * derived independently.
    */
  def fp16ToUnorm(h: Int, bits: Int): Int = {
    val sign = (h >> 15) & 1
    val exp  = (h >> 10) & 0x1f
    val mant = h & 0x3ff
    val full = (1 << 10) | mant
    if (sign == 1 || exp < 7) 0
    else if (exp >= 15) (1 << bits) - 1
    else (full >> (17 - exp)) >> (8 - bits)
  }
  def toRgb565(r: Int, g: Int, b: Int): Int =
    (fp16ToUnorm(r, 5) << 11) | (fp16ToUnorm(g, 6) << 5) | fp16ToUnorm(b, 5)

  def px(i: Int): (Int, Int, Int, Int) = {
    val r = floatToBits16(i / 16.0f)
    val g = floatToBits16((15 - i) / 16.0f)
    val b = floatToBits16(0.5f)
    val z = 0x4000 + i
    (r, g, b, z)
  }

  def rawWrite(d: BorgLoopbackProbeHarness, addr: Int, data: BigInt): Unit = {
    d.io.mmio.req.valid.poke(true.B)
    d.io.mmio.req.bits.addr.poke(borgAddr(addr).U)
    d.io.mmio.req.bits.data.poke(data.U)
    d.io.mmio.req.bits.write.poke(true.B)
    d.io.mmio.req.bits.size.poke(2.U)
    var g = 0
    while (g < 4000 && !d.io.mmio.req.ready.peek().litToBoolean) { d.clock.step(1); g += 1 }
    Predef.assert(g < 4000, "mmio req never accepted")
    d.clock.step(1)
    d.io.mmio.req.valid.poke(false.B)
    d.io.mmio.resp.ready.poke(true.B)
    g = 0
    while (g < 4000 && !d.io.mmio.resp.valid.peek().litToBoolean) { d.clock.step(1); g += 1 }
    Predef.assert(g < 4000, "mmio resp never arrived")
    d.clock.step(1)
  }

  def idleStep(d: BorgLoopbackProbeHarness): Unit = {
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.waccept.poke(false.B)
    d.clock.step(1)
  }

  def runScenario(d: BorgLoopbackProbeHarness): (Int, Seq[Long]) = {
    d.reset.poke(true.B)
    d.io.mmio.req.valid.poke(false.B)
    d.io.mmio.resp.ready.poke(false.B)
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.waccept.poke(false.B)
    d.io.gpuMem.data.poke(0.U)
    d.clock.step(6)
    d.reset.poke(false.B)
    // Tile-buffer auto-clear, plus time for the link to train (loopback mode).
    for (_ <- 0 until 400) idleStep(d)

    for (i <- 0 until 16) {
      val (r, g, b, z) = px(i)
      rawWrite(d, BorgGpuRegs.tile_ctrl_offset.litValue.toInt, i & 0xf)
      rawWrite(d, BorgGpuRegs.tile_bz_offset.litValue.toInt,
        (BigInt(b & 0xffff) << 16) | BigInt(z & 0xffff))
      rawWrite(d, BorgGpuRegs.tile_rg_offset.litValue.toInt,
        (BigInt(r & 0xffff) << 16) | BigInt(g & 0xffff))
    }
    for (_ <- 0 until 4) idleStep(d)

    rawWrite(d, BorgGpuRegs.flush_fb_base_offset.litValue.toInt, tileBase)
    rawWrite(d, BorgGpuRegs.flush_width_offset.litValue.toInt, 5)

    rawWrite(d, BorgGpuRegs.control_offset.litValue.toInt, 2)
    rawWrite(d, 7 * 4, 0x3c00)
    rawWrite(d, 6 * 4, 0x0000)
    rawWrite(d, 128 + 0 * 4, Instructions.ADD(7, 6, 0))
    rawWrite(d, 128 + 1 * 4, Instructions.ADD(7, 6, 1))
    rawWrite(d, 128 + 2 * 4, Instructions.ADD(7, 6, 2))
    rawWrite(d, 128 + 3 * 4, 0)
    rawWrite(d, BorgGpuRegs.frag_pc_offset.litValue.toInt, 0)

    rawWrite(d, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, (0 << 10) | 0)
    for (_ <- 0 until 5) idleStep(d)

    for (_ <- 0 until 16) {
      rawWrite(d, BorgGpuRegs.iter_offset.litValue.toInt, 1)
      for (_ <- 0 until 120) idleStep(d)
    }

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

    utest.test("borg_direct_and_borg_loopback_framebuffers_are_bit_identical") {
      var direct: (Int, Seq[Long]) = null
      var loopback: (Int, Seq[Long]) = null

      simulate(new BorgLoopbackProbeHarness(cfg, BorgDirect)) { d => direct = runScenario(d) }
      simulate(new BorgLoopbackProbeHarness(cfg, BorgLoopback)) { d => loopback = runScenario(d) }

      println(s"  direct:   base 0x${direct._1.toHexString}, ${direct._2.length} words")
      println(s"  loopback: base 0x${loopback._1.toHexString}, ${loopback._2.length} words")

      utest.assert(direct._1 == loopback._1)
      utest.assert(direct._2 == loopback._2)

      utest.assert(direct._1 == tileBase)
      utest.assert(direct._2.length == 16)
      val expected = (0 until 16).map { w =>
        val (r, g, b, _) = px(w)
        toRgb565(r, g, b).toLong & 0xffffL
      }
      utest.assert(direct._2 == expected)
    }
  }
}
