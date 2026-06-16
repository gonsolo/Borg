// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// HdmiScanoutFp16 — reads Borg GPU tiled RGB565 framebuffer from SDRAM.
//
// The Borg flusher writes 4×4 tiles to SDRAM in tiled order:
//   tile_addr = fbBase + tile_index × 32
//   pixel_addr = tile_addr + pixel_index × 2
//   pixel layout: one RGB565 halfword per pixel (R[15:11] G[10:5] B[4:0])
//
// MemoryController GPU read returns 4 bytes (2 SDRAM halfwords); the scanout
// reads at pixel_addr and uses the low 16 bits (this pixel); the high 16 bits
// are the next pixel and are ignored (1 read per pixel keeps the addressing
// trivial — the fill loop is not bandwidth-critical).
//
// Buffering strategy (Step: full-frame BRAM):
//   The previous design prefetched one scanline per hblank, but at 25 MHz the
//   SDRAM read latency (~10 cy/word, ×2 for the MemoryController's 2-halfword
//   GPU read) means 32 pixels = 64 reads ≈ 1300–1900 cy — far over the ~450 cy
//   line budget.  Only ~1/3 of each line got fetched ⇒ garbled output.
//
//   Instead we hold the whole framebuffer (fbWidth×fbHeight px, RGB8) in a
//   block RAM and fill it with a free-running FSM that walks every pixel and
//   loops forever.  A full refill takes ~2 ms (≪ 16.7 ms frame), so for a
//   static framebuffer the BRAM converges to the correct image within ~2
//   frames.  Display reads the BRAM at pixel-clock rate (1-cycle latency), so
//   there is no realtime SDRAM bandwidth pressure on the scanout path.
//
// Display: fbWidth×fbHeight framebuffer at 1:1 pixel mapping, centered on 640×480.
// Note: at 128×128 the fill FSM takes ~33 ms per refill (> 1 frame) — expect ~1-frame lag.

package soc

import chisel3._
import chisel3.util._

class HdmiScanoutFp16IO extends Bundle {
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
  // Framebuffer base addresses, programmed by firmware over MMIO so the scanout
  // and the GPU flusher derive from the SAME source (borg_layout.h) and cannot
  // drift apart (the cause of the green-corner-pixel bug).  Power-on default is 0
  // (blank) until the firmware writes them at init.
  val fbBase   = Input(UInt(25.W))
  val fbBase1  = Input(UInt(25.W))
  val curBuf   = Output(Bool())  // buffer currently being read (latched at wrap)
  val red      = Output(UInt(8.W))
  val green    = Output(UInt(8.W))
  val blue     = Output(UInt(8.W))
  // Sim-only observability: the last RGB value the fill FSM wrote to frameBuf[0].
  // Left unconnected in synthesis (optimised away); used by the SDRAM co-sim.
  val dbgFill0 = Output(UInt(24.W))
}

// fbBase/fbBase1 are runtime inputs (io.fbBase/io.fbBase1), programmed by
// firmware — NOT constructor constants — so the scanout cannot drift from the
// GPU's framebuffer layout.
class HdmiScanoutFp16(fbWidth: Int = 32, fbHeight: Int = 32) extends Module {
  val io = IO(new HdmiScanoutFp16IO)

  val tilesPerRow  = fbWidth / 4
  val overlayScale = 1   // 1:1 — no scaling
  val overlayW     = fbWidth * overlayScale
  val overlayH     = fbHeight * overlayScale
  val startX = ((640 - overlayW) / 2).U(10.W)
  val startY = ((480 - overlayH) / 2).U(10.W)
  val endX   = startX +& overlayW.U
  val endY   = startY +& overlayH.U

  val numPixels = fbWidth * fbHeight

  // ── Frame buffer: numPixels × RGB8, mapped to block RAM ──
  // Raster-indexed: pixel (col, row) lives at index row*fbWidth + col.
  val frameBuf = SyncReadMem(numPixels, UInt(24.W))

  // ── RGB565 → RGB888 expansion ──
  // Replicate the high bits into the low bits so full-scale maps to 0xFF.
  def rgb565ToRgb8(px: UInt): (UInt, UInt, UInt) = {
    val r5 = px(15, 11)
    val g6 = px(10, 5)
    val b5 = px(4, 0)
    val r8 = Cat(r5, r5(4, 2))   // 5 -> 8 bits
    val g8 = Cat(g6, g6(5, 4))   // 6 -> 8 bits
    val b8 = Cat(b5, b5(4, 2))   // 5 -> 8 bits
    (r8, g8, b8)
  }

  // ── Free-running fill FSM ──
  // Walks every pixel (raster order), issuing 1 GPU read each, expanding the
  // RGB565 halfword to RGB8 and writing the frame BRAM.  Loops forever.
  val sReq :: sWait :: Nil = Enum(2)
  val fstate  = RegInit(sReq)
  val fillIdx = RegInit(0.U(log2Ceil(numPixels).W))

  // Decompose the raster fill index into tiled SDRAM byte address.
  val fillCol = fillIdx(log2Ceil(fbWidth) - 1, 0)
  val fillRow = fillIdx(log2Ceil(numPixels) - 1, log2Ceil(fbWidth))
  val tileCol = fillCol(log2Ceil(fbWidth) - 1, 2)
  val tileRow = fillRow(log2Ceil(fbHeight) - 1, 2)
  val localX  = fillCol(1, 0)
  val localY  = fillRow(1, 0)
  val tileIndex = tileRow * tilesPerRow.U +& tileCol
  val pixIndex  = Cat(localY, localX)            // local_y * 4 + local_x
  // Double-buffer: capture the front-buffer base address at the wrap point
  // (last pixel of each loop, inside sWait) so it is stable from the very
  // first cycle of the next loop through sReq/sWait.
  // Latching in sReq (first state of next loop) would set it one cycle too
  // late: gpuAddr is combinatorial from baseAddr, so it would change mid-request.
  // Front-buffer base for the whole fill loop.  After the first loop it is the
  // value latched at the previous wrap (stable across the loop, no mid-request
  // change).  Before the first wrap there is nothing latched yet, so the first
  // loop uses io.fbBase directly — the firmware programs it before rendering, so
  // it is stable (worst case a one-loop boot transient if it is reprogrammed
  // mid-loop).  This keeps the base valid from the very first read.
  val baseAddr   = RegInit(0.U(25.W))
  val baseLoaded = RegInit(false.B)
  val effBase    = Mux(baseLoaded, baseAddr, io.fbBase)
  val pixAddr    = effBase +& (tileIndex << 5) +& (pixIndex << 1)
  // Report which buffer is currently being read so the CPU can synchronize the
  // double-buffer swap (wait until the scanout has released the back buffer).
  io.curBuf := effBase === io.fbBase1

  io.gpuReq  := io.enable && (fstate === sReq || fstate === sWait)
  io.gpuAddr := pixAddr

  val wrEn   = WireDefault(false.B)
  val wrData = WireDefault(0.U(24.W))
  when(wrEn) { frameBuf.write(fillIdx, wrData) }

  // sim observability: snapshot the value the fill wrote to frameBuf index 0.
  val dbgFill0Reg = RegInit(0.U(24.W))
  when(wrEn && fillIdx === 0.U) { dbgFill0Reg := wrData }
  io.dbgFill0 := dbgFill0Reg

  when(io.enable) {
    switch(fstate) {
      is(sReq) { fstate := sWait }
      is(sWait) {
        when(io.gpuReady) {
          // gpuData low 16 bits = this pixel's RGB565 halfword.
          val (r8, g8, b8) = rgb565ToRgb8(io.gpuData(15, 0))
          wrEn    := true.B
          wrData  := Cat(r8, g8, b8)
          val wrap = fillIdx === (numPixels - 1).U
          fillIdx := Mux(wrap, 0.U, fillIdx + 1.U)
          fstate  := sReq
          // Latch the new front-buffer base at the wrap boundary so it is
          // stable for all of the next loop (sReq through sWait).
          when(wrap) {
            baseAddr   := Mux(io.frontBuf, io.fbBase1, io.fbBase)
            baseLoaded := true.B
          }
        }
      }
    }
  }

  // ── Display: read frame BRAM (1-cycle latency), magnified 2× ──
  // The BRAM read returns data one cycle after the address is presented, so
  // the gating signal is registered to match — this delays the whole overlay
  // by a single pixel, which is imperceptible.
  val inFbH = io.hCount >= startX && io.hCount < endX
  val inFbV = io.vCount >= startY && io.vCount < endY
  val show  = io.de && inFbH && inFbV

  val fbX = ((io.hCount - startX) / overlayScale.U)(log2Ceil(fbWidth) - 1, 0)
  val fbY = ((io.vCount - startY) / overlayScale.U)(log2Ceil(fbHeight) - 1, 0)
  val dispIdx = Cat(fbY, fbX)   // row*fbWidth + col

  // Defensive guard against an ECP5 BRAM read-during-write collision: when the
  // fill FSM writes the same index the display port is reading in the same cycle,
  // the BRAM read output is implementation-defined.  Forward the write data (the
  // correct new value for that pixel) instead.  NOTE: this was NOT the cause of
  // the historical green corner pixel — that was a stale scanout fbBase (fixed by
  // programming fbBase/fbBase1 from firmware) — but the guard is cheap and correct
  // insurance against a genuine same-address read/write hazard on coloured pixels.
  val pixel     = frameBuf.read(dispIdx)
  val collision = wrEn && (fillIdx === dispIdx)
  val collisionD = RegNext(collision, false.B)
  val wrDataD    = RegNext(wrData)
  val pixelSafe  = Mux(collisionD, wrDataD, pixel)

  val showD = RegNext(show, false.B)

  io.red   := Mux(showD, pixelSafe(23, 16), 0.U)
  io.green := Mux(showD, pixelSafe(15, 8),  0.U)
  io.blue  := Mux(showD, pixelSafe(7, 0),   0.U)
}
