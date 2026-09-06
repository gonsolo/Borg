// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgTileSequencer — Pass 2 of BorgSequencer's two-pass triangle-batch
  * render (see BorgSequencer's own class doc for the split's rationale).
  *
  * Reloads the fragment shader, then walks every tile in the framebuffer:
  * reads the tile's triangle bin list and per-triangle setup back from DRAM
  * (written by BorgGeometrySequencer's Pass 1, via a small 2-way associative
  * cache over the two uniform pages), rasterizes, and flushes. Never reads
  * Pass 1's live registers -- only DRAM and `curBufIdx` (owned by the
  * top-level BorgSequencer wrapper, since it must persist across whole
  * frames, not just this one pass).
  *
  * FSM (18 states): sIdle -> sWaitDMA (frag shader reload) -> sStartPass2 ->
  * per tile: sReadBinCount -> sClearTile -> (if the tile has triangles)
  * sReadBinEntry -> sWaitDMA -> sLoadTriSetup -> [sWaitDMA -> sLoadCovDelta ->
  * sWaitDMA, on a setup-cache miss] -> sEnqueueTile -> sIteratePixels ->
  * sWaitRast -> (repeat sReadBinEntry.. for each triangle in the bin) ->
  * sWaitFlush -> sWaitFlushSync -> sNextRenderTile -> (repeat sReadBinCount..
  * for each tile, or pulse `done` and return to sIdle once the whole
  * framebuffer is covered).
  */
class BorgTileSequencerIO(val cfg: BorgConfig) extends Bundle {
  val start = Input(Bool())
  val done  = Output(Bool())
  val busy  = Output(Bool())
  // Which framebuffer is being rendered this frame -- owned by the wrapper
  // (toggled once per whole frame, on its own done), read-only here since
  // this pass's per-buffer dirty-bit arrays are indexed by it.
  val curBufIdx = Input(UInt(1.W))

  val mmio    = new SeqMmioIO(cfg)
  val binner  = new SeqBinnerIO(cfg)  // only countReadAddr/countReadEn/countReadData used
  val flusher = new SeqFlusherIO
  val iter    = new SeqIteratorIO(cfg.coordWidth)
  val dma     = new SeqDmaIO

  val covDelta = if (cfg.samples > 1)
    Some(Output(Vec(3, Vec(2, UInt(cfg.totalBits.W))))) else None
  val texEnOverride = Output(Bool())

  val uniformWrite     = new MemWritePort(6, 16)
  val uniformWritePage = Output(UInt(1.W))
}

class BorgTileSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgTileSequencerIO(cfg))

  val nStates = 18
  val states = Enum(nStates)
  val (sIdle :: sWaitDMA :: sLoadRastShader :: sLoadFragShader :: sStartPass2 ::
       sReadBinCount :: sClearTile ::
       sReadBinEntry :: sWaitBinEntry :: sLoadTriSetup :: sLoadCovDelta ::
       sEnqueueTile :: sIteratePixels :: sWaitRast :: sWaitFlush :: sWaitFlushSync ::
       sNextBinTri :: sNextRenderTile :: Nil) = states
  val state = RegInit(sIdle)

  val nextAfterDMA = RegInit(sIdle)

  val tileX = RegInit(0.U(cfg.coordWidth.W))
  val tileY = RegInit(0.U(cfg.coordWidth.W))

  val binTriIdx    = RegInit(0.U(10.W))  // current index into tile's bin list
  val binTriCount  = RegInit(0.U(10.W))  // number of triangles in current tile's bin
  val binEntryData = RegInit(0.U(16.W))  // triangle index read from DRAM bin list
  // writeIdx: sClearTile's own 0-18 clear-cycle counter. (Named independently
  // of Pass 1's uniform-staging writeIdx -- the two counters are unrelated,
  // reusing one flat register across both was a pre-split economy that no
  // longer applies once each pass is its own module.)
  val clearCounter = RegInit(0.U(5.W))
  // setupLoadIdx: tracks DMA word count during sLoadTriSetup (for the has_uvs
  // snoop) and is reused for the covDelta snoop in sLoadCovDelta.
  val setupLoadIdx = RegInit(0.U(6.W))

  // 2-entry associative setup cache over the two uniform pages. Each tile
  // this frame re-reads every covering triangle's 128B setup once; tagReg(p)
  // = the triangle index resident in page p (0xFFFF=invalid), uvsReg(p) =
  // its has_uvs flag, cacheVictim = round-robin replacement pointer (2-way
  // == LRU). Reset when this pass starts (io.start), not at the top-level
  // frame-idle boundary -- Pass 1 doesn't use this cache at all, so there is
  // no reason for its reset to live outside this module.
  val tagReg      = RegInit(VecInit(Seq.fill(2)("hFFFF".U(16.W))))
  val uvsReg      = RegInit(VecInit(Seq.fill(2)(false.B)))
  val cacheVictim = RegInit(0.U(1.W))
  val covDeltaCache = if (cfg.samples > 1)
    Some(RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(6)(0.U(16.W))))))) else None
  // covDeltaCache(uniformPage) is only safe to read AT SELECTION TIME: the
  // triangle actually in flight through the downstream pixel pipeline
  // (BorgIterator/BorgShaderDispatcher) can still be draining pixels for
  // several cycles after this sequencer has already moved on to the next
  // triangle and changed uniformPage. A live Mux on uniformPage would then
  // serve the wrong triangle's covDelta to those in-flight pixels.
  // covDeltaActive is latched exactly once per triangle selection (hit:
  // immediately in handleLoadTriSetup; miss: as each word arrives from the
  // DMA snoop) and held stable until the next selection -- same pattern as
  // triHasUvs, which is a real register instead of a live uvsReg(page) read.
  val covDeltaActive = if (cfg.samples > 1)
    Some(RegInit(VecInit(Seq.fill(6)(0.U(16.W))))) else None

  // Per-triangle has_uvs flag for the triangle currently selected by
  // handleLoadTriSetup -- restored from the cache on a hit, or reloaded from
  // DRAM (written by Pass 1's sStoreSetup) on a miss. Independent of Pass 1's
  // own same-named register: that one only ever lives between Pass 1's own
  // sLoadBBox and sStoreSetup for the triangle currently being set up.
  val triHasUvs = RegInit(false.B)

  val uniformPage = RegInit(0.U(1.W))

  // Registered tileComplete: breaks the combinational loop
  //   advance -> tileComplete (comb) -> iteratePixels -> advance.
  // One cycle lag is harmless: the pipeline takes >>1 cycles per pixel.
  val clearTileComplete = RegInit(false.B)
  val tileCompleteLatch = RegInit(false.B)
  when(clearTileComplete) {
    tileCompleteLatch := false.B
    clearTileComplete := false.B
  }.otherwise {
    tileCompleteLatch := io.iter.complete
  }

  // Per-tile dirty-bit tracking for skip-empty-tile flush optimisation.
  // Double-buffer aware: indexed by [curBufIdx][tileLinear].
  // tileWasDirty(b)(i): tile i had content last time buffer b was rendered.
  // tileIsDirty(b)(i):  tile i has content in the current render of buffer b.
  // Initialised all-true so every tile is flushed the first time each buffer
  // is rendered (SDRAM uninitialised at reset).
  // lastClearColorBuf(b): clear colour used when buffer b was last rendered;
  // sentinel ~0 forces a full-flush on the first render of each buffer.
  val tileWasDirty      = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(cfg.maxBinTiles)(true.B)))))
  val tileIsDirty       = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(cfg.maxBinTiles)(false.B)))))
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
      for (e <- 0 until 3; k <- 0 until 2) {
        cd(e)(k) := active(2 * e + k)
      }
    }
    io.texEnOverride := triHasUvs

    io.dma.start := false.B
    io.dma.desc  := dmaDescReg

    io.uniformWrite.en   := false.B
    io.uniformWrite.addr := 0.U
    io.uniformWrite.data := 0.U
    io.uniformWritePage  := uniformPage

    io.iter.clear         := false.B
    io.iter.enqueue.valid := false.B
    io.iter.enqueue.bits.x := tileX
    io.iter.enqueue.bits.y := tileY
    io.iter.iterate       := false.B
    io.flusher.trigger    := false.B
    // tileBase = fbBase + ((tileY / 4) * tilesPerRow + (tileX / 4)) * 32
    // (RGB565: 16 pixels x 2 bytes = 32 bytes per tile)
    val tileIndex = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    io.flusher.base := io.mmio.fbBase + (tileIndex << 5)

    // Not used by this sub-FSM: BorgGeometrySequencer owns the write side of
    // SeqBinnerIO. Tied off rather than split into a bespoke bundle, to keep
    // this restructuring's diff mechanical and reviewable.
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
      is(sStartPass2)      { handleStartPass2() }
      is(sReadBinCount)    { handleReadBinCount() }
      is(sClearTile)       { handleClearTile() }
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
    }
  }

  private def handleIdle(): Unit = {
    when(io.start) {
      tagReg(0)   := "hFFFF".U    // invalidate 2-entry setup cache for new frame
      tagReg(1)   := "hFFFF".U
      cacheVictim := 0.U
      state       := sLoadRastShader
    }
  }

  private def handleWaitDMA(): Unit = {
    when(!io.dma.busy) {
      state := nextAfterDMA
    }
  }

  private def handleLoadRastShader(): Unit = {
    // The rasterizer edge-test shader is a permanent hardware ROM
    // (BorgRasterRom, fetched directly by BorgCore) -- it no longer lives in
    // the writable IMEM, so there is nothing to DMA here. Go straight to
    // loading the fragment shader.
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
    nextAfterDMA := sStartPass2
    state        := sWaitDMA
  }

  private def handleStartPass2(): Unit = {
    tileX := 0.U
    tileY := 0.U
    if (BorgDebug.trace) printf("[SEQ] Pass2 start buf=%d\n", io.curBufIdx)
    // Rotate per-buffer dirty-bit arrays for the buffer being rendered now.
    // If the clear colour changed vs the last time THIS buffer was rendered,
    // treat ALL tiles as dirty so every empty tile picks up the new colour.
    val clearColor   = io.mmio.clearColorHi ## io.mmio.clearColorLo
    val colorChanged = clearColor =/= lastClearColorBuf(io.curBufIdx)
    lastClearColorBuf(io.curBufIdx) := clearColor
    for (i <- 0 until cfg.maxBinTiles) {
      tileWasDirty(io.curBufIdx)(i) := tileIsDirty(io.curBufIdx)(i) || colorChanged
      tileIsDirty(io.curBufIdx)(i)  := false.B
    }
    // Issue count read for tile (0,0) = tile index 0
    io.binner.countReadAddr := 0.U
    io.binner.countReadEn   := true.B
    state := sReadBinCount
  }

  private def handleReadBinCount(): Unit = {
    // SyncReadMem data is valid NOW (1 cycle after read was issued in
    // sStartPass2/sNextRenderTile). Capture it immediately -- the output
    // goes undefined on the next cycle when readEn drops.
    binTriCount := io.binner.countReadData
    if (BorgDebug.trace) printf("[SEQ] tile(%d,%d) binCount=%d\n",
      tileX >> 2, tileY >> 2, io.binner.countReadData)
    binTriIdx     := 0.U
    clearCounter  := 0.U
    state         := sClearTile
  }

  private def handleClearTile(): Unit = {
    // Pulse iter.clear for exactly one cycle (clearCounter=0), then wait for
    // BorgTileBuffer to finish its 16-cycle BRAM clear sequence.
    // Total wait: 1 (pulse) + 16 (BRAM writes) + 1 (register pipeline) = 18 cycles.
    // Keep clearTileComplete high throughout to suppress any stale
    // tileComplete from the previous tile's iterator.
    clearTileComplete := true.B
    when(clearCounter === 0.U) {
      io.iter.clear := true.B
      clearCounter  := clearCounter + 1.U
    }.elsewhen(clearCounter < 18.U) {
      clearCounter := clearCounter + 1.U
    }.otherwise {
      clearTileComplete := false.B
      // After clear: if this tile has triangles, start the inner bin loop.
      // Otherwise, only flush the clear colour if the tile was dirty last
      // frame (had rendered content) -- clean empty tiles already hold the
      // right value in DRAM, so we can skip the 64-word SDRAM write entirely.
      val tileLinearCC = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
      when(binTriCount === 0.U) {
        when(tileWasDirty(io.curBufIdx)(tileLinearCC(log2Ceil(cfg.maxBinTiles) - 1, 0))) {
          state := sWaitFlush        // was dirty: must write clear colour to DRAM
        }.otherwise {
          state := sNextRenderTile   // already clean: skip flush
        }
      }.otherwise {
        // Read first bin entry (triangle index) from DRAM
        // addr = binBase + tileLinearIndex * binRowBytes + binTriIdx * 2
        tileIsDirty(io.curBufIdx)(tileLinearCC(log2Ceil(cfg.maxBinTiles) - 1, 0)) := true.B
        state := sReadBinEntry
      }
    }
  }

  private def handleReadBinEntry(): Unit = {
    val tileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    // Truncated to DMADescriptor.baseAddr's fixed 25-bit/32MB GPU address
    // space (same constant used throughout this file). Unlike mmioAddr/
    // w_addr in BorgLane, this bound isn't provable from the RTL alone -- it
    // relies on firmware keeping the triangle-bin table within the 32MB
    // budget. Chisel's generic width growth through the multiply/add chain
    // conservatively exceeds 25 bits even though real addresses stay in
    // range; unchanged from this design's behavior before this file split.
    val entryAddr  = (io.mmio.binBase + (tileLinear * io.mmio.binRowBytes) + (binTriIdx << 1))(24, 0)
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := entryAddr
    desc.length   := 1.U  // 1 word (bin entry = uint16, stored in low half of 32b word)
    desc.dest     := 2.U  // snoop only -- data captured in DMA snoop handler below
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sWaitBinEntry
    state        := sWaitDMA
  }

  private def handleWaitBinEntry(): Unit = {
    state := sLoadTriSetup
  }

  private def handleLoadTriSetup(): Unit = {
    // 2-entry associative setup cache: if this triangle's setup is resident
    // in either uniform page, point the frag at that page (uniformPage
    // drives the core's uniform read page while this sequencer is busy) and
    // skip the DMA. This catches the tile-major A,B,A interleaving a 1-entry
    // cache would miss. On a miss, DMA into the round-robin victim page and
    // update its tag.
    when(binEntryData === tagReg(0)) {
      if (BorgDebug.trace) printf("[SEQ] loadTriSetup HIT page0 triIdx=%d\n", binEntryData)
      uniformPage := 0.U
      triHasUvs   := uvsReg(0)
      covDeltaActive.foreach(_ := covDeltaCache.get(0))
      state       := sEnqueueTile
    }.elsewhen(binEntryData === tagReg(1)) {
      if (BorgDebug.trace) printf("[SEQ] loadTriSetup HIT page1 triIdx=%d\n", binEntryData)
      uniformPage := 1.U
      triHasUvs   := uvsReg(1)
      covDeltaActive.foreach(_ := covDeltaCache.get(1))
      state       := sEnqueueTile
    }.otherwise {
      val victim = cacheVictim
      // Base address must match BorgGeometrySequencer's write-side stride
      // exactly. Length stays fixed at the original 32 words (31 uniforms +
      // has_uvs): that destination is the real, capacity-limited on-chip
      // uniform memory (BorgDMA's destIdx is truncated to 5 bits, exactly 32
      // slots), so covDelta -- consumed only by this sequencer, never by the
      // shader core -- is fetched by a SEPARATE, second, snoop-only transfer
      // (sLoadCovDelta) instead of being appended here, where it would
      // silently alias back onto and corrupt slots 0-5 (this triangle's real
      // edge-coefficient uniforms).
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.mmio.setupBase + (binEntryData << setupStrideShift)
      desc.length   := 32.U  // 31 uniforms + 1 has_uvs flag
      desc.dest     := 1.U   // uniform write; page = uniformPage(:=victim) via DMA
      desc.offset   := 0.U

      if (BorgDebug.trace) printf("[SEQ] loadTriSetup MISS triIdx=%d -> page%d addr=0x%x\n",
        binEntryData, victim, io.mmio.setupBase + (binEntryData << setupStrideShift))

      tagReg(victim) := binEntryData
      uniformPage    := victim        // frag reads from the victim page
      cacheVictim    := ~victim       // round-robin replacement (2-way == LRU)
      dmaDescReg     := desc
      io.dma.desc    := desc
      io.dma.start   := true.B
      nextAfterDMA   := (if (cfg.samples > 1) sLoadCovDelta else sEnqueueTile)
      setupLoadIdx   := 0.U
      state          := sWaitDMA
    }
  }

  private def setupStrideShift = if (cfg.samples > 1) 8 else 7

  /** Second, snoop-only DMA transfer fetching covDelta (6 words) for the
    * triangle just loaded by handleLoadTriSetup's miss path. Only reached
    * when cfg.samples > 1 (nextAfterDMA only ever targets this state in that
    * case). dest=2 means BorgDMA does not write any destination memory -- the
    * data is captured purely via the general io.dma.snoop tap below,
    * avoiding the capacity-limited uniform buffer entirely. */
  private def handleLoadCovDelta(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.setupBase + (binEntryData << setupStrideShift) + 128.U  // word 32 = byte 128
    desc.length   := 6.U
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
    io.iter.enqueue.valid := true.B
    if (BorgDebug.trace) printf("[SEQ] sEnqueueTile tileX=%d tileY=%d\n", tileX, tileY)
    state := sIteratePixels
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
    // Wait for dispatcher pipeline to drain before loading next triangle's
    // uniforms. The last pixel of the previous triangle may still be
    // executing in the shader pipeline, reading from the uniform buffer. If
    // the DMA overwrites uniforms now, the shader gets corrupted data
    // ("slot 15 race").
    when(io.iter.dispatcherIdle) {
      val nextBinIdx = binTriIdx + 1.U
      when(nextBinIdx < binTriCount) {
        binTriIdx := nextBinIdx
        clearTileComplete := true.B
        // Don't re-clear tile buffer -- fragments accumulate on top of clear color
        state := sReadBinEntry
      }.otherwise {
        state := sWaitFlush
      }
    }
  }

  private def handleWaitFlush(): Unit = {
    // Wait for dispatcher pipeline to drain (last pixel may still be in
    // flight) before triggering the flusher. This prevents the race where
    // the flusher reads slot 15 before the dispatcher writes it (the "last
    // pixel race").
    when(io.iter.dispatcherIdle) {
      io.flusher.trigger := true.B
      state := sWaitFlushSync
    }
  }

  private def handleWaitFlushSync(): Unit = {
    when(!io.flusher.busy) {
      state := sNextRenderTile
    }
  }

  private def handleNextRenderTile(): Unit = {
    val nextTileX = (tileX >> 2) + 1.U
    when(nextTileX >= io.mmio.fbWidthTiles) {
      tileX := 0.U
      val nextTileY = (tileY >> 2) + 1.U
      when(nextTileY >= io.mmio.fbHeightTiles) {
        // Whole framebuffer covered. Pulses `done` combinationally in the
        // same cycle (matches the original design's overall completion
        // timing -- see BorgSequencer's wrapper for why the exact cycle
        // count here has no external observer), then returns to sIdle.
        io.done := true.B
        state   := sIdle
      }.otherwise {
        tileY := nextTileY << 2
        val nextTileLinear = nextTileY * io.mmio.tilesPerRow
        io.binner.countReadAddr := nextTileLinear(log2Ceil(cfg.maxBinTiles) - 1, 0)
        io.binner.countReadEn   := true.B
        state := sReadBinCount
      }
    }.otherwise {
      tileX := nextTileX << 2
      val nextTileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + nextTileX
      io.binner.countReadAddr := nextTileLinear(log2Ceil(cfg.maxBinTiles) - 1, 0)
      io.binner.countReadEn   := true.B
      state := sReadBinCount
    }
  }

  private def wireSnoops(): Unit = {
    // --- Snoop bin entry data ---
    when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sWaitBinEntry) {
      binEntryData := io.dma.snoop.bits(15, 0)
    }

    // --- Snoop has_uvs flag from setup store word 31 ---
    // During sLoadTriSetup -> sWaitDMA, the first (dest=1) transfer writes 32
    // words to the uniform buffer. Track the word count via setupLoadIdx and
    // recover triHasUvs from word 31. At samples>1 this transfer's
    // nextAfterDMA target is sLoadCovDelta (not sEnqueueTile) -- covDelta
    // itself is fetched by a separate dest=2 transfer, see below.
    val firstTransferTarget = if (cfg.samples > 1) sLoadCovDelta else sEnqueueTile
    when(io.dma.uniformSnoop.en && state === sWaitDMA && nextAfterDMA === firstTransferTarget) {
      setupLoadIdx := setupLoadIdx + 1.U
      when(setupLoadIdx === 31.U) {
        triHasUvs := io.dma.uniformSnoop.data(0)
        // Remember has_uvs for the page just loaded (= current uniformPage)
        // so a later cache hit on this triangle restores it without
        // re-reading setup.
        uvsReg(uniformPage) := io.dma.uniformSnoop.data(0)
        if (BorgDebug.trace) printf("[SEQ] pass2 hasUvs triIdx=%d flag=%d\n", binEntryData, io.dma.uniformSnoop.data(0))
      }
    }

    // --- Snoop covDelta (6 words) from the second, snoop-only (dest=2)
    // transfer issued by handleLoadCovDelta ---
    // dest=2 never touches io.dma.uniformSnoop (that signal is wired
    // directly from the destination uniformWrite port, which dest=2 never
    // drives) -- must read the general io.dma.snoop tap instead, which fires
    // for every DMA read regardless of destination. setupLoadIdx is reused
    // as a fresh 0-5 counter here (reset by handleLoadCovDelta).
    covDeltaCache.foreach { cache =>
      when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sEnqueueTile &&
           setupLoadIdx < 6.U) {
        val data = io.dma.snoop.bits(15, 0)
        val idx  = setupLoadIdx(2, 0)
        cache(uniformPage)(idx) := data  // persist for future hits
        covDeltaActive.get(idx) := data  // and use immediately for this triangle
        setupLoadIdx := setupLoadIdx + 1.U
        if (BorgDebug.trace) printf("[SEQ] pass2 covDelta triIdx=%d word=%d data=0x%x\n",
          binEntryData, setupLoadIdx, data)
      }
    }
  }
}
