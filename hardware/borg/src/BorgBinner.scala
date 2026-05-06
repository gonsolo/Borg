// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** IO bundle for [[BorgBinner]] (Step 32.1).
  *
  * The binner writes triangle indices into per-tile bin lists in PSRAM.
  * It is triggered once per triangle during the geometry pass and iterates
  * over all tiles in the triangle's bounding box.
  */
class BorgBinnerIO extends Bundle {
  // --- Trigger interface ---
  /** One-cycle pulse to start binning a triangle. */
  val start       = Input(Bool())
  /** High while the binner is processing tiles. */
  val busy        = Output(Bool())
  /** One-cycle pulse when all tiles for this triangle have been written. */
  val done        = Output(Bool())

  // --- Triangle metadata ---
  /** Index of the current triangle (0..SEQ_MAX_TRI-1), written as uint16 to PSRAM. */
  val triIndex    = Input(UInt(16.W))
  /** Tile-aligned bounding box: {minX, minY, maxX, maxY} in pixel coordinates.
    * minX/minY are inclusive; maxX/maxY are exclusive (one past the last pixel).
    * All values must be 4-pixel-aligned (tile size = 4×4). */
  val bbox        = Input(new Bbox(10))

  // --- PSRAM layout parameters ---
  /** PSRAM byte address of the bin list region base (from tbr_bin_base). */
  val binBase     = Input(UInt(20.W))
  /** Bin list row size in bytes (= SEQ_MAX_TRI * TBR_BIN_ENTRY_SIZE). */
  val binRowBytes = Input(UInt(20.W))
  /** Number of tiles per framebuffer row (= fb_width / 4). */
  val tilesPerRow = Input(UInt(10.W))

  // --- PSRAM write port ---
  val gpuMem      = new GpuMemIO

  // --- Per-tile count reset (for frame start) ---
  /** One-cycle pulse to zero all per-tile counters. Assert once per frame
    * before the first triangle is binned. */
  val clearCounts = Input(Bool())

  // --- Step 32.3: External count read port (for Pass 2 tile render) ---
  /** Tile index to read the triangle count for. */
  val countReadAddr = Input(UInt(10.W))
  /** Read enable — assert for one cycle; data valid on next cycle. */
  val countReadEn   = Input(Bool())
  /** Triangle count for the tile addressed by countReadAddr (1-cycle latency). */
  val countReadData = Output(UInt(10.W))
}

/** BorgBinner — per-tile bin list writer (Step 32.1).
  *
  * For each tile in the triangle's bounding box, the binner:
  *   1. Reads the current count for that tile from on-chip SRAM.
  *   2. Computes the PSRAM byte address: `binBase + tile_index * binRowBytes + count * 2`.
  *   3. Writes the 16-bit triangle index to PSRAM via `GpuMemIO`.
  *   4. Increments and stores the updated count back to SRAM.
  *
  * Per-tile counts are stored in a small `SyncReadMem` (maxTiles entries × 10 bits).
  * 10 bits supports up to 1023 triangles per tile (more than SEQ_MAX_TRI).
  *
  * FSM:
  * {{{
  *   sIdle → sReadCount → sWaitCount → sWritePsram → sStoreCount → sNextTile → ...
  *   sNextTile → sReadCount (more tiles) | sIdle (bbox exhausted)
  * }}}
  *
  * The module is gated behind `hasBinner: Boolean` in [[BorgConfig]] and is
  * invisible to FPGA iCE40 targets.
  *
  * @param maxTiles Maximum number of tiles (default 1024 = 32×32 for 128×128 @ 4×4).
  */
class BorgBinner(val maxTiles: Int = 1024) extends Module {
  val io = IO(new BorgBinnerIO)

  // --- Per-tile count SRAM ---
  // 10-bit count per tile: supports up to 1023 triangles per tile.
  val countMem = SyncReadMem(maxTiles, UInt(10.W))

  // --- FSM ---
  val sIdle :: sReadCount :: sWaitCount :: sWritePsram :: sStoreCount :: sNextTile :: Nil = Enum(6)
  val state = RegInit(sIdle)

  // --- Tile iteration registers ---
  val tileX = RegInit(0.U(10.W))   // current tile column (pixel coord, 4-aligned)
  val tileY = RegInit(0.U(10.W))   // current tile row (pixel coord, 4-aligned)

  // Latched bbox (stable for the duration of one triangle's binning)
  val bboxMinX = RegInit(0.U(10.W))
  val bboxMinY = RegInit(0.U(10.W))
  val bboxMaxX = RegInit(0.U(10.W))
  val bboxMaxY = RegInit(0.U(10.W))

  // Latched triangle index
  val triIdxReg = RegInit(0.U(16.W))

  // Current tile's count (read from SRAM)
  val curCount = RegInit(0.U(10.W))

  // Current tile index (linear: tileRow * tilesPerRow + tileCol)
  val curTileIndex = ((tileY >> 2) * io.tilesPerRow) + (tileX >> 2)

  // --- Count clear logic ---
  // When clearCounts is pulsed, zero all entries over multiple cycles.
  val clearIdx  = RegInit(0.U(log2Ceil(maxTiles + 1).W))
  val clearing  = RegInit(false.B)

  // Pending start: latched when start fires during clearing.
  // Auto-starts binning once clearing finishes.
  val pendingStart = RegInit(false.B)
  val pendBboxMinX = Reg(UInt(10.W))
  val pendBboxMinY = Reg(UInt(10.W))
  val pendBboxMaxX = Reg(UInt(10.W))
  val pendBboxMaxY = Reg(UInt(10.W))
  val pendTriIdx   = Reg(UInt(16.W))

  when(io.clearCounts && !clearing) {
    clearIdx := 0.U
    clearing := true.B
    pendingStart := false.B  // new frame — cancel any stale pending
    printf("[BIN] clearCounts pulse\n")
  }
  when(clearing) {
    countMem.write(clearIdx, 0.U)
    when(clearIdx === (maxTiles - 1).U) {
      clearing := false.B
    }.otherwise {
      clearIdx := clearIdx + 1.U
    }
    // Latch start during clearing
    when(io.start) {
      pendingStart := true.B
      pendBboxMinX := io.bbox.min.x
      pendBboxMinY := io.bbox.min.y
      pendBboxMaxX := io.bbox.max.x
      pendBboxMaxY := io.bbox.max.y
      printf("[BIN] pendingStart latched bbox=(%d,%d)-(%d,%d) tri=%d\n",
        io.bbox.min.x, io.bbox.min.y, io.bbox.max.x, io.bbox.max.y, io.triIndex)
      pendTriIdx   := io.triIndex
    }
  }

  // --- SRAM read port ---
  // SyncReadMem: issue the read in sReadCount; data is valid in sWaitCount.
  // The enable is gated to sReadCount so we don't issue spurious reads.
  // Step 32.3: External read port shares the same SyncReadMem read port.
  // During binning (state != sIdle), the binner's internal tile index drives reads.
  // When idle, the external countReadAddr/countReadEn drives the read port
  // so the sequencer can query per-tile counts for Pass 2.
  val extReadEn = io.countReadEn && state === sIdle && !clearing
  val intReadEn = state === sReadCount
  val readAddr  = Mux(intReadEn, curTileIndex, io.countReadAddr)
  val readEn    = intReadEn || extReadEn
  val countReadData = countMem.read(readAddr, readEn)
  io.countReadData := countReadData
  when(extReadEn) {
    printf("[BIN] extRead addr=%d state=%d clearing=%d pending=%d\n",
      io.countReadAddr, state, clearing, pendingStart)
  }
  // Log the data one cycle later (SyncReadMem has 1-cycle latency)
  val extReadEn_d = RegNext(extReadEn)
  when(extReadEn_d) {
    printf("[BIN] extRead data=%d\n", countReadData)
  }

  // --- Output defaults ---
  // Include pendingStart in busy: prevents a 1-cycle !busy gap between
  // clearing and the auto-start, which would cause the sequencer to
  // skip ahead before binning begins.
  io.busy := state =/= sIdle || clearing || pendingStart
  io.done := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U

  // --- FSM ---
  switch(state) {

    is(sIdle) {
      // Accept start from either direct pulse or pending (latched during clear).
      val doStart = (io.start && !clearing) || (pendingStart && !clearing)
      when(doStart) {
        printf("[BIN] doStart pending=%d bbox=(%d,%d)-(%d,%d)\n",
          pendingStart, Mux(pendingStart, pendBboxMinX, io.bbox.min.x),
          Mux(pendingStart, pendBboxMinY, io.bbox.min.y),
          Mux(pendingStart, pendBboxMaxX, io.bbox.max.x),
          Mux(pendingStart, pendBboxMaxY, io.bbox.max.y))
        // Use pending inputs if start came during clearing; otherwise use live inputs.
        when(pendingStart) {
          bboxMinX  := pendBboxMinX
          bboxMinY  := pendBboxMinY
          bboxMaxX  := pendBboxMaxX
          bboxMaxY  := pendBboxMaxY
          triIdxReg := pendTriIdx
          tileX     := pendBboxMinX
          tileY     := pendBboxMinY
          pendingStart := false.B
        }.otherwise {
          bboxMinX  := io.bbox.min.x
          bboxMinY  := io.bbox.min.y
          bboxMaxX  := io.bbox.max.x
          bboxMaxY  := io.bbox.max.y
          triIdxReg := io.triIndex
          tileX     := io.bbox.min.x
          tileY     := io.bbox.min.y
        }
        state     := sReadCount
      }
    }

    // Cycle 0: SyncReadMem read is issued (via countReadData above).
    // Data will be available next cycle.
    is(sReadCount) {
      state := sWaitCount
    }

    // Cycle 1: SyncReadMem data is now valid. Capture it.
    is(sWaitCount) {
      curCount := countReadData
      state    := sWritePsram
    }

    // Write triangle index to PSRAM:
    //   addr = binBase + tileIndex * binRowBytes + count * 2
    // The 16-bit triangle index is zero-extended to 32 bits for the write.
    is(sWritePsram) {
      val psramAddr = io.binBase +
        (curTileIndex * io.binRowBytes) +
        (curCount << 1)

      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := psramAddr
      io.gpuMem.wdata := triIdxReg  // zero-extended uint16 → uint32

      when(io.gpuMem.ready) {
        state := sStoreCount
      }
    }

    // Write incremented count back to SRAM.
    is(sStoreCount) {
      countMem.write(curTileIndex, curCount + 1.U)
      printf("[BIN] sStoreCount tile=%d count=%d->%d\n", curTileIndex, curCount, curCount + 1.U)
      state := sNextTile
    }

    // Advance to next tile in bbox. Row-major order: X increments first.
    is(sNextTile) {
      val nextX = tileX + 4.U
      when(nextX >= bboxMaxX) {
        tileX := bboxMinX
        val nextY = tileY + 4.U
        when(nextY >= bboxMaxY) {
          // All tiles processed
          io.done := true.B
          state   := sIdle
        }.otherwise {
          tileY := nextY
          state := sReadCount
        }
      }.otherwise {
        tileX := nextX
        state := sReadCount
      }
    }
  }
}
