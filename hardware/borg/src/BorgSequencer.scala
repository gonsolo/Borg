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

  // Step 50.2b: per-edge MSAA sample deltas, [edge][k] where k=0 is d0 and
  // k=1 is d1 (the other two samples are sign flips, derived in hardware).
  // Latched from the setup shader's r8..r13 and held stable for the whole
  // triangle, so the dispatcher can use them throughout tile iteration.
  val covDelta = if (cfg.samples > 1)
    Some(Output(Vec(3, Vec(2, UInt(cfg.totalBits.W))))) else None
  // Per-triangle texture enable: true when current triangle has UVs.
  // Driven from descriptor metadata has_uvs flag.
  val texEnOverride = Output(Bool())

  val coreTrigger = new CoreTriggerIO
  val coreStatus = Flipped(new CoreStatusIO)
  val pipeWrite = Flipped(new PipeWriteIO(cfg.totalBits))
  val uniformWrite = new MemWritePort(6, 16)
  val uniformWritePage = Output(UInt(1.W))
}

/** BorgSequencer — top-level supervisor over the GPU's two-pass
  * triangle-batch render, Pass 1 (per-triangle geometry) and Pass 2
  * (per-tile rendering).
  *
  * Restructured (2026-09) from a single flat 35-state FSM into this thin
  * 4-state supervisor plus two independently-instantiated sub-FSMs,
  * [[BorgGeometrySequencer]] (Pass 1, 17 states) and [[BorgTileSequencer]]
  * (Pass 2, 18 states). Two independent things motivated the split:
  *
  *   - A real post-CTS 25 MHz timing run found every one of the worst 1000
  *     timing paths in the whole design started at a bit of the old flat
  *     `state` register, confirmed by hierarchical net name. Tracing the
  *     paths showed one shared, ~70-77-gate serial chain that only diverged
  *     in its last 1-2 gates -- consistent with Chisel's `switch(state)
  *     { is(sX) {...} }` desugaring to chained equality tests, so any
  *     output wire touched by multiple of the 35 arms synthesizes as a
  *     linear priority-select chain across all of them, not a balanced
  *     decoder. More states means a longer chain for every such wire.
  *     Splitting into two ~half-sized switches directly shortens those
  *     chains.
  *   - Independent of timing, the flat 35-state design mixed two logically
  *     separate passes in one FSM. The split follows a boundary that
  *     already existed conceptually (and in the state list's own grouping
  *     comments): Pass 2 never reads Pass 1's live registers, only what
  *     Pass 1 wrote to DRAM (bin lists, per-triangle setup) plus `triCount`
  *     (an mmio input, not internal state) -- the two passes were already
  *     almost fully decoupled.
  *
  * This wrapper owns only what genuinely crosses the Pass 1/Pass 2
  * boundary: the start/done handshake sequencing "run Pass 1 to completion,
  * then Pass 2, then done", `curBufIdx` (must persist across whole frames,
  * so it can't live inside either per-frame sub-FSM), and arbitration of the
  * two sub-FSMs' shared DMA and uniform-write ports (trivial, since they
  * never run concurrently). Everything else -- shader triggering, DMA
  * snooping, the setup cache, tile iteration, flushing -- is exclusively
  * Pass 1's or Pass 2's, verified while designing this split by auditing
  * every register and IO field in the original monolith for cross-pass
  * liveness; almost none needed it.
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  private val p1 = Module(new BorgGeometrySequencer(cfg))
  private val p2 = Module(new BorgTileSequencer(cfg))

  private val wIdle :: wPass1 :: wPass2 :: wDone :: Nil = Enum(4)
  private val wstate = RegInit(wIdle)

  // Which framebuffer Pass 2 is rendering into. Persists across whole
  // frames (toggled once per frame here, at wDone) -- neither sub-FSM can
  // own it, since each is reset to its own idle at the end of its pass.
  private val curBufIdx = RegInit(0.U(1.W))

  // --- Supervisor FSM ---
  // Mirrors the original design's sIdle -> ... -> sNextTriangle ->
  // sLoadRastShader -> ... -> sDone -> sIdle shape exactly, with the two
  // (formerly inline) passes now opaque sub-modules behind a start/done
  // handshake. The zero-triangle firmware presence-probe path (wIdle ->
  // wDone -> wIdle, 1 cycle from `start` sampled to `done` asserted) is
  // cycle-identical to the original sIdle -> sDone -> sIdle path --
  // software/borg/borg_driver.c's hardcoded 8-cycle wait for that probe
  // remains valid unchanged. No other external timing depends on the exact
  // cycle count anywhere in this handoff chain -- the real per-frame
  // completion wait polls `busy` unconditionally.
  p1.io.start := false.B
  p2.io.start := false.B
  io.done := false.B

  switch(wstate) {
    is(wIdle) {
      when(io.mmio.start) {
        when(io.mmio.triCount === 0.U) {
          // No triangles -- pulse busy and immediately finish. Used by
          // firmware's sequencer detection probe (seq_trigger with
          // tri_count=0). Without this guard, the full pipeline would run
          // with garbage descriptors and the flusher would corrupt DRAM.
          wstate := wDone
        }.otherwise {
          p1.io.start := true.B
          if (BorgDebug.trace) printf("[SEQ] Pass1 start triCount=%d\n", io.mmio.triCount)
          wstate := wPass1
        }
      }
    }
    is(wPass1) {
      when(p1.io.done) {
        p2.io.start := true.B
        wstate := wPass2
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

  io.busy         := wstate =/= wIdle
  io.seqShaderActive := p1.io.seqShaderActive

  // --- Config fan-out (pure input data; safe to share) ---
  p1.io.mmio := io.mmio
  p2.io.mmio := io.mmio
  p2.io.curBufIdx := curBufIdx

  // --- DMA: shared port, arbitrated by which pass is active. Response
  // signals (busy/snoop/uniformSnoop) broadcast to both -- only the pass
  // whose own `state`/`nextAfterDMA` actually matches will act on them,
  // exactly like Borg.scala's existing Mux(s.io.busy, ..., ...) pattern for
  // resources shared among sibling modules. ---
  private val pass1Active = wstate === wPass1
  io.dma.start := Mux(pass1Active, p1.io.dma.start, p2.io.dma.start)
  io.dma.desc  := Mux(pass1Active, p1.io.dma.desc,  p2.io.dma.desc)
  p1.io.dma.busy := io.dma.busy
  p2.io.dma.busy := io.dma.busy
  p1.io.dma.snoop := io.dma.snoop
  p2.io.dma.snoop := io.dma.snoop
  p1.io.dma.uniformSnoop := io.dma.uniformSnoop
  p2.io.dma.uniformSnoop := io.dma.uniformSnoop

  // --- Uniform write: same shared/arbitrated shape as DMA. ---
  io.uniformWrite.en   := Mux(pass1Active, p1.io.uniformWrite.en,   p2.io.uniformWrite.en)
  io.uniformWrite.addr := Mux(pass1Active, p1.io.uniformWrite.addr, p2.io.uniformWrite.addr)
  io.uniformWrite.data := Mux(pass1Active, p1.io.uniformWrite.data, p2.io.uniformWrite.data)
  io.uniformWritePage  := Mux(pass1Active, p1.io.uniformWritePage,  p2.io.uniformWritePage)

  // --- Ports exclusive to one pass: direct connection, no arbitration. ---
  // coreTrigger/coreStatus/pipeWrite: only Pass 1 ever triggers BorgCore
  // directly (vertex/setup shaders). Pass 2's shader execution is triggered
  // by the rasterizer's own dispatcher, not by this sequencer.
  io.coreTrigger  := p1.io.coreTrigger
  p1.io.coreStatus := io.coreStatus
  p1.io.pipeWrite  := io.pipeWrite

  // store: Pass 1 only (per-triangle setup spill to DRAM).
  io.store.active := p1.io.store.active
  io.store.req    := p1.io.store.req
  io.store.addr   := p1.io.store.addr
  io.store.wdata  := p1.io.store.wdata
  p1.io.store.ready := io.store.ready

  // flusher/iter: Pass 2 only (tile rendering).
  io.flusher.base    := p2.io.flusher.base
  io.flusher.trigger := p2.io.flusher.trigger
  p2.io.flusher.busy := io.flusher.busy
  io.iter.clear         := p2.io.iter.clear
  io.iter.enqueue       := p2.io.iter.enqueue
  io.iter.iterate       := p2.io.iter.iterate
  p2.io.iter.dispatcherIdle := io.iter.dispatcherIdle
  p2.io.iter.complete       := io.iter.complete
  p2.io.iter.stall          := io.iter.stall

  // covDelta: while Pass 2 is actively iterating pixels, its own cache holds
  // the real per-tile value. At every OTHER wrapper state -- including
  // wIdle after a full frame completes -- fall back to Pass 1's raw
  // just-computed value. This exactly reproduces the pre-split design's
  // `Mux(pass2Active, ..., covDeltaRegs)`, where `pass2Active = state >=
  // sLoadRastShader` was likewise false whenever the (single, flat) state
  // register was back at sIdle. That fallback is load-bearing, not
  // incidental: BorgSequencerTests' covDelta_diagnostic_real_values reads
  // covDelta only after the whole sequencer goes idle again, specifically to
  // isolate the setup shader's raw math from whether the triangle was later
  // culled/binned/rendered at all -- collapsing this to "always Pass 2's
  // value" broke that test (all-zero readout) without the shader math itself
  // being wrong.
  private val pass2Active = wstate === wPass2
  io.covDelta.foreach { cd =>
    val p1out = p1.io.covDeltaOut.get
    val p2out = p2.io.covDelta.get
    for (e <- 0 until 3; k <- 0 until 2) {
      cd(e)(k) := Mux(pass2Active, p2out(e)(k), p1out(2 * e + k))
    }
  }
  io.texEnOverride := p2.io.texEnOverride

  // --- BorgBinner: writer (start/triIndex/bbox/clearCounts) is Pass 1;
  // count-reader (countReadAddr/countReadEn/countReadData) is Pass 2. The
  // two halves of SeqBinnerIO never overlap, so this is direct routing, not
  // arbitration. Both sub-modules get the full bundle (see each one's own
  // wireOutputDefaults for the tie-offs on their unused half). ---
  io.binner.start       := p1.io.binner.start
  io.binner.triIndex    := p1.io.binner.triIndex
  io.binner.bbox        := p1.io.binner.bbox
  io.binner.clearCounts := p1.io.binner.clearCounts
  io.binner.countReadAddr := p2.io.binner.countReadAddr
  io.binner.countReadEn   := p2.io.binner.countReadEn
  p1.io.binner.busy         := io.binner.busy
  p2.io.binner.busy         := io.binner.busy
  p1.io.binner.countReadData := io.binner.countReadData
  p2.io.binner.countReadData := io.binner.countReadData
}
