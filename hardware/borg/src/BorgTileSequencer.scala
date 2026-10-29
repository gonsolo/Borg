// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Pass 2 of BorgSequencer: walks every tile, reads its bin list and the
  * per-triangle records Pass 1 wrote to DRAM (through a 2-way setup cache over
  * the uniform pages), rasterizes, and flushes. */
class BorgTileSequencerIO(val cfg: BorgConfig) extends Bundle {
  val start = Input(Bool())
  val done  = Output(Bool())
  val busy  = Output(Bool())
  // Toggled by the wrapper once per frame; indexes the per-buffer dirty bits.
  val curBufIdx = Input(UInt(1.W))

  val mmio    = new SeqMmioIO(cfg)
  val binner  = new SeqBinnerIO(cfg)  // only countReadAddr/countReadEn/countReadData used
  val flusher = new SeqFlusherIO
  val iter    = new SeqIteratorIO(cfg.coordWidth)
  val dma     = new SeqDmaIO(cfg)

  val covDelta = if (cfg.samples > 1)
    Some(Output(Vec(cfg.coveragePlanesStored, Vec(2, UInt(cfg.totalBits.W))))) else None
  val frontFacingOverride = Output(Bool())
  // Advances only once the triangle's pixels have drained, so it names the
  // triangle every tested fragment belongs to.
  val curTriIndex = Output(UInt(16.W))
  // The current triangle's varyings (record word 48), for FATTR; the dispatcher
  // drains before the next triangle is selected, so each fragment sees its own.
  val attrBase = Output(UInt(GpuMemIO.AddrBits.W))
  // Top or left edges (inward normal (a, b): a > 0, or a == 0 and b > 0) own
  // samples exactly on them, so a shared edge is covered once.
  val topLeft = Output(Vec(3, Bool()))
  // The tile renders once per colour attachment.
  val attPass = Output(UInt(3.W))

  val uniformWrite     = new MemWritePort(6, cfg.totalBits)
  val uniformWritePage = Output(UInt(1.W))

  // Render lists: a triangle whose state block (record meta word) differs from
  // the registers' has it played before it is rasterized.
  val listMode = Input(Bool())
  // A partial render (docs/B11_partial_render.md) draws only the triangles below this.
  val triLimit = Input(UInt(17.W))
  val playReq  = Output(Bool())
  val playAddr = Output(UInt(GpuMemIO.AddrBits.W))
  val playDone = Input(Bool())

}

class BorgTileSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgTileSequencerIO(cfg))

  val nStates = 22
  val states = Enum(nStates)
  val (sIdle :: sWaitDMA :: sLoadRastShader :: sLoadFragShader :: sLoadFsConst :: sStartPass2 ::
       sReadBinCount :: sClearTile :: sLoadTile ::
       sReadBinEntry :: sWaitBinEntry :: sLoadTriSetup :: sLoadCovDelta ::
       sEnqueueTile :: sIteratePixels :: sWaitRast :: sWaitFlush :: sWaitFlushSync ::
       sNextBinTri :: sNextRenderTile :: _) = states
  // (A pattern binds at most 22 names.) Render lists: play a state block; back to the triangle.
  val sPlayState = states(20); val sResumeTri = states(21)
  val state = RegInit(sIdle)

  val nextAfterDMA = RegInit(sIdle)
  // Depth, stencil and the occlusion count are the same every pass; Borg.scala
  // stores the former only in the last pass and counts the latter in the first.
  val attPass = RegInit(0.U(3.W))
  // Which uniform page the fragment constant window goes to next.
  val constPage = RegInit(0.U(1.W))

  val tileX = RegInit(0.U(cfg.coordWidth.W))
  val tileY = RegInit(0.U(cfg.coordWidth.W))

  val binTriIdx    = RegInit(0.U(10.W))
  val binTriCount  = RegInit(0.U(10.W))
  val binEntryData = RegInit(0.U(16.W))  // triangle index read from the bin list
  val clearCounter = RegInit(0.U(5.W))

  // Word count of the record snoop, reused for the covDelta snoop.
  val setupLoadIdx = RegInit(0.U(6.W))

  // Setup cache: tagReg(p) is the triangle resident in uniform page p
  // (0xFFFF = invalid), cacheVictim the round-robin victim (2-way == LRU).
  // A 32-uniform build (Wafer) drops the page bit on uniform read and write,
  // so it gets one entry: a second tag would alias page 0.
  private val setupPages = if (cfg.maxUniforms > 32) 2 else 1
  val tagReg      = RegInit(VecInit(Seq.fill(2)("hFFFF".U(16.W))))
  private val covDeltaWords = 2 * cfg.coveragePlanesStored
  // Per-page facing flag (meta word bit 1), restored on a cache hit.
  val backFacingReg = RegInit(VecInit(Seq.fill(2)(false.B)))
  // Render lists: state block word address per page, of the selected
  // triangle, and of what the registers hold now (0 = none yet).
  private val stateBits = GpuMemIO.AddrBits - 2
  val stateCache = Reg(Vec(2, UInt(stateBits.W)))
  val triState   = RegInit(0.U(stateBits.W))
  val curState   = RegInit(0.U(stateBits.W))
  val resumeTri  = RegInit(false.B)      // the shader reload under way is a state switch
  val savedPage  = RegInit(0.U(1.W))
  val cacheVictim = RegInit(0.U(1.W))
  val covDeltaCache = if (cfg.samples > 1)
    Some(RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(covDeltaWords)(0.U(cfg.totalBits.W))))))) else None
  // Pixels of the previous triangle still drain after uniformPage changes, so
  // the *Active registers are latched once per triangle selection instead of
  // read live from the per-page caches.
  val topLeftCache  = Reg(Vec(2, Vec(3, Bool())))
  val topLeftActive = RegInit(VecInit(Seq.fill(3)(true.B)))
  val planeA = Reg(Vec(3, UInt(2.W)))   // a: {negative, zero}
  val covDeltaActive = if (cfg.samples > 1)
    Some(RegInit(VecInit(Seq.fill(covDeltaWords)(0.U(cfg.totalBits.W))))) else None

  // Two-sided stencil picks front or back ops with it.
  val triIsBackFacing = RegInit(false.B)

  val uniformPage = RegInit(0.U(1.W))

  // Registered tileComplete breaks the loop advance -> tileComplete ->
  // iteratePixels -> advance; a pixel takes many cycles, so the lag is harmless.
  val clearTileComplete = RegInit(false.B)
  val loadStarted = RegInit(false.B)
  val tileCompleteLatch = RegInit(false.B)
  when(clearTileComplete) {
    tileCompleteLatch := false.B
    clearTileComplete := false.B
  }.otherwise {
    tileCompleteLatch := io.iter.complete
  }

  // Skip-empty-tile flush: did the tile have content the last time this buffer
  // was rendered? A RAM, since each tile's bit is read and rewritten once per
  // render; as registers the 4096-tile array does not fit the ECP5. No reset
  // contents needed: the ~0 colour sentinel flushes every tile on first use.
  private val dirtyAddrBits = log2Ceil(cfg.maxBinTiles)
  val tileDirtyMem  = SyncReadMem(2 * cfg.maxBinTiles, Bool())
  val dirtyReadAddr = WireDefault(0.U((dirtyAddrBits + 1).W))
  val dirtyReadEn   = WireDefault(false.B)
  val dirtyReadData = tileDirtyMem.read(dirtyReadAddr, dirtyReadEn)
  val tileWasDirty  = RegInit(true.B)
  // The clear colour differs from this buffer's last render: flush every tile.
  val colorChanged  = RegInit(true.B)
  val lastClearColorBuf = RegInit(VecInit(Seq.fill(2)(~0.U(64.W))))

  val dmaDescReg = RegInit(0.U.asTypeOf(new DMADescriptor))

  wireOutputDefaults()
  wireFsm()
  wireSnoops()

  private def wireOutputDefaults(): Unit = {
    io.busy := state =/= sIdle
    io.done := false.B

    io.covDelta.foreach { cd =>
      val active = covDeltaActive.get
      for (e <- 0 until cfg.coveragePlanesStored; k <- 0 until 2) {
        // setupShort leaves the depth plane's deltas unwritten in the record
        cd(e)(k) := Mux(io.mmio.singleSample, 0.U, active(2 * e + k))
      }
    }
    io.frontFacingOverride := !triIsBackFacing
    io.curTriIndex := binEntryData
    io.attPass := attPass
    io.attrBase := (io.mmio.setupBase +& (binEntryData << setupStrideShift) +& (48 * 4).U)(GpuMemIO.AddrBits - 1, 0)
    io.topLeft := topLeftActive

    io.dma.start := false.B
    io.dma.desc  := dmaDescReg

    io.uniformWrite.en   := false.B
    io.uniformWrite.addr := 0.U
    io.uniformWrite.data := 0.U
    io.uniformWritePage  := uniformPage

    io.playReq  := false.B
    io.playAddr := Cat(triState, 0.U(2.W))

    io.iter.clear         := false.B
    io.iter.enqueue.valid := false.B
    io.iter.enqueue.bits.x := tileX
    io.iter.enqueue.bits.y := tileY
    io.iter.iterate       := false.B
    io.flusher.trigger    := false.B
    io.flusher.loadStart  := false.B
    // Tile index * 32 bytes (16 RGB565/D16 pixels); Borg.scala adds the
    // attachment base and scales for 4-byte formats.
    val tileIndex = (((tileY >> 2) +& io.mmio.fbOriginY) * io.mmio.fbPitch) + ((tileX >> 2) +& io.mmio.fbOriginX)
    io.flusher.tileOffset := tileIndex << 5

    // BorgDrawWalker owns the write side of SeqBinnerIO.
    io.binner.start       := false.B
    io.binner.triIndex    := 0.U
    io.binner.bbox.min.x  := 0.U
    io.binner.bbox.min.y  := 0.U
    io.binner.bbox.max.x  := 0.U
    io.binner.bbox.max.y  := 0.U
    io.binner.clearCounts := false.B
    io.binner.countReadAddr := 0.U
    io.binner.countReadEn   := false.B
  }

  private def wireFsm(): Unit = {
    switch(state) {
      is(sIdle)            { handleIdle() }
      is(sWaitDMA)         { handleWaitDMA() }
      is(sLoadRastShader)  { handleLoadRastShader() }
      is(sLoadFragShader)  { handleLoadFragShader() }
      is(sLoadFsConst)     { handleLoadFsConst() }
      is(sStartPass2)      { handleStartPass2() }
      is(sReadBinCount)    { handleReadBinCount() }
      is(sClearTile)       { handleClearTile() }
      is(sLoadTile)        { handleLoadTile() }
      is(sReadBinEntry)    { handleReadBinEntry() }
      is(sWaitBinEntry)    { handleWaitBinEntry() }
      is(sLoadTriSetup)    { handleLoadTriSetup() }
      is(sLoadCovDelta)    { handleLoadCovDelta() }
      is(sEnqueueTile)     { handleEnqueueTile() }
      is(sIteratePixels)   { handleIteratePixels() }
      is(sWaitRast)        { handleWaitRast() }
      is(sNextBinTri)      { handleNextBinTri() }
      is(sWaitFlush)       { handleWaitFlush() }
      is(sWaitFlushSync)   { handleWaitFlushSync() }
      is(sNextRenderTile)  { handleNextRenderTile() }
      is(sPlayState)       { when(io.playDone) { state := sLoadFragShader } }
      is(sResumeTri)       { uniformPage := savedPage; resumeTri := false.B; state := sEnqueueTile }
    }
  }

  private def handleIdle(): Unit = {
    when(io.start) {
      tagReg(0)   := "hFFFF".U
      tagReg(1)   := "hFFFF".U
      cacheVictim := 0.U
      curState    := 0.U
      resumeTri   := false.B
      // A render list loads each state's fragment shader at its first triangle.
      state       := Mux(io.listMode, sStartPass2, sLoadRastShader)
    }
  }

  private def handleWaitDMA(): Unit = {
    when(!io.dma.busy) {
      state := nextAfterDMA
    }
  }

  private def handleLoadRastShader(): Unit = {
    // The raster shader is a ROM (BorgRasterRom); nothing to load.
    state := sLoadFragShader
  }

  private def handleLoadFragShader(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.fragShaderAddr
    desc.length   := io.mmio.fragShaderLen
    desc.dest     := 0.U  // dest=0 -> IMEM
    desc.offset   := 1.U  // BORG_IMEM_FRAG_OFFSET

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sLoadFsConst
    constPage    := 0.U
    state        := sWaitDMA
  }

  /** The fragment constant window, into every uniform page the setup cache can
    * select, once per render; the record DMA only writes the words below it. */
  private def handleLoadFsConst(): Unit = {
    val first = BorgSetupRom.Record.FsConstFirst
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.fsConstBase
    desc.length   := (32 - first).U
    desc.dest     := 1.U                      // uniforms, page = uniformPage
    desc.offset   := first.U
    uniformPage   := constPage
    val last = constPage === (if (cfg.maxUniforms > 32) 1 else 0).U
    constPage := constPage + 1.U
    val afterFs = Mux(resumeTri, sResumeTri, sStartPass2)
    when(io.mmio.fsConstBase === 0.U) {
      state := afterFs
    }.otherwise {
      dmaDescReg   := desc
      io.dma.desc  := desc
      io.dma.start := true.B
      nextAfterDMA := Mux(last, afterFs, sLoadFsConst)
      state        := sWaitDMA
    }
  }

  private def handleStartPass2(): Unit = {
    tileX := 0.U
    tileY := 0.U
    if (BorgDebug.trace) printf("[SEQ] Pass2 start buf=%d\n", io.curBufIdx)
    val clearColor = io.mmio.clearColorHi ## io.mmio.clearColorLo
    colorChanged := clearColor =/= lastClearColorBuf(io.curBufIdx)
    lastClearColorBuf(io.curBufIdx) := clearColor
    readTile(0.U)
    state := sReadBinCount
  }

  private def handleReadBinCount(): Unit = {
    attPass := 0.U
    // SyncReadMem data is valid only this cycle, while readEn was high.
    binTriCount := io.binner.countReadData
    // Latch last render's dirty bit, write this render's.
    val tileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    tileWasDirty := dirtyReadData || colorChanged
    // TILE_LOAD.keep: an empty tile keeps what its attachments hold, so it is
    // skipped whole and its dirty bit stays what the last full render left.
    val keepTile = io.mmio.tileKeep && io.binner.countReadData === 0.U
    when(!keepTile) {
      tileDirtyMem.write(Cat(io.curBufIdx, tileLinear.pad(dirtyAddrBits)(dirtyAddrBits - 1, 0)),
                         io.binner.countReadData =/= 0.U)
    }
    if (BorgDebug.trace) printf("[SEQ] tile(%d,%d) binCount=%d\n",
      tileX >> 2, tileY >> 2, io.binner.countReadData)
    binTriIdx     := 0.U
    clearCounter  := 0.U
    state         := Mux(keepTile, sNextRenderTile, sClearTile)
  }

  /** Load the tile's attachments (BorgTileLoader), then continue as a clear,
    * except that an empty tile always flushes: the dirty-tile skip assumes
    * DRAM already holds the clear colour. */
  private def handleLoadTile(): Unit = {
    clearTileComplete := true.B
    when(!loadStarted) {
      io.flusher.loadStart := true.B
      loadStarted := true.B
    }.elsewhen(!io.flusher.loadBusy && !io.flusher.loadStart) {
      clearTileComplete := false.B
      when(binTriCount === 0.U) {
        state := sWaitFlush
      }.otherwise {
        state := sReadBinEntry
      }
    }
  }

  private def handleClearTile(): Unit = {
    // 1 pulse + 16 BRAM clear writes + 1 register = 18 cycles; clearTileComplete
    // stays high to mask the previous tile's stale tileComplete.
    clearTileComplete := true.B
    when(clearCounter === 0.U) {
      io.iter.clear := true.B
      clearCounter  := clearCounter + 1.U
    }.elsewhen(clearCounter < 18.U) {
      clearCounter := clearCounter + 1.U
    }.elsewhen(io.mmio.tileLoad) {
      // loadOp = LOAD: the clear set every aspect; now load the ones that load.
      clearTileComplete := true.B
      loadStarted       := false.B
      state             := sLoadTile
    }.otherwise {
      clearTileComplete := false.B
      // A clean empty tile already holds the clear colour in DRAM.
      when(binTriCount === 0.U) {
        // Multiple colour attachments always flush.
        when(io.mmio.attCount > 1.U || tileWasDirty) {
          state := sWaitFlush
        }.otherwise {
          state := sNextRenderTile
        }
      }.otherwise {
        state := sReadBinEntry
      }
    }
  }

  private def handleReadBinEntry(): Unit = {
    val tileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    // Firmware keeps the bin table inside the GPU address space.
    val entryAddr  = (io.mmio.binBase + (tileLinear * io.mmio.binRowBytes) + (binTriIdx << 1))(GpuMemIO.AddrBits - 1, 0)
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := entryAddr
    desc.length   := 1.U  // uint16 entry in the low half
    desc.dest     := 2.U  // snoop only
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sWaitBinEntry
    state        := sWaitDMA
  }

  private def handleWaitBinEntry(): Unit = {
    // Bins are in triangle order: the first entry at the limit ends the tile's list.
    state := Mux(binEntryData >= io.triLimit, sWaitFlush, sLoadTriSetup)
  }

  private def handleLoadTriSetup(): Unit = {
    // A hit points the core's uniform read page at the resident setup and skips
    // the DMA; two entries catch the tile-major A,B,A interleaving.
    when(binEntryData === tagReg(0)) {
      if (BorgDebug.trace) printf("[SEQ] loadTriSetup HIT page0 triIdx=%d\n", binEntryData)
      uniformPage := 0.U
      triState := stateCache(0)
      triIsBackFacing := backFacingReg(0)
      covDeltaActive.foreach(_ := covDeltaCache.get(0))
      topLeftActive := topLeftCache(0)
      state       := sEnqueueTile
    }.elsewhen(binEntryData === tagReg(1) && (setupPages > 1).B) {
      if (BorgDebug.trace) printf("[SEQ] loadTriSetup HIT page1 triIdx=%d\n", binEntryData)
      uniformPage := 1.U
      triState := stateCache(1)
      triIsBackFacing := backFacingReg(1)
      covDeltaActive.foreach(_ := covDeltaCache.get(1))
      topLeftActive := topLeftCache(1)
      state       := sEnqueueTile
    }.otherwise {
      val victim = if (setupPages > 1) cacheVictim else 0.U
      // covDelta comes in a second snoop-only transfer: BorgDMA's uniform
      // destination is 5 bits, so appending it would alias the edge planes.
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.mmio.setupBase + (binEntryData << setupStrideShift)
      // 16-word uniform image, meta last; the constant window is above it.
      desc.length   := BorgSetupRom.Record.Image.U
      desc.dest     := 1.U   // uniforms, page = uniformPage
      desc.offset   := 0.U

      if (BorgDebug.trace) printf("[SEQ] loadTriSetup MISS triIdx=%d -> page%d addr=0x%x\n",
        binEntryData, victim, io.mmio.setupBase + (binEntryData << setupStrideShift))

      tagReg(victim) := binEntryData
      uniformPage    := victim
      cacheVictim    := ~victim
      dmaDescReg     := desc
      io.dma.desc    := desc
      io.dma.start   := true.B
      nextAfterDMA   := (if (cfg.samples > 1) sLoadCovDelta else sEnqueueTile)
      setupLoadIdx   := 0.U
      state          := sWaitDMA
    }
  }

  private def setupStrideShift: UInt = io.mmio.recordShift

  /** MSAA only: the missed triangle's covDelta, snooped (dest=2) past the
    * uniform buffer. */
  private def handleLoadCovDelta(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.setupBase + (binEntryData << setupStrideShift) + 128.U  // word 32 = byte 128
    desc.length   := covDeltaWords.U
    desc.dest     := 2.U  // snoop only
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sEnqueueTile
    setupLoadIdx := 0.U
    state        := sWaitDMA
  }

  private def handleEnqueueTile(): Unit = {
    when(io.listMode && triState =/= curState) {
      // Another draw's state: play its block, load its fragment shader and
      // constants, come back. sNextBinTri drained the dispatcher first.
      io.playReq := true.B
      curState   := triState
      savedPage  := uniformPage
      resumeTri  := true.B
      state      := sPlayState
    }.otherwise {
      io.iter.enqueue.valid := true.B
      if (BorgDebug.trace) printf("[SEQ] sEnqueueTile tileX=%d tileY=%d\n", tileX, tileY)
      state := sIteratePixels
    }
  }

  private def handleIteratePixels(): Unit = {
    io.iter.iterate := true.B
    state := sWaitRast
  }

  private def handleWaitRast(): Unit = {
    when(!io.iter.stall && !tileCompleteLatch) {
      io.iter.iterate := true.B
    }
    when(tileCompleteLatch) {
      state := sNextBinTri
    }
  }

  private def handleNextBinTri(): Unit = {
    // The last pixel may still read the uniforms the next DMA overwrites.
    when(io.iter.dispatcherIdle) {
      val nextBinIdx = binTriIdx + 1.U
      when(nextBinIdx < binTriCount) {
        binTriIdx := nextBinIdx
        clearTileComplete := true.B
        state := sReadBinEntry
      }.otherwise {
        state := sWaitFlush
      }
    }
  }

  private def handleWaitFlush(): Unit = {
    // The last pixel may still be in flight to the tile buffer.
    when(io.iter.dispatcherIdle) {
      io.flusher.trigger := true.B
      state := sWaitFlushSync
    }
  }

  private def handleWaitFlushSync(): Unit = {
    // The tile again for the next colour attachment.
    when(!io.flusher.busy) {
      when(attPass =/= io.mmio.attCount - 1.U) {
        attPass := attPass + 1.U
        binTriIdx    := 0.U
        clearCounter := 0.U
        state        := sClearTile
      }.otherwise {
        state := sNextRenderTile
      }
    }
  }

  /** Start a tile's two reads, both valid in sReadBinCount: its bin count
    * (BorgBinner's count RAM) and its dirty bit in the current buffer. */
  private def readTile(tileLinear: UInt): Unit = {
    val tile = tileLinear.pad(dirtyAddrBits)(dirtyAddrBits - 1, 0)
    io.binner.countReadAddr := tile
    io.binner.countReadEn   := true.B
    dirtyReadAddr := Cat(io.curBufIdx, tile)
    dirtyReadEn   := true.B
  }

  private def handleNextRenderTile(): Unit = {
    val nextTileX = (tileX >> 2) + 1.U
    when(nextTileX >= io.mmio.fbWidthTiles) {
      tileX := 0.U
      val nextTileY = (tileY >> 2) + 1.U
      when(nextTileY >= io.mmio.fbHeightTiles) {
        io.done := true.B
        state   := sIdle
      }.otherwise {
        tileY := nextTileY << 2
        readTile(nextTileY * io.mmio.tilesPerRow)
        state := sReadBinCount
      }
    }.otherwise {
      tileX := nextTileX << 2
      readTile(((tileY >> 2) * io.mmio.tilesPerRow) + nextTileX)
      state := sReadBinCount
    }
  }

  private def wireSnoops(): Unit = {
    when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sWaitBinEntry) {
      binEntryData := io.dma.snoop.bits(15, 0)
    }

    // The record's uniform image: top-left flags, facing and state block.
    val firstTransferTarget = if (cfg.samples > 1) sLoadCovDelta else sEnqueueTile
    when(io.dma.uniformSnoop.en && state === sWaitDMA && nextAfterDMA === firstTransferTarget) {
      setupLoadIdx := setupLoadIdx + 1.U
      // Edge plane k's a (word 3k) and b (3k + 1): the top-left flags.
      val d = io.dma.uniformSnoop.data
      val neg = d(cfg.totalBits - 1); val zero = d(cfg.totalBits - 2, 0) === 0.U
      for (k <- 0 until 3) {
        when(setupLoadIdx === (3 * k).U) { planeA(k) := Cat(neg, zero) }
        when(setupLoadIdx === (3 * k + 1).U) {
          val aNeg = planeA(k)(1); val aZero = planeA(k)(0)
          val tl = (!aNeg && !aZero) || (aZero && !neg && !zero)
          topLeftCache(uniformPage)(k) := tl
          topLeftActive(k) := tl
        }
      }
      when(setupLoadIdx === BorgSetupRom.Record.Meta.U) {
        triIsBackFacing := io.dma.uniformSnoop.data(1)
        triState := io.dma.uniformSnoop.data(GpuMemIO.AddrBits - 1, 2)
        stateCache(uniformPage) := io.dma.uniformSnoop.data(GpuMemIO.AddrBits - 1, 2)
        backFacingReg(uniformPage) := io.dma.uniformSnoop.data(1)
        if (BorgDebug.trace) printf("[SEQ] pass2 meta triIdx=%d backFacing=%d\n",
          binEntryData, io.dma.uniformSnoop.data(1))
      }
    }

    // covDelta: dest=2 never drives uniformSnoop, so read the general snoop tap.
    covDeltaCache.foreach { cache =>
      when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sEnqueueTile &&
           setupLoadIdx < covDeltaWords.U) {
        // Full datapath width: a 16-bit slice zeroes FP32 sample thresholds.
        val data = io.dma.snoop.bits(cfg.totalBits - 1, 0)
        val idx  = setupLoadIdx(2, 0)
        cache(uniformPage)(idx) := data
        covDeltaActive.get(idx) := data
        setupLoadIdx := setupLoadIdx + 1.U
        if (BorgDebug.trace) printf("[SEQ] pass2 covDelta triIdx=%d word=%d data=0x%x\n",
          binEntryData, setupLoadIdx, data)
      }
    }
  }
}
