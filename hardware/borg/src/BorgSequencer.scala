// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class SeqMmioIO(cfg: BorgConfig) extends Bundle {
  // A tile-grid extent never exceeds maxBinTiles; narrow operands shrink the tile-index multiplies.
  private val tileRowWidth = math.min(10, log2Ceil(cfg.maxBinTiles + 1))

  // binRowBytes is at most maxTrianglesPerTile * 2 (borg_layout.h's SEQ_MAX_TRI).
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
  val cullMode        = Input(UInt(2.W))
  val frontFaceInvert = Input(Bool())
  val tileLoad        = Input(Bool())
  // A tile no triangle reaches is not cleared, loaded or flushed.
  val tileKeep        = Input(Bool())
  val singleSample    = Input(Bool())
  val recordShift     = Input(UInt(4.W))   // triangle t at setupBase + t << shift
  // Each stage's constants (0 = none), loaded once per draw, see BorgSetupRom.Record.
  val vsConstBase     = Input(UInt(GpuMemIO.AddrBits.W))
  val fsConstBase     = Input(UInt(GpuMemIO.AddrBits.W))
  // Bins and tile walks are window-relative; flush/load addresses and pixel coordinates are not.
  val fbOriginX       = Input(UInt(12.W))
  val fbOriginY       = Input(UInt(12.W))
  val fbPitch         = Input(UInt(12.W))
  val attCount        = Input(UInt(4.W))   // passes: up to 4 attachments, RAW128 ones twice
  // 0 = render the draw in the registers.
  val listBase        = Input(UInt(GpuMemIO.AddrBits.W))
}

class SeqBinnerIO(cfg: BorgConfig) extends Bundle {
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
  // In 32-byte tiles (RGB565, D16); Borg.scala scales it for wider formats.
  val tileOffset = Output(UInt(GpuMemIO.AddrBits.W))
  val trigger = Output(Bool())
  val busy = Input(Bool())
  // Pulsed after the tile's clear when any aspect is loaded.
  val loadStart = Output(Bool())
  val loadBusy  = Input(Bool())
}

class SeqIteratorIO(val coordWidth: Int) extends Bundle {
  val clear = Output(Bool())
  // Drained before the tile flush.
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
  // A later part of a partial render (docs/B11_partial_render.md).
  val loadAll = Output(Bool())

  // Per-edge MSAA sample deltas d0, d1; the other two samples are their sign flips.
  val covDelta = if (cfg.samples > 1)
    Some(Output(Vec(cfg.coveragePlanesStored, Vec(2, UInt(cfg.totalBits.W))))) else None
  // Per-triangle facing, meaningful only while busy.
  val frontFacingOverride = Output(Bool())
  val curTriIndex = Output(UInt(16.W))   // pass 2's current triangle (occlusion queries)

  val coreTrigger = new CoreTriggerIO
  val coreStatus = Flipped(new CoreStatusIO)
  val uniformWrite = new MemWritePort(6, cfg.totalBits)
  val uniformWritePage = Output(UInt(1.W))

  // Every lane's write-back: the vertex shader shades three corners at once.
  val draw       = Input(new DrawMmioIO)
  val csrWriteLanes  = Flipped(Vec(cfg.fragLanes, new CsrWriteIO(cfg.totalBits)))
  val ids        = Output(new InvocationIdsIO(cfg))
  val record     = Output(new CoreRecordIO)
  val topLeft    = Output(Vec(3, Bool()))
  val attPass    = Output(UInt(3.W))
  val regWrite   = Decoupled(new RegWrite)
}

/** Runs a draw in two passes: [[BorgDrawWalker]] shades, sets up and bins every triangle, then
  * [[BorgTileSequencer]] renders and flushes every tile. Pass 2 reads only what Pass 1 wrote to
  * memory; this wrapper sequences them, walks render lists and shares their DMA and uniform ports.
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  private val p2 = Module(new BorgTileSequencer(cfg))
  private val dw = Module(new BorgDrawWalker(cfg))

  private val (wIdle :: wPass1 :: wPass2 :: wDone :: wEntry :: wWaitEntry :: wPlayState :: wPlayParams :: wWaitPlay :: wDraw ::
               wPartial :: wPartClear :: wResume :: Nil) = Enum(13)
  private val wstate = RegInit(wIdle)

  // Render list entries: state block and parameter block addresses (0 = none); a state address
  // of 0 ends the list. Every draw bins into the same bins, then Pass 2 renders every tile once.
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

  // Toggles per frame, so neither pass can own it.
  private val curBufIdx = RegInit(0.U(1.W))

  // Partial render (docs/B11_partial_render.md): a full bin pauses the walker on triangle t,
  // Pass 2 renders the triangles below t, and the walker resumes at t on cleared bins.
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
          // Without this the flusher would write every tile.
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
      curBufIdx := curBufIdx ^ 1.U
      wstate    := wIdle
    }
  }

  io.busy            := wstate =/= wIdle
  io.seqShaderActive := dw.io.seqShaderActive
  io.loadAll         := parted

  // Only Pass 1 triggers the core; Pass 2's shading comes from the rasterizer's dispatcher.
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
  io.record := dw.io.record
  io.record.attrBase := p2.io.attrBase
  io.record.attrFlush := p2.io.start
  io.topLeft := p2.io.topLeft
  p2.io.mmio := io.mmio
  p2.io.curBufIdx := curBufIdx
  p2.io.listMode := listMode
  p2.io.triLimit := triLimit
  p2.io.playDone := player.io.done
  when(p2.io.playReq) {
    player.io.start := true.B
    player.io.addr  := p2.io.playAddr
  }
  player.io.dmaBusy := io.dma.busy
  player.io.snoop   := io.dma.snoop

  // Responses go to both passes; only the one waiting acts. Pass 1 writes uniform page 0.
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

  io.covDelta.foreach(_ := p2.io.covDelta.get)
  io.frontFacingOverride := p2.io.frontFacingOverride
  io.curTriIndex := p2.io.curTriIndex
  io.attPass := p2.io.attPass

  io.binner.countReadAddr := p2.io.binner.countReadAddr
  io.binner.countReadEn   := p2.io.binner.countReadEn
  p2.io.binner.busy         := io.binner.busy
  p2.io.binner.full         := io.binner.full
  p2.io.binner.countReadData := io.binner.countReadData
}
