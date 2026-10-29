// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// HdmiScanoutFp16: scans the Borg tiled RGB565 framebuffer out of SDRAM.
// Tiles are 4x4: pixel_addr = fbBase + tile_index*32 + pixel_index*2. A free-running
// FSM copies the whole frame into block RAM, one GPU read per pixel (low 16 bits),
// since per-line prefetch cannot meet the line budget at 25 MHz SDRAM latency.
// The display reads the BRAM 1:1, centered on 640x480.

package soc

import chisel3._
import chisel3.util._

class HdmiScanoutFp16IO(separatePixelClock: Boolean) extends Bundle {
  // Display-side clock and reset when separatePixelClock.
  val pixClk   = Option.when(separatePixelClock)(Input(Clock()))
  val pixRst   = Option.when(separatePixelClock)(Input(Bool()))
  val gpuReq   = Output(Bool())
  val gpuAddr  = Output(UInt(25.W))
  val gpuData  = Input(UInt(32.W))
  val gpuReady = Input(Bool())
  val hCount   = Input(UInt(10.W))
  val vCount   = Input(UInt(10.W))
  val de       = Input(Bool())
  val tick25   = Input(Bool())
  val enable   = Input(Bool())
  val frontBuf = Input(Bool())   // 0 = read fbBase, 1 = read fbBase1
  // Programmed by firmware from the same layout as the flusher; 0 (blank) until then.
  val fbBase   = Input(UInt(25.W))
  val fbBase1  = Input(UInt(25.W))
  val curBuf   = Output(Bool())  // buffer currently being read (latched at wrap)
  val red      = Output(UInt(8.W))
  val green    = Output(UInt(8.W))
  val blue     = Output(UInt(8.W))
  // Sim-only: the last RGB value written to frameBuf[0].
  val dbgFill0 = Output(UInt(24.W))
}

// separatePixelClock: the display side runs on io.pixClk; the module clock only drives
// the fill FSM. rgb565Store keeps 16 bits per pixel in BRAM instead of 24.
// doubleBuffer=false: one buffer at io.fbBase, no swap logic.
class HdmiScanoutFp16(fbWidth: Int = 32, fbHeight: Int = 32, separatePixelClock: Boolean = false,
                      rgb565Store: Boolean = false, doubleBuffer: Boolean = true)
    extends Module {
  val io = IO(new HdmiScanoutFp16IO(separatePixelClock))

  val tilesPerRow  = fbWidth / 4
  val overlayScale = 1   // 1:1 — no scaling
  val overlayW     = fbWidth * overlayScale
  val overlayH     = fbHeight * overlayScale
  val startX = ((640 - overlayW) / 2).U(10.W)
  val startY = ((480 - overlayH) / 2).U(10.W)
  val endX   = startX +& overlayW.U
  val endY   = startY +& overlayH.U

  val numPixels = fbWidth * fbHeight

  // Raster-indexed: pixel (col, row) at row*fbWidth + col.
  val pxBits   = if (rgb565Store) 16 else 24
  val frameBuf = SyncReadMem(numPixels, UInt(pxBits.W))

  // Replicate the high bits into the low bits so full scale maps to 0xFF.
  def rgb565ToRgb8(px: UInt): (UInt, UInt, UInt) = {
    val r5 = px(15, 11)
    val g6 = px(10, 5)
    val b5 = px(4, 0)
    val r8 = Cat(r5, r5(4, 2))   // 5 -> 8 bits
    val g8 = Cat(g6, g6(5, 4))   // 6 -> 8 bits
    val b8 = Cat(b5, b5(4, 2))   // 5 -> 8 bits
    (r8, g8, b8)
  }

  val sReq :: sWait :: Nil = Enum(2)
  val fstate  = RegInit(sReq)
  val fillIdx = RegInit(0.U(log2Ceil(numPixels).W))

  val fillCol = fillIdx(log2Ceil(fbWidth) - 1, 0)
  val fillRow = fillIdx(log2Ceil(numPixels) - 1, log2Ceil(fbWidth))
  val tileCol = fillCol(log2Ceil(fbWidth) - 1, 2)
  val tileRow = fillRow(log2Ceil(fbHeight) - 1, 2)
  val localX  = fillCol(1, 0)
  val localY  = fillRow(1, 0)
  val tileIndex = tileRow * tilesPerRow.U +& tileCol
  val pixIndex  = Cat(localY, localX)            // local_y * 4 + local_x
  // gpuAddr is combinational from the base, so it is latched at the wrap (in sWait), not
  // in sReq. The first loop, before any wrap, uses io.fbBase directly.
  val baseAddr   = if (doubleBuffer) RegInit(0.U(25.W)) else io.fbBase
  val baseLoaded = if (doubleBuffer) RegInit(false.B) else true.B
  val effBase    = if (doubleBuffer) Mux(baseLoaded, baseAddr, io.fbBase) else io.fbBase
  val pixAddr    = effBase +& (tileIndex << 5) +& (pixIndex << 1)
  // Lets the CPU wait until the scanout has released the back buffer.
  io.curBuf := (if (doubleBuffer) effBase === io.fbBase1 else false.B)

  io.gpuReq  := io.enable && (fstate === sReq || fstate === sWait)
  io.gpuAddr := pixAddr

  val wrEn   = WireDefault(false.B)
  val wrData = WireDefault(0.U(pxBits.W))

  // Yosys never infers DP16KD for a memory with two port clocks (it falls back to LUT RAM),
  // so frameBuf stays on the read clock and the slow fill side crosses with a toggle
  // synchronizer; the latched index and data stay stable far longer than it takes.
  if (separatePixelClock) {
    val wrIdxLatch  = RegEnable(fillIdx, wrEn)
    val wrDataLatch = RegEnable(wrData, wrEn)
    val wrToggle    = RegInit(false.B)
    when(wrEn) { wrToggle := !wrToggle }

    withClockAndReset(io.pixClk.get, io.pixRst.get) {
      val sync0 = RegNext(wrToggle, false.B)
      val sync1 = RegNext(sync0, false.B)
      val sync2 = RegNext(sync1, false.B)
      when(sync1 =/= sync2) { frameBuf.write(wrIdxLatch, wrDataLatch) }
    }
  } else {
    when(wrEn) { frameBuf.write(fillIdx, wrData) }
  }

  val dbgFill0Reg = RegInit(0.U(24.W))
  when(wrEn && fillIdx === 0.U) {
    dbgFill0Reg := (if (rgb565Store) { val (r, g, b) = rgb565ToRgb8(wrData); Cat(r, g, b) } else wrData)
  }
  io.dbgFill0 := dbgFill0Reg

  when(io.enable) {
    switch(fstate) {
      is(sReq) { fstate := sWait }
      is(sWait) {
        when(io.gpuReady) {
          val (r8, g8, b8) = rgb565ToRgb8(io.gpuData(15, 0))
          wrEn    := true.B
          wrData  := (if (rgb565Store) io.gpuData(15, 0) else Cat(r8, g8, b8))
          val wrap = fillIdx === (numPixels - 1).U
          fillIdx := Mux(wrap, 0.U, fillIdx + 1.U)
          fstate  := sReq
          if (doubleBuffer) when(wrap) {
            baseAddr   := Mux(io.frontBuf, io.fbBase1, io.fbBase)
            baseLoaded := true.B
          }
        }
      }
    }
  }

  // The BRAM read takes one cycle, so the gating is registered to match.
  private def display(): Unit = {
    val inFbH = io.hCount >= startX && io.hCount < endX
    val inFbV = io.vCount >= startY && io.vCount < endY
    val show  = io.de && inFbH && inFbV

    val fbX = ((io.hCount - startX) / overlayScale.U)(log2Ceil(fbWidth) - 1, 0)
    val fbY = ((io.vCount - startY) / overlayScale.U)(log2Ceil(fbHeight) - 1, 0)
    val dispIdx = Cat(fbY, fbX)   // row*fbWidth + col

    // frameBuf must stay single-clocked for BRAM inference, see the write-side CDC.
    val pixel = frameBuf.read(dispIdx)
    // Forward the write data on a same-cycle read/write collision, whose BRAM output is
    // undefined. With a separate pixel clock there is no same cycle, at worst one stale pixel.
    val pixelSafe = if (separatePixelClock) pixel else {
      val collisionD = RegNext(wrEn && (fillIdx === dispIdx), false.B)
      Mux(collisionD, RegNext(wrData), pixel)
    }

    val showD = RegNext(show, false.B)
    val (pxR, pxG, pxB) =
      if (rgb565Store) rgb565ToRgb8(pixelSafe) else (pixelSafe(23, 16), pixelSafe(15, 8), pixelSafe(7, 0))

    if (separatePixelClock) {
      // The DP16KD output plus its width-stitching mux cannot reach the TMDS encoder in one
      // 8 ns hop at 125 MHz. dispIdx is stable for 5 cycles, so pipeline three stages, each
      // on a different enable so Yosys cannot absorb them into the BRAM output register.
      val showD2 = RegNext(showD, false.B)
      val showD3 = RegNext(showD2, false.B)
      val showD4 = RegNext(showD3, false.B)
      val redD1   = RegEnable(pxR, 0.U(8.W), show)
      val greenD1 = RegEnable(pxG, 0.U(8.W), show)
      val blueD1  = RegEnable(pxB, 0.U(8.W), show)
      val redD2   = RegEnable(redD1,   0.U(8.W), showD)
      val greenD2 = RegEnable(greenD1, 0.U(8.W), showD)
      val blueD2  = RegEnable(blueD1,  0.U(8.W), showD)
      val redD3   = RegEnable(redD2,   0.U(8.W), showD2)
      val greenD3 = RegEnable(greenD2, 0.U(8.W), showD2)
      val blueD3  = RegEnable(blueD2,  0.U(8.W), showD2)
      io.red   := Mux(showD4, redD3, 0.U)
      io.green := Mux(showD4, greenD3, 0.U)
      io.blue  := Mux(showD4, blueD3, 0.U)
    } else {
      io.red   := Mux(showD, pxR, 0.U)
      io.green := Mux(showD, pxG, 0.U)
      io.blue  := Mux(showD, pxB, 0.U)
    }
  }
  if (separatePixelClock) withClockAndReset(io.pixClk.get, io.pixRst.get) { display() }
  else display()
}
