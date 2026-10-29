// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class BorgBinnerIO(maxTiles: Int = 1024, maxTrianglesPerTile: Int = 256, coordWidth: Int = 9) extends Bundle {
  // These widths must match SeqMmioIO and SeqBinnerIO in BorgSequencer.
  private val tileRowWidth = math.min(10, log2Ceil(maxTiles + 1))
  private val binRowBytesWidth = math.min(20, log2Ceil(maxTrianglesPerTile * 2 + 1))
  private val countAddrWidth = math.min(13, log2Ceil(maxTiles))
  private val countWidth     = math.min(10, log2Ceil(maxTrianglesPerTile + 1))
  val start       = Input(Bool())
  val busy        = Output(Bool())
  val done        = Output(Bool())

  val triIndex    = Input(UInt(16.W))
  /** Pixel coordinates, 4-aligned; max is exclusive. */
  val bbox        = Input(new Bbox(coordWidth))

  val binBase     = Input(UInt(GpuMemIO.AddrBits.W))
  /** 2 bytes per entry; a tile holds binRowBytes / 2 triangles. */
  val binRowBytes = Input(UInt(binRowBytesWidth.W))
  val tilesPerRow = Input(UInt(tileRowWidth.W))
  val tileRows    = Input(UInt(tileRowWidth.W))   // the window's tile rows (y clamp)

  val gpuMem      = new GpuMemIO

  /** Zero all per-tile counts, once per frame before binning. */
  val clearCounts = Input(Bool())

  /** Pass 2's count read, data one cycle later. */
  val countReadAddr = Input(UInt(countAddrWidth.W))
  val countReadEn   = Input(Bool())
  val countReadData = Output(UInt(countWidth.W))

  /** A tile's bin was full: the triangle stopped there, and is binned again once the
    * sequencer has rendered what the bins hold (docs/B11_partial_render.md). Valid
    * from the triangle's end until the next start. */
  val full = Output(Bool())
}

/** Per-tile bin list writer: for each tile in a triangle's bounding box, appends
  * the triangle index at `binBase + tile * binRowBytes + count * 2` and bumps
  * the tile's on-chip count. */
class BorgBinner(val maxTiles: Int = 1024, val maxTrianglesPerTile: Int = 256, val coordWidth: Int = 9) extends Module {
  val io = IO(new BorgBinnerIO(maxTiles, maxTrianglesPerTile, coordWidth))

  private val countWidth = math.min(10, log2Ceil(maxTrianglesPerTile + 1))

  val countMem = SyncReadMem(maxTiles, UInt(countWidth.W))

  // Entries a bin row holds, bounded by what the count can represent.
  private val capacity = {
    val c = io.binRowBytes >> 1
    val max = ((1 << countWidth) - 1).U
    Mux(c > max, max, c)
  }
  val fullReg = RegInit(false.B)
  io.full := fullReg

  val sIdle :: sReadCount :: sWaitCount :: sWriteDram :: sStoreCount :: sNextTile :: Nil = Enum(6)
  val state = RegInit(sIdle)

  val tileX = RegInit(0.U(coordWidth.W))   // pixel coords, 4-aligned
  val tileY = RegInit(0.U(coordWidth.W))

  val bboxMinX = RegInit(0.U(coordWidth.W))
  val bboxMinY = RegInit(0.U(coordWidth.W))
  val bboxMaxX = RegInit(0.U(coordWidth.W))
  val bboxMaxY = RegInit(0.U(coordWidth.W))

  val triIdxReg = RegInit(0.U(16.W))

  val curCount = RegInit(0.U(countWidth.W))

  // Narrowed to the countMem index so synthesis drops the high bits of the
  // binRowBytes multiply.
  private val tileIndexWidth = log2Ceil(maxTiles)
  val curTileIndex = (((tileY >> 2) * io.tilesPerRow) + (tileX >> 2))(tileIndexWidth - 1, 0)

  // clearCounts zeroes one entry per cycle.
  val clearIdx  = RegInit(0.U(log2Ceil(maxTiles + 1).W))
  val clearing  = RegInit(false.B)

  // A start during clearing is latched and runs once clearing ends.
  val pendingStart = RegInit(false.B)
  val pendBboxMinX = Reg(UInt(coordWidth.W))
  val pendBboxMinY = Reg(UInt(coordWidth.W))
  val pendBboxMaxX = Reg(UInt(coordWidth.W))
  val pendBboxMaxY = Reg(UInt(coordWidth.W))
  val pendTriIdx   = Reg(UInt(16.W))

  when(io.clearCounts && !clearing) {
    clearIdx := 0.U
    clearing := true.B
    fullReg := false.B
    pendingStart := false.B
    if (BorgDebug.trace) printf("[BIN] clearCounts pulse\n")
  }
  when(clearing) {
    when(clearIdx === (maxTiles - 1).U) {
      clearing := false.B
    }.otherwise {
      clearIdx := clearIdx + 1.U
    }
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

  // One write() call: two write ports stop Yosys inferring DP16KD BRAM.
  val countMemWriteEn   = clearing || (state === sStoreCount)
  val countMemWriteAddr = Mux(clearing, clearIdx, curTileIndex)
  val countMemWriteData = Mux(clearing, 0.U(countWidth.W), curCount + 1.U)
  when(countMemWriteEn) {
    countMem.write(countMemWriteAddr, countMemWriteData)
  }

  // One read port: the tile walk while binning, Pass 2's reads when idle.
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
  val extReadEn_d = RegNext(extReadEn)
  when(extReadEn_d) {
    if (BorgDebug.trace) printf("[BIN] extRead data=%d\n", countReadData)
  }

  // pendingStart counts as busy: no !busy gap before the auto-start.
  io.busy := state =/= sIdle || clearing || pendingStart
  io.done := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

  switch(state) {

    is(sIdle) {
      val doStart = (io.start && !clearing) || (pendingStart && !clearing)
      when(doStart) {
        val rawMinX = Mux(pendingStart, pendBboxMinX, io.bbox.min.x)
        val rawMinY = Mux(pendingStart, pendBboxMinY, io.bbox.min.y)
        val rawMaxX = Mux(pendingStart, pendBboxMaxX, io.bbox.max.x)
        val rawMaxY = Mux(pendingStart, pendBboxMaxY, io.bbox.max.y)
        val triId   = Mux(pendingStart, pendTriIdx,   io.triIndex)

        // Clamp to the window so a garbage bbox cannot walk out-of-range tiles
        // and clobber the counts and bins.
        val fbDim   = (io.tilesPerRow << 2).asUInt        // window width in pixels (exclusive max)
        val fbDimY  = (io.tileRows << 2).asUInt           // window height in pixels
        def lastTl(d: UInt) = Mux(d >= 4.U, d - 4.U, 0.U) // first pixel of the last tile
        def clampMin(v: UInt, d: UInt): UInt = Mux(v > lastTl(d), lastTl(d), v)(coordWidth - 1, 0)
        def clampMax(v: UInt, d: UInt): UInt = Mux(v > d, d, v)(coordWidth - 1, 0)
        val minX = clampMin(rawMinX, fbDim)
        val minY = clampMin(rawMinY, fbDimY)
        val maxX = clampMax(rawMaxX, fbDim)
        val maxY = clampMax(rawMaxY, fbDimY)

        if (BorgDebug.trace) printf("[BIN] doStart pending=%d raw=(%d,%d)-(%d,%d) clamped=(%d,%d)-(%d,%d)\n",
          pendingStart, rawMinX, rawMinY, rawMaxX, rawMaxY, minX, minY, maxX, maxY)

        bboxMinX  := minX
        bboxMinY  := minY
        bboxMaxX  := maxX
        bboxMaxY  := maxY
        triIdxReg := triId
        fullReg   := false.B
        tileX     := minX
        tileY     := minY
        pendingStart := false.B
        state     := sReadCount
      }
    }

    is(sReadCount) {
      state := sWaitCount
    }

    is(sWaitCount) {
      curCount := countReadData
      // A bin with no room at all drops the entry, so a full one can always be emptied.
      when(countReadData >= capacity && capacity =/= 0.U) {
        fullReg := true.B
        state   := sIdle
      }.elsewhen(countReadData >= capacity) {
        state := sNextTile
      }.otherwise {
        state := sWriteDram
      }
    }

    is(sWriteDram) {
      val dramAddr = io.binBase +
        (curTileIndex * io.binRowBytes) +
        (curCount << 1)

      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := dramAddr
      io.gpuMem.wdata := triIdxReg

      when(io.gpuMem.ready) {
        state := sStoreCount
      }
    }

    // The count write itself is the shared write port above.
    is(sStoreCount) {
      if (BorgDebug.trace) printf("[BIN] sStoreCount tile=%d count=%d->%d\n", curTileIndex, curCount, curCount + 1.U)
      state := sNextTile
    }

    is(sNextTile) {
      val nextX = tileX + 4.U
      when(nextX >= bboxMaxX) {
        tileX := bboxMinX
        val nextY = tileY + 4.U
        when(nextY >= bboxMaxY) {
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
