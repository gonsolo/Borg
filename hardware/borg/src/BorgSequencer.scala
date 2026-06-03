// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgSequencer IO — Steps 29.1–29.3: vertex + setup + uniform staging.
  *
  * The sequencer orchestrates autonomous vertex shading, triangle setup, and
  * uniform staging by:
  *   1. Loading the vertex shader binary from PSRAM into IMEM via DMA.
  *   2. For each of 3 vertices: loading 8 vertex words (pos+color+uv) into
  *      the uniform buffer via DMA, then triggering BorgCore to run the
  *      vertex shader, snooping clip-space outputs from PipeWriteIO, and
  *      capturing color/z from the DMA write stream into colorRegs.
  *   3. Writing snooped screen-space coordinates into the uniform buffer.
  *   4. Loading the setup shader from PSRAM into IMEM via DMA.
  *   5. Running the setup shader to compute scaled edge vectors and inv_area.
  *   6. Staging all 31 uniform registers for the rasterizer and fragment
  *      shaders (sStageUniforms, replacing CPU's setup_tile_uniforms()).
  *
  * Descriptor layout in PSRAM (3 × borg_vertex_t, stride = 32 bytes):
  *   vertex i at descBase + i*32:
  *     offset  0: pos.x  (FP16 in bits[15:0] of 32-bit PSRAM word)
  *     offset  4: pos.y
  *     offset  8: pos.z
  *     offset 12: color.r
  *     offset 16: color.g
  *     offset 20: color.b
  *     offset 24: uv.u  (for future UV support)
  *     offset 28: uv.v
  *
  * Physical uniform register map (from SPIRB blob parse of shader_blobs.h):
  *   Rasterizer shader (uniform_regs[0..11] = [0..11]):
  *     u0  = e0.dx * inv_width     (scaled edge 0 x)
  *     u1  = e0.dy * inv_width     (scaled edge 0 y)
  *     u2  = e1.dx * inv_width     (scaled edge 1 x)
  *     u3  = e1.dy * inv_width     (scaled edge 1 y)
  *     u4  = e2.dx * inv_width     (scaled edge 2 x)
  *     u5  = e2.dy * inv_width     (scaled edge 2 y)
  *     u6  = -v0.x, u7 = -v0.y    (negated vertex 0 position)
  *     u8  = -v1.x, u9 = -v1.y    (negated vertex 1 position)
  *     u10 = -v2.x, u11 = -v2.y   (negated vertex 2 position)
  *   Fragment shader (uniform_regs[0..18] = [12..30], FTEX layout):
  *     u12 = inv_area
  *     u13-u15 = UV.u of (v2, v1, v0)  — pre-scaled by tex_w in descriptor
  *     u16-u18 = UV.v of (v2, v1, v0)  — pre-scaled by tex_h in descriptor
  *     u19-u21 = color.R of (v2, v1, v0)
  *     u22-u24 = color.G of (v2, v1, v0)
  *     u25-u27 = color.B of (v2, v1, v0)
  *     u28-u30 = z_val   of (v2, v1, v0)
  *
  *  When tex disabled (has_uvs=false): UV words are zero (Morton=0, white texel
  *  returned by dispatcher), giving texel(1,1,1) × vertexColor = vertexColor.
  *
  * The setup shader outputs pre-scaled edge constants to r0-r5 (already
  * multiplied by inv_width = 1/64 for a 64-wide framebuffer).
  */
class SeqMmioIO extends Bundle {
  val start = Input(Bool())
  val descBase = Input(UInt(20.W))
  val vertShaderAddr = Input(UInt(20.W))
  val vertShaderLen = Input(UInt(6.W))
  val setupShaderAddr = Input(UInt(20.W))
  val setupShaderLen = Input(UInt(6.W))
  val seqInvWidth = Input(UInt(16.W))
  val triCount = Input(UInt(5.W))
  val rastShaderAddr = Input(UInt(20.W))
  val rastShaderLen = Input(UInt(6.W))
  val fragShaderAddr = Input(UInt(20.W))
  val fragShaderLen = Input(UInt(6.W))
  val clearColorLo = Input(UInt(32.W))
  val clearColorHi = Input(UInt(32.W))
  val fbBase = Input(UInt(25.W))   // 25b = 32 MB GPU memory address space
  val tilesPerRow = Input(UInt(10.W))
  val binBase = Input(UInt(25.W))
  val binRowBytes = Input(UInt(20.W))  // stride (bytes/tile), not an address
  val setupBase = Input(UInt(25.W))
  val fbWidthTiles = Input(UInt(10.W))
  val fbHeightTiles = Input(UInt(10.W))
}

class SeqBinnerIO extends Bundle {
  val start = Output(Bool())
  val triIndex = Output(UInt(16.W))
  val bbox = Output(new Bbox(10))
  val clearCounts = Output(Bool())
  val busy = Input(Bool())
  val countReadAddr = Output(UInt(10.W))
  val countReadEn = Output(Bool())
  val countReadData = Input(UInt(10.W))
}

class SeqStoreIO extends Bundle {
  val active = Output(Bool())
  val req = Output(Bool())
  val addr = Output(UInt(25.W))   // 25b = 32 MB GPU memory address space
  val wdata = Output(UInt(32.W))
  val ready = Input(Bool())
}

class SeqFlusherIO extends Bundle {
  val base = Output(UInt(25.W))   // 25b = 32 MB GPU memory address space
  val trigger = Output(Bool())
  val busy = Input(Bool())
}

class SeqIteratorIO(val coordWidth: Int) extends Bundle {
  val clear = Output(Bool())
  // Dispatcher pipeline idle signal — true when dispatch FSM is in sIdle.
  // Used to drain the dispatcher before flushing the tile buffer.
  val dispatcherIdle = Input(Bool())
  val enqueue = Valid(new Coord(coordWidth))
  val iterate = Output(Bool())
  val complete = Input(Bool())
  val stall = Input(Bool())
}

class SeqDmaIO extends Bundle {
  val start = Output(Bool())
  val desc = Output(new DMADescriptor)
  val busy = Input(Bool())
  val snoop = Flipped(Valid(UInt(32.W)))
  val uniformSnoop = Flipped(new MemWritePort(3, 16))
}

class BorgSequencerIO(val cfg: BorgConfig) extends Bundle {
  val mmio = new SeqMmioIO
  val binner = new SeqBinnerIO
  val store = new SeqStoreIO
  val flusher = new SeqFlusherIO
  val iter = new SeqIteratorIO(cfg.coordWidth)
  val dma = new SeqDmaIO

  val busy = Output(Bool())
  val done = Output(Bool())
  val seqShaderActive = Output(Bool())
  val debugState = Output(UInt(6.W))
  val debugTileCompleteLatch = Output(Bool())
  // Per-triangle texture enable: true when current triangle has UVs.
  // Driven from descriptor metadata has_uvs flag.
  val texEnOverride = Output(Bool())

  val coreTrigger = new CoreTriggerIO
  val coreStatus = Flipped(new CoreStatusIO)
  val pipeWrite = Flipped(new PipeWriteIO(cfg.totalBits))
  val uniformWrite = new MemWritePort(6, 16)
  val clipOut = Output(Vec(3, Vec(4, UInt(16.W))))
  val setupOut = Output(Vec(8, UInt(16.W)))
  val uniformWritePage = Output(UInt(1.W))
}

/** BorgSequencer — vertex + setup + uniform staging sequencer (Step 29).
  *
  * Step 29.1 FSM (vertex shader sequencing):
  *   sIdle → sLoadShader → sWaitDMA → sLoadVert → sWaitDMA →
  *   sRunVert → sWaitVert → (repeat for 3 vertices)
  *
  * Step 29.2 FSM (triangle setup):
  *   → sWriteSetupInputs → sLoadSetupShader → sWaitDMA →
  *   sRunSetup → sWaitSetup
  *
  * Step 29.3 FSM (uniform staging, replaces CPU's setup_tile_uniforms()):
  *   → sStageUniforms → sDone → sIdle
  *
  * `sStageUniforms` cycles writeIdx from 0 to 30, writing each uniform in
  * turn.  Data sources are determined by writeIdx:
  *   0-5:   setupRegs[writeIdx]               (scaled edge components)
  *   6-11:  FNEG(clipRegs[v][c])              (negated vertex positions)
  *   12:    setupRegs[7]                       (inv_area)
  *   13-21: colorRegs[v][c] (r,g,b per vertex in barycentric order)
  *   22-24: colorRegs[v][3]                   (z_vals per vertex)
  *   25-30: 0                                 (UVs — future work)
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  // --- FSM states ---
  // Pass 1 (geometry): sIdle, sLoadShader, sWaitDMA, sLoadMVP, sLoadVert, sRunVert, sWaitVert,
  //   sWriteSetupInputs, sLoadSetupShader, sRunSetup, sWaitSetup,
  //   sLoadBBox, sBinTri, sWaitBinner, sStageUniforms, sStoreSetup, sNextTriangle
  // Pass 2 (tile render): sLoadRastShader, sLoadFragShader, sStartPass2,
  //   sReadBinCount, sWaitBinCount, sClearTile,
  //   sReadBinEntry, sWaitBinEntry, sLoadTriSetup,
  //   sEnqueueTile, sIteratePixels, sWaitRast, sWaitFlush, sWaitFlushSync,
  //   sNextBinTri, sNextRenderTile
  // Terminal: sDone
  val nStates = 34
  val states = Enum(nStates)
  val (sIdle :: sLoadShader :: sWaitDMA :: sLoadMVP :: sLoadVert :: sRunVert :: sWaitVert ::
       sWriteSetupInputs :: sLoadSetupShader :: sRunSetup :: sWaitSetup ::
       sLoadBBox :: sBinTri :: sWaitBinner :: sStageUniforms :: sStoreSetup :: sNextTriangle :: Nil) = states.take(17)
  val (sLoadRastShader :: sLoadFragShader :: sStartPass2 ::
       sReadBinCount :: sWaitBinCount :: sClearTile ::
       sReadBinEntry :: sWaitBinEntry :: sLoadTriSetup ::
       sEnqueueTile :: sIteratePixels :: sWaitRast :: sWaitFlush :: sWaitFlushSync ::
       sNextBinTri :: sNextRenderTile :: sDone :: Nil) = states.drop(17)
  val state = RegInit(sIdle)

  // Which state to enter after DMA completes
  val nextAfterDMA = RegInit(sIdle)

  // Current vertex index (0, 1, 2)
  val vertIdx = RegInit(0.U(2.W))

  // Step 31.3: Current triangle index (0 to 15)
  val triIdx = RegInit(0.U(5.W))

  // Step 31.4: Tile loop registers (reused for Pass 2 full-screen iteration)
  val tileX = RegInit(0.U(cfg.coordWidth.W))
  val tileY = RegInit(0.U(cfg.coordWidth.W))
  val bboxMinX = RegInit(0.U(16.W))
  val bboxMinY = RegInit(0.U(16.W))
  val bboxMaxX = RegInit(0.U(16.W))
  val bboxMaxY = RegInit(0.U(16.W))

  // Step 32.3: Pass 2 registers
  val binTriIdx   = RegInit(0.U(10.W))  // current index into tile's bin list
  val binTriCount = RegInit(0.U(10.W))  // number of triangles in current tile's bin
  val binEntryData = RegInit(0.U(16.W)) // triangle index read from PSRAM bin list
  // storeWriteIdx: separate counter for sStoreSetup (shares same range as writeIdx)
  val storeWriteIdx = RegInit(0.U(6.W))
  // setupLoadIdx: tracks DMA word count during pass 2 sLoadTriSetup (for has_uvs snoop)
  val setupLoadIdx = RegInit(0.U(6.W))
  // Uniform cache: last triangle index whose setup uniforms were DMA-loaded into
  // the uniform buffer.  If the next tile's bin entry matches, skip the DMA entirely
  // — the uniforms are still valid from the previous load.  Invalidated at frame start.
  // 0xFFFF is used as "invalid" since triangle indices are < SEQ_MAX_TRI (≤ 1024).
  val lastLoadedTri = RegInit("hFFFF".U(16.W))
  // uDataReg: latched uniform value for PSRAM store (computed during sStageUniforms)
  val uDataStore = RegInit(VecInit.fill(31)(0.U(16.W)))

  // General-purpose sequential write counter
  // - sWriteSetupInputs: 0-5 (6 uniform writes)
  // - sStageUniforms:    0-30 (31 uniform writes)
  val writeIdx = RegInit(0.U(5.W))

  // Shadow registers for clip-space outputs (3 vertices × 4 components: x,y,z,w)
  val clipRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))

  // Shadow registers for color + z per vertex (3 vertices × 4 components: r,g,b,z)
  // Populated by snooping the DMA uniform write stream during vertex DMA.
  // DMA loads 8 words per vertex at descBase + v*32:
  //   uniform offset 0 = pos.x, 1 = pos.y, 2 = pos.z,
  //   uniform offset 3 = color.r, 4 = color.g, 5 = color.b,
  //   uniform offset 6 = uv.u, 7 = uv.v  (pre-scaled by tex dimensions in descriptor)
  // We capture offsets 2(z), 3(r), 4(g), 5(b), 6(u), 7(v).
  val colorRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))  // [v][r,g,b,z]
  // UV per vertex — pre-scaled by tex_w/tex_h in the PSRAM descriptor.
  // Offsets 6,7 from the DMA vertex stream (uniform[6]=u_tex, [7]=v_tex).
  val uvRegs = RegInit(VecInit.fill(3, 2)(0.U(16.W)))     // [v][u,v]

  // Shadow registers for setup shader outputs (8 values)
  // r0-r5 = scaled edge components, r6 = area, r7 = inv_area
  val setupRegs = RegInit(VecInit.fill(8)(0.U(16.W)))

  // Latched DMA descriptor — BorgDMA (Step 26.3) reads io.desc fields
  // directly every cycle in sRead, so the sequencer must hold them stable
  // from start until busy deasserts.
  val dmaDescReg = RegInit(0.U.asTypeOf(new DMADescriptor))

  // Ping-pong page register (toggles each time sStageUniforms runs)
  val uniformPage = RegInit(0.U(1.W))

  // Core completion detection
  val core_was_active = RegNext(
    io.coreStatus.running || io.coreStatus.autoRunPending, false.B
  )
  val core_just_finished = core_was_active &&
    !io.coreStatus.running && !io.coreStatus.autoRunPending

  // Registered tileComplete: breaks the combinational loop
  //   advance → tileComplete (comb) → iteratePixels → advance.
  // One cycle lag is harmless: the pipeline takes >>1 cycles per pixel.
  // clearTileComplete is used to reset the latch at the start of each tile.
  val clearTileComplete = RegInit(false.B)
  val tileCompleteLatch = RegInit(false.B)
  when(clearTileComplete) {
    tileCompleteLatch := false.B
    clearTileComplete := false.B
  }.otherwise {
    tileCompleteLatch := io.iter.complete
  }

  val bboxWordIdx = RegInit(0.U(2.W))
  val binnerStarted = RegInit(false.B)  // tracks whether binner accepted start
  // Per-triangle has_uvs flag from descriptor metadata (offset 104).
  val triHasUvs = RegInit(false.B)

  wireOutputDefaults()
  wireFsm()
  wireSnoops()

  private def wireOutputDefaults(): Unit = {
    // --- Output defaults ---
    io.busy := state =/= sIdle
    io.done := false.B
    // seqShaderActive: only true during vertex/setup shader execution states
    // (where r30/r31 must be zero, not pixel coordinates).
    io.seqShaderActive := state === sRunVert || state === sWaitVert ||
                          state === sRunSetup || state === sWaitSetup
    io.debugState := state
    io.debugTileCompleteLatch := tileCompleteLatch
    io.texEnOverride := triHasUvs

    io.dma.start := false.B
    io.dma.desc  := dmaDescReg

    io.coreTrigger.valid := false.B
    io.coreTrigger.pc    := 0.U

    io.uniformWrite.en   := false.B
    io.uniformWrite.addr := 0.U
    io.uniformWrite.data := 0.U

    io.uniformWritePage := uniformPage

    io.clipOut  := clipRegs
    io.setupOut := setupRegs

    io.iter.clear     := false.B
    io.iter.enqueue.valid := false.B
    io.iter.enqueue.bits.x := tileX
    io.iter.enqueue.bits.y := tileY
    io.iter.iterate     := false.B
    io.flusher.trigger      := false.B
    // tileBase = fbBase + ((tileY / 4) * tilesPerRow + (tileX / 4)) * 128
    val tileIndex = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    io.flusher.base := io.mmio.fbBase + (tileIndex << 7)

    // --- Step 32.2: Binner output defaults ---
    io.binner.start       := false.B
    io.binner.triIndex    := triIdx
    io.binner.bbox.min.x  := bboxMinX(9, 0)
    io.binner.bbox.min.y  := bboxMinY(9, 0)
    io.binner.bbox.max.x  := bboxMaxX(9, 0)
    io.binner.bbox.max.y  := bboxMaxY(9, 0)
    io.binner.clearCounts := false.B

    // --- Step 32.3: Store + count read output defaults ---
    io.store.active    := state === sStoreSetup
    io.store.req       := false.B
    io.store.addr      := 0.U
    io.store.wdata     := 0.U
    io.binner.countReadAddr  := 0.U
    io.binner.countReadEn    := false.B


  }

  private def wireFsm(): Unit = {
    // --- FSM ---
    switch(state) {

      is(sIdle) { handleIdle() }

      // Load vertex shader binary from PSRAM into IMEM via DMA
      is(sLoadShader) { handleLoadShader() }

      // Wait for DMA transfer to complete
      is(sWaitDMA) { handleWaitDMA() }

      // Load TS-baked MVP (16 words) from descriptor+96 into uniform[8..23].
      is(sLoadMVP) { handleLoadMVP() }

      // Load full vertex data (8 FP16 words: x,y,z,r,g,b,u,v) from descriptor.
      // Descriptor stride is 256 bytes. Vertex i is at descBase + triIdx*256 + i*32 bytes.
      // DMA writes all 8 words to uniform[0..7] in uniform page 0.
      // During the wait, sWaitDMA snoops uniform writes to colorRegs (see below).
      is(sLoadVert) { handleLoadVert() }

      // Trigger vertex shader execution on BorgCore at PC=0
      is(sRunVert) { handleRunVert() }

      // Wait for vertex shader to finish; snoop clip-space outputs (x,y into clipRegs)
      is(sWaitVert) { handleWaitVert() }

      // --- Step 29.2: Triangle setup ---

      // Write 6 screen-space coordinates from clipRegs into uniform buffer,
      // plus inv_width as u6 for edge normalization (Step 30.1c).
      // u0=v0.x, u1=v0.y, u2=v1.x, u3=v1.y, u4=v2.x, u5=v2.y, u6=inv_width
      is(sWriteSetupInputs) { handleWriteSetupInputs() }

      // Load setup shader from PSRAM into IMEM via DMA
      is(sLoadSetupShader) { handleLoadSetupShader() }

      // Trigger setup shader execution on BorgCore at PC=0
      is(sRunSetup) { handleRunSetup() }

      // Wait for setup shader to finish; snoop outputs into setupRegs
      is(sWaitSetup) { handleWaitSetup() }

      // --- Step 31.4: Load Bounding Box ---
      is(sLoadBBox) { handleLoadBBox() }

      // --- Step 32.2: Trigger BorgBinner for this triangle ---
      is(sBinTri) { handleBinTri() }

      // Wait for BorgBinner to finish writing all tile bins for this triangle.
      is(sWaitBinner) { handleWaitBinner() }

      // --- Step 29.3: Uniform staging ---
      // Write all 31 uniform registers (u0-u30) to replace setup_tile_uniforms().
      // Physical uniform indices match the fixed SPIRB layout:
      //   u0-u5:  scaled edge components from setupRegs[0..5]
      //   u6-u11: negated vertex positions from FNEG(clipRegs[v][c])
      //   u12:    inv_area from setupRegs[7]
      //   u13-u21: colors in barycentric order (v1,v0,v2) × RGB
      //   u22-u24: z_vals (z of v1, v0, v2)
      //   u25-u30: 0 (UVs — not yet implemented)
      is(sStageUniforms) { handleStageUniforms() }

      // --- Step 32.3: Store uniforms to PSRAM setup store ---
      // Write all 31 uniform values (latched in uDataStore) to PSRAM at
      // setupBase + triIdx * 128 + storeWriteIdx * 4.
      // Each value is stored as a 32-bit word (low 16 bits = uniform, high = 0).
      is(sStoreSetup) { handleStoreSetup() }

      // =====================================================================
      //  Pass 2: Shader reload + full-screen tile render
      // =====================================================================

      // --- Shader Reload (once before Pass 2) ---
      // Load rast shader from PSRAM into IMEM via DMA
      is(sLoadRastShader) { handleLoadRastShader() }

      // Load frag shader from PSRAM into IMEM via DMA
      is(sLoadFragShader) { handleLoadFragShader() }

      // --- Start Pass 2: iterate ALL framebuffer tiles ---
      is(sStartPass2) { handleStartPass2() }

      // Read the bin count for the current tile from binner's on-chip SRAM.
      // The count read was issued in the previous state (sStartPass2 or sNextRenderTile).
      // SyncReadMem has 1-cycle latency, so wait one cycle.
      is(sReadBinCount) { handleReadBinCount() }

      // sWaitBinCount is now unused — data is captured in sReadBinCount.
      // State kept in enum to avoid renumbering.
      is(sWaitBinCount) { /* dead state */ }

      // --- Tile clear (reused from Step 31.4) ---
      is(sClearTile) { handleClearTile() }

      // --- Read triangle index from PSRAM bin list via DMA ---
      is(sReadBinEntry) { handleReadBinEntry() }

      // Bin entry has been snooped by the DMA handler into binEntryData.
      // Now DMA-load the triangle's setup uniforms from PSRAM.
      is(sWaitBinEntry) { handleWaitBinEntry() }

      // DMA-load the triangle's 31 setup uniforms from PSRAM into the uniform buffer.
      // addr = setupBase + binEntryData * 128
      is(sLoadTriSetup) { handleLoadTriSetup() }

      // --- Per-triangle rasterization (reused from Step 31.4) ---
      is(sEnqueueTile) { handleEnqueueTile() }

      is(sIteratePixels) { handleIteratePixels() }

      is(sWaitRast) { handleWaitRast() }

      // Advance to next triangle in this tile's bin list, or flush
      is(sNextBinTri) { handleNextBinTri() }

      is(sWaitFlush) { handleWaitFlush() }

      is(sWaitFlushSync) { handleWaitFlushSync() }

      // Advance to next tile in full-screen iteration
      is(sNextRenderTile) { handleNextRenderTile() }

      // =====================================================================
      //  Pass 1: Triangle loop
      // =====================================================================

      // Advance to next triangle, or start Pass 2
      is(sNextTriangle) { handleNextTriangle() }

      // Sequencer complete — pulse done for one cycle, return to idle
      is(sDone) { handleDone() }
    }
  }

  private def handleIdle(): Unit = {
    when(io.mmio.start) {
      when(io.mmio.triCount === 0.U) {
        // No triangles — pulse busy and immediately finish.
        // Used by firmware's sequencer detection probe (seq_trigger with
        // tri_count=0). Without this guard, the full pipeline would run
        // with garbage descriptors and the flusher would corrupt PSRAM.
        state := sDone
      }.otherwise {
        triIdx       := 0.U
        vertIdx      := 0.U
        lastLoadedTri := "hFFFF".U  // invalidate uniform cache for new frame
        // Step 32.2: clear binner per-tile counts at the start of each frame.
        // The binner's multi-cycle clearing runs in parallel with the first
        // vertex shader DMA load, so it adds zero latency.
        io.binner.clearCounts := true.B
        if (BorgDebug.trace) printf("[SEQ] Pass1 start triCount=%d\n", io.mmio.triCount)
        state   := sLoadShader
      }
    }
  }

  private def handleLoadShader(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.vertShaderAddr
    desc.length   := io.mmio.vertShaderLen
    desc.dest     := 0.U  // dest=0 → IMEM
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sLoadMVP   // load MVP uniforms before first vertex
    state        := sWaitDMA
  }

  private def handleLoadMVP(): Unit = {
    // DMA 16 TS-baked MVP words from descriptor+SEQ_MVP_OFFSET into uniform[8..23].
    // Descriptor stride is 256 bytes (SEQ_DESC_STRIDE).  MVP is at offset 96.
    val desc = Wire(new DMADescriptor)
    val triOffset = triIdx * 256.U
    desc.baseAddr := io.mmio.descBase + triOffset + 96.U  // SEQ_MVP_OFFSET
    desc.length   := 16.U  // 16 FP16 words
    desc.dest     := 1.U   // uniformPage is always 0 → page 0
    desc.offset   := 8.U   // write to u8..u23

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sLoadVert
    state        := sWaitDMA
  }

  private def handleWaitDMA(): Unit = {
    when(!io.dma.busy) {
      state := nextAfterDMA
    }
  }

  private def handleLoadVert(): Unit = {
    val desc = Wire(new DMADescriptor)
    val triOffset = triIdx * 256.U  // SEQ_DESC_STRIDE = 256
    desc.baseAddr := io.mmio.descBase + triOffset + vertIdx * 32.U
    desc.length   := 8.U   // 8 × 32-bit words (x,y,z,r,g,b,u,v)
    // DMA dest encoding: 1=page0, 2=page1. Write to current uniformPage
    // so vertex shader reads from the same page (Step 30.1c fix).
    desc.dest     := Mux(uniformPage === 0.U, 1.U, 2.U)
    desc.offset   := 0.U   // write to u0..u7



    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sRunVert
    state        := sWaitDMA
  }

  private def handleRunVert(): Unit = {
    io.coreTrigger.valid := true.B
    io.coreTrigger.pc    := 0.U
    state                := sWaitVert
  }

  private def handleWaitVert(): Unit = {
    when(core_just_finished) {
      when(vertIdx === 2.U) {
        // All 3 vertices done → proceed to triangle setup (Step 29.2)
        writeIdx := 0.U
        state    := sWriteSetupInputs

      }.otherwise {
        vertIdx := vertIdx + 1.U
        state   := sLoadVert
      }
    }
  }

  private def handleWriteSetupInputs(): Unit = {
    io.uniformWrite.en   := true.B
    io.uniformWrite.addr := Cat(uniformPage, writeIdx(4, 0))
    when(writeIdx < 6.U) {
      val v = writeIdx(2, 1)  // writeIdx / 2 → vertex index (0, 1, 2)
      val c = writeIdx(0)     // writeIdx % 2 → component (0=x, 1=y)
      io.uniformWrite.data := clipRegs(v)(Cat(0.U(1.W), c))
    }.otherwise {
      // u6 = inv_width
      io.uniformWrite.data := io.mmio.seqInvWidth
    }
    when(writeIdx === 6.U) {
      state := sLoadSetupShader
    }.otherwise {
      writeIdx := writeIdx + 1.U
    }
  }

  private def handleLoadSetupShader(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.setupShaderAddr
    desc.length   := io.mmio.setupShaderLen
    desc.dest     := 0.U  // dest=0 → IMEM
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sRunSetup
    state        := sWaitDMA
  }

  private def handleRunSetup(): Unit = {
    io.coreTrigger.valid := true.B
    io.coreTrigger.pc    := 0.U
    state                := sWaitSetup
  }

  private def handleWaitSetup(): Unit = {
    when(core_just_finished) {
      writeIdx    := 0.U
      state       := sLoadBBox
    }
  }

  private def handleLoadBBox(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.descBase + (triIdx * 256.U) + 160.U  // SEQ_META_OFFSET
    desc.length   := 3.U  // 3 words: bbox_min, bbox_max, flags (has_uvs)
    desc.dest     := 2.U  // 2 = snoop only
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    writeIdx     := 0.U
    nextAfterDMA := sBinTri
    state        := sWaitDMA
  }

  private def handleBinTri(): Unit = {
    io.binner.start := true.B
    if (BorgDebug.trace) printf("[SEQ] sBinTri triIdx=%d bbox=(%d,%d)-(%d,%d) binnerBusy=%d\n",
      triIdx, bboxMinX, bboxMinY, bboxMaxX, bboxMaxY, io.binner.busy)
    state := sWaitBinner
  }

  private def handleWaitBinner(): Unit = {
    when(!io.binner.busy) {
      if (BorgDebug.trace) printf("[SEQ] sWaitBinner done\n")
      state := sStageUniforms
    }
  }

  private def handleStageUniforms(): Unit = {
    val w = writeIdx

    // FTEX uniform layout (new, matching frag.s SPIRB output with uniform_base=12):
    //   u0-u5:   scaled edge components (setupRegs[0..5])
    //   u6-u11:  negated vertex positions FNEG(clipRegs)
    //   u12:     inv_area (setupRegs[7])
    //   u13-u15: U texture coord  (v2, v1, v0) — pre-scaled by tex_w in descriptor
    //   u16-u18: V texture coord  (v2, v1, v0) — pre-scaled by tex_h in descriptor
    //   u19-u21: color R          (v2, v1, v0)
    //   u22-u24: color G          (v2, v1, v0)
    //   u25-u27: color B          (v2, v1, v0)
    //   u28-u30: z value          (v2, v1, v0)
    // Within each group of 3: slot 0→vertex2, slot 1→vertex1, slot 2→vertex0

    val uData = WireDefault(0.U(16.W))

    // Helper: within a group of 3 starting at 'base', map to vertex index.
    // Shader lists uniforms in reverse weight order (suffix 2, 1, 0).
    // Barycentric mapping: weight2→v1, weight1→v0, weight0→v2.
    // So: offset 0→v1, offset 1→v0, offset 2→v2.
    def vertOf(base: Int): UInt =
      Mux(w === base.U, 1.U(2.W), Mux(w === (base+1).U, 0.U(2.W), 2.U(2.W)))

    when(w < 6.U) {
      uData := setupRegs(w(2, 0))
    }.elsewhen(w < 12.U) {
      val vIdx = (w - 6.U)(2, 1)
      val cIdx = (w - 6.U)(0)
      val raw  = clipRegs(vIdx)(Cat(0.U(1.W), cIdx))
      uData := raw ^ (1.U(16.W) << 15)
    }.elsewhen(w === 12.U) {
      uData := setupRegs(7)
    }.elsewhen(w < 16.U) {
      uData := uvRegs(vertOf(13))(0)          // u13-u15: U-coord
    }.elsewhen(w < 19.U) {
      uData := uvRegs(vertOf(16))(1)          // u16-u18: V-coord
    }.elsewhen(w < 22.U) {
      uData := colorRegs(vertOf(19))(0)       // u19-u21: R
    }.elsewhen(w < 25.U) {
      uData := colorRegs(vertOf(22))(1)       // u22-u24: G
    }.elsewhen(w < 28.U) {
      uData := colorRegs(vertOf(25))(2)       // u25-u27: B
    }.otherwise {
      uData := colorRegs(vertOf(28))(3)       // u28-u30: Z
    }
    // (w > 30 cannot occur: writeIdx stops at 30)

    io.uniformWrite.en   := true.B
    io.uniformWrite.addr := Cat(uniformPage, writeIdx(4, 0))
    io.uniformWrite.data := uData

    // Debug: dump uniform values during staging
    when(writeIdx === 0.U || writeIdx === 19.U || writeIdx === 22.U || writeIdx === 25.U) {
      if (BorgDebug.trace) printf("[SEQ] stageU tri=%d u%d=0x%x\n", triIdx, writeIdx, uData)
    }

    // Step 32.3: Latch computed uniform into uDataStore for PSRAM write
    uDataStore(writeIdx) := uData

    when(writeIdx === 30.U) {
      storeWriteIdx := 0.U
      state := sStoreSetup
    }.otherwise {
      writeIdx := writeIdx + 1.U
    }
  }

  private def handleStoreSetup(): Unit = {
    val psramAddr = io.mmio.setupBase + (triIdx << 7) + (storeWriteIdx << 2)
    io.store.req   := true.B
    io.store.addr  := psramAddr
    // Word 31 = has_uvs flag (for pass 2 recovery)
    // storeWriteIdx is 6-bit; guard uDataStore access with (4,0) slice
    // (only reached when storeWriteIdx < 31, so top bit is always 0).
    io.store.wdata := Mux(storeWriteIdx === 31.U, triHasUvs.asUInt, uDataStore(storeWriteIdx(4, 0)))
    when(io.store.ready) {
      when(storeWriteIdx < 2.U || storeWriteIdx === 19.U || storeWriteIdx === 22.U || storeWriteIdx === 25.U || storeWriteIdx === 31.U) {
        if (BorgDebug.trace) printf("[SEQ] storeSetup triIdx=%d [%d] addr=0x%x data=0x%x\n",
          triIdx, storeWriteIdx, psramAddr,
          Mux(storeWriteIdx === 31.U, triHasUvs.asUInt, uDataStore(storeWriteIdx(4, 0))))
      }
      when(storeWriteIdx === 31.U) {
        state := sNextTriangle
      }.otherwise {
        storeWriteIdx := storeWriteIdx + 1.U
      }
    }
  }

  private def handleLoadRastShader(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.rastShaderAddr
    desc.length   := io.mmio.rastShaderLen
    desc.dest     := 0.U  // dest=0 -> IMEM
    desc.offset   := 0.U  // BORG_IMEM_RAST_OFFSET

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sLoadFragShader
    state        := sWaitDMA
  }

  private def handleLoadFragShader(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.fragShaderAddr
    desc.length   := io.mmio.fragShaderLen
    desc.dest     := 0.U  // dest=0 -> IMEM
    desc.offset   := 13.U // BORG_IMEM_FRAG_OFFSET

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sStartPass2
    state        := sWaitDMA
  }

  private def handleStartPass2(): Unit = {
    tileX := 0.U
    tileY := 0.U
    if (BorgDebug.trace) printf("[SEQ] Pass2 start\n")
    // Issue count read for tile (0,0) = tile index 0
    io.binner.countReadAddr := 0.U
    io.binner.countReadEn   := true.B
    if (BorgDebug.trace) printf("[SEQ] issuing countRead addr=0 binnerBusy=%d\n", io.binner.busy)
    state := sReadBinCount
  }

  private def handleReadBinCount(): Unit = {
    // SyncReadMem data is valid NOW (1 cycle after read was issued in
    // sStartPass2/sNextRenderTile). Capture it immediately — the output
    // goes undefined on the next cycle when readEn drops.
    binTriCount := io.binner.countReadData
    if (BorgDebug.trace) printf("[SEQ] tile(%d,%d) binCount=%d\n",
      tileX >> 2, tileY >> 2, io.binner.countReadData)
    binTriIdx   := 0.U
    writeIdx    := 0.U
    state       := sClearTile
  }

  private def handleClearTile(): Unit = {
    // Pulse tileCtrlClear for exactly one cycle (writeIdx=0), then wait
    // for BorgTileBuffer to finish its 16-cycle BRAM clear sequence.
    // Total wait: 1 (pulse) + 16 (BRAM writes) + 1 (register pipeline) = 18 cycles.
    // Keep clearTileComplete high throughout to suppress any stale tileComplete
    // from the previous tile's iterator (io.iter.complete may remain high).
    clearTileComplete := true.B
    when(writeIdx === 0.U) {
      io.iter.clear  := true.B
      writeIdx := writeIdx + 1.U
    }.elsewhen(writeIdx < 18.U) {
      writeIdx := writeIdx + 1.U
    }.otherwise {
      clearTileComplete := false.B
      // After clear: if this tile has triangles, start the inner bin loop.
      // Otherwise, flush the clear color directly.
      when(binTriCount === 0.U) {
        state := sWaitFlush
      }.otherwise {
        // Read first bin entry (triangle index) from PSRAM
        // addr = binBase + tileLinearIndex * binRowBytes + binTriIdx * 2
        state := sReadBinEntry
      }
    }
  }

  private def handleReadBinEntry(): Unit = {
    val tileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    val entryAddr  = io.mmio.binBase + (tileLinear * io.mmio.binRowBytes) + (binTriIdx << 1)
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := entryAddr
    desc.length   := 1.U  // 1 word (bin entry = uint16, stored in low half of 32b word)
    desc.dest     := 2.U  // snoop only — data captured in DMA snoop handler below
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sWaitBinEntry
    state        := sWaitDMA
  }

  private def handleWaitBinEntry(): Unit = {
    state := sLoadTriSetup
  }

  private def handleLoadTriSetup(): Unit = {
    // Uniform cache: if this triangle's uniforms were loaded for the previous
    // tile, skip the DMA — the uniform buffer still holds valid data.
    // triHasUvs was set during that earlier load and is still correct.
    when(binEntryData === lastLoadedTri) {
      if (BorgDebug.trace) printf("[SEQ] loadTriSetup CACHE HIT triIdx=%d\n", binEntryData)
      state := sEnqueueTile
    }.otherwise {
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.mmio.setupBase + (binEntryData << 7)
      desc.length   := 32.U  // 31 uniforms + 1 has_uvs flag
      desc.dest     := Mux(uniformPage === 0.U, 1.U, 2.U)  // page 0 or 1
      desc.offset   := 0.U

      if (BorgDebug.trace) printf("[SEQ] loadTriSetup MISS triIdx=%d addr=0x%x dest=%d\n",
        binEntryData, io.mmio.setupBase + (binEntryData << 7),
        Mux(uniformPage === 0.U, 1.U, 2.U))

      lastLoadedTri := binEntryData
      dmaDescReg    := desc
      io.dma.desc   := desc
      io.dma.start  := true.B
      nextAfterDMA  := sEnqueueTile
      // Track word count for has_uvs snoop (word 31)
      setupLoadIdx  := 0.U
      state         := sWaitDMA
    }
  }

  private def handleEnqueueTile(): Unit = {
    io.iter.enqueue.valid := true.B
    if (BorgDebug.trace) printf("[SEQ] sEnqueueTile tileX=%d tileY=%d\n", tileX, tileY)
    state := sIteratePixels
  }

  private def handleIteratePixels(): Unit = {
    io.iter.iterate := true.B
    if (BorgDebug.trace) printf("[SEQ] sIteratePixels -> sWaitRast (first advance)\n")
    state := sWaitRast
  }

  private def handleWaitRast(): Unit = {
    when(!io.iter.stall && !tileCompleteLatch) {
      io.iter.iterate := true.B
      if (BorgDebug.trace) printf("[SEQ] sWaitRast iterate (stall=0 latch=0)\n")
    }
    when(io.iter.stall && !tileCompleteLatch) {
      if (BorgDebug.trace) printf("[SEQ] sWaitRast STALLED (stall=1 latch=0)\n")
    }
    when(tileCompleteLatch) {
      if (BorgDebug.trace) printf("[SEQ] tileCompleteLatch -> sNextBinTri\n")
      state := sNextBinTri
    }
  }

  private def handleNextBinTri(): Unit = {
    // Wait for dispatcher pipeline to drain before loading next triangle's
    // uniforms. The last pixel of the previous triangle may still be executing
    // in the shader pipeline, reading from the uniform buffer. If the DMA
    // overwrites uniforms now, the shader gets corrupted data ("slot 15 race").
    when(!io.iter.dispatcherIdle) {
      if (BorgDebug.trace) printf("[SEQ] sNextBinTri: waiting for dispatcher drain\n")
    }.otherwise {
      val nextBinIdx = binTriIdx + 1.U
      when(nextBinIdx < binTriCount) {
        binTriIdx := nextBinIdx
        clearTileComplete := true.B
        writeIdx := 0.U
        if (BorgDebug.trace) printf("[SEQ] sNextBinTri -> sReadBinEntry (binTriIdx=%d/%d)\n", nextBinIdx, binTriCount)
        // Don't re-clear tile buffer — fragments accumulate on top of clear color
        state := sReadBinEntry
      }.otherwise {
        if (BorgDebug.trace) printf("[SEQ] sNextBinTri -> sWaitFlush (all tris done for tile)\n")
        state := sWaitFlush
      }
    }
  }

  private def handleWaitFlush(): Unit = {
    // Wait for dispatcher pipeline to drain (last pixel may still be in flight)
    // before triggering the flusher. This prevents the race where the flusher
    // reads slot 15 before the dispatcher writes it (the "last pixel race").
    when(io.iter.dispatcherIdle) {
      io.flusher.trigger := true.B
      if (BorgDebug.trace) printf("[SEQ] flush base=0x%x setupBase=0x%x\n", io.flusher.base, io.mmio.setupBase)
      state := sWaitFlushSync
    }.otherwise {
      if (BorgDebug.trace) printf("[SEQ] sWaitFlush: waiting for dispatcher drain\n")
    }
  }

  private def handleWaitFlushSync(): Unit = {
    if (BorgDebug.trace) printf("[SEQ] sWaitFlushSync flusherBusy=%d\n", io.flusher.busy)
    when(!io.flusher.busy) {
      state := sNextRenderTile
    }
  }

  private def handleNextRenderTile(): Unit = {
    if (BorgDebug.trace) printf("[SEQ] sNextRenderTile tileX=%d tileY=%d\n", tileX, tileY)
    val nextTileX = (tileX >> 2) + 1.U
    when(nextTileX >= io.mmio.fbWidthTiles) {
      tileX := 0.U
      val nextTileY = (tileY >> 2) + 1.U
      when(nextTileY >= io.mmio.fbHeightTiles) {
        state := sDone
      }.otherwise {
        tileY := nextTileY << 2
        // Issue count read for the next tile
        val nextTileLinear = nextTileY * io.mmio.tilesPerRow
        io.binner.countReadAddr := nextTileLinear(9, 0)
        io.binner.countReadEn   := true.B
        state := sReadBinCount
      }
    }.otherwise {
      tileX := nextTileX << 2
      // Issue count read for the next tile
      val nextTileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + nextTileX
      io.binner.countReadAddr := nextTileLinear(9, 0)
      io.binner.countReadEn   := true.B
      state := sReadBinCount
    }
  }

  private def handleNextTriangle(): Unit = {
    val nextIdx = triIdx + 1.U
    when(nextIdx < io.mmio.triCount) {
      triIdx  := nextIdx
      vertIdx := 0.U
      state   := sLoadShader
    }.otherwise {
      // All triangles processed — start Pass 2 (shader reload + tile render)
      state := sLoadRastShader
    }
  }

  private def handleDone(): Unit = {
    if (BorgDebug.trace) printf("[SEQ] sDone -> sIdle\n")
    io.done := true.B
    state   := sIdle
  }

  private def wireSnoops(): Unit = {
    // --- Clip-space output snooping (Step 29.1) ---
    // Vertex shader writes results to r0(x), r1(y) (passthrough of u0, u1).
    when(io.pipeWrite.en && state === sWaitVert) {
      for (comp <- 0 until 4) {
        when(io.pipeWrite.addr === comp.U) {
          clipRegs(vertIdx)(comp) := io.pipeWrite.data(15, 0)
        }
      }
    }

    // --- Color/z capture from DMA uniform write stream (Step 29.3) ---
    // During vertex DMA (sWaitDMA with nextAfterDMA=sRunVert), BorgDMA writes
    // 8 words to uniform[0..7]:
    //   uniform[0]=x, [1]=y, [2]=z, [3]=r, [4]=g, [5]=b, [6]=u_tex, [7]=v_tex
    // We snoop the DMA write stream to capture z(index=2), r(3), g(4), b(5).
    when(io.dma.uniformSnoop.en && state === sWaitDMA &&
         nextAfterDMA === sRunVert) {
      val addr = io.dma.uniformSnoop.addr(2, 0)  // low 3 bits = offset 0-7
      val data = io.dma.uniformSnoop.data
      when(addr === 2.U) { colorRegs(vertIdx)(3) := data }  // z
      when(addr === 3.U) {
        colorRegs(vertIdx)(0) := data   // r
        if (BorgDebug.trace) printf("[SEQ] colorSnoop vert=%d R=0x%x\n", vertIdx, data)
      }
      when(addr === 4.U) {
        colorRegs(vertIdx)(1) := data   // g
        if (BorgDebug.trace) printf("[SEQ] colorSnoop vert=%d G=0x%x\n", vertIdx, data)
      }
      when(addr === 5.U) {
        colorRegs(vertIdx)(2) := data   // b
        if (BorgDebug.trace) printf("[SEQ] colorSnoop vert=%d B=0x%x\n", vertIdx, data)
      }
      // UV: pre-scaled by tex_w/tex_h in the PSRAM descriptor (record_draw_call)
      when(addr === 6.U) { uvRegs(vertIdx)(0)    := data }  // u (scaled)
      when(addr === 7.U) { uvRegs(vertIdx)(1)    := data }  // v (scaled)
    }

    // --- Setup shader output snooping (Step 29.2) ---
    // Setup shader writes: r0–r7 = scaled edge components + area + inv_area.
    when(io.pipeWrite.en && state === sWaitSetup) {
      for (i <- 0 until 8) {
        when(io.pipeWrite.addr === i.U) {
          setupRegs(i) := io.pipeWrite.data(15, 0)
        }
      }
    }

    // --- DMA snoop for Bounding Box (Step 31.4) ---
    // Use a dedicated counter instead of writeIdx to avoid corrupting the
    // sStageUniforms write index (which also starts from 0).
    when(state === sLoadBBox) {
      bboxWordIdx := 0.U
    }
    when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sBinTri) {
      when(bboxWordIdx === 0.U) {
        bboxMinX := io.dma.snoop.bits(15, 0)
        bboxMinY := io.dma.snoop.bits(31, 16)
        bboxWordIdx := 1.U
      }.elsewhen(bboxWordIdx === 1.U) {
        bboxMaxX := io.dma.snoop.bits(15, 0)
        bboxMaxY := io.dma.snoop.bits(31, 16)
        bboxWordIdx := 2.U
      }.elsewhen(bboxWordIdx === 2.U) {
        triHasUvs := io.dma.snoop.bits(0)
        if (BorgDebug.trace) printf("[SEQ] hasUvs triIdx=%d flag=%d\n", triIdx, io.dma.snoop.bits(0))
        bboxWordIdx := 3.U
      }
    }

    // --- Step 32.3: DMA snoop for bin entry data ---
    // When DMA reads a bin list entry (1 word, snoop-only), capture the low 16 bits
    // as the triangle index for sLoadTriSetup.
    when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sWaitBinEntry) {
      binEntryData := io.dma.snoop.bits(15, 0)
    }

    // --- Pass 2: Snoop has_uvs flag from setup store word 31 ---
    // During sLoadTriSetup → sWaitDMA (nextAfterDMA=sEnqueueTile), the DMA
    // writes 32 words to the uniform buffer. Track the word count via
    // setupLoadIdx and recover triHasUvs from word 31.
    when(io.dma.uniformSnoop.en && state === sWaitDMA && nextAfterDMA === sEnqueueTile) {
      setupLoadIdx := setupLoadIdx + 1.U
      when(setupLoadIdx === 31.U) {
        triHasUvs := io.dma.uniformSnoop.data(0)
        if (BorgDebug.trace) printf("[SEQ] pass2 hasUvs triIdx=%d flag=%d\n", binEntryData, io.dma.uniformSnoop.data(0))
      }
    }
  }
}
