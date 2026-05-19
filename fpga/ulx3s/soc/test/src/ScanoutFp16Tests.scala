// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// E2E test: GPU write FP16 tiled data → SDRAM → HdmiScanoutFp16 → RGB8 output.
//
// Steps:
//   1. Fill tile (0,0) in SDRAM with known FP16 pixel values via GPU writes.
//   2. Drive VGA timing to trigger scanout prefetch for row 0.
//   3. During active display, verify RGB8 output matches expected colors.

package soc

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import memory.{MemoryController, SdramBackendSim}
import utest._

// ── Test harness ──
class ScanoutFp16HarnessIO extends Bundle {
  // GPU write port (fill framebuffer)
  val gpuWr    = Input(Bool())
  val gpuReq   = Input(Bool())
  val gpuWdata = Input(UInt(32.W))
  val gpuAddr  = Input(UInt(25.W))
  val gpuReady = Output(Bool())

  // VGA timing
  val hCount = Input(UInt(10.W))
  val vCount = Input(UInt(10.W))
  val de     = Input(Bool())
  val tick25 = Input(Bool())
  val enable = Input(Bool())

  // RGB output
  val red   = Output(UInt(8.W))
  val green = Output(UInt(8.W))
  val blue  = Output(UInt(8.W))

  val memBusy = Output(Bool())
}

class ScanoutFp16Harness extends Module {
  val io = IO(new ScanoutFp16HarnessIO)

  val mem     = Module(new MemoryController)
  val sdram   = Module(new SdramBackendSim(words = 16384, rdDelay = 4, wrDelay = 2))
  val scanout = Module(new HdmiScanoutFp16(fbBase = 0x1000, fbWidth = 32, fbHeight = 32))

  sdram.io.backend <> mem.io.backend

  // Tie off CPU ports
  mem.io.instr.req.valid    := false.B
  mem.io.instr.req.bits     := 0.U
  mem.io.instr.resp.ready   := true.B
  mem.io.cpuData.req.valid  := false.B
  mem.io.cpuData.req.bits   := 0.U.asTypeOf(mem.io.cpuData.req.bits)
  mem.io.cpuData.resp.ready := true.B

  // GPU port mux: fill (write) or scanout (read)
  val filling = io.gpuWr
  mem.io.gpuMem.wr    := io.gpuWr
  mem.io.gpuMem.req   := Mux(filling, false.B, scanout.io.gpuReq)
  mem.io.gpuMem.addr  := Mux(filling, io.gpuAddr, scanout.io.gpuAddr)
  mem.io.gpuMem.wdata := io.gpuWdata

  io.gpuReady := mem.io.gpuMem.ready

  scanout.io.gpuData  := mem.io.gpuMem.data
  scanout.io.gpuReady := Mux(filling, false.B, mem.io.gpuMem.ready)
  scanout.io.hCount   := io.hCount
  scanout.io.vCount   := io.vCount
  scanout.io.de       := io.de
  scanout.io.tick25   := io.tick25
  scanout.io.enable   := io.enable

  io.red     := scanout.io.red
  io.green   := scanout.io.green
  io.blue    := scanout.io.blue
  io.memBusy := sdram.io.backend.busy
}

object ScanoutFp16Tests extends TestSuite {

  val tests = Tests {

    utest.test("HdmiScanoutFp16: FP16 tiled data → RGB8 output") {
      simulate(new ScanoutFp16Harness) { dut =>

        val TIMEOUT = 500000
        var cycle = 0
        def tick(n: Int = 1): Unit = {
          for (_ <- 0 until n) dut.clock.step()
          cycle += n
          Predef.assert(cycle < TIMEOUT, s"TIMEOUT at cycle $cycle")
        }

        // Reset
        dut.reset.poke(true.B)
        tick(5)
        dut.reset.poke(false.B)
        tick(5)
        cycle = 10

        // Deassert everything
        dut.io.gpuWr.poke(false.B)
        dut.io.gpuReq.poke(false.B)
        dut.io.gpuAddr.poke(0.U)
        dut.io.gpuWdata.poke(0.U)
        dut.io.hCount.poke(0.U)
        dut.io.vCount.poke(0.U)
        dut.io.de.poke(false.B)
        dut.io.tick25.poke(false.B)
        dut.io.enable.poke(false.B)

        // ── Helper: write one 16-bit FP16 value to SDRAM ──
        def gpuWrite16(addr: Int, data: Int): Unit = {
          dut.io.gpuWr.poke(true.B)
          dut.io.gpuAddr.poke(addr.U)
          dut.io.gpuWdata.poke(data.U)
          tick()
          var waited = 0
          while (!dut.io.gpuReady.peek().litToBoolean && waited < 200) {
            tick(); waited += 1
          }
          Predef.assert(waited < 200, s"GPU write at 0x${addr.toHexString} timed out")
          dut.io.gpuWr.poke(false.B)
          tick()
          waited = 0
          while (dut.io.memBusy.peek().litToBoolean && waited < 200) {
            tick(); waited += 1
          }
        }

        // ── Phase 1: Fill tile (0,0) with test patterns ──
        // Tiled layout: tile_addr = fbBase + tile_index * 128
        //               pixel_addr = tile_addr + pixel_index * 8
        //               R at +0, G at +2, B at +4, Z at +6
        val fbBase = 0x1000

        // Pixel (0,0): pure red → R=0x3C00 (1.0), G=0, B=0
        println(s"[$cycle] Writing pixel (0,0): red")
        gpuWrite16(fbBase + 0, 0x3C00)  // R
        gpuWrite16(fbBase + 2, 0x0000)  // G
        gpuWrite16(fbBase + 4, 0x0000)  // B
        gpuWrite16(fbBase + 6, 0x0000)  // Z

        // Pixel (1,0): pure green → R=0, G=0x3C00 (1.0), B=0
        println(s"[$cycle] Writing pixel (1,0): green")
        gpuWrite16(fbBase + 8,  0x0000)  // R
        gpuWrite16(fbBase + 10, 0x3C00)  // G
        gpuWrite16(fbBase + 12, 0x0000)  // B
        gpuWrite16(fbBase + 14, 0x0000)  // Z

        // Pixel (2,0): pure blue → R=0, G=0, B=0x3C00 (1.0)
        println(s"[$cycle] Writing pixel (2,0): blue")
        gpuWrite16(fbBase + 16, 0x0000)  // R
        gpuWrite16(fbBase + 18, 0x0000)  // G
        gpuWrite16(fbBase + 20, 0x3C00)  // B
        gpuWrite16(fbBase + 22, 0x0000)  // Z

        // Pixel (3,0): 50% gray → R=G=B=0x3800 (0.5)
        println(s"[$cycle] Writing pixel (3,0): 50% gray")
        gpuWrite16(fbBase + 24, 0x3800)  // R
        gpuWrite16(fbBase + 26, 0x3800)  // G
        gpuWrite16(fbBase + 28, 0x3800)  // B
        gpuWrite16(fbBase + 30, 0x0000)  // Z

        println(s"[$cycle] Fill complete.")

        // ── Phase 2: Trigger scanout prefetch for FB row 0 ──
        // HdmiScanoutFp16 with 32×32 magnified 2×:
        //   startY = (480-64)/2 = 208
        //   endY = 272
        // triggerFetch fires when hCount=640, tick25=true, and
        // nextOverlayV is in [startY, endY).
        // nextOverlayV = vCount + 1
        // For FB row 0: nextOverlayV = startY = 208 → vCount = 207
        dut.io.enable.poke(true.B)
        dut.io.hCount.poke(640.U)
        dut.io.vCount.poke(207.U)
        dut.io.de.poke(false.B)       // hblank
        dut.io.tick25.poke(true.B)
        tick()
        dut.io.tick25.poke(false.B)   // single pulse
        println(s"[$cycle] Triggered prefetch for row 0")

        // Let FSM complete: 32 pixels × 2 reads × ~12 cycles = ~768 cycles
        tick(1000)
        println(s"[$cycle] Prefetch done")

        // ── Phase 3: Verify RGB8 output ──
        // During active display: vCount=208, de=true
        // startX = (640-64)/2 = 288
        // Pixel (0,0) appears at hCount = 288 (and 289, due to 2× magnification)
        // Pixel (1,0) appears at hCount = 290 (and 291)
        // Pixel (2,0) appears at hCount = 292 (and 293)
        // Pixel (3,0) appears at hCount = 294 (and 295)
        dut.io.vCount.poke(208.U)
        dut.io.de.poke(true.B)

        // Test pixel (0,0): red → R=255, G=0, B=0
        dut.io.hCount.poke(288.U)
        tick(2)  // allow line buffer read
        var r = dut.io.red.peek().litValue.toInt
        var g = dut.io.green.peek().litValue.toInt
        var b = dut.io.blue.peek().litValue.toInt
        println(s"[$cycle] Pixel (0,0): R=$r G=$g B=$b (expected R=255 G=0 B=0)")
        Predef.assert(r == 255, s"Pixel (0,0) R: expected 255, got $r")
        Predef.assert(g == 0,   s"Pixel (0,0) G: expected 0, got $g")
        Predef.assert(b == 0,   s"Pixel (0,0) B: expected 0, got $b")

        // Test pixel (1,0): green → R=0, G=255, B=0
        dut.io.hCount.poke(290.U)
        tick(2)
        r = dut.io.red.peek().litValue.toInt
        g = dut.io.green.peek().litValue.toInt
        b = dut.io.blue.peek().litValue.toInt
        println(s"[$cycle] Pixel (1,0): R=$r G=$g B=$b (expected R=0 G=255 B=0)")
        Predef.assert(r == 0,   s"Pixel (1,0) R: expected 0, got $r")
        Predef.assert(g == 255, s"Pixel (1,0) G: expected 255, got $g")
        Predef.assert(b == 0,   s"Pixel (1,0) B: expected 0, got $b")

        // Test pixel (2,0): blue → R=0, G=0, B=255
        dut.io.hCount.poke(292.U)
        tick(2)
        r = dut.io.red.peek().litValue.toInt
        g = dut.io.green.peek().litValue.toInt
        b = dut.io.blue.peek().litValue.toInt
        println(s"[$cycle] Pixel (2,0): R=$r G=$g B=$b (expected R=0 G=0 B=255)")
        Predef.assert(r == 0,   s"Pixel (2,0) R: expected 0, got $r")
        Predef.assert(g == 0,   s"Pixel (2,0) G: expected 0, got $g")
        Predef.assert(b == 255, s"Pixel (2,0) B: expected 255, got $b")

        // Test pixel (3,0): 50% gray → R=G=B=128
        // FP16 0x3800 = 0.5 → 0.5 × 256 = 128
        dut.io.hCount.poke(294.U)
        tick(2)
        r = dut.io.red.peek().litValue.toInt
        g = dut.io.green.peek().litValue.toInt
        b = dut.io.blue.peek().litValue.toInt
        println(s"[$cycle] Pixel (3,0): R=$r G=$g B=$b (expected R=128 G=128 B=128)")
        Predef.assert(r == 128, s"Pixel (3,0) R: expected 128, got $r")
        Predef.assert(g == 128, s"Pixel (3,0) G: expected 128, got $g")
        Predef.assert(b == 128, s"Pixel (3,0) B: expected 128, got $b")

        // Test outside overlay: should be black
        dut.io.hCount.poke(100.U)
        tick(2)
        r = dut.io.red.peek().litValue.toInt
        g = dut.io.green.peek().litValue.toInt
        b = dut.io.blue.peek().litValue.toInt
        println(s"[$cycle] Outside overlay: R=$r G=$g B=$b (expected 0 0 0)")
        Predef.assert(r == 0 && g == 0 && b == 0, "Outside overlay should be black")

        println(s"[$cycle] ✓ HdmiScanoutFp16 E2E test passed!")
      }
    }

    utest.test("HdmiScanoutFp16: multi-tile cross-tile addressing") {
      simulate(new ScanoutFp16Harness) { dut =>

        val TIMEOUT = 500000
        var cycle = 0
        def tick(n: Int = 1): Unit = {
          for (_ <- 0 until n) dut.clock.step()
          cycle += n
          Predef.assert(cycle < TIMEOUT, s"TIMEOUT at cycle $cycle")
        }

        dut.reset.poke(true.B); tick(5)
        dut.reset.poke(false.B); tick(5)
        cycle = 10

        dut.io.gpuWr.poke(false.B)
        dut.io.gpuReq.poke(false.B)
        dut.io.gpuAddr.poke(0.U)
        dut.io.gpuWdata.poke(0.U)
        dut.io.hCount.poke(0.U)
        dut.io.vCount.poke(0.U)
        dut.io.de.poke(false.B)
        dut.io.tick25.poke(false.B)
        dut.io.enable.poke(false.B)

        def gpuWrite16(addr: Int, data: Int): Unit = {
          dut.io.gpuWr.poke(true.B)
          dut.io.gpuAddr.poke(addr.U)
          dut.io.gpuWdata.poke(data.U)
          tick()
          var waited = 0
          while (!dut.io.gpuReady.peek().litToBoolean && waited < 200) {
            tick(); waited += 1
          }
          Predef.assert(waited < 200, s"GPU write at 0x${addr.toHexString} timed out")
          dut.io.gpuWr.poke(false.B)
          tick()
          waited = 0
          while (dut.io.memBusy.peek().litToBoolean && waited < 200) {
            tick(); waited += 1
          }
        }

        // Helper: fill all 16 pixels in a tile with the same color
        def fillTile(tileIndex: Int, r: Int, g: Int, b: Int): Unit = {
          val tileAddr = 0x1000 + tileIndex * 128
          for (pix <- 0 until 16) {
            gpuWrite16(tileAddr + pix * 8 + 0, r)
            gpuWrite16(tileAddr + pix * 8 + 2, g)
            gpuWrite16(tileAddr + pix * 8 + 4, b)
            gpuWrite16(tileAddr + pix * 8 + 6, 0)
          }
        }

        // Helper: trigger prefetch for a given FB row
        def prefetchRow(fbRow: Int): Unit = {
          // overlay magnified 2×: startY=208
          // nextOverlayV = startY + fbRow*2 → vCount = startY + fbRow*2 - 1
          val vCount = 208 + fbRow * 2 - 1
          dut.io.hCount.poke(640.U)
          dut.io.vCount.poke(vCount.U)
          dut.io.de.poke(false.B)
          dut.io.tick25.poke(false.B)
          tick()  // settle with new vCount/hCount before pulsing tick25
          dut.io.tick25.poke(true.B)
          tick()
          dut.io.tick25.poke(false.B)
          tick(2000)  // let FSM complete
        }

        // Helper: read RGB at a given FB pixel x during display of a given FB row
        def readPixel(fbRow: Int, fbX: Int): (Int, Int, Int) = {
          dut.io.vCount.poke((208 + fbRow * 2).U)
          dut.io.de.poke(true.B)
          dut.io.hCount.poke((288 + fbX * 2).U)
          tick(2)
          (dut.io.red.peek().litValue.toInt,
           dut.io.green.peek().litValue.toInt,
           dut.io.blue.peek().litValue.toInt)
        }

        // ── Fill 3 tiles with distinct colors ──
        // 32×32 FB, tilesPerRow = 8
        // Tile (col=0, row=0): index 0 → RED
        // Tile (col=1, row=0): index 1 → GREEN
        // Tile (col=0, row=1): index 8 → BLUE
        println(s"[$cycle] Filling tile (0,0) = red")
        fillTile(0, 0x3C00, 0x0000, 0x0000)
        println(s"[$cycle] Filling tile (1,0) = green")
        fillTile(1, 0x0000, 0x3C00, 0x0000)
        println(s"[$cycle] Filling tile (0,1) = blue")
        fillTile(8, 0x0000, 0x0000, 0x3C00)
        println(s"[$cycle] Fill complete.")

        dut.io.enable.poke(true.B)

        // ── Scanline y=0: pixels 0-3 from tile(0,0)=red, pixels 4-7 from tile(1,0)=green ──
        println(s"[$cycle] Prefetching row 0...")
        prefetchRow(0)

        val (r0, g0, b0) = readPixel(0, 0)   // tile (0,0) pixel (0,0)
        println(s"[$cycle] FB(0,0): R=$r0 G=$g0 B=$b0 (expect red)")
        Predef.assert(r0 == 255 && g0 == 0 && b0 == 0, s"FB(0,0) expected red, got ($r0,$g0,$b0)")

        val (r3, g3, b3) = readPixel(0, 3)   // tile (0,0) pixel (3,0)
        println(s"[$cycle] FB(3,0): R=$r3 G=$g3 B=$b3 (expect red)")
        Predef.assert(r3 == 255 && g3 == 0 && b3 == 0, s"FB(3,0) expected red, got ($r3,$g3,$b3)")

        val (r4, g4, b4) = readPixel(0, 4)   // tile (1,0) pixel (0,0) → green
        println(s"[$cycle] FB(4,0): R=$r4 G=$g4 B=$b4 (expect green)")
        Predef.assert(r4 == 0 && g4 == 255 && b4 == 0, s"FB(4,0) expected green, got ($r4,$g4,$b4)")

        val (r7, g7, b7) = readPixel(0, 7)   // tile (1,0) pixel (3,0) → green
        println(s"[$cycle] FB(7,0): R=$r7 G=$g7 B=$b7 (expect green)")
        Predef.assert(r7 == 0 && g7 == 255 && b7 == 0, s"FB(7,0) expected green, got ($r7,$g7,$b7)")

        // ── Scanline y=4: pixels 0-3 from tile(0,1)=blue ──
        println(s"[$cycle] Prefetching row 4...")
        prefetchRow(4)

        val (r40, g40, b40) = readPixel(4, 0)  // tile (0,1) pixel (0,0) → blue
        println(s"[$cycle] FB(0,4): R=$r40 G=$g40 B=$b40 (expect blue)")
        Predef.assert(r40 == 0 && g40 == 0 && b40 == 255, s"FB(0,4) expected blue, got ($r40,$g40,$b40)")

        val (r43, g43, b43) = readPixel(4, 3)  // tile (0,1) pixel (3,0) → blue
        println(s"[$cycle] FB(3,4): R=$r43 G=$g43 B=$b43 (expect blue)")
        Predef.assert(r43 == 0 && g43 == 0 && b43 == 255, s"FB(3,4) expected blue, got ($r43,$g43,$b43)")

        println(s"[$cycle] ✓ Multi-tile cross-tile addressing test passed!")
      }
    }
  }
}
