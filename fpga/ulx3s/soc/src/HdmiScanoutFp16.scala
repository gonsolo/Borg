// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// HdmiScanoutFp16 — reads Borg GPU tiled FP16 framebuffer from SDRAM.
//
// The Borg flusher writes 4×4 tiles to SDRAM in tiled order:
//   tile_addr = fbBase + tile_index × 128
//   pixel_addr = tile_addr + pixel_index × 8
//   pixel layout: R(+0) G(+2) B(+4) Z(+6), each FP16 (16 bits)
//
// MemoryController GPU read returns 4 bytes (2 SDRAM words):
//   Read at pixel_addr+0: {G[15:0], R[15:0]}
//   Read at pixel_addr+4: {Z[15:0], B[15:0]}
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
  val curBuf   = Output(Bool())  // buffer currently being read (latched at wrap)
  val red      = Output(UInt(8.W))
  val green    = Output(UInt(8.W))
  val blue     = Output(UInt(8.W))
}

class HdmiScanoutFp16(fbBase: Int = 0x100000, fbBase1: Int = 0x120004, fbWidth: Int = 32, fbHeight: Int = 32) extends Module {
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

  // ── FP16 → RGB8 conversion ──
  // FP16: 1 sign + 5 exponent (bias=15) + 10 mantissa
  // For color [0..1]: clamp negatives to 0, ≥1.0 to 255.
  def fp16ToRgb8(fp16: UInt): UInt = {
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val full = Cat(1.U(1.W), mant)  // 11-bit: 1.mantissa

    // value × 256 ≈ full >> (17 - exp)
    // For exp in [7..14]: result fits in 8 bits
    // For exp >= 15: clamp to 255
    // For exp < 7 or sign=1: 0
    val rgb8 = Wire(UInt(8.W))
    when(sign || exp < 7.U) {
      rgb8 := 0.U
    }.elsewhen(exp >= 15.U) {
      rgb8 := 255.U
    }.otherwise {
      rgb8 := MuxLookup(exp, 0.U)(Seq(
        14.U -> full(10, 3),
        13.U -> Cat(0.U(1.W), full(10, 4)),
        12.U -> Cat(0.U(2.W), full(10, 5)),
        11.U -> Cat(0.U(3.W), full(10, 6)),
        10.U -> Cat(0.U(4.W), full(10, 7)),
         9.U -> Cat(0.U(5.W), full(10, 8)),
         8.U -> Cat(0.U(6.W), full(10, 9)),
         7.U -> Cat(0.U(7.W), full(10))
      ))
    }
    rgb8
  }

  // ── Free-running fill FSM ──
  // Walks every pixel (raster order), issuing 2 GPU reads each (RG then BZ),
  // converting to RGB8 and writing the frame BRAM.  Loops forever.
  val sReqRG :: sWaitRG :: sReqBZ :: sWaitBZ :: Nil = Enum(4)
  val fstate  = RegInit(sReqRG)
  val fillIdx = RegInit(0.U(log2Ceil(numPixels).W))
  val rgWord  = Reg(UInt(32.W))

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
  // (last pixel of each loop, inside sWaitBZ) so it is stable from the very
  // first cycle of the next loop through sReqRG/sWaitRG/sReqBZ/sWaitBZ.
  // Latching in sReqRG (first state of next loop) would set it one cycle too
  // late: gpuAddr is combinatorial from baseAddr, so it would change mid-request.
  val baseAddr = RegInit(fbBase.U(25.W))
  val pixAddr  = baseAddr +& (tileIndex << 7) +& (pixIndex << 3)
  // Report which buffer is currently being read so the CPU can synchronize the
  // double-buffer swap (wait until the scanout has released the back buffer).
  io.curBuf := baseAddr === fbBase1.U(25.W)

  io.gpuReq  := io.enable && (fstate === sReqRG || fstate === sWaitRG ||
                              fstate === sReqBZ || fstate === sWaitBZ)
  io.gpuAddr := Mux(fstate === sReqBZ || fstate === sWaitBZ, pixAddr + 4.U, pixAddr)

  val wrEn   = WireDefault(false.B)
  val wrData = WireDefault(0.U(24.W))
  when(wrEn) { frameBuf.write(fillIdx, wrData) }

  when(io.enable) {
    switch(fstate) {
      is(sReqRG) { fstate := sWaitRG }
      is(sWaitRG) {
        when(io.gpuReady) {
          rgWord := io.gpuData    // {G[15:0], R[15:0]}
          fstate := sReqBZ
        }
      }
      is(sReqBZ) { fstate := sWaitBZ }
      is(sWaitBZ) {
        when(io.gpuReady) {
          // gpuData = {Z[15:0], B[15:0]}
          val r8 = fp16ToRgb8(rgWord(15, 0))
          val g8 = fp16ToRgb8(rgWord(31, 16))
          val b8 = fp16ToRgb8(io.gpuData(15, 0))
          wrEn    := true.B
          wrData  := Cat(r8, g8, b8)
          val wrap = fillIdx === (numPixels - 1).U
          fillIdx := Mux(wrap, 0.U, fillIdx + 1.U)
          fstate  := sReqRG
          // Latch the new front-buffer base at the wrap boundary so it is
          // stable for all of the next loop (sReqRG through sWaitBZ).
          when(wrap) {
            baseAddr := Mux(io.frontBuf, fbBase1.U(25.W), fbBase.U(25.W))
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

  // Guard against ECP5 BRAM read-during-write collisions: when the fill FSM
  // writes the same index the display is reading, the BRAM output is undefined.
  val pixel     = frameBuf.read(dispIdx)
  val collision = wrEn && (fillIdx === dispIdx)

  // Forward the WRITE data on a collision.  When the fill FSM writes the index
  // being read, the BRAM read is garbage, but wrData IS the correct new value
  // for that pixel — so display it directly.  This fixes the blinking (0,0)
  // pixel: the fill FSM rewrites index 0 every loop and intermittently collides
  // with the display's read of (0,0).  The framebuffer in SDRAM is clean
  // (verified by CPU read-back), so the speck was pure read-side corruption; in
  // sim the zeroed BRAM read equals the (zero) write data, so it never showed.
  // Write-forwarding is correct for every pixel — it replaces the old prevPixel
  // "hold the previous pixel" hack, which used a stale blanking read at (0,0).
  val collisionD = RegNext(collision, false.B)
  val wrDataD    = RegNext(wrData)
  val pixelSafe  = Mux(collisionD, wrDataD, pixel)

  val showD = RegNext(show, false.B)

  io.red   := Mux(showD, pixelSafe(23, 16), 0.U)
  io.green := Mux(showD, pixelSafe(15, 8),  0.U)
  io.blue  := Mux(showD, pixelSafe(7, 0),   0.U)
}
