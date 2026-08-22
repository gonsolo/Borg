// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgSequencer IO — Steps 29.1–29.3: vertex + setup + uniform staging.
  *
  * The sequencer orchestrates autonomous vertex shading, triangle setup, and
  * uniform staging by:
  *   1. Loading the vertex shader binary from DRAM into IMEM via DMA.
  *   2. For each of 3 vertices: loading 8 vertex words (pos+color+uv) into
  *      the uniform buffer via DMA, then triggering BorgCore to run the
  *      vertex shader, snooping clip-space outputs from PipeWriteIO, and
  *      capturing color/z from the DMA write stream into colorRegs.
  *   3. Writing snooped screen-space coordinates into the uniform buffer.
  *   4. Loading the setup shader from DRAM into IMEM via DMA.
  *   5. Running the setup shader to compute scaled edge vectors and inv_area.
  *   6. Staging all 31 uniform registers for the rasterizer and fragment
  *      shaders (sStageUniforms, replacing CPU's setup_tile_uniforms()).
  *
  * Descriptor layout in DRAM (3 × borg_vertex_t, stride = 32 bytes):
  *   vertex i at descBase + i*32:
  *     offset  0: pos.x  (FP16 in bits[15:0] of 32-bit DRAM word)
  *     offset  4: pos.y
  *     offset  8: pos.z
  *     offset 12: color.r
  *     offset 16: color.g
  *     offset 20: color.b
  *     offset 24: uv.u  (pre-scaled by tex_w in the descriptor)
  *     offset 28: uv.v  (pre-scaled by tex_h in the descriptor)
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
  *     u19-u21 = frag_pos.x of (v2, v1, v0)  — screen-space/transformed position (borgc lighting)
  *     u22-u24 = frag_pos.y of (v2, v1, v0)
  *     u25-u27 = frag_pos.z of (v2, v1, v0)
  *     u28-u30 = z_val      of (v2, v1, v0)  — projected depth for z-interp
  *
  *  When tex disabled (has_uvs=false): UV words are zero (Morton=0, white texel
  *  returned by dispatcher), giving texel(1,1,1) × vertexColor = vertexColor.
  *
  * The setup shader outputs pre-scaled edge constants to r0-r5 (already
  * multiplied by inv_width = 1/64 for a 64-wide framebuffer).
  */
class SeqMmioIO(cfg: BorgConfig) extends Bundle {
  // tilesPerRow/fbWidthTiles/fbHeightTiles all express a tile-grid extent
  // that can never exceed cfg.maxBinTiles (BorgBinner's on-chip count SRAM
  // -- and BorgSequencer's own tileWasDirty/tileIsDirty arrays -- already
  // hard-require this for correctness, narrowing here just makes the width
  // match the existing invariant). Narrower operands shrink the tile-index
  // multiplies (curTileIndex-style math below and in BorgBinner) that
  // dominate this module's synthesized area on the ASIC's 16-tile config;
  // math.min keeps Default/Simt (maxBinTiles=1024) byte-identical at 10 bits.
  private val tileRowWidth = math.min(10, log2Ceil(cfg.maxBinTiles + 1))

  // binRowBytes = maxTrianglesPerTile*2 bytes at most (see BorgConfig's doc
  // comment -- tied to software/borg/borg_layout.h's SEQ_MAX_TRI, a single
  // compile-time constant shared by every target). Narrows the
  // tileLinear*binRowBytes multiply the same way tileRowWidth narrows the
  // tile-index one above; math.min keeps this at the register's full 20
  // bits unless maxTrianglesPerTile is deliberately set below ~512k.
  private val binRowBytesWidth = math.min(20, log2Ceil(cfg.maxTrianglesPerTile * 2 + 1))

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
  val fragShaderLen = Input(UInt(7.W))
  val clearColorLo = Input(UInt(32.W))
  val clearColorHi = Input(UInt(32.W))
  val fbBase = Input(UInt(25.W))   // 25b = 32 MB GPU memory address space
  val tilesPerRow = Input(UInt(tileRowWidth.W))
  val binBase = Input(UInt(25.W))
  val binRowBytes = Input(UInt(binRowBytesWidth.W))  // stride (bytes/tile), not an address
  val setupBase = Input(UInt(25.W))
  val fbWidthTiles = Input(UInt(tileRowWidth.W))
  val fbHeightTiles = Input(UInt(tileRowWidth.W))
  // Fragment uniform-staging mode (tex_config.frag_uses_fragpos): 0 = vertex
  // colour at u19-u27 (hand frag.s), 1 = model frag_pos (borgc cube.frag).
  val fragUsesFragPos = Input(Bool())
}

class SeqBinnerIO(cfg: BorgConfig) extends Bundle {
  // Tile index into countMem (maxBinTiles entries) and per-tile triangle
  // count (0..maxTrianglesPerTile) -- narrowed from the historical fixed
  // 13/10-bit widths to match the config, same math.min-guarded pattern as
  // SeqMmioIO's tileRowWidth/binRowBytesWidth (never widens past the
  // original bound, only narrows when the config's real max is smaller).
  private val countAddrWidth = math.min(13, log2Ceil(cfg.maxBinTiles))
  private val countWidth     = math.min(10, log2Ceil(cfg.maxTrianglesPerTile + 1))
  val start = Output(Bool())
  val triIndex = Output(UInt(16.W))
  val bbox = Output(new Bbox(cfg.coordWidth))
  val clearCounts = Output(Bool())
  val busy = Input(Bool())
  val countReadAddr = Output(UInt(countAddrWidth.W))
  val countReadEn = Output(Bool())
  val countReadData = Input(UInt(countWidth.W))
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
  val mmio = new SeqMmioIO(cfg)
  val binner = new SeqBinnerIO(cfg)
  val store = new SeqStoreIO
  val flusher = new SeqFlusherIO
  val iter = new SeqIteratorIO(cfg.coordWidth)
  val dma = new SeqDmaIO

  val busy = Output(Bool())
  val done = Output(Bool())
  val seqShaderActive = Output(Bool())
  // Per-triangle texture enable: true when current triangle has UVs.
  // Driven from descriptor metadata has_uvs flag.
  val texEnOverride = Output(Bool())

  val coreTrigger = new CoreTriggerIO
  val coreStatus = Flipped(new CoreStatusIO)
  val pipeWrite = Flipped(new PipeWriteIO(cfg.totalBits))
  val uniformWrite = new MemWritePort(6, 16)
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
  *   13-15: uvRegs[v][0]                       (UV.u per vertex, pre-scaled by tex_w)
  *   16-18: uvRegs[v][1]                       (UV.v per vertex, pre-scaled by tex_h)
  *   19-21: clipRegs[v][0] or colorRegs[v][0]  (frag_pos.x or color.R, per fragUsesFragPos)
  *   22-24: clipRegs[v][1] or colorRegs[v][1]  (frag_pos.y or color.G)
  *   25-27: clipRegs[v][2] or colorRegs[v][2]  (frag_pos.z or color.B)
  *   28-30: clipRegs[v][2]                     (z_val per vertex, projected depth)
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
  val bboxMinX = RegInit(0.U(cfg.coordWidth.W))
  val bboxMinY = RegInit(0.U(cfg.coordWidth.W))
  val bboxMaxX = RegInit(0.U(cfg.coordWidth.W))
  val bboxMaxY = RegInit(0.U(cfg.coordWidth.W))

  // Step 32.3: Pass 2 registers
  val binTriIdx   = RegInit(0.U(10.W))  // current index into tile's bin list
  val binTriCount = RegInit(0.U(10.W))  // number of triangles in current tile's bin
  val binEntryData = RegInit(0.U(16.W)) // triangle index read from DRAM bin list
  // storeWriteIdx: separate counter for sStoreSetup (shares same range as writeIdx)
  val storeWriteIdx = RegInit(0.U(6.W))
  // setupLoadIdx: tracks DMA word count during pass 2 sLoadTriSetup (for has_uvs snoop)
  val setupLoadIdx = RegInit(0.U(6.W))
  // 2-entry associative setup cache over the two existing uniform pages.  Pass 2
  // re-reads each triangle's 128B setup once per tile it covers; the old 1-entry
  // cache (lastLoadedTri + page always 0) missed the tile-major A,B,A interleaving
  // (page 1 was idle).  Now each page caches one triangle: tagReg(p) = the triangle
  // index resident in page p (0xFFFF=invalid), uvsReg(p) = its has_uvs flag,
  // cacheVictim = round-robin replacement pointer (2-way ⇒ == LRU).  Invalidated at
  // frame start.  0xFFFF is "invalid" since triangle indices are < SEQ_MAX_TRI (≤1024).
  val tagReg      = RegInit(VecInit(Seq.fill(2)("hFFFF".U(16.W))))
  val uvsReg      = RegInit(VecInit(Seq.fill(2)(false.B)))
  val cacheVictim = RegInit(0.U(1.W))
  // General-purpose sequential write counter
  // - sWriteSetupInputs: 0-5 (6 uniform writes)
  // - sStageUniforms:    0-30 (31 uniform writes)
  val writeIdx = RegInit(0.U(5.W))

  // Shadow registers for clip-space outputs (3 vertices × 3 components: x,y,z).
  // A 4th (w) component was captured here historically but never read by
  // anything (no consumer inside this module, and the io.clipOut port that
  // used to forward it had no consumer either) -- dropped.
  val clipRegs = RegInit(VecInit.fill(3, 3)(0.U(16.W)))

  // Shadow registers for color + z per vertex (3 vertices × 4 components: r,g,b,z)
  // Populated by snooping the DMA uniform write stream during vertex DMA.
  // DMA loads 8 words per vertex at descBase + v*32:
  //   uniform offset 0 = pos.x, 1 = pos.y, 2 = pos.z,
  //   uniform offset 3 = color.r, 4 = color.g, 5 = color.b,
  //   uniform offset 6 = uv.u, 7 = uv.v  (pre-scaled by tex dimensions in descriptor)
  // We capture offsets 2(z), 3(r), 4(g), 5(b), 6(u), 7(v).
  val colorRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))  // [v][r,g,b,z]
  // UV per vertex — pre-scaled by tex_w/tex_h in the DRAM descriptor.
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

  // bboxWordIdx removed — bbox now computed from GPU clipRegs in handleWaitVert.
  // Per-triangle has_uvs flag from descriptor metadata (offset 104).
  val triHasUvs = RegInit(false.B)

  // Per-tile dirty-bit tracking for skip-empty-tile flush optimisation.
  // Double-buffer aware: indexed by [bufIdx][tileLinear].
  // bufIdx alternates each completed frame (toggled in handleDone); it tracks
  // which framebuffer the GPU is rendering into so each buffer maintains its
  // own dirty history independently.
  // tileWasDirty(b)(i): tile i had content last time buffer b was rendered.
  // tileIsDirty(b)(i):  tile i has content in the current render of buffer b.
  // Initialised all-true so every tile is flushed the first time each buffer
  // is rendered (SDRAM uninitialised at reset).
  // lastClearColorBuf(b): clear colour used when buffer b was last rendered;
  // sentinel ~0 forces a full-flush on the first render of each buffer.
  val tileWasDirty     = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(cfg.maxBinTiles)(true.B)))))
  val tileIsDirty      = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(cfg.maxBinTiles)(false.B)))))
  val lastClearColorBuf = RegInit(VecInit(Seq.fill(2)(~0.U(64.W))))
  val curBufIdx        = RegInit(0.U(1.W))

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
    io.texEnOverride := triHasUvs

    io.dma.start := false.B
    io.dma.desc  := dmaDescReg

    io.coreTrigger.valid  := false.B
    io.coreTrigger.pc     := 0.U
    io.coreTrigger.isRast := false.B  // vert/setup always run from writable IMEM

    io.uniformWrite.en   := false.B
    io.uniformWrite.addr := 0.U
    io.uniformWrite.data := 0.U

    io.uniformWritePage := uniformPage

    io.iter.clear     := false.B
    io.iter.enqueue.valid := false.B
    io.iter.enqueue.bits.x := tileX
    io.iter.enqueue.bits.y := tileY
    io.iter.iterate     := false.B
    io.flusher.trigger      := false.B
    // tileBase = fbBase + ((tileY / 4) * tilesPerRow + (tileX / 4)) * 32
    // (RGB565: 16 pixels x 2 bytes = 32 bytes per tile)
    val tileIndex = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    io.flusher.base := io.mmio.fbBase + (tileIndex << 5)

    // --- Step 32.2: Binner output defaults ---
    io.binner.start       := false.B
    io.binner.triIndex    := triIdx
    io.binner.bbox.min.x  := bboxMinX
    io.binner.bbox.min.y  := bboxMinY
    io.binner.bbox.max.x  := bboxMaxX
    io.binner.bbox.max.y  := bboxMaxY
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

      // Load vertex shader binary from DRAM into IMEM via DMA
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

      // Load setup shader from DRAM into IMEM via DMA
      is(sLoadSetupShader) { handleLoadSetupShader() }

      // Trigger setup shader execution on BorgCore at PC=0
      is(sRunSetup) { handleRunSetup() }

      // Wait for setup shader to finish; snoop outputs into setupRegs
      is(sWaitSetup) { handleWaitSetup() }

      // --- Step 31.4: Bbox (GPU-computed) + has_uvs DMA ---
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
      //   u13-u15: UV.u per vertex (v2,v1,v0), pre-scaled by tex_w
      //   u16-u18: UV.v per vertex (v2,v1,v0), pre-scaled by tex_h
      //   u19-u21: frag_pos.x or color.R per vertex (per fragUsesFragPos)
      //   u22-u24: frag_pos.y or color.G per vertex
      //   u25-u27: frag_pos.z or color.B per vertex
      //   u28-u30: z_vals (projected depth, from clipRegs r2)
      is(sStageUniforms) { handleStageUniforms() }

      // --- Step 32.3: Store uniforms to DRAM setup store ---
      // Write all 31 uniform values (recomputed via computeUniformData) to DRAM at
      // setupBase + triIdx * 128 + storeWriteIdx * 4.
      // Each value is stored as a 32-bit word (low 16 bits = uniform, high = 0).
      is(sStoreSetup) { handleStoreSetup() }

      // =====================================================================
      //  Pass 2: Shader reload + full-screen tile render
      // =====================================================================

      // --- Shader Reload (once before Pass 2) ---
      // Load rast shader from DRAM into IMEM via DMA
      is(sLoadRastShader) { handleLoadRastShader() }

      // Load frag shader from DRAM into IMEM via DMA
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

      // --- Read triangle index from DRAM bin list via DMA ---
      is(sReadBinEntry) { handleReadBinEntry() }

      // Bin entry has been snooped by the DMA handler into binEntryData.
      // Now DMA-load the triangle's setup uniforms from DRAM.
      is(sWaitBinEntry) { handleWaitBinEntry() }

      // DMA-load the triangle's 31 setup uniforms from DRAM into the uniform buffer.
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
        // with garbage descriptors and the flusher would corrupt DRAM.
        state := sDone
      }.otherwise {
        triIdx       := 0.U
        vertIdx      := 0.U
        tagReg(0)    := "hFFFF".U    // invalidate 2-entry setup cache for new frame
        tagReg(1)    := "hFFFF".U
        cacheVictim  := 0.U
        uniformPage  := 0.U          // Pass-1 uniform staging must use page 0
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

  // FP16 positive → cfg.coordWidth-bit integer pixel coordinate.
  // Unsigned integer comparison on positive FP16 is monotone (IEEE 754 property).
  // Negative inputs (off-screen left/top) are clamped to 0.
  //
  // The shift amount and overflow threshold below MUST use the fixed FP16
  // mantissa width (10), not cfg.coordWidth: they extract the correct integer
  // value from the FP16 bit pattern, a property of the FP16 format itself,
  // independent of how many bits of that integer we ultimately want to keep.
  // Compute the true (up to 10-bit) pixel integer first, THEN clamp/truncate
  // to cfg.coordWidth bits. (Conflating the two here previously shifted by
  // one bit too few for every coordWidth != 10 -- i.e. every real config,
  // since Default/Simt use coordWidth=9 -- roughly doubling every nonzero
  // pixel coordinate and corrupting all bbox/binning downstream.)
  private def fp16ToPixelInt(fp: UInt): UInt = {
    val w    = cfg.coordWidth
    val e    = fp(14, 10)       // biased exponent (0..30)
    val m    = fp(9, 0)         // mantissa
    val norm = Cat(1.U(1.W), m) // 11-bit implicit-1 representation
    val raw10 = Mux(e < 15.U, 0.U(10.W),
                Mux(e >= 25.U, 1023.U(10.W),
                  (norm >> (10.U - (e - 15.U)))(9, 0)
                ))
    val maxW = ((1 << w) - 1).U(10.W)
    Mux(raw10 > maxW, ((1 << w) - 1).U(w.W), raw10(w - 1, 0))
  }

  private def handleWaitVert(): Unit = {
    when(core_just_finished) {
      when(vertIdx === 2.U) {
        // All 3 vertices done — compute bbox from GPU clip-space outputs in clipRegs.
        // FP16 positive values compare correctly as unsigned integers (IEEE 754).
        // Clamp negatives (off-screen) to 0.
        def pos(fp: UInt): UInt = Mux(fp(15), 0.U(16.W), fp)
        val x0 = pos(clipRegs(0)(0)); val x1 = pos(clipRegs(1)(0)); val x2 = pos(clipRegs(2)(0))
        val y0 = pos(clipRegs(0)(1)); val y1 = pos(clipRegs(1)(1)); val y2 = pos(clipRegs(2)(1))
        def fp16Min(a: UInt, b: UInt): UInt = Mux(a <= b, a, b)
        def fp16Max(a: UInt, b: UInt): UInt = Mux(a >= b, a, b)
        val minXpix = fp16ToPixelInt(fp16Min(fp16Min(x0, x1), x2))
        val maxXpix = fp16ToPixelInt(fp16Max(fp16Max(x0, x1), x2))
        val minYpix = fp16ToPixelInt(fp16Min(fp16Min(y0, y1), y2))
        val maxYpix = fp16ToPixelInt(fp16Max(fp16Max(y0, y1), y2))
        bboxMinX := Cat(minXpix(cfg.coordWidth - 1, 2), 0.U(2.W))  // round down to 4-pixel tile boundary
        bboxMaxX := maxXpix
        bboxMinY := Cat(minYpix(cfg.coordWidth - 1, 2), 0.U(2.W))
        bboxMaxY := maxYpix
        if (BorgDebug.trace) printf("[SEQ] gpuBbox (%d,%d)-(%d,%d)\n", minXpix, minYpix, maxXpix, maxYpix)

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
    io.uniformWrite.addr := (if (cfg.maxUniforms > 32) Cat(uniformPage, writeIdx(4, 0)) else writeIdx(4, 0))
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
      writeIdx := 0.U
      // Screen y-down: front-facing (CW in screen) → area < 0 → r6 = -area/W > 0 (sign 0).
      // Back-facing → r6 < 0 (sign 1) → skip.
      when(setupRegs(6)(15)) {
        if (BorgDebug.trace) printf("[SEQ] cull triIdx=%d r6=0x%x\n", triIdx, setupRegs(6))
        state := sNextTriangle
      }.otherwise {
        state := sLoadBBox
      }
    }
  }

  private def handleLoadBBox(): Unit = {
    // Bbox is now computed from GPU clipRegs (Phase 2).  Only read the 1-word
    // has_uvs flag from descriptor + SEQ_META_OFFSET + 8 = desc + 168.
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.descBase + (triIdx * 256.U) + 168.U  // SEQ_META_OFFSET+8 = has_uvs
    desc.length   := 1.U  // 1 word: has_uvs flag
    desc.dest     := 2.U  // snoop only
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

  // Combinational uniform-value lookup — shared by sStageUniforms (writes the
  // live uniform buffer) and sStoreSetup (writes the same values to DRAM for
  // Pass 2 reload). Recomputed from index 'w' rather than latched into a
  // separate 31-entry register array, since the source shadow registers
  // (setupRegs/clipRegs/uvRegs/colorRegs) are stable across both states.
  //
  // FTEX uniform layout (matching frag.s SPIRB output with uniform_base=12):
  //   u0-u5:   scaled edge components (setupRegs[0..5])
  //   u6-u11:  negated vertex positions FNEG(clipRegs)
  //   u12:     inv_area (setupRegs[7])
  //   u13-u15: U texture coord  (v2, v1, v0) — pre-scaled by tex_w in descriptor
  //   u16-u18: V texture coord  (v2, v1, v0) — pre-scaled by tex_h in descriptor
  //   u19-u21: frag_pos.x       (v2, v1, v0) — model position (borgc lighting)
  //   u22-u24: frag_pos.y       (v2, v1, v0)
  //   u25-u27: frag_pos.z       (v2, v1, v0)
  //   u28-u30: z value          (v2, v1, v0) — projected depth for z-interp
  // Within each group of 3: slot 0→vertex2, slot 1→vertex1, slot 2→vertex0
  private def computeUniformData(w: UInt): UInt = {
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
      // FNEG via sign-bit XOR. Written as the mask literal directly rather
      // than `1.U(16.W) << 15`: a static shift by a Scala Int still grows
      // Chisel's declared width by the shift amount (16+15=31 bits) even
      // though the shifted VALUE (bit 15 set, a compile-time constant)
      // always fits in 16 -- tripping an implicit-truncation warning for
      // width that was never real.
      uData := raw ^ "h8000".U(16.W)
    }.elsewhen(w === 12.U) {
      uData := setupRegs(7)
    }.elsewhen(w < 16.U) {
      uData := uvRegs(vertOf(13))(0)          // u13-u15: U-coord
    }.elsewhen(w < 19.U) {
      uData := uvRegs(vertOf(16))(1)          // u16-u18: V-coord
    }.elsewhen(w < 22.U) {
      // u19-u21: vertex colour R (hand frag) OR frag_pos.x (borgc frag), per mode.
      // borgc cube.frag computes its normal from dFdx/dFdy(frag_pos), and
      // cube.vert sets frag_pos = gl_Position.xyz — the TRANSFORMED position.
      // Feed the per-vertex transformed position (clipRegs = screen-space x,y,
      // projected z) so the derivative-normal rotates with the cube; staging the
      // model-space position here makes the per-face normal constant and the light
      // appear glued to the cube.
      uData := Mux(io.mmio.fragUsesFragPos, clipRegs(vertOf(19))(0), colorRegs(vertOf(19))(0))
    }.elsewhen(w < 25.U) {
      uData := Mux(io.mmio.fragUsesFragPos, clipRegs(vertOf(22))(1), colorRegs(vertOf(22))(1))
    }.elsewhen(w < 28.U) {
      uData := Mux(io.mmio.fragUsesFragPos, clipRegs(vertOf(25))(2), colorRegs(vertOf(25))(2))
    }.otherwise {
      uData := clipRegs(vertOf(28))(2)        // u28-u30: Z from vertex shader r2 (projected depth)
    }
    // (w > 30 cannot occur: writeIdx/storeWriteIdx(4,0) stop at 30)
    uData
  }

  private def handleStageUniforms(): Unit = {
    val uData = computeUniformData(writeIdx)

    io.uniformWrite.en   := true.B
    io.uniformWrite.addr := (if (cfg.maxUniforms > 32) Cat(uniformPage, writeIdx(4, 0)) else writeIdx(4, 0))
    io.uniformWrite.data := uData

    // Debug: dump uniform values during staging
    when(writeIdx === 0.U || writeIdx === 19.U || writeIdx === 22.U || writeIdx === 25.U) {
      if (BorgDebug.trace) printf("[SEQ] stageU tri=%d u%d=0x%x\n", triIdx, writeIdx, uData)
    }

    when(writeIdx === 30.U) {
      storeWriteIdx := 0.U
      state := sStoreSetup
    }.otherwise {
      writeIdx := writeIdx + 1.U
    }
  }

  private def handleStoreSetup(): Unit = {
    val dramAddr = io.mmio.setupBase + (triIdx << 7) + (storeWriteIdx << 2)
    io.store.req   := true.B
    io.store.addr  := dramAddr
    // Word 31 = has_uvs flag (for pass 2 recovery)
    // storeWriteIdx is 6-bit; guard the uniform-index slice with (4,0)
    // (only reached when storeWriteIdx < 31, so top bit is always 0).
    val storeData = Mux(storeWriteIdx === 31.U, triHasUvs.asUInt, computeUniformData(storeWriteIdx(4, 0)))
    io.store.wdata := storeData
    when(io.store.ready) {
      when(storeWriteIdx < 2.U || storeWriteIdx === 19.U || storeWriteIdx === 22.U || storeWriteIdx === 25.U || storeWriteIdx === 31.U) {
        if (BorgDebug.trace) printf("[SEQ] storeSetup triIdx=%d [%d] addr=0x%x data=0x%x\n",
          triIdx, storeWriteIdx, dramAddr, storeData)
      }
      when(storeWriteIdx === 31.U) {
        state := sNextTriangle
      }.otherwise {
        storeWriteIdx := storeWriteIdx + 1.U
      }
    }
  }

  private def handleLoadRastShader(): Unit = {
    // The rasterizer edge-test shader is now a permanent hardware ROM
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
    desc.offset   := 1.U  // BORG_IMEM_FRAG_OFFSET (freed from 13 now that rast is a ROM)

    dmaDescReg   := desc
    io.dma.desc   := desc
    io.dma.start  := true.B
    nextAfterDMA := sStartPass2
    state        := sWaitDMA
  }

  private def handleStartPass2(): Unit = {
    tileX := 0.U
    tileY := 0.U
    if (BorgDebug.trace) printf("[SEQ] Pass2 start buf=%d\n", curBufIdx)
    // Rotate per-buffer dirty-bit arrays for the buffer being rendered now.
    // curBufIdx tracks which framebuffer we are rendering into this frame; it
    // was toggled in handleDone() at the end of the previous frame.
    // If the clear colour changed vs the last time THIS buffer was rendered,
    // treat ALL tiles as dirty so every empty tile picks up the new colour.
    val clearColor   = io.mmio.clearColorHi ## io.mmio.clearColorLo
    val colorChanged = clearColor =/= lastClearColorBuf(curBufIdx)
    lastClearColorBuf(curBufIdx) := clearColor
    for (i <- 0 until cfg.maxBinTiles) {
      tileWasDirty(curBufIdx)(i) := tileIsDirty(curBufIdx)(i) || colorChanged
      tileIsDirty(curBufIdx)(i)  := false.B
    }
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
      // Otherwise, only flush the clear colour if the tile was dirty last frame
      // (had rendered content) — clean empty tiles already hold the right value
      // in DRAM, so we can skip the 64-word SDRAM write entirely.
      val tileLinearCC = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
      when(binTriCount === 0.U) {
        when(tileWasDirty(curBufIdx)(tileLinearCC(log2Ceil(cfg.maxBinTiles) - 1, 0))) {
          state := sWaitFlush        // was dirty: must write clear colour to DRAM
        }.otherwise {
          state := sNextRenderTile   // already clean: skip flush
        }
      }.otherwise {
        // Read first bin entry (triangle index) from DRAM
        // addr = binBase + tileLinearIndex * binRowBytes + binTriIdx * 2
        tileIsDirty(curBufIdx)(tileLinearCC(log2Ceil(cfg.maxBinTiles) - 1, 0)) := true.B  // mark tile dirty in current buffer
        state := sReadBinEntry
      }
    }
  }

  private def handleReadBinEntry(): Unit = {
    val tileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + (tileX >> 2)
    // Truncated to DMADescriptor.baseAddr's fixed 25-bit/32MB GPU address
    // space (same constant used throughout this file). Unlike mmioAddr/
    // w_addr in BorgLane, this bound isn't provable from the RTL alone --
    // it relies on firmware keeping the triangle-bin table within the 32MB
    // budget. Chisel's generic width growth through the multiply/add chain
    // conservatively exceeds 25 bits even though real addresses stay in
    // range; unchanged from this design's behavior before this truncation
    // was made explicit.
    val entryAddr  = (io.mmio.binBase + (tileLinear * io.mmio.binRowBytes) + (binTriIdx << 1))(24, 0)
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
    // 2-entry associative setup cache: if this triangle's setup is resident in
    // either uniform page, point the frag at that page (uniformPage drives the
    // core's uniform read page while the sequencer is busy) and skip the DMA.
    // This catches the tile-major A,B,A interleaving the old 1-entry cache missed.
    // On a miss, DMA into the round-robin victim page and update its tag.
    when(binEntryData === tagReg(0)) {
      if (BorgDebug.trace) printf("[SEQ] loadTriSetup HIT page0 triIdx=%d\n", binEntryData)
      uniformPage := 0.U
      triHasUvs   := uvsReg(0)
      state       := sEnqueueTile
    }.elsewhen(binEntryData === tagReg(1)) {
      if (BorgDebug.trace) printf("[SEQ] loadTriSetup HIT page1 triIdx=%d\n", binEntryData)
      uniformPage := 1.U
      triHasUvs   := uvsReg(1)
      state       := sEnqueueTile
    }.otherwise {
      val victim = cacheVictim
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.mmio.setupBase + (binEntryData << 7)
      desc.length   := 32.U  // 31 uniforms + 1 has_uvs flag
      desc.dest     := 1.U   // uniform write; page = uniformPage(:=victim) via DMA
      desc.offset   := 0.U

      if (BorgDebug.trace) printf("[SEQ] loadTriSetup MISS triIdx=%d -> page%d addr=0x%x\n",
        binEntryData, victim, io.mmio.setupBase + (binEntryData << 7))

      tagReg(victim) := binEntryData
      uniformPage    := victim        // frag reads from the victim page
      cacheVictim    := ~victim       // round-robin replacement (2-way == LRU)
      dmaDescReg     := desc
      io.dma.desc    := desc
      io.dma.start   := true.B
      nextAfterDMA   := sEnqueueTile
      // Track word count for has_uvs snoop (word 31)
      setupLoadIdx   := 0.U
      state          := sWaitDMA
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
        io.binner.countReadAddr := nextTileLinear(log2Ceil(cfg.maxBinTiles) - 1, 0)
        io.binner.countReadEn   := true.B
        state := sReadBinCount
      }
    }.otherwise {
      tileX := nextTileX << 2
      // Issue count read for the next tile
      val nextTileLinear = ((tileY >> 2) * io.mmio.tilesPerRow) + nextTileX
      io.binner.countReadAddr := nextTileLinear(log2Ceil(cfg.maxBinTiles) - 1, 0)
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
    io.done    := true.B
    curBufIdx  := curBufIdx ^ 1.U  // advance to the other framebuffer for the next render
    state      := sIdle
  }

  private def wireSnoops(): Unit = {
    // --- Clip-space output snooping (Step 29.1) ---
    // Vertex shader writes results to r0(x), r1(y) (passthrough of u0, u1).
    when(io.pipeWrite.en && state === sWaitVert) {
      for (comp <- 0 until 3) {
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
      when(addr === 2.U) {
        colorRegs(vertIdx)(3) := data                       // z (projected depth source)
      }
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
      // UV: pre-scaled by tex_w/tex_h in the DRAM descriptor (record_draw_call)
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

    // --- DMA snoop for has_uvs flag (Phase 2: bbox computed from GPU clipRegs) ---
    // handleLoadBBox now DMAs 1 word (has_uvs) from desc+168.
    when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sBinTri) {
      triHasUvs := io.dma.snoop.bits(0)
      if (BorgDebug.trace) printf("[SEQ] hasUvs triIdx=%d flag=%d\n", triIdx, io.dma.snoop.bits(0))
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
        // Remember has_uvs for the page just loaded (= current uniformPage) so a
        // later cache hit on this triangle restores it without re-reading setup.
        uvsReg(uniformPage) := io.dma.uniformSnoop.data(0)
        if (BorgDebug.trace) printf("[SEQ] pass2 hasUvs triIdx=%d flag=%d\n", binEntryData, io.dma.uniformSnoop.data(0))
      }
    }
  }
}
