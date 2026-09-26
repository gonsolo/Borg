// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgSequencer IO. The sequencer renders a draw (docs/B1_geometry_front_end.md)
  * in two passes: Pass 1 ([[BorgDrawWalker]]) runs the vertex shader and the
  * setup ROM per triangle and bins it, Pass 2 ([[BorgTileSequencer]]) renders
  * and flushes every tile.
  */
class SeqMmioIO(cfg: BorgConfig) extends Bundle {
  // tilesPerRow/fbWidthTiles/fbHeightTiles all express a tile-grid extent
  // that can never exceed cfg.maxBinTiles (BorgBinner's on-chip count SRAM
  // -- and BorgTileSequencer's own tileWasDirty/tileIsDirty arrays --
  // already hard-require this for correctness, narrowing here just makes
  // the width match the existing invariant). Narrower operands shrink the
  // tile-index multiplies (curTileIndex-style math below and in BorgBinner)
  // that dominate this module's synthesized area on the ASIC's 16-tile
  // config; math.min keeps Default/Simt (maxBinTiles=1024) byte-identical
  // at 10 bits.
  private val tileRowWidth = math.min(10, log2Ceil(cfg.maxBinTiles + 1))

  // binRowBytes = maxTrianglesPerTile*2 bytes at most (see BorgConfig's doc
  // comment -- tied to software/borg/borg_layout.h's SEQ_MAX_TRI, a single
  // compile-time constant shared by every target). Narrows the
  // tileLinear*binRowBytes multiply the same way tileRowWidth narrows the
  // tile-index one above; math.min keeps this at the register's full 20
  // bits unless maxTrianglesPerTile is deliberately set below ~512k.
  private val binRowBytesWidth = math.min(20, log2Ceil(cfg.maxTrianglesPerTile * 2 + 1))

  val start = Input(Bool())
  val vertShaderAddr = Input(UInt(GpuMemIO.AddrBits.W))
  val vertShaderLen = Input(UInt(6.W))
  val fragShaderAddr = Input(UInt(GpuMemIO.AddrBits.W))
  val fragShaderLen = Input(UInt(7.W))
  val clearColorLo = Input(UInt(32.W))
  val clearColorHi = Input(UInt(32.W))
  val fbBase = Input(UInt(GpuMemIO.AddrBits.W))
  val tilesPerRow = Input(UInt(tileRowWidth.W))
  val binBase = Input(UInt(GpuMemIO.AddrBits.W))
  val binRowBytes = Input(UInt(binRowBytesWidth.W))  // stride (bytes/tile), not an address
  val setupBase = Input(UInt(GpuMemIO.AddrBits.W))
  val fbWidthTiles = Input(UInt(tileRowWidth.W))
  val fbHeightTiles = Input(UInt(tileRowWidth.W))
  // Face culling (CULL_CFG), applied by the draw walker after setup.
  val cullMode        = Input(UInt(2.W))
  val frontFaceInvert = Input(Bool())
  // Any attachment aspect has loadOp = LOAD (TILE_LOAD != 0).
  val tileLoad        = Input(Bool())
  // ATTACH_MS: multisampled attachments stored/loaded per sample. At
  // msaaMultiPass each pass then flushes its own sample instead of folding it
  // into the resolve accumulator.
  val attachMs        = Input(Bool())
  // SAMPLE_MASK_CFG bit 6, rasterizationSamples = 1: at msaaMultiPass a tile
  // takes one pass (sample 0) instead of `samples`.
  val singleSample    = Input(Bool())
  // DRAW_CFG's record stride (triangle t at setupBase + t << shift).
  val recordShift     = Input(UInt(4.W))
  // Each stage's constant window (0 = none): loaded once per draw into the
  // uniform words above the hardware's -- see BorgSetupRom.Record.
  val vsConstBase     = Input(UInt(GpuMemIO.AddrBits.W))
  val fsConstBase     = Input(UInt(GpuMemIO.AddrBits.W))
  // Render window (FB_ORIGIN, FB_PITCH): the window's first tile in the
  // framebuffer, and the framebuffer's tiles per row. Bins, tile walks and
  // coordWidth stay window-relative; flush/load addresses and pixel
  // coordinates are the framebuffer's.
  val fbOriginX       = Input(UInt(12.W))
  val fbOriginY       = Input(UInt(12.W))
  val fbPitch         = Input(UInt(12.W))
  // Colour attachments: how many (1..4); the tile is rendered once per
  // attachment, see BorgTileSequencer.attPass.
  val attCount        = Input(UInt(4.W))   // passes: up to 4 attachments, RAW128 ones twice
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
  val addr = Output(UInt(GpuMemIO.AddrBits.W))
  val wdata = Output(UInt(32.W))
  val ready = Input(Bool())
}

class SeqFlusherIO extends Bundle {
  // Byte offset of the current tile from the start of an attachment whose
  // tiles are 32 bytes (RGB565 colour, D16 depth). Borg.scala scales it for
  // wider colour formats and adds each attachment's own base.
  val tileOffset = Output(UInt(GpuMemIO.AddrBits.W))
  val trigger = Output(Bool())
  val busy = Input(Bool())
  // Tile load (loadOp = LOAD): pulsed after the tile's clear when any aspect
  // is loaded; the tile waits for loadBusy to drop. Same tileOffset.
  val loadStart = Output(Bool())
  val loadBusy  = Input(Bool())
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

class SeqDmaIO(val cfg: BorgConfig) extends Bundle {
  val start = Output(Bool())
  val desc = Output(new DMADescriptor)
  val busy = Input(Bool())
  val snoop = Flipped(Valid(UInt(32.W)))
  val uniformSnoop = Flipped(new MemWritePort(3, cfg.totalBits))
}

class BorgSequencerIO(val cfg: BorgConfig) extends Bundle {
  val mmio = new SeqMmioIO(cfg)
  val binner = new SeqBinnerIO(cfg)
  val store = new SeqStoreIO
  val flusher = new SeqFlusherIO
  val iter = new SeqIteratorIO(cfg.coordWidth)

  // Multi-pass MSAA control, passed straight through from Pass 2 (only Pass 2
  // renders tiles). Flipped for the same reason as there: BorgTileBuffer owns
  // the directions.
  val pass = if (cfg.msaaMultiPass) Some(Flipped(new TilePassIO(cfg.samples))) else None
  val dma = new SeqDmaIO(cfg)

  val busy = Output(Bool())
  val done = Output(Bool())
  val seqShaderActive = Output(Bool())
  /** The binner dropped an entry this render (BorgBinner.overflow): Pass 2
    * is skipped, so the render writes nothing and can be re-issued split. */
  val binOverflow = Input(Bool())

  // Step 50.2b: per-edge MSAA sample deltas, [edge][k] where k=0 is d0 and
  // k=1 is d1 (the other two samples are sign flips, derived in hardware).
  // Latched from the setup shader's r8..r13 and held stable for the whole
  // triangle, so the dispatcher can use them throughout tile iteration.
  val covDelta = if (cfg.samples > 1)
    Some(Output(Vec(cfg.coveragePlanesStored, Vec(2, UInt(cfg.totalBits.W))))) else None
  // Per-triangle facing (Vulkan conformance item 10, two-sided stencil).
  // Straight passthrough of Pass 2's own output -- only meaningful while the
  // sequencer is busy, which is how Borg.scala gates it.
  val frontFacingOverride = Output(Bool())
  val curTriIndex = Output(UInt(16.W))   // pass 2's current triangle (occlusion queries)

  val coreTrigger = new CoreTriggerIO
  val coreStatus = Flipped(new CoreStatusIO)
  val pipeWrite = Flipped(new PipeWriteIO(cfg.totalBits))
  val uniformWrite = new MemWritePort(6, cfg.totalBits)
  val uniformWritePage = Output(UInt(1.W))

  // Draw front end: the draw registers, every lane's write-back (the vertex
  // shader shades three corners at once), and what the core needs from the
  // walker (VertexIndex/InstanceIndex, record addresses) and from Pass 2
  // (the current triangle's attributes, for FATTR).
  val draw       = if (cfg.drawEnabled) Some(Input(new DrawMmioIO)) else None
  val pipeWriteLanes = if (cfg.drawEnabled) Some(Flipped(Vec(cfg.fragLanes, new PipeWriteIO(cfg.totalBits)))) else None
  val ids        = if (cfg.drawEnabled) Some(Output(new InvocationIdsIO(cfg))) else None
  val record     = if (cfg.drawEnabled) Some(Output(new CoreRecordIO)) else None
  val topLeft    = if (cfg.drawEnabled) Some(Output(Vec(3, Bool()))) else None
  // The colour attachment the current tile pass renders (ATTIDX).
  val attPass    = Output(UInt(3.W))
}

/** BorgSequencer — top-level supervisor over the GPU's two-pass render:
  * Pass 1 ([[BorgDrawWalker]]: primitive assembly, the vertex shader and the
  * setup ROM per triangle, binning) and Pass 2 ([[BorgTileSequencer]]: tile
  * rendering and flushing).
  *
  * The two passes are separate modules because they are separate FSMs: Pass
  * 2 never reads Pass 1's live registers, only what Pass 1 wrote to memory
  * (bin lists, per-triangle records). Splitting the old flat 35-state FSM
  * also shortened its timing paths -- every one of the worst 1000 paths of a
  * post-CTS 25 MHz run started at a bit of its `state` register, because a
  * `switch` desugars to a priority chain across all its arms.
  *
  * This wrapper owns only what crosses the boundary: the start/done
  * handshake (Pass 1 to completion, then Pass 2, then done), `curBufIdx`
  * (which persists across frames), and arbitration of the two passes'
  * shared DMA and uniform-write ports (trivial, as they never run
  * concurrently).
  *
  * A build without the draw front end (cfg.drawEnabled false: FP16, or no
  * memory operations) has no Pass 1: a trigger finishes at once.
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  private val p2 = Module(new BorgTileSequencer(cfg))
  private val dw = Option.when(cfg.drawEnabled)(Module(new BorgDrawWalker(cfg)))

  private val wIdle :: wPass1 :: wPass2 :: wDone :: Nil = Enum(4)
  private val wstate = RegInit(wIdle)

  // Which framebuffer Pass 2 is rendering into. Persists across whole
  // frames (toggled once per frame here, at wDone) -- neither sub-FSM can
  // own it, since each is reset to its own idle at the end of its pass.
  private val curBufIdx = RegInit(0.U(1.W))

  p2.io.start := false.B
  dw.foreach(_.io.start := false.B)
  private val p1Done = dw.map(_.io.done).getOrElse(true.B)
  private val nothingToDraw = io.draw.map(d => d.vertexCount === 0.U || d.instanceCount === 0.U).getOrElse(true.B)
  io.done := false.B

  switch(wstate) {
    is(wIdle) {
      when(io.mmio.start) {
        when(nothingToDraw) {
          // Nothing to draw: pulse busy and finish. Without this guard the
          // whole pipeline would run and the flusher write every tile.
          wstate := wDone
        }.otherwise {
          dw.foreach(_.io.start := true.B)
          if (BorgDebug.trace) printf("[SEQ] Pass1 start\n")
          wstate := wPass1
        }
      }
    }
    is(wPass1) {
      when(p1Done) {
        // A bin overflowed: some triangle is missing from some tile, so any
        // tile rendered now could be wrong. Render and flush nothing.
        when(io.binOverflow) {
          wstate := wDone
        }.otherwise {
          p2.io.start := true.B
          wstate := wPass2
        }
      }
    }
    is(wPass2) {
      when(p2.io.done) {
        wstate := wDone
      }
    }
    is(wDone) {
      if (BorgDebug.trace) printf("[SEQ] done -> idle\n")
      io.done   := true.B
      curBufIdx := curBufIdx ^ 1.U  // advance to the other framebuffer for the next render
      wstate    := wIdle
    }
  }

  io.busy            := wstate =/= wIdle
  io.seqShaderActive := dw.map(_.io.seqShaderActive).getOrElse(false.B)

  // --- Pass 1: the draw walker ---
  // Pass 1 alone triggers BorgCore directly (the vertex shader and the setup
  // ROM), stores records and feeds the binner; Pass 2's shading is triggered
  // by the rasterizer's own dispatcher.
  io.coreTrigger.valid   := false.B
  io.coreTrigger.pc      := 0.U
  io.coreTrigger.isRast  := false.B
  io.coreTrigger.isSetup := false.B
  io.store.active := false.B; io.store.req := false.B
  io.store.addr   := 0.U;     io.store.wdata := 0.U
  io.binner.start := false.B; io.binner.triIndex := 0.U
  io.binner.bbox  := 0.U.asTypeOf(io.binner.bbox)
  io.binner.clearCounts := false.B
  dw.foreach { w =>
    w.io.mmio := io.mmio
    w.io.draw := io.draw.get
    w.io.coreStatus := io.coreStatus
    w.io.pipeWrite  := io.pipeWriteLanes.get
    w.io.dma.busy := io.dma.busy
    w.io.dma.snoop := io.dma.snoop
    w.io.dma.uniformSnoop := io.dma.uniformSnoop
    w.io.store.ready := io.store.ready
    w.io.binner.busy := io.binner.busy
    w.io.binner.countReadData := io.binner.countReadData
    io.coreTrigger := w.io.coreTrigger
    io.store.active := w.io.store.active; io.store.req := w.io.store.req
    io.store.addr   := w.io.store.addr;   io.store.wdata := w.io.store.wdata
    io.binner.start := w.io.binner.start; io.binner.triIndex := w.io.binner.triIndex
    io.binner.bbox  := w.io.binner.bbox;  io.binner.clearCounts := w.io.binner.clearCounts
    io.ids.get := w.io.ids
    // Pass 1 writes records; Pass 2 reads the current triangle's attributes.
    io.record.get := w.io.record
    io.record.get.attrBase := p2.io.attrBase.get
    io.record.get.attrFlush := p2.io.start
    io.topLeft.get := p2.io.topLeft.get
  }
  // Pass-control is Pass 2's alone; Pass 1 never touches the tile buffer.
  io.pass.foreach { w =>
    val t = p2.io.pass.get
    w.sampleIdx  := t.sampleIdx
    w.accumEn    := t.accumEn
    w.accumFirst := t.accumFirst
    w.resolve    := t.resolve
    t.accumBusy  := w.accumBusy
  }

  p2.io.mmio := io.mmio
  p2.io.curBufIdx := curBufIdx

  // --- DMA and uniform writes: shared ports, arbitrated by which pass is
  // active. Responses (busy/snoop/uniformSnoop) go to both; only the pass
  // waiting on them acts. Pass 1 writes uniform page 0. ---
  private val pass1Active = wstate === wPass1
  private def pass1[T <: Data](f: BorgDrawWalker => T, idle: T): T = dw.map(f).getOrElse(idle)
  io.dma.start := Mux(pass1Active, pass1(_.io.dma.start, false.B), p2.io.dma.start)
  io.dma.desc  := Mux(pass1Active, pass1(_.io.dma.desc, p2.io.dma.desc), p2.io.dma.desc)
  p2.io.dma.busy := io.dma.busy
  p2.io.dma.snoop := io.dma.snoop
  p2.io.dma.uniformSnoop := io.dma.uniformSnoop

  io.uniformWrite.en   := Mux(pass1Active, pass1(_.io.uniformWrite.en, false.B), p2.io.uniformWrite.en)
  io.uniformWrite.addr := Mux(pass1Active, pass1(_.io.uniformWrite.addr, 0.U), p2.io.uniformWrite.addr)
  io.uniformWrite.data := Mux(pass1Active, pass1(_.io.uniformWrite.data, 0.U), p2.io.uniformWrite.data)
  io.uniformWritePage  := Mux(pass1Active, 0.U, p2.io.uniformWritePage)

  // --- Pass 2 only: flusher and iterator (tile rendering). ---
  io.flusher.tileOffset := p2.io.flusher.tileOffset
  io.flusher.trigger := p2.io.flusher.trigger
  p2.io.flusher.busy := io.flusher.busy
  io.flusher.loadStart := p2.io.flusher.loadStart
  p2.io.flusher.loadBusy := io.flusher.loadBusy
  io.iter.clear         := p2.io.iter.clear
  io.iter.enqueue       := p2.io.iter.enqueue
  io.iter.iterate       := p2.io.iter.iterate
  p2.io.iter.dispatcherIdle := io.iter.dispatcherIdle
  p2.io.iter.complete       := io.iter.complete
  p2.io.iter.stall          := io.iter.stall

  // covDelta: the current triangle's MSAA sample deltas, from its record.
  io.covDelta.foreach(_ := p2.io.covDelta.get)
  io.frontFacingOverride := p2.io.frontFacingOverride
  io.curTriIndex := p2.io.curTriIndex
  io.attPass := p2.io.attPass

  // --- BorgBinner's count reader is Pass 2's (the writer is Pass 1's, above). ---
  io.binner.countReadAddr := p2.io.binner.countReadAddr
  io.binner.countReadEn   := p2.io.binner.countReadEn
  p2.io.binner.busy         := io.binner.busy
  p2.io.binner.countReadData := io.binner.countReadData
}
