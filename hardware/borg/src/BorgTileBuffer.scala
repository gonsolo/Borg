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
  * Storage: RGBZ packed into a single 64-bit SyncReadMem (1 EBR).
  *
  * Hardware Z-test (Step 12): A 16×16-bit register shadow tracks Z values.
  * When zTestEn is asserted with writeEn, the comparator checks the shadow
  * (combinational, zero latency). Pixel is written only if new Z < shadow Z.
  * This avoids BRAM read port muxing — saves ~900 LUTs vs BRAM-based Z-test.
  * Cost: 256 FFs for the Z shadow (16 entries × 16 bits).
  *
  * Clear writes FP16_MAX_DEPTH for Z and 0 for RGB sequentially (16 cycles).
  *
  * Tile index: tile_idx = iter_x[1:0] | (iter_y[1:0] << 2)
  *
  * Steps 11–12 of the Borg GPU roadmap.
  */

class BorgTileBufferIO(val dataBits: Int = 16) extends Bundle {
  // Write port (from rasterizer auto-write or MMIO)
  val writeIdx  = Input(UInt(4.W))       // 0–15 tile pixel index
  val writeData = Input(new ColorZ(dataBits))
  val writeEn   = Input(Bool())

  // Z-tested write (Step 12): only write if new Z < existing Z
  val zTestEn   = Input(Bool())          // when high with writeEn, use Z-compare
  val zTestBusy = Output(Bool())         // always false (combinational Z-test)

  // Read port (for MMIO flush — 2-cycle latency: BRAM + hold reg)
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

  // --- Z shadow: register-based (16 × 8-bit = 128 FFs) ---
  // Tracks upper 8 bits of Z (sign + exponent + 2 MSB mantissa).
  // For positive FP16, upper-byte comparison gives sufficient depth precision.
  // --- Hardware Z-test (Step 12) ---
  // Uses 2-cycle BRAM read→compare→write instead of 128 DFF shadow registers.
  // Saves ~128 LCs. Rasterizer stalls while zTestBusy is high.

  // --- Clear state machine ---
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

  // --- Z-test State Machine ---
  // Cycle 1: zTestEn=1, zReadDone=0  -> Issue BRAM Read, assert zTestBusy
  // Cycle 2: zTestEn=1, zReadDone=1  -> Compare Z, issue BRAM Write, drop zTestBusy
  val zReadDone = RegInit(false.B)
  when(io.zTestEn && !clearing) {
    zReadDone := true.B
  }.otherwise {
    zReadDone := false.B
  }

  io.zTestBusy := !zReadDone

  val doingZRead = io.zTestEn && !zReadDone && !clearing

  // --- BRAM read port (Muxed MMIO vs Z-test) ---
  val effectiveReadIdx = Mux(doingZRead, io.writeIdx, Mux(io.readEn && !clearing, io.readIdx, 0.U))
  val effectiveReadEn  = doingZRead || (io.readEn && !clearing)
  val rgbzRead = rgbzMem.read(effectiveReadIdx, effectiveReadEn)

  // --- Z-test Compare ---
  // Compare full 16-bit Z (unsigned: valid for positive FP16)
  val oldZ = rgbzRead.asTypeOf(new ColorZ(dataBits)).z
  val newZ = io.writeData.z
  val zTestPass = newZ < oldZ

  val doingZWrite = io.zTestEn && zReadDone && !clearing && zTestPass

  // --- Write logic ---
  val doWrite = doingZWrite || (io.writeEn && !io.zTestEn && !clearing)

  when(doWrite) {
    rgbzMem.write(io.writeIdx, io.writeData.asUInt)
  }

  val readDataHeld = Reg(new ColorZ(dataBits))

  // Capture BRAM output one cycle after readEn pulse
  val readEnDel = RegNext(io.readEn && !clearing, false.B)
  when(readEnDel) {
    readDataHeld := rgbzRead.asTypeOf(new ColorZ(dataBits))
  }

  io.readData := readDataHeld
}
