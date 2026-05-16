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
// Prefetch: one 32-pixel scanline during hblank.
//   32 pixels × 2 GPU reads = 64 reads × ~10cy = ~640cy < 800cy hblank budget.
//
// Display: 32×32 framebuffer magnified 2× to 64×64 overlay centered on 640×480.

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
  val red      = Output(UInt(8.W))
  val green    = Output(UInt(8.W))
  val blue     = Output(UInt(8.W))
}

class HdmiScanoutFp16(fbBase: Int = 0x100000, fbWidth: Int = 32, fbHeight: Int = 32) extends Module {
  val io = IO(new HdmiScanoutFp16IO)

  val tilesPerRow = fbWidth / 4
  val overlayScale = 2   // each pixel displayed 2×2
  val overlayW = fbWidth * overlayScale
  val overlayH = fbHeight * overlayScale
  val startX = ((640 - overlayW) / 2).U(10.W)
  val startY = ((480 - overlayH) / 2).U(10.W)
  val endX   = startX +& overlayW.U
  val endY   = startY +& overlayH.U

  val inFbH = io.hCount >= startX && io.hCount < endX
  val inFbV = io.vCount >= startY && io.vCount < endY

  // ── Line buffer: fbWidth × 24-bit (RGB8) ──
  val lineBuffer = RegInit(VecInit(Seq.fill(fbWidth)(0.U(24.W))))

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

  // ── Prefetch FSM ──
  // Fetches one scanline of fbWidth pixels during hblank.
  // For each pixel: 2 GPU reads (RG word, then BZ word).
  val sIdle :: sReqRG :: sWaitRG :: sReqBZ :: sWaitBZ :: sStore :: Nil = Enum(6)
  val state   = RegInit(sIdle)
  val pixIdx  = RegInit(0.U(log2Ceil(fbWidth).W))
  val rgWord  = Reg(UInt(32.W))

  // Compute pixel address from (pixIdx, fetchRow)
  // pixel(x, y) in tiled FB:
  //   tile_col   = x >> 2
  //   tile_row   = y >> 2
  //   local_x    = x & 3
  //   local_y    = y & 3
  //   tile_index = tile_row * tilesPerRow + tile_col
  //   pix_index  = local_y * 4 + local_x
  //   byte_addr  = fbBase + tile_index * 128 + pix_index * 8
  val fetchRow = Reg(UInt(log2Ceil(fbHeight).W))

  val tileCol   = pixIdx(log2Ceil(fbWidth) - 1, 2)
  val tileRow   = fetchRow(log2Ceil(fbHeight) - 1, 2)
  val localX    = pixIdx(1, 0)
  val localY    = fetchRow(1, 0)
  val tileIndex = tileRow * tilesPerRow.U +& tileCol
  val pixIndex  = Cat(localY, localX)  // local_y * 4 + local_x
  val pixAddr   = fbBase.U(25.W) +& (tileIndex << 7) +& (pixIndex << 3)

  // Determine which framebuffer row to prefetch next
  val nextOverlayV = Mux(io.vCount >= endY - 1.U, startY, io.vCount + 1.U)
  val nextFbRow    = (nextOverlayV - startY) >> log2Ceil(overlayScale).U
  val fetchNextV   = nextOverlayV >= startY && nextOverlayV < endY
  val triggerFetch = io.tick25 && io.hCount === 640.U && fetchNextV

  io.gpuReq  := state === sReqRG || state === sWaitRG ||
                state === sReqBZ || state === sWaitBZ
  io.gpuAddr := Mux(state === sReqBZ || state === sWaitBZ,
                    pixAddr + 4.U,
                    pixAddr)

  switch(state) {
    is(sIdle) {
      when(triggerFetch && io.enable) {
        state    := sReqRG
        pixIdx   := 0.U
        fetchRow := nextFbRow(log2Ceil(fbHeight) - 1, 0)
      }
    }
    is(sReqRG) {
      state := sWaitRG
    }
    is(sWaitRG) {
      when(io.gpuReady) {
        rgWord := io.gpuData   // {G[15:0], R[15:0]}
        state  := sReqBZ
      }
    }
    is(sReqBZ) {
      state := sWaitBZ
    }
    is(sWaitBZ) {
      when(io.gpuReady) {
        // gpuData = {Z[15:0], B[15:0]}
        val rFp16 = rgWord(15, 0)
        val gFp16 = rgWord(31, 16)
        val bFp16 = io.gpuData(15, 0)

        val r8 = fp16ToRgb8(rFp16)
        val g8 = fp16ToRgb8(gFp16)
        val b8 = fp16ToRgb8(bFp16)

        lineBuffer(pixIdx) := Cat(r8, g8, b8)

        when(pixIdx === (fbWidth - 1).U) {
          state := sIdle
        }.otherwise {
          pixIdx := pixIdx + 1.U
          state  := sReqRG
        }
      }
    }
  }

  // ── Display: read from line buffer, magnified 2× ──
  val fbX    = ((io.hCount - startX) >> log2Ceil(overlayScale).U)(log2Ceil(fbWidth) - 1, 0)
  val pixel  = lineBuffer(fbX)
  val dispR  = pixel(23, 16)
  val dispG  = pixel(15, 8)
  val dispB  = pixel(7, 0)

  io.red   := Mux(io.de && inFbH && inFbV, dispR, 0.U)
  io.green := Mux(io.de && inFbH && inFbV, dispG, 0.U)
  io.blue  := Mux(io.de && inFbH && inFbV, dispB, 0.U)
}
