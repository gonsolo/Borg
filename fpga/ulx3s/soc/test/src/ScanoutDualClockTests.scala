// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// HdmiScanoutFp16(separatePixelClock = true): the display side (VGA timing
// consumption, the frame buffer's read port, RGB output) runs on a genuinely
// different clock than the fill FSM -- ULX3S.scala feeds it hdmiClock
// (125 MHz) while the fill FSM stays on sysClock (10 MHz).
//
// frameBuf itself is single-clocked on io.pixClk (see HdmiScanoutFp16's
// write-side CDC comment): yosys's ECP5 BRAM inference never offers DP16KD
// for a memory whose read/write ports use genuinely different clocks, so the
// low-bandwidth fill-write side crosses into pixClk via a plain toggle
// synchronizer instead, with NO back-pressure/ack. That design is only
// correct when pixClk samples fast enough relative to the write rate to
// never see two writes land between samples -- true for every real
// instantiation in this codebase (pixClk >= 125 MHz vs sysClock <= 25 MHz,
// and a write can fire at most once every 2 sysClock cycles), but NOT true
// in general. This test drives that real relationship (pixClk the harness's
// own fast, undivided clock; sysClock a slower clock divided down from it,
// exactly the "pixClk much faster" case the toggle sync relies on) and
// proves a pixel written through the fill FSM on the slow clock is correctly
// visible on the display side on the fast clock, across more than one fill
// loop and at a few different speed margins.

package soc

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Wraps HdmiScanoutFp16(separatePixelClock = true), instantiating it under a
  * clock (`sysClk`) divided down from the harness's own (test-driven) clock
  * by `sysDiv` cycles, and wiring io.pixClk to the harness's own undivided
  * clock -- so the harness's clock plays pixClk (fast) and the divided
  * clock plays sysClock (slow), matching the real hardware ratio.
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

class ScanoutDualClockHarness(fbW: Int, fbH: Int, sysDiv: Int, rgb565Store: Boolean = false,
                              doubleBuffer: Boolean = true) extends Module {
  val io = IO(new ScanoutDualClockHarnessIO)

  // sysClock: free-running, divided from the harness's own (pixClk-playing)
  // clock by sysDiv (a power of two keeps this a plain bit of the counter,
  // matching how hdmiClock's own tick25 divider works).
  require((sysDiv & (sysDiv - 1)) == 0 && sysDiv >= 2, "sysDiv must be a power of two >= 2")
  private val divBits = log2Ceil(sysDiv)
  private val divCount = RegInit(0.U(divBits.W))
  divCount := divCount + 1.U
  private val sysClk = divCount(divBits - 1).asClock

  val scanout = withClockAndReset(sysClk, reset.asBool) {
    Module(new HdmiScanoutFp16(fbWidth = fbW, fbHeight = fbH, separatePixelClock = true,
                               rgb565Store = rgb565Store, doubleBuffer = doubleBuffer))
  }
  scanout.io.pixClk.get := clock
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

  /** Run the fill FSM (sysClk, divided sysDiv:1 from the harness's own
    * driving clock) for a generous, fixed cycle budget on the harness's own
    * (fast) clock, continuously serving whatever gpuReq is asking for with
    * `dataOf(byteAddr)` as the low-16-bit RGB565 word. gpuReq is asserted in
    * both the fill FSM's sReq and sWait states, but gpuReady is only
    * consumed in sWait -- so gpuReady must be held (not pulsed) across
    * however many cycles gpuReq stays high. The budget is scaled by sysDiv
    * since the fill FSM only advances once every sysDiv driving-clock ticks.
    */
  def fill(dut: ScanoutDualClockHarness, n: Int, sysDiv: Int, dataOf: Int => Int): Unit = {
    for (_ <- 0 until (n * 6 + 100) * sysDiv) {
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

  /** The overlay's screen offset for a 1:1-scale fbW x fbH buffer centered
    * on the 640x480 screen -- mirrors HdmiScanoutFp16's own startX/startY. */
  def screenOffset(fbW: Int, fbH: Int): (Int, Int) = ((640 - fbW) / 2, (480 - fbH) / 2)

  /** Point the display at pixel (col, row) and step enough of the harness's
    * own (pixClk) clock for the BRAM's 1-cycle read latency, then return the
    * displayed RGB8. */
  def readPixel(dut: ScanoutDualClockHarness, fbW: Int, fbH: Int, col: Int, row: Int): (Int, Int, Int) = {
    val (startX, startY) = screenOffset(fbW, fbH)
    dut.io.hCount.poke((startX + col).U); dut.io.vCount.poke((startY + row).U); dut.io.de.poke(true.B)
    dut.clock.step(8) // a few pixClk cycles, well past 1-cycle BRAM latency
    (dut.io.red.peek().litValue.toInt, dut.io.green.peek().litValue.toInt, dut.io.blue.peek().litValue.toInt)
  }

  def check(dut: ScanoutDualClockHarness, fbW: Int, fbH: Int, col: Int, row: Int,
            dataOf: Int => Int, label: String): Unit = {
    val expected565 = dataOf(pixByteAddr(fbW, col, row)) & 0xFFFF
    val (expR, expG, expB) = rgb565ToRgb8(expected565)
    val (r, g, b) = readPixel(dut, fbW, fbH, col, row)
    println(s"  $label: pixel($col,$row) = ($r,$g,$b), expect ($expR,$expG,$expB)")
    utest.assert(r == expR && g == expG && b == expB)
  }

  val tests = Tests {
    utest.test("a pixel written on the slow fill clock is read correctly on the fast display clock") {
      val fbW = 8; val fbH = 8; val numPixels = fbW * fbH
      simulate(new ScanoutDualClockHarness(fbW, fbH, sysDiv = 4)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(8 * 4); dut.reset.poke(false.B)
        dut.io.fbBase.poke(0.U); dut.io.enable.poke(true.B); dut.io.gpuReady.poke(false.B)
        def word(addr: Int): Int = (addr + 1) & 0xFFFF // any deterministic, non-zero function of addr
        fill(dut, numPixels, sysDiv = 4, word)
        check(dut, fbW, fbH, col = 3, row = 5, word, "sysDiv 4")
      }
    }

    utest.test("a pixel written on the slow fill clock is read correctly at a wider speed margin") {
      val fbW = 8; val fbH = 8; val numPixels = fbW * fbH
      simulate(new ScanoutDualClockHarness(fbW, fbH, sysDiv = 16)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(8 * 16); dut.reset.poke(false.B)
        dut.io.fbBase.poke(0.U); dut.io.enable.poke(true.B); dut.io.gpuReady.poke(false.B)
        def word(addr: Int): Int = (addr + 7) & 0xFFFF
        fill(dut, numPixels, sysDiv = 16, word)
        check(dut, fbW, fbH, col = 6, row = 2, word, "sysDiv 16")
      }
    }

    utest.test("the fill FSM keeps looping and overwriting across more than one fill loop") {
      // Exercises the wrap/double-buffer-latch path (baseAddr/baseLoaded in
      // HdmiScanoutFp16) with the real pixClk/sysClock margin (12.5x on
      // actual hardware) approximated by sysDiv = 16.
      val fbW = 8; val fbH = 8; val numPixels = fbW * fbH
      simulate(new ScanoutDualClockHarness(fbW, fbH, sysDiv = 16)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(8 * 16); dut.reset.poke(false.B)
        dut.io.fbBase.poke(0.U); dut.io.enable.poke(true.B); dut.io.gpuReady.poke(false.B)
        def wordLoop1(addr: Int): Int = (addr + 1) & 0xFFFF
        def wordLoop2(addr: Int): Int = (addr + 100) & 0xFFFF
        fill(dut, numPixels, sysDiv = 16, wordLoop1)
        fill(dut, numPixels, sysDiv = 16, wordLoop2)
        check(dut, fbW, fbH, col = 2, row = 1, wordLoop2, "after 2 fill loops, sysDiv 16")
      }
    }

    utest.test("RGB565 storage and a single buffer show the same picture over two fill loops") {
      val fbW = 8; val fbH = 8; val numPixels = fbW * fbH
      simulate(new ScanoutDualClockHarness(fbW, fbH, sysDiv = 16, rgb565Store = true, doubleBuffer = false)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(8 * 16); dut.reset.poke(false.B)
        dut.io.fbBase.poke(0.U); dut.io.enable.poke(true.B); dut.io.gpuReady.poke(false.B)
        def wordLoop1(addr: Int): Int = (addr * 37 + 5) & 0xFFFF
        def wordLoop2(addr: Int): Int = (addr * 91 + 0xA55A) & 0xFFFF
        fill(dut, numPixels, sysDiv = 16, wordLoop1)
        check(dut, fbW, fbH, col = 6, row = 2, wordLoop1, "rgb565 loop 1")
        fill(dut, numPixels, sysDiv = 16, wordLoop2)
        check(dut, fbW, fbH, col = 1, row = 7, wordLoop2, "rgb565 loop 2")
        check(dut, fbW, fbH, col = 4, row = 4, wordLoop2, "rgb565 loop 2 (second pixel)")
      }
    }
  }
}
