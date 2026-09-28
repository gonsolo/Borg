// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// HdmiScanoutFp16(separatePixelClock = true): the display side (VGA timing
// consumption, the frame buffer's read port, RGB output) runs on a genuinely
// different clock than the fill FSM -- ULX3S.scala now feeds it hdmiClock
// (125 MHz) while the fill FSM stays on sysClock (10 MHz), instead of a
// single shared clock as before today's fix. This is the one piece of that
// fix with no real-hardware confirmation of its own (the hardware tests
// confirmed the PLL/cold-boot side and the tick25-gated VGA/TMDS technique
// via a standalone pattern generator, not this module's dual-clock frame
// buffer) and no existing simulation coverage: ScanoutRealBackendTests only
// ever instantiates the module with separatePixelClock = false (its
// default), so it has never exercised this code path at all.
//
// This test drives the fill FSM on the module's own (test) clock and
// generates a second, independently-clocked domain for the display side via
// a simple divided clock (a standard technique for multi-clock RTL
// simulation, see e.g. BorgArcSimTop.scala's `.asClock` pattern) -- proving
// a pixel written through the fill FSM on one clock is correctly visible on
// the display side on the other, in both directions (display faster than
// fill, and slower), and across more than one fill loop.

package soc

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Wraps HdmiScanoutFp16(separatePixelClock = true), generating io.pixClk as
  * a clock divided from the module's own (test-driven) clock by `pixDiv`
  * cycles, so the test can drive everything else through ordinary poke/peek
  * on the single simulator-stepped clock.
  */
class ScanoutDualClockHarnessIO extends Bundle {
  val gpuReq   = Output(Bool())
  val gpuAddr  = Output(UInt(25.W))
  val gpuData  = Input(UInt(32.W))
  val gpuReady = Input(Bool())
  val enable   = Input(Bool())
  val fbBase   = Input(UInt(25.W))
  val hCount   = Input(UInt(10.W))
  val vCount   = Input(UInt(10.W))
  val de       = Input(Bool())
  val red      = Output(UInt(8.W))
  val green    = Output(UInt(8.W))
  val blue     = Output(UInt(8.W))
}

class ScanoutDualClockHarness(fbW: Int, fbH: Int, pixDiv: Int) extends Module {
  val io = IO(new ScanoutDualClockHarnessIO)

  // A genuinely different clock for the display side: free-running, divided
  // from the test's own clock by pixDiv (a power of two keeps this a plain
  // bit of the counter, matching how hdmiClock's own tick25 divider works).
  require((pixDiv & (pixDiv - 1)) == 0 && pixDiv >= 2, "pixDiv must be a power of two >= 2")
  private val divBits = log2Ceil(pixDiv)
  private val divCount = RegInit(0.U(divBits.W))
  divCount := divCount + 1.U
  private val pixClk = divCount(divBits - 1).asClock

  val scanout = Module(new HdmiScanoutFp16(fbWidth = fbW, fbHeight = fbH, separatePixelClock = true))
  scanout.io.pixClk.get := pixClk
  scanout.io.pixRst.get := reset.asBool

  scanout.io.gpuReq  <> io.gpuReq
  scanout.io.gpuAddr <> io.gpuAddr
  scanout.io.gpuData := io.gpuData
  scanout.io.gpuReady := io.gpuReady
  scanout.io.enable   := io.enable
  scanout.io.frontBuf := false.B
  scanout.io.fbBase   := io.fbBase
  scanout.io.fbBase1  := io.fbBase
  scanout.io.hCount   := io.hCount
  scanout.io.vCount   := io.vCount
  scanout.io.de       := io.de
  scanout.io.tick25   := true.B
  io.red   := scanout.io.red
  io.green := scanout.io.green
  io.blue  := scanout.io.blue
}

object ScanoutDualClockTests extends TestSuite {
  /** Byte address of pixel (col, row) in HdmiScanoutFp16's own 4x4-tiled
    * layout (fbBase 0), mirroring its tileIndex/pixIndex computation exactly
    * -- see HdmiScanoutFp16.scala. */
  def pixByteAddr(fbW: Int, col: Int, row: Int): Int = {
    val tilesPerRow = fbW / 4
    val tileIndex = (row / 4) * tilesPerRow + (col / 4)
    val pixIndex  = (row % 4) * 4 + (col % 4)
    (tileIndex << 5) + (pixIndex << 1)
  }

  /** RGB565 -> RGB8, mirroring HdmiScanoutFp16.rgb565ToRgb8 exactly. */
  def rgb565ToRgb8(px: Int): (Int, Int, Int) = {
    val r5 = (px >> 11) & 0x1F; val g6 = (px >> 5) & 0x3F; val b5 = px & 0x1F
    ((r5 << 3) | (r5 >> 2), (g6 << 2) | (g6 >> 4), (b5 << 3) | (b5 >> 2))
  }

  /** Run the fill FSM (test clock) for a generous, fixed cycle budget,
    * continuously serving whatever gpuReq is asking for with
    * `dataOf(byteAddr)` as the low-16-bit RGB565 word. gpuReq is asserted in
    * both the fill FSM's sReq and sWait states, but gpuReady is only
    * consumed in sWait -- so gpuReady must be held (not pulsed) across
    * however many cycles gpuReq stays high, or roughly half the requests
    * silently do nothing. */
  def fill(dut: ScanoutDualClockHarness, n: Int, dataOf: Int => Int): Unit = {
    for (_ <- 0 until n * 6 + 100) {
      val req = dut.io.gpuReq.peek().litToBoolean
      if (req) {
        val addr = dut.io.gpuAddr.peek().litValue.toInt
        dut.io.gpuData.poke((dataOf(addr) & 0xFFFF).U)
      }
      dut.io.gpuReady.poke(req.B)
      dut.clock.step(1)
    }
    dut.io.gpuReady.poke(false.B)
  }

  /** Point the display at pixel (col, row) and step enough test-clock cycles
    * for several pixClk edges plus the frame buffer's read latency, then
    * return the displayed RGB8. */
  /** The overlay's screen offset for a 1:1-scale fbW x fbH buffer centered
    * on the 640x480 screen -- mirrors HdmiScanoutFp16's own startX/startY. */
  def screenOffset(fbW: Int, fbH: Int): (Int, Int) = ((640 - fbW) / 2, (480 - fbH) / 2)

  def readPixel(dut: ScanoutDualClockHarness, fbW: Int, fbH: Int, col: Int, row: Int, pixDiv: Int): (Int, Int, Int) = {
    val (startX, startY) = screenOffset(fbW, fbH)
    dut.io.hCount.poke((startX + col).U); dut.io.vCount.poke((startY + row).U); dut.io.de.poke(true.B)
    dut.clock.step(pixDiv * 4 + 8) // several pixClk periods, well past 1-cycle BRAM latency
    (dut.io.red.peek().litValue.toInt, dut.io.green.peek().litValue.toInt, dut.io.blue.peek().litValue.toInt)
  }

  def check(dut: ScanoutDualClockHarness, fbW: Int, fbH: Int, col: Int, row: Int, pixDiv: Int,
            dataOf: Int => Int, label: String): Unit = {
    val expected565 = dataOf(pixByteAddr(fbW, col, row)) & 0xFFFF
    val (expR, expG, expB) = rgb565ToRgb8(expected565)
    val (r, g, b) = readPixel(dut, fbW, fbH, col, row, pixDiv)
    println(s"  $label: pixel($col,$row) = ($r,$g,$b), expect ($expR,$expG,$expB)")
    utest.assert(r == expR && g == expG && b == expB)
  }

  val tests = Tests {
    utest.test("a pixel written on the fill clock is read correctly on a faster display clock") {
      val fbW = 8; val fbH = 8; val numPixels = fbW * fbH
      simulate(new ScanoutDualClockHarness(fbW, fbH, pixDiv = 2)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
        dut.io.fbBase.poke(0.U); dut.io.enable.poke(true.B); dut.io.gpuReady.poke(false.B)
        def word(addr: Int): Int = (addr + 1) & 0xFFFF // any deterministic, non-zero function of addr
        fill(dut, numPixels, word)
        check(dut, fbW, fbH, col = 3, row = 5, pixDiv = 2, word, "fast pixClk (div 2)")
      }
    }

    utest.test("a pixel written on the fill clock is read correctly on a slower display clock") {
      val fbW = 8; val fbH = 8; val numPixels = fbW * fbH
      simulate(new ScanoutDualClockHarness(fbW, fbH, pixDiv = 8)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
        dut.io.fbBase.poke(0.U); dut.io.enable.poke(true.B); dut.io.gpuReady.poke(false.B)
        def word(addr: Int): Int = (addr + 7) & 0xFFFF
        fill(dut, numPixels, word)
        check(dut, fbW, fbH, col = 6, row = 2, pixDiv = 8, word, "slow pixClk (div 8)")
      }
    }

    utest.test("the fill FSM keeps looping and overwriting across many pixClk periods") {
      // Exercises the wrap/double-buffer-latch path (baseAddr/baseLoaded in
      // HdmiScanoutFp16) with a display clock much slower than the fill clock
      // -- the extreme direction closest to a real full-frame refill racing a
      // slow display read.
      val fbW = 8; val fbH = 8; val numPixels = fbW * fbH
      simulate(new ScanoutDualClockHarness(fbW, fbH, pixDiv = 16)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
        dut.io.fbBase.poke(0.U); dut.io.enable.poke(true.B); dut.io.gpuReady.poke(false.B)
        def wordLoop1(addr: Int): Int = (addr + 1) & 0xFFFF
        def wordLoop2(addr: Int): Int = (addr + 100) & 0xFFFF
        fill(dut, numPixels, wordLoop1)
        fill(dut, numPixels, wordLoop2)
        check(dut, fbW, fbH, col = 2, row = 1, pixDiv = 16, wordLoop2, "after 2 fill loops, slow pixClk (div 16)")
      }
    }
  }
}
