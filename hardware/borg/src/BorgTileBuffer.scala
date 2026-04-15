// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

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
  val writeIdx  = Input(UInt(4.W))       // 0-15 tile pixel index
  val writeData = Input(new ColorZ(dataBits))
  val writeEn   = Input(Bool())

  // Read port (for MMIO flush - 2-cycle latency: BRAM + hold reg)
  val readIdx   = Input(UInt(4.W))
  val readEn    = Input(Bool())
  val readData  = Output(new ColorZ(dataBits))

  // Clear (resets all entries: Z to FP16_MAX_DEPTH, RGB to 0)
  val clearEn   = Input(Bool())
  val clearBusy = Output(Bool())         // high while clearing BRAM sequentially
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
  // Init to 0 → auto-clear on reset (BRAM has no initial values unlike RegInit).
  val clearCounter = RegInit(0.U(5.W))
  val clearing = clearCounter < TILE_SIZE.U

  io.clearBusy := clearing

  // Clear value: Z=FP16_MAX_DEPTH, RGB=0
  val clearColor = Wire(new ColorZ(dataBits))
  clearColor.r := 0.U
  clearColor.g := 0.U
  clearColor.b := 0.U
  clearColor.z := FP16_MAX_DEPTH
  val clearWord = clearColor.asUInt

  // --- Clear logic ---
  when(io.clearEn && !clearing) {
    clearCounter := 0.U
  }

  when(clearing) {
    rgbzMem.write(clearCounter, clearWord)
    clearCounter := clearCounter + 1.U
  }

  // --- Write logic ---
  when(io.writeEn && !clearing) {
    rgbzMem.write(io.writeIdx, io.writeData.asUInt)
  }

  // --- Read port ---
  val effectiveReadEn = io.readEn && !clearing
  val rgbzRead = rgbzMem.read(io.readIdx, effectiveReadEn)

  val readDataHeld = RegInit(0.U.asTypeOf(new ColorZ(dataBits)))

  // Capture BRAM output one cycle after readEn pulse
  val readEnDel = RegNext(effectiveReadEn, false.B)
  when(readEnDel) {
    readDataHeld := rgbzRead.asTypeOf(new ColorZ(dataBits))
  }

  io.readData := readDataHeld
}
