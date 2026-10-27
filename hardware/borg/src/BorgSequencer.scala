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
  // config; math.min keeps Default (maxBinTiles=1024) byte-identical
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
  // TILE_LOAD.keep: a tile no triangle of this render reaches is left alone
  // (no clear, no load, no flush) -- a render that continues an attachment.
  val tileKeep        = Input(Bool())
  // SAMPLE_MASK_CFG bit 6, rasterizationSamples = 1.
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
  // RENDER_LIST: byte address of the render list, 0 = render the draw in the registers.
  val listBase        = Input(UInt(GpuMemIO.AddrBits.W))
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
  val full = Input(Bool())
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

  val dma = new SeqDmaIO(cfg)

  val busy = Output(Bool())
  val done = Output(Bool())
  val seqShaderActive = Output(Bool())
  /** A later part of a render (docs/B11_partial_render.md): every tile loads every
    * aspect that has an attachment, and a tile no triangle reaches is kept. */
  val loadAll = Output(Bool())

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
  val uniformWrite = new MemWritePort(6, cfg.totalBits)
  val uniformWritePage = Output(UInt(1.W))

  // Draw front end: the draw registers, every lane's write-back (the vertex
  // shader shades three corners at once), and what the core needs from the
  // walker (VertexIndex/InstanceIndex, record addresses) and from Pass 2
  // (the current triangle's attributes, for FATTR).
  val draw       = Input(new DrawMmioIO)
  val csrWriteLanes  = Flipped(Vec(cfg.fragLanes, new CsrWriteIO(cfg.totalBits)))
  val ids        = Output(new InvocationIdsIO(cfg))
  val record     = Output(new CoreRecordIO)
  val topLeft    = Output(Vec(3, Bool()))
  // The colour attachment the current tile pass renders (ATTIDX).
  val attPass    = Output(UInt(3.W))
  // Render lists: a state block's register writes, to the MMIO bus.
  val regWrite   = Decoupled(new RegWrite)
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
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  private val p2 = Module(new BorgTileSequencer(cfg))
  private val dw = Module(new BorgDrawWalker(cfg))

  private val (wIdle :: wPass1 :: wPass2 :: wDone :: wEntry :: wWaitEntry :: wPlayState :: wPlayParams :: wWaitPlay :: wDraw ::
               wPartial :: wPartClear :: wResume :: Nil) = Enum(13)
  private val wstate = RegInit(wIdle)

  // --- Render list (docs/B1_geometry_front_end.md, "Render lists") --------
  // RENDER_LIST names a list of entries, two words each: the address of the
  // draw's state block and of its parameter block (0 = none); a state address
  // of 0 ends the list. Per entry the player writes the blocks into the
  // registers and Pass 1 runs, triangles numbered on from the previous draw
  // and binned into the same bins; then Pass 2 renders every tile once, and
  // replays a triangle's state block when it differs from the last one's.
  private val player   = Module(new BorgRegPlayer)
  private val listMode = RegInit(false.B)
  private val listPtr  = Reg(UInt(GpuMemIO.AddrBits.W))
  private val entry    = Reg(Vec(2, UInt(GpuMemIO.AddrBits.W)))   // state block, parameter block
  private val entryWord = RegInit(0.U(1.W))
  private val lastState = RegInit(0.U(GpuMemIO.AddrBits.W))      // the state block the registers hold
  private val anyDraw  = RegInit(false.B)                         // Pass 1 has run this render
  private val afterPlay = RegInit(wIdle)
  private val entryDesc = Wire(new DMADescriptor)
  entryDesc.baseAddr := listPtr
  entryDesc.length   := 2.U
  entryDesc.dest     := 2.U
  entryDesc.offset   := 0.U
  private val entryDmaStart = WireDefault(false.B)
  player.io.start := false.B
  player.io.addr  := entry(0)
  io.regWrite <> player.io.write

  // Which framebuffer Pass 2 is rendering into. Persists across whole
  // frames (toggled once per frame here, at wDone) -- neither sub-FSM can
  // own it, since each is reset to its own idle at the end of its pass.
  private val curBufIdx = RegInit(0.U(1.W))

  // --- Partial render (docs/B11_partial_render.md) ------------------------
  // A full bin pauses the walker on triangle t: Pass 2 renders the triangles below t,
  // the bins are cleared, the current draw's blocks are played again (list mode) and
  // the walker bins t once more. Every part after the first loads what the last stored.
  private val noLimit   = (1 << 16).U(17.W)
  private val triLimit  = RegInit(noLimit)
  private val parted    = RegInit(false.B)
  private val replaying = RegInit(false.B)   // the blocks are played for a resume, not a new draw
  private val partClear = WireDefault(false.B)

  p2.io.start := false.B
  dw.io.start := false.B
  dw.io.resume := false.B
  private val p1Done = dw.io.done
  private val nothingToDraw = io.draw.vertexCount === 0.U || io.draw.instanceCount === 0.U
  io.done := false.B

  switch(wstate) {
    is(wIdle) {
      when(io.mmio.start) {
        parted   := false.B
        triLimit := noLimit
        when(nothingToDraw) {
          // Nothing to draw: pulse busy and finish. Without this guard the
          // whole pipeline would run and the flusher write every tile.
          wstate := wDone
        }.otherwise {
          dw.io.start := true.B
          if (BorgDebug.trace) printf("[SEQ] Pass1 start\n")
          wstate := wPass1
        }
        listMode := false.B
        when(io.mmio.listBase =/= 0.U) {
          dw.io.start := false.B
          listMode := true.B
          listPtr  := io.mmio.listBase
          lastState := 0.U
          anyDraw  := false.B
          wstate   := wEntry
        }
      }
    }
    is(wEntry) {
      entryDmaStart := true.B
      entryWord := 0.U
      wstate := wWaitEntry
    }
    is(wWaitEntry) {
      when(io.dma.snoop.valid) { entry(entryWord) := io.dma.snoop.bits; entryWord := 1.U }
      when(!io.dma.busy) {
        when(entry(0) === 0.U) {
          // End of the list.
          when(!anyDraw) { wstate := wDone }
            .otherwise { p2.io.start := true.B; wstate := wPass2 }
        }.otherwise {
          wstate := Mux(entry(0) === lastState, wPlayParams, wPlayState)
        }
      }
    }
    is(wPlayState) {
      player.io.start := true.B
      lastState := entry(0)
      afterPlay := wPlayParams
      wstate := wWaitPlay
    }
    is(wPlayParams) {
      val next = Mux(replaying, wResume, wDraw)
      player.io.start := entry(1) =/= 0.U
      player.io.addr  := entry(1)
      afterPlay := next
      wstate := Mux(entry(1) =/= 0.U, wWaitPlay, next)
    }
    is(wWaitPlay) {
      when(player.io.done) { wstate := afterPlay }
    }
    is(wDraw) {
      listPtr := listPtr + 8.U
      when(nothingToDraw) {
        wstate := wEntry
      }.otherwise {
        dw.io.start := true.B
        wstate := wPass1
      }
    }
    is(wPass1) {
      when(p1Done) {
        anyDraw := true.B
        when(listMode) {
          wstate := wEntry
        }.otherwise {
          p2.io.start := true.B
          wstate := wPass2
        }
      }.elsewhen(dw.io.paused) {
        triLimit := dw.io.binner.triIndex
        p2.io.start := true.B
        wstate := wPartial
      }
    }
    is(wPartial) {
      when(p2.io.done) {
        parted    := true.B
        triLimit  := noLimit
        partClear := true.B
        wstate    := wPartClear
      }
    }
    is(wPartClear) {
      when(!io.binner.busy) {
        replaying := listMode
        wstate := Mux(listMode, wPlayState, wResume)
      }
    }
    is(wResume) {
      replaying := false.B
      dw.io.resume := true.B
      wstate := wPass1
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
  io.seqShaderActive := dw.io.seqShaderActive
  io.loadAll         := parted

  // --- Pass 1: the draw walker ---
  // Pass 1 alone triggers BorgCore directly (the vertex shader and the setup
  // ROM), stores records and feeds the binner; Pass 2's shading is triggered
  // by the rasterizer's own dispatcher.
  dw.io.mmio := io.mmio
  dw.io.first := !listMode || !anyDraw
  dw.io.stateAddr := Mux(listMode, lastState, 0.U)
  dw.io.draw := io.draw
  dw.io.coreStatus := io.coreStatus
  dw.io.csrWrite   := io.csrWriteLanes
  dw.io.dma.busy := io.dma.busy
  dw.io.dma.snoop := io.dma.snoop
  dw.io.dma.uniformSnoop := io.dma.uniformSnoop
  dw.io.store.ready := io.store.ready
  dw.io.binner.busy := io.binner.busy
  dw.io.binner.full := io.binner.full
  dw.io.binner.countReadData := io.binner.countReadData
  io.coreTrigger := dw.io.coreTrigger
  io.store.active := dw.io.store.active; io.store.req := dw.io.store.req
  io.store.addr   := dw.io.store.addr;   io.store.wdata := dw.io.store.wdata
  io.binner.start := dw.io.binner.start; io.binner.triIndex := dw.io.binner.triIndex
  io.binner.bbox  := dw.io.binner.bbox;  io.binner.clearCounts := dw.io.binner.clearCounts || partClear
  io.ids := dw.io.ids
  // Pass 1 writes records; Pass 2 reads the current triangle's attributes.
  io.record := dw.io.record
  io.record.attrBase := p2.io.attrBase
  io.record.attrFlush := p2.io.start
  io.topLeft := p2.io.topLeft
  p2.io.mmio := io.mmio
  p2.io.curBufIdx := curBufIdx
  // Pass 2 asks for a triangle's state block when it is not the one in the registers.
  p2.io.listMode := listMode
  p2.io.triLimit := triLimit
  p2.io.playDone := player.io.done
  when(p2.io.playReq) {
    player.io.start := true.B
    player.io.addr  := p2.io.playAddr
  }
  player.io.dmaBusy := io.dma.busy
  player.io.snoop   := io.dma.snoop

  // --- DMA and uniform writes: shared ports, arbitrated by which pass is
  // active. Responses (busy/snoop/uniformSnoop) go to both; only the pass
  // waiting on them acts. Pass 1 writes uniform page 0. ---
  private val pass1Active = wstate === wPass1
  io.dma.start := Mux(pass1Active, dw.io.dma.start, p2.io.dma.start)
  io.dma.desc  := Mux(pass1Active, dw.io.dma.desc, p2.io.dma.desc)
  // The list walk and the player take the port between the passes' own transfers.
  when(wstate === wEntry || wstate === wWaitEntry) {
    io.dma.start := entryDmaStart
    io.dma.desc  := entryDesc
  }
  when(player.io.busy) {
    io.dma.start := player.io.dmaStart
    io.dma.desc  := player.io.dmaDesc
  }
  p2.io.dma.busy := io.dma.busy
  p2.io.dma.snoop := io.dma.snoop
  p2.io.dma.uniformSnoop := io.dma.uniformSnoop

  io.uniformWrite.en   := Mux(pass1Active, dw.io.uniformWrite.en, p2.io.uniformWrite.en)
  io.uniformWrite.addr := Mux(pass1Active, dw.io.uniformWrite.addr, p2.io.uniformWrite.addr)
  io.uniformWrite.data := Mux(pass1Active, dw.io.uniformWrite.data, p2.io.uniformWrite.data)
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
  p2.io.binner.full         := io.binner.full
  p2.io.binner.countReadData := io.binner.countReadData
}
