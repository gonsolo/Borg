// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

/** BorgTileBuffer — 4×4 on-chip tile buffer for RGB + Z.
  *
  * Stores fragment results on-chip during rasterization of a 4×4 tile.
  * After all pixels in the tile are processed, the CPU flushes the buffer
  * to PSRAM in a batch, eliminating per-pixel PSRAM round-trips.
  *
  * Storage: All 4 channels packed into a single 64-bit SyncReadMem (1 EBR).
  * This avoids the ~256 FF cost of register-based Z storage.
  * Z comparison for Step 11.5 will use a 1-cycle BRAM read in the FSM.
  *
  * Clear writes FP16_MAX_DEPTH for Z and 0 for RGB sequentially (16 cycles).
  *
  * Tile index: tile_idx = iter_x[1:0] | (iter_y[1:0] << 2)
  *
  * Step 11 of the Borg GPU roadmap.
  */

class BorgTileBufferIO(val dataBits: Int = 16) extends Bundle {
  // Write port (from rasterizer auto-write or MMIO)
  val write = Flipped(new TileWriteIO)

  // Read port (for tile flush - 2-cycle latency: BRAM + hold reg)
  val read  = Flipped(new TileReadIO(dataBits))

  // Clear (resets all entries: Z to FP16_MAX_DEPTH, RGB to 0)
  val clear = Flipped(new TileClearIO)
}

class BorgTileBuffer(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileBufferIO(dataBits))

  val FP16_MAX_DEPTH_VAL = 0x7BFF  // Scala constant
  val FP16_MAX_DEPTH = FP16_MAX_DEPTH_VAL.U(dataBits.W)
  val TILE_SIZE = 16  // 4×4
  val PACKED_BITS = new ColorZ(dataBits).getWidth  // 64 bits

  // --- RGBZ buffer: single BRAM (16 × 64-bit = 1024 bits, fits in 1 iCE40 EBR) ---
  val rgbzMem = SyncReadMem(TILE_SIZE, UInt(PACKED_BITS.W))

  // --- Clear state machine ---
  // BRAM needs sequential writes (1 entry per cycle).
  // clearCounter starts at 0 → clearing is true for the first 16 cycles after
  // reset, so the BRAM (which has no RegInit semantics) is auto-cleared.
  val clearCounter = RegInit(0.U(5.W))
  val clearing = clearCounter < TILE_SIZE.U

  io.clear.busy := clearing

  // Clear value: LATCH the color at clear-start.  The sequencer drives
  // io.clear.color through a mux gated on io.iter.clear, which is only a
  // 1-cycle pulse — but the BRAM clear writes span the following 16 cycles.
  // Sampling io.clear.color combinationally during those writes would read the
  // mux's fall-through default (black), painting the whole background black.
  // Latching at clear-start keeps the color stable across the entire sequence.
  //
  // RegInit defaults the latch to RGB=0, Z=FP16_MAX_DEPTH so the *reset*
  // auto-clear establishes the far-plane depth convention (Z=0x7BFF).  A reset
  // value of Z=0 would be the near plane and reject every fragment until the
  // first explicit clear.
  val clearInit = (new ColorZ(dataBits)).Lit(
    _.r -> 0.U, _.g -> 0.U, _.b -> 0.U, _.z -> FP16_MAX_DEPTH
  )
  val clearWordReg = RegInit(clearInit.asUInt)
  val clearWord = clearWordReg

  // --- Clear logic ---
  when(io.clear.en && !clearing) {
    clearCounter := 0.U
    clearWordReg := io.clear.color.asUInt
    if (BorgDebug.trace) printf("[TBUF] CLEAR-START R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      io.clear.color.r, io.clear.color.g, io.clear.color.b, io.clear.color.z)
  }

  when(clearing) {
    rgbzMem.write(clearCounter, clearWord)
    when(clearCounter === 0.U || clearCounter === 15.U) {
      if (BorgDebug.trace) printf("[TBUF] CLEAR slot=%d raw=0x%x\n", clearCounter, clearWord)
    }
    clearCounter := clearCounter + 1.U
  }

  // --- Write logic ---
  when(io.write.en && !clearing) {
    rgbzMem.write(io.write.idx, io.write.data.asUInt)
    if (BorgDebug.trace) printf("[TBUF] WRITE slot=%d R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      io.write.idx, io.write.data.r, io.write.data.g, io.write.data.b, io.write.data.z)
  }

  // --- Read port ---
  val effectiveReadEn = io.read.en && !clearing
  val rgbzRead = rgbzMem.read(io.read.idx, effectiveReadEn)

  when(effectiveReadEn) {
    if (BorgDebug.trace) printf("[TBUF] READ-REQ slot=%d\n", io.read.idx)
  }

  val readDataHeld = RegInit(0.U.asTypeOf(new ColorZ(dataBits)))

  // Capture BRAM output one cycle after readEn pulse
  val readEnDel = RegNext(effectiveReadEn, false.B)
  when(readEnDel) {
    readDataHeld := rgbzRead.asTypeOf(new ColorZ(dataBits))
    val parsed = rgbzRead.asTypeOf(new ColorZ(dataBits))
    if (BorgDebug.trace) printf("[TBUF] READ-DATA R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      parsed.r, parsed.g, parsed.b, parsed.z)
  }

  io.read.data := readDataHeld
}
