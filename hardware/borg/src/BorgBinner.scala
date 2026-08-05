// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** IO bundle for [[BorgBinner]] (Step 32.1).
  *
  * The binner writes triangle indices into per-tile bin lists in DRAM.
  * It is triggered once per triangle during the geometry pass and iterates
  * over all tiles in the triangle's bounding box.
  */
class BorgBinnerIO(maxTiles: Int = 1024, maxTrianglesPerTile: Int = 256, coordWidth: Int = 9) extends Bundle {
  // See BorgSequencer.SeqMmioIO's tileRowWidth comment -- same invariant,
  // same formula, so this and BorgSequencer's tilesPerRow always agree in
  // width when both are constructed from the same cfg.maxBinTiles.
  private val tileRowWidth = math.min(10, log2Ceil(maxTiles + 1))
  // See BorgSequencer.SeqMmioIO's binRowBytesWidth comment -- same formula,
  // so this and BorgSequencer's binRowBytes always agree in width when both
  // are constructed from the same cfg.maxTrianglesPerTile.
  private val binRowBytesWidth = math.min(20, log2Ceil(maxTrianglesPerTile * 2 + 1))
  // Same math.min-guarded narrowing as SeqBinnerIO (BorgSequencer.scala) --
  // must agree with it since BorgSequencer drives these ports directly.
  private val countAddrWidth = math.min(13, log2Ceil(maxTiles))
  private val countWidth     = math.min(10, log2Ceil(maxTrianglesPerTile + 1))
  // --- Trigger interface ---
  /** One-cycle pulse to start binning a triangle. */
  val start       = Input(Bool())
  /** High while the binner is processing tiles. */
  val busy        = Output(Bool())
  /** One-cycle pulse when all tiles for this triangle have been written. */
  val done        = Output(Bool())

  // --- Triangle metadata ---
  /** Index of the current triangle (0..SEQ_MAX_TRI-1), written as uint16 to DRAM. */
  val triIndex    = Input(UInt(16.W))
  /** Tile-aligned bounding box: {minX, minY, maxX, maxY} in pixel coordinates.
    * minX/minY are inclusive; maxX/maxY are exclusive (one past the last pixel).
    * All values must be 4-pixel-aligned (tile size = 4×4). */
  val bbox        = Input(new Bbox(coordWidth))

  // --- DRAM layout parameters ---
  /** GPU memory byte address of the bin list region base (from tbr_bin_base); 25b = 32 MB. */
  val binBase     = Input(UInt(25.W))
  /** Bin list row size in bytes (= SEQ_MAX_TRI * TBR_BIN_ENTRY_SIZE). */
  val binRowBytes = Input(UInt(binRowBytesWidth.W))
  /** Number of tiles per framebuffer row (= fb_width / 4). */
  val tilesPerRow = Input(UInt(tileRowWidth.W))

  // --- DRAM write port ---
  val gpuMem      = new GpuMemIO

  // --- Per-tile count reset (for frame start) ---
  /** One-cycle pulse to zero all per-tile counters. Assert once per frame
    * before the first triangle is binned. */
  val clearCounts = Input(Bool())

  // --- Step 32.3: External count read port (for Pass 2 tile render) ---
  /** Tile index to read the triangle count for. */
  val countReadAddr = Input(UInt(countAddrWidth.W))
  /** Read enable — assert for one cycle; data valid on next cycle. */
  val countReadEn   = Input(Bool())
  /** Triangle count for the tile addressed by countReadAddr (1-cycle latency). */
  val countReadData = Output(UInt(countWidth.W))
}

/** BorgBinner — per-tile bin list writer (Step 32.1).
  *
  * For each tile in the triangle's bounding box, the binner:
  *   1. Reads the current count for that tile from on-chip SRAM.
  *   2. Computes the DRAM byte address: `binBase + tile_index * binRowBytes + count * 2`.
  *   3. Writes the 16-bit triangle index to DRAM via `GpuMemIO`.
  *   4. Increments and stores the updated count back to SRAM.
  *
  * Per-tile counts are stored in a small `SyncReadMem` (maxTiles entries × 10 bits).
  * 10 bits supports up to 1023 triangles per tile (more than SEQ_MAX_TRI).
  *
  * FSM:
  * {{{
  *   sIdle → sReadCount → sWaitCount → sWriteDram → sStoreCount → sNextTile → ...
  *   sNextTile → sReadCount (more tiles) | sIdle (bbox exhausted)
  * }}}
  *
  * The module is gated behind `hasBinner: Boolean` in [[BorgConfig]] and is
  * invisible to FPGA iCE40 targets.
  *
  * @param maxTiles Maximum number of tiles (default 1024 = 32×32 for 128×128 @ 4×4).
  * @param maxTrianglesPerTile Upper bound on `binRowBytes` (see BorgConfig's doc
  *   comment) -- default 256 matches software/borg/borg_layout.h's SEQ_MAX_TRI.
  */
class BorgBinner(val maxTiles: Int = 1024, val maxTrianglesPerTile: Int = 256, val coordWidth: Int = 9) extends Module {
  val io = IO(new BorgBinnerIO(maxTiles, maxTrianglesPerTile, coordWidth))

  // Same math.min-guarded narrowing as BorgBinnerIO (must agree with it).
  private val countWidth = math.min(10, log2Ceil(maxTrianglesPerTile + 1))

  // --- Per-tile count SRAM ---
  // countWidth-bit count per tile: supports up to maxTrianglesPerTile triangles.
  val countMem = SyncReadMem(maxTiles, UInt(countWidth.W))

  // --- FSM ---
  val sIdle :: sReadCount :: sWaitCount :: sWriteDram :: sStoreCount :: sNextTile :: Nil = Enum(6)
  val state = RegInit(sIdle)

  // --- Tile iteration registers ---
  val tileX = RegInit(0.U(coordWidth.W))   // current tile column (pixel coord, 4-aligned)
  val tileY = RegInit(0.U(coordWidth.W))   // current tile row (pixel coord, 4-aligned)

  // Latched bbox (stable for the duration of one triangle's binning)
  val bboxMinX = RegInit(0.U(coordWidth.W))
  val bboxMinY = RegInit(0.U(coordWidth.W))
  val bboxMaxX = RegInit(0.U(coordWidth.W))
  val bboxMaxY = RegInit(0.U(coordWidth.W))

  // Latched triangle index
  val triIdxReg = RegInit(0.U(16.W))

  // Current tile's count (read from SRAM)
  val curCount = RegInit(0.U(countWidth.W))

  // Current tile index (linear: tileRow * tilesPerRow + tileCol)
  val curTileIndex = ((tileY >> 2) * io.tilesPerRow) + (tileX >> 2)

  // --- Count clear logic ---
  // When clearCounts is pulsed, zero all entries over multiple cycles.
  val clearIdx  = RegInit(0.U(log2Ceil(maxTiles + 1).W))
  val clearing  = RegInit(false.B)

  // Pending start: latched when start fires during clearing.
  // Auto-starts binning once clearing finishes.
  val pendingStart = RegInit(false.B)
  val pendBboxMinX = Reg(UInt(coordWidth.W))
  val pendBboxMinY = Reg(UInt(coordWidth.W))
  val pendBboxMaxX = Reg(UInt(coordWidth.W))
  val pendBboxMaxY = Reg(UInt(coordWidth.W))
  val pendTriIdx   = Reg(UInt(16.W))

  when(io.clearCounts && !clearing) {
    clearIdx := 0.U
    clearing := true.B
    pendingStart := false.B  // new frame — cancel any stale pending
    if (BorgDebug.trace) printf("[BIN] clearCounts pulse\n")
  }
  when(clearing) {
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
      if (BorgDebug.trace) printf("[BIN] pendingStart latched bbox=(%d,%d)-(%d,%d) tri=%d\n",
        io.bbox.min.x, io.bbox.min.y, io.bbox.max.x, io.bbox.max.y, io.triIndex)
      pendTriIdx   := io.triIndex
    }
  }

  // --- Single-write-port count SRAM write ---
  // Merge clearing and increment into one write() call so firtool emits a single
  // write port. Two separate write() calls produce two write ports, which prevents
  // Yosys from inferring DP16KD BRAM and forces a costly LUT-based implementation.
  // Clearing takes priority; simultaneous firing only occurs if clearCounts arrives
  // mid-binning, which is out-of-spec (sequencer clears at frame start before binning).
  val countMemWriteEn   = clearing || (state === sStoreCount)
  val countMemWriteAddr = Mux(clearing, clearIdx, curTileIndex)
  val countMemWriteData = Mux(clearing, 0.U(countWidth.W), curCount + 1.U)
  when(countMemWriteEn) {
    countMem.write(countMemWriteAddr, countMemWriteData)
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
    if (BorgDebug.trace) printf("[BIN] extRead addr=%d state=%d clearing=%d pending=%d\n",
      io.countReadAddr, state, clearing, pendingStart)
  }
  // Log the data one cycle later (SyncReadMem has 1-cycle latency)
  val extReadEn_d = RegNext(extReadEn)
  when(extReadEn_d) {
    if (BorgDebug.trace) printf("[BIN] extRead data=%d\n", countReadData)
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
  io.gpuMem.wlen  := 1.U   // binner writes single 16-bit indices

  // --- FSM ---
  switch(state) {

    is(sIdle) {
      // Accept start from either direct pulse or pending (latched during clear).
      val doStart = (io.start && !clearing) || (pendingStart && !clearing)
      when(doStart) {
        // Select the live or pending-latched bbox/triIdx.
        val rawMinX = Mux(pendingStart, pendBboxMinX, io.bbox.min.x)
        val rawMinY = Mux(pendingStart, pendBboxMinY, io.bbox.min.y)
        val rawMaxX = Mux(pendingStart, pendBboxMaxX, io.bbox.max.x)
        val rawMaxY = Mux(pendingStart, pendBboxMaxY, io.bbox.max.y)
        val triId   = Mux(pendingStart, pendTriIdx,   io.triIndex)

        // --- SAFETY CLAMP to the framebuffer tile grid ---
        // A degenerate/garbage triangle (e.g. a bad vertex transform projecting
        // every vertex to the FP16 saturation corner ~1023) yields a bbox that
        // spans the whole 1024-pixel coordinate space.  Unclamped, the tile walk
        // would iterate ~(1024/4)² tiles per triangle — effectively a hang, with
        // out-of-range tile indices clobbering the count SRAM / bin DRAM.  Clamp
        // the bbox to the real grid so the walk is always bounded and in-range,
        // regardless of upstream bugs.  Square fb (width==height) is assumed, as
        // on every current target; the bound is the fb width in pixels.
        val fbDim   = (io.tilesPerRow << 2).asUInt        // fb extent in pixels (exclusive max)
        val lastTl  = Mux(fbDim >= 4.U, fbDim - 4.U, 0.U) // first pixel of the last tile
        def clampMin(v: UInt): UInt = Mux(v > lastTl, lastTl, v)(coordWidth - 1, 0)
        def clampMax(v: UInt): UInt = Mux(v > fbDim, fbDim, v)(coordWidth - 1, 0)
        val minX = clampMin(rawMinX)
        val minY = clampMin(rawMinY)
        val maxX = clampMax(rawMaxX)
        val maxY = clampMax(rawMaxY)

        if (BorgDebug.trace) printf("[BIN] doStart pending=%d raw=(%d,%d)-(%d,%d) clamped=(%d,%d)-(%d,%d)\n",
          pendingStart, rawMinX, rawMinY, rawMaxX, rawMaxY, minX, minY, maxX, maxY)

        bboxMinX  := minX
        bboxMinY  := minY
        bboxMaxX  := maxX
        bboxMaxY  := maxY
        triIdxReg := triId
        tileX     := minX
        tileY     := minY
        pendingStart := false.B
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
      state    := sWriteDram
    }

    // Write triangle index to DRAM:
    //   addr = binBase + tileIndex * binRowBytes + count * 2
    // The 16-bit triangle index is zero-extended to 32 bits for the write.
    is(sWriteDram) {
      val dramAddr = io.binBase +
        (curTileIndex * io.binRowBytes) +
        (curCount << 1)

      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := dramAddr
      io.gpuMem.wdata := triIdxReg  // zero-extended uint16 → uint32

      when(io.gpuMem.ready) {
        state := sStoreCount
      }
    }

    // Write incremented count back to SRAM (handled by single-port write above).
    is(sStoreCount) {
      if (BorgDebug.trace) printf("[BIN] sStoreCount tile=%d count=%d->%d\n", curTileIndex, curCount, curCount + 1.U)
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
