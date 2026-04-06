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
  * Storage split:
  *   - Z buffer: 16 × 16-bit registers (~256 FFs).
  *     Registers allow same-cycle read for Z comparison (Step 11.5).
  *   - RGB buffer: 16 × 48-bit SyncReadMem (1 iCE40 EBR block).
  *     BRAM is fine for RGB since it's only read during flush (1-cycle latency OK).
  *
  * Tile index: tile_idx = iter_x[1:0] | (iter_y[1:0] << 2)
  *
  * Step 11 of the Borg GPU roadmap.
  */

class BorgTileBufferIO(val dataBits: Int = 16) extends Bundle {
  // Write port (from rasterizer auto-write or MMIO)
  val writeIdx  = Input(UInt(4.W))       // 0–15 tile pixel index
  val writeR    = Input(UInt(dataBits.W))
  val writeG    = Input(UInt(dataBits.W))
  val writeB    = Input(UInt(dataBits.W))
  val writeZ    = Input(UInt(dataBits.W))
  val writeEn   = Input(Bool())

  // Read port (for MMIO flush — 1-cycle latency for RGB, combinational for Z)
  val readIdx   = Input(UInt(4.W))
  val readEn    = Input(Bool())
  val readR     = Output(UInt(dataBits.W))
  val readG     = Output(UInt(dataBits.W))
  val readB     = Output(UInt(dataBits.W))
  val readZ     = Output(UInt(dataBits.W))

  // Z peek (combinational — for hardware Z comparison in Step 11.5)
  val peekZIdx  = Input(UInt(4.W))
  val peekZ     = Output(UInt(dataBits.W))

  // Clear (resets all Z to FP16_MAX_DEPTH, RGB to 0)
  val clearEn   = Input(Bool())
  val clearBusy = Output(Bool())         // high while clearing RGB BRAM sequentially
}

class BorgTileBuffer(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileBufferIO(dataBits))

  val FP16_MAX_DEPTH_VAL = 0x7BFF  // Scala constant
  val FP16_MAX_DEPTH = FP16_MAX_DEPTH_VAL.U(dataBits.W)
  val TILE_SIZE = 16  // 4×4

  // --- Z buffer: register-based (same-cycle read for comparison) ---
  val zBuf = RegInit(VecInit(Seq.fill(TILE_SIZE)(FP16_MAX_DEPTH_VAL.U(dataBits.W))))

  // --- RGB buffer: BRAM-based (1-cycle read latency, OK for flush) ---
  // Pack RGB into a single 48-bit word for efficient BRAM usage
  val rgbMem = SyncReadMem(TILE_SIZE, UInt((dataBits * 3).W))

  // --- Clear state machine ---
  // Z registers can be cleared in 1 cycle (parallel reset).
  // RGB BRAM needs sequential writes (1 entry per cycle).
  val clearCounter = RegInit(TILE_SIZE.U(5.W))  // counts 0..15 during clear
  val clearing = clearCounter < TILE_SIZE.U

  io.clearBusy := clearing

  // --- Clear logic ---
  when(io.clearEn && !clearing) {
    // Start clear: reset all Z registers immediately
    for (i <- 0 until TILE_SIZE) {
      zBuf(i) := FP16_MAX_DEPTH
    }
    // Start sequential RGB clear
    clearCounter := 0.U
  }

  when(clearing) {
    rgbMem.write(clearCounter, 0.U)
    clearCounter := clearCounter + 1.U
  }

  // --- Write logic ---
  when(io.writeEn && !clearing) {
    zBuf(io.writeIdx) := io.writeZ
    val rgbPacked = Cat(io.writeR, io.writeG, io.writeB)
    rgbMem.write(io.writeIdx, rgbPacked)
  }

  // --- Read logic ---
  // RGB: 1-cycle latency from BRAM
  val rgbRead = rgbMem.read(io.readIdx, io.readEn && !clearing)
  io.readR := rgbRead(dataBits * 3 - 1, dataBits * 2)
  io.readG := rgbRead(dataBits * 2 - 1, dataBits)
  io.readB := rgbRead(dataBits - 1, 0)

  // Z: combinational from registers
  io.readZ := zBuf(io.readIdx)

  // --- Z peek: combinational (for future hardware Z comparison) ---
  io.peekZ := zBuf(io.peekZIdx)
}
