// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*
import hutt.HuttBus

/** BorgIO defines the interface for the shading processor. It uses
  * memory-mapped I/O for register and instruction memory access.
  *
  * The MMIO port is a single-outstanding Decoupled req/resp bus over a 10-bit
  * byte address space.  Read responses arrive one cycle after `req.fire` (the
  * register file / RDL is RegNext'd).  Write responses ack the cycle after as
  * well (resp.bits is don't-care for writes).
  */
class BorgIO(val cfg: BorgConfig) extends Bundle with BorgMmioIf {
  val mmio = Flipped(new HuttBus(10))

  // GPU read port (Step 19.2: texture fetches → MemoryController)
  val gpuMem = new GpuMemIO

  // DIAGNOSTIC TEMP: expose the sequencer's latched MSAA covDelta for test
  // observability while debugging Step 50.2 corruption. Gated on
  // cfg.debugPorts too (in addition to the pre-existing samples>1 gate) --
  // BorgConfig.Wafer has no debug harness to observe it, unlike ULX3S/sim.
  val covDeltaDebug =
    if (cfg.samples > 1 && cfg.debugPorts) Some(Output(Vec(3, Vec(2, UInt(cfg.totalBits.W))))) else None
}

/** Borg — minimal FP16 shading processor with 4-cycle FMA pipeline.
  *
  * This is an integration wrapper that composes BorgCore (FPU pipeline),
  * BorgRasterizer (pixel iterator), BorgTileFlusher, BorgTileBuffer,
  * BorgGpuRegs (RDL-generated MMIO register block), BorgDMA, BorgSequencer,
  * BorgBinner, and BorgCommandFIFO.
  *
  * Instruction encoding is defined in [[Instructions]].
  *
  * == Pipeline (4 cycles per instruction) ==
  *
  *   - Cycle 1: Fetch instruction, read rs1/rs2/rs3 from register file
  *   - Cycles 2–3: FMA unit computes result
  *   - Cycle 4: Write-back to rd
  *
  * == MMIO Interface ==
  *
  *   - Registers 0x000–0x07C (32 words): read/write register file r0–r31
  *   - IMEM / upper MMIO: write via MMIO or DMA (BorgDMA)
  *   - RDL-generated register block (BorgGpuRegs): named control/status/tex/seq
  *     fields — see hardware/rdl/borg.rdl for the authoritative address map.
  *     Key fields: control_start, control_reset_pipeline, control_start_pc,
  *     control_uniform_write_page; status_idle, status_flush_busy,
  *     status_fifo_full, status_dma_busy, status_seq_busy.
  */
object Borg {
  /** Allow tests to instantiate [[Borg]] with a [[FloatConfig]] directly,
    * e.g. `new Borg(FloatConfig.FP16)`.  Maps to [[BorgConfig.Default]] with the
    * requested float format and simulation-appropriate defaults.
    */
  def apply(fp: FloatConfig): Borg = new Borg(BorgConfig.Default.copy(fp = fp))
}

class Borg(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  /** Auxiliary constructor: allows `new Borg(FloatConfig.FP16)` in tests. */
  def this(fp: FloatConfig) = this(BorgConfig.Default.copy(fp = fp))

  val io = IO(new BorgIO(cfg))
  dontTouch(io)

  // --- Unified Bus Bundle ---
  val bus = Wire(new BorgBusIO())

  // --- Sub-modules ---
  // NOTE: flusher is declared before tile so that arcilator evaluates flusher
  // first in the compiled MLIR.  The pipelined sBurst makes flusher.io_read_en
  // a combinational function of io_gpuMem_waccept; tile.io_read_en depends on
  // flusher.io_read_en.  Arcilator processes hw.instance calls in declaration
  // order, so flusher must precede tile or tile sees a one-cycle-stale read_en.
  // --- Reset fan-out ---
  // Each block gets its OWN registered copy of the reset instead of sharing
  // one net. Measured on the 2026-09-16 signoff netlist: the single reset drove
  // 3887 gates reaching 5161 flops, and post-placement repair grew it a
  // 59891-cell, 2.1M um^2 repeater tree -- a third of it inside 6.5% of the
  // core, which detailed placement then could not legalize (DPL-0036). Several
  // smaller nets can each be placed with their own loads.
  //
  // All copies release on the same cycle, one cycle after the incoming reset.
  // A CHAIN (copy N registered from copy N-1) would keep synthesis from merging
  // them, but it staggers reset release per block, and that is observable: a
  // register written in the first cycles after reset is lost while its block is
  // still held (BorgGpuMemWordTests caught exactly that -- ls_base dropped, so
  // the shader's STORE went to the wrong address).
  //
  // Measured on the 2026-09-18 synthesis: yosys merges these eight identical
  // registers back into one net (dontTouch does not prevent it), so the reset
  // arrives as two nets rather than eight -- the pad reset (458 loads) and the
  // merged copy (1552). That is still a 2.5x drop in worst-case reset fan-out
  // versus the 3887-load single net, on top of the reduction from the
  // reset-free datapath registers above; the copies cost eight flops.
  private def resetCopy(name: String): Bool = {
    val r = RegNext(reset.asBool, true.B)
    r.suggestName(s"rstCopy_$name")
    dontTouch(r)
    r
  }

  val core      = withReset(resetCopy("core"))   { Module(new BorgCore(cfg)) }
  val rast      = withReset(resetCopy("rast"))   { Module(new BorgRasterizer(cfg)) }
  val flusher   = withReset(resetCopy("flush"))  { Module(new BorgTileFlusher(16, cfg.samples, cfg.hasDepthFlush)) }   // before tile — see note above
  val tile      = withReset(resetCopy("tile"))   { Module(new BorgTileBuffer(16, cfg.samples, cfg.tileColorBits, cfg.hasStencil, cfg.hasBlend, cfg.msaaMultiPass)) }
  val rdlRegs   = withReset(resetCopy("regs"))   { Module(new BorgGpuRegs()) } // Auto-generated RDL register block
  val dma       = withReset(resetCopy("dma"))    { Module(new BorgDMA(cfg)) }
  val sequencer = withReset(resetCopy("seq"))    { Module(new BorgSequencer(cfg)) }
  val binner    = withReset(resetCopy("bin"))    { Module(new BorgBinner(cfg.maxBinTiles, cfg.maxTrianglesPerTile, cfg.coordWidth)) }
  val compute   = Option.when(cfg.computeEnabled) {
    withReset(resetCopy("comp")) { Module(new BorgComputeSequencer(cfg)) }
  }
  private val computeBusy = compute.map(_.io.busy).getOrElse(false.B)
  // Set when a dispatch completes, cleared by the next start (COMPUTE_CTRL bit 0).
  val computeDoneSticky = Option.when(cfg.computeEnabled)(RegInit(false.B))

  // Sticky done flag for sequencer detection (module-level so it's visible
  // in the data_out MuxCase).  Set when the sequencer pulses io.done,
  // cleared by the next seq_trigger write.
  val seqDoneSticky = RegInit(false.B)

  // Convenience aliases
  private def d = dma
  private def f = flusher
  private def s = sequencer
  private def b = binner

  // -- MMIO handshake state (visible across multiple wire* methods) ---------
  // respPending bridges the one-cycle gap between req.fire and the resp the
  // CPU consumes via resp.fire.  rast.io.autoRunStall asserts when the
  // rasterizer is auto-running and owns the bus.
  // Declared before the wire* method calls because Scala val initialization
  // is in declaration order and wireBus() consumes these fields.
  val mmioRespPending = RegInit(false.B)
  val mmioReqAddrReg  = RegEnable(io.mmio.req.bits.addr,  io.mmio.req.fire)
  val mmioReqDataReg  = RegEnable(io.mmio.req.bits.data,  io.mmio.req.fire)

  wireBus()
  wireRdlRegs()
  wireCore()
  wireRasterizer()
  wireTileBuffer()
  wireFlusher()
  wireSequencer()
  wireBinner()
  wireMmioRead()
  wireDMA()
  wirePerf()
  wireCompute()

  /** COMPUTE_* registers (nogen in the RDL, so a build without compute has no
    * storage for them) and the compute sequencer's core-status snoop. */
  private def wireCompute(): Unit = compute.foreach { c =>
    def writes(off: UInt): Bool = bus.is_writing && bus.address === off
    val pcReg  = RegInit(0.U(6.W))
    val gx, gy, gz = RegInit(0.U(16.W))
    val sx, sy, count = RegInit(0.U(8.W))
    when(writes(BorgGpuRegs.compute_pc_offset))        { pcReg := bus.data_in(5, 0) }
    when(writes(BorgGpuRegs.compute_groups_xy_offset)) { gx := bus.data_in(15, 0); gy := bus.data_in(31, 16) }
    when(writes(BorgGpuRegs.compute_groups_z_offset))  { gz := bus.data_in(15, 0) }
    when(writes(BorgGpuRegs.compute_local_offset)) {
      sx := bus.data_in(7, 0); sy := bus.data_in(15, 8); count := bus.data_in(23, 16)
    }
    val start = writes(BorgGpuRegs.compute_ctrl_offset) && bus.data_in(0)

    c.io.mmio.start      := RegNext(start, false.B)
    c.io.mmio.pc         := pcReg
    c.io.mmio.groupsX    := gx
    c.io.mmio.groupsY    := gy
    c.io.mmio.groupsZ    := gz
    c.io.mmio.localX     := sx
    c.io.mmio.localY     := sy
    c.io.mmio.localCount := count

    c.io.coreStatus.running        := core.io.status.running
    c.io.coreStatus.autoRunPending := core.io.status.autoRunPending

    val done = computeDoneSticky.get
    when(start)        { done := false.B }
    when(c.io.done)    { done := true.B }
  }

  private def wireRdlRegs(): Unit = {
    rdlRegs.io.bus.address   := bus.address
    rdlRegs.io.bus.writeData := bus.data_in
    rdlRegs.io.bus.writeEn   := bus.is_writing
    rdlRegs.io.bus.readEn    := bus.is_reading
  }

  // Performance counters — present-phase decomposition (read at perf_* MMIO).
  // The sequencer-busy window IS the present phase.  Each counter resets on the
  // rising edge of that window, accumulates while its gate is high, and freezes
  // when the window ends — so firmware reads the just-completed frame's exact
  // cycle breakdown.  Counters overlap intentionally (e.g. flush cycles that
  // stall on SDRAM count in both perf_flush and perf_stall).
  private def wirePerf(): Unit = {
    if (cfg.hasPerfCounters) {
      val running = s.io.busy
      val runPrev = RegNext(running, false.B)
      val start   = running && !runPrev
      def counter(gate: Bool): UInt = {
        val c = RegInit(0.U(32.W))
        when(start)                     { c := 0.U }
          .elsewhen(running && gate)    { c := c + 1.U }
        c
      }
      rdlRegs.io.hw.perf_total_value := counter(true.B)
      rdlRegs.io.hw.perf_frag_value  := counter(core.io.status.running)
      rdlRegs.io.hw.perf_flush_value := counter(f.io.busy)
      rdlRegs.io.hw.perf_stall_value := counter(io.gpuMem.req && !io.gpuMem.ready)
      rdlRegs.io.hw.perf_dma_value   := counter(d.io.busy)
    } else {
      rdlRegs.io.hw.perf_total_value := 0.U
      rdlRegs.io.hw.perf_frag_value  := 0.U
      rdlRegs.io.hw.perf_flush_value := 0.U
      rdlRegs.io.hw.perf_stall_value := 0.U
      rdlRegs.io.hw.perf_dma_value   := 0.U
    }
  }

  private def wireBus(): Unit = {
    // Internal `bus` mirrors the request fields with the addr/data persisted
    // for the cycle after req.fire so RegNext-based read-data paths see a
    // stable address.
    bus.address    := Mux(io.mmio.req.fire, io.mmio.req.bits.addr, mmioReqAddrReg)
    bus.data_in    := Mux(io.mmio.req.fire, io.mmio.req.bits.data, mmioReqDataReg)
    bus.is_writing := io.mmio.req.fire &&  io.mmio.req.bits.write
    bus.is_reading := io.mmio.req.fire && !io.mmio.req.bits.write

    // Single-outstanding: don't accept a new request while one is in flight or
    // while the rasterizer owns the bus.
    io.mmio.req.ready := !mmioRespPending && !rast.io.autoRunStall

    when(io.mmio.req.fire) { mmioRespPending := true.B }
    when(io.mmio.resp.fire) { mmioRespPending := false.B }
  }

  private def wireCore(): Unit = {
    core.io.bus <> bus
    core.io.iter       := rast.io.shaderIter    // latched pre-advance position for coordLut

    // CoreTrigger mux: sequencer takes priority ONLY when it is actively
    // asserting coreTrigger.valid (sRunVert, sRunSetup). A compute dispatch
    // never overlaps a render (the driver serializes them), so its place in
    // the order only matters for which trigger wins a misuse.
    val nonSeqTrigger = compute.map { c =>
      Mux(c.io.coreTrigger.valid, c.io.coreTrigger, rast.io.coreTrigger)
    }.getOrElse(rast.io.coreTrigger)
    core.io.coreTrigger.valid  := Mux(s.io.coreTrigger.valid, true.B,
                                                nonSeqTrigger.valid)
    core.io.coreTrigger.pc     := Mux(s.io.coreTrigger.valid, s.io.coreTrigger.pc,
                                                nonSeqTrigger.pc)
    core.io.coreTrigger.isRast := Mux(s.io.coreTrigger.valid, s.io.coreTrigger.isRast,
                                                nonSeqTrigger.isRast)
    core.io.compute.foreach(_ := compute.get.io.lanes)
    core.io.barrier.foreach(compute.get.io.barrier := _)

    core.io.control.start            := rdlRegs.io.hw.control_start
    core.io.control.reset            := rdlRegs.io.hw.control_reset_pipeline
    core.io.control.startPC          := rdlRegs.io.hw.control_start_pc
    // Step 29.3: uniformWritePage mux — sequencer > MMIO.
    core.io.control.uniformWritePage := Mux(s.io.busy,
                                            s.io.uniformWritePage,
                                            rdlRegs.io.hw.control_uniform_write_page)

    // DMA write ports (Step 22.1).
    // Uniform write port is shared between DMA and sequencer;
    // sequencer takes priority (they never contend in practice).
    core.io.dmaImemWrite <> d.io.imemWrite
    // u31 is FIRMWARE-OWNED (holds the borgc fragment's DDX-rescale constant,
    // written via MMIO before each render).  The Pass-2 setup reload DMAs 32
    // words — word 31 is the has_uvs flag, needed only by the sequencer's
    // uniformSnoop below — so the DMA must never write uniform slot 31.  The
    // sequencer's own staging stops at u30 and is unaffected.
    // Route the sequencer's active uniform page to the DMA so a dest=1 setup
    // load fills the correct page of the 2-entry cache (was hardcoded page 0).
    d.io.uniformWritePage := s.io.uniformWritePage
    val dmaUniWriteEn = d.io.uniformWrite.en && d.io.uniformWrite.addr(4, 0) =/= 31.U
    // The compute sequencer writes the workgroup ID (u29..u31) the same way.
    val nonSeqUniWrite = compute.map { c =>
      Mux(c.io.uniformWrite.en, c.io.uniformWrite, d.io.uniformWrite)
    }.getOrElse(d.io.uniformWrite)
    val nonSeqUniWriteEn = compute.map(_.io.uniformWrite.en || dmaUniWriteEn).getOrElse(dmaUniWriteEn)
    core.io.dmaUniformWrite.en   := nonSeqUniWriteEn || s.io.uniformWrite.en
    core.io.dmaUniformWrite.addr := Mux(s.io.uniformWrite.en,
                                        s.io.uniformWrite.addr,
                                        nonSeqUniWrite.addr)
    core.io.dmaUniformWrite.data := Mux(s.io.uniformWrite.en,
                                        s.io.uniformWrite.data,
                                        nonSeqUniWrite.data)
    // Snoop: sequencer observes what DMA writes to the uniform buffer
    s.io.dma.uniformSnoop.en   := d.io.uniformWrite.en
    s.io.dma.uniformSnoop.addr := d.io.uniformWrite.addr(2, 0)
    s.io.dma.uniformSnoop.data := d.io.uniformWrite.data
    s.io.dma.snoop             := d.io.snoop

    // Step 34.6: FTEX core ↔ rasterizer texture request/response
    rast.io.texReq  := core.io.texReq
    rast.io.texU    := core.io.texU
    rast.io.texV    := core.io.texV
    core.io.texDone := rast.io.texDone
    core.io.texR    := rast.io.texR
    core.io.texG    := rast.io.texG
    core.io.texB    := rast.io.texB
  }

  private def wireRasterizer(): Unit = {
    rast.io.advance   := (bus.is_writing && bus.address === BorgGpuRegs.iter_offset) ||
                         s.io.iter.iterate

    // Pipeline write-back snoop
    rast.io.pipeWrite <> core.io.pipeWrite

    // Core state feedback
    rast.io.coreStatus <> core.io.status

    // GPU memory port: arbitration.
    // Priority: DMA > Flusher > Geo (Binner+Store) > Core (LOAD/STORE) >
    // Rast (texFetch).
    //
    // Core and Rast can never both be active: FTEX and LOAD/STORE are both
    // instructions, the core executes one at a time, and each stalls the
    // pipeline for its whole access. Their relative order is therefore
    // arbitrary -- but they are separate ports, so both must be in the mux.
    // Core sits above Rast so that if the invariant is ever broken the
    // failure is a stalled texture fetch rather than a corrupted load.
    val geoBusy  = b.io.busy || s.io.store.active
    val geoReq   = Mux(b.io.busy, b.io.gpuMem.req,   s.io.store.req)
    val geoAddr  = Mux(b.io.busy, b.io.gpuMem.addr,  s.io.store.addr)
    val geoWr    = Mux(b.io.busy, b.io.gpuMem.wr,    true.B)
    val geoWdata = Mux(b.io.busy, b.io.gpuMem.wdata, s.io.store.wdata)

    // 4-way mux: DMA > Flusher > Geo > Rast
    // The core is a master only in a build that has LOAD/STORE. Without it
    // the mux collapses back to the four-way form it had before, rather than
    // carrying a permanently-idle fifth input.
    val coreMem = core.io.memBusy
    def coreOr(sel: GpuMemIO => UInt, fallback: UInt): UInt =
      core.io.gpuMem.map(g => Mux(coreMem, sel(g), fallback)).getOrElse(fallback)
    def coreOrB(sel: GpuMemIO => Bool, fallback: Bool): Bool =
      core.io.gpuMem.map(g => Mux(coreMem, sel(g), fallback)).getOrElse(fallback)

    io.gpuMem.req   := Mux(d.io.busy, d.io.gpuMem.req,
                       Mux(f.io.busy, f.io.gpuMem.req,
                       Mux(geoBusy, geoReq, coreOrB(_.req, rast.io.gpuMem.req))))
    io.gpuMem.addr  := Mux(d.io.busy, d.io.gpuMem.addr,
                       Mux(f.io.busy, f.io.gpuMem.addr,
                       Mux(geoBusy, geoAddr, coreOr(_.addr, rast.io.gpuMem.addr))))
    io.gpuMem.wr    := Mux(d.io.busy, false.B,  // DMA only reads — never assert wr
                       Mux(f.io.busy, f.io.gpuMem.wr,
                       Mux(geoBusy, geoWr, coreOrB(_.wr, rast.io.gpuMem.wr))))
    // Every memory behind this port stores one write word as ONE 16-bit
    // halfword (MemoryController writes GPU words as HuttSize.Half and streams
    // wdata(15, 0) per burst beat; BorgLinkSlave sends one 16-bit flit per
    // word). The flusher and binner write genuine halfwords. The setup store
    // and the core's STORE write datapath-width words, so above 16 bits they
    // go out as a two-word burst, low halfword first -- which a word read at
    // the same address (DMA reload, LOAD) reassembles. Presenting them as a
    // single word instead silently dropped the upper half: FP32 -pi stored as
    // 0x00000FDB (BorgGpuMemWordTests).
    val wordData = Mux(geoBusy, geoWdata, coreOr(_.wdata, rast.io.gpuMem.wdata))
    val wideWr   = if (cfg.totalBits > 16)
      !d.io.busy && !f.io.busy &&
        Mux(geoBusy, !b.io.busy, coreOrB(_.wr, false.B))
    else false.B
    // Registered, so no combinational path from waccept back into wdata.
    val upperHalf = RegInit(false.B)
    when(io.gpuMem.ready) { upperHalf := false.B }
      .elsewhen(wideWr && io.gpuMem.waccept) { upperHalf := true.B }
    val splitData = Mux(upperHalf, wordData(31, 16), wordData(15, 0))
    io.gpuMem.wdata := Mux(f.io.busy, f.io.gpuMem.wdata,
                       Mux(wideWr, splitData, wordData))
    core.io.gpuMem.foreach { g =>
      g.data    := io.gpuMem.data
      g.ready   := io.gpuMem.ready && !d.io.busy && !f.io.busy && !geoBusy && coreMem
      g.waccept := false.B
    }
    core.io.lsBase.foreach(_ := rdlRegs.io.hw.ls_base_base_addr)
    // Burst length: the flusher streams whole tiles, a wide word is two
    // halfwords (see wideWr above), everything else is 1 word.
    io.gpuMem.wlen  := Mux(f.io.busy, f.io.gpuMem.wlen, Mux(wideWr, 2.U, 1.U))
    rast.io.gpuMem.data  := io.gpuMem.data
    rast.io.gpuMem.ready := io.gpuMem.ready && !d.io.busy && !f.io.busy && !geoBusy && !coreMem
    rast.io.gpuMem.waccept := false.B
    f.io.gpuMem.data  := io.gpuMem.data
    f.io.gpuMem.ready := io.gpuMem.ready && !d.io.busy && f.io.busy
    // Per-word burst pull goes only to the flusher (the sole burst master).
    // No f.io.busy guard needed: waccept=1 only during the flusher's own burst;
    // gating on f.io.busy creates a circular combinational path through io_read_en
    // that arcilator mishandles (use-before-def in MLIR evaluation order).
    f.io.gpuMem.waccept := io.gpuMem.waccept
    d.io.gpuMem.data  := io.gpuMem.data
    d.io.gpuMem.ready := io.gpuMem.ready && d.io.busy
    d.io.gpuMem.waccept := false.B

    // Texture configuration — wired from MMIO TEX_CONFIG register (Step 21.2)
    rast.io.texConfig.baseAddr := rdlRegs.io.hw.tex_config_base_addr
    rast.io.log2Dim            := rdlRegs.io.hw.tex_config_log2_dim
    // Unused now that texturing is FTEX-inline only: BorgTextureUnit only
    // ever consumes texConfig.mortonIndex in the same cycle FTEX overrides
    // it directly (BorgShaderDispatcher.ftexMortonIndex), so this base value
    // never actually reaches a real fetch. Tied off rather than removed from
    // TexConfigIO, which BorgTextureUnit's IO still requires.
    rast.io.texConfig.mortonIndex := 0.U
    // Per-triangle tex enable: when sequencer is busy, use its per-triangle
    // has_uvs flag. When idle, use the MMIO register (legacy/CPU path).
    rast.io.texConfig.en       := Mux(s.io.busy,
      s.io.texEnOverride && rdlRegs.io.hw.tex_config_en.asBool,
      rdlRegs.io.hw.tex_config_en.asBool)

    // frag_pc and uniform_page from dedicated registers
    rast.io.fragPcReg      := rdlRegs.io.hw.frag_pc_frag_pc
    // DEPTH_CFG (Step 50 item 11). Reset values are compare_op=1 (LESS) and
    // write_en=1, i.e. exactly the behaviour that used to be hardcoded, so
    // firmware that never writes this register sees no change.
    rast.io.depthCompareOp := rdlRegs.io.hw.depth_cfg_compare_op
    rast.io.depthWriteEn   := rdlRegs.io.hw.depth_cfg_write_en.asBool
    // BLEND_CFG / BLEND_CONST (Step 50 item 9). Reset 0 means blending
    // disabled -- the historical unconditional overwrite -- so, as with
    // DEPTH_CFG, firmware that never writes these sees no change.
    rast.io.blendCfg.foreach { b =>
      b.enable         := rdlRegs.io.hw.blend_cfg_enable.asBool
      b.srcColorFactor := rdlRegs.io.hw.blend_cfg_src_color_factor
      b.dstColorFactor := rdlRegs.io.hw.blend_cfg_dst_color_factor
      b.colorOp        := rdlRegs.io.hw.blend_cfg_color_op
      b.srcAlphaFactor := rdlRegs.io.hw.blend_cfg_src_alpha_factor
      b.dstAlphaFactor := rdlRegs.io.hw.blend_cfg_dst_alpha_factor
      b.alphaOp        := rdlRegs.io.hw.blend_cfg_alpha_op
      b.constant.r     := rdlRegs.io.hw.blend_const_const_r
      b.constant.g     := rdlRegs.io.hw.blend_const_const_g
      b.constant.b     := rdlRegs.io.hw.blend_const_const_b
      b.constant.a     := rdlRegs.io.hw.blend_const_const_a
      b.colorWriteMask := rdlRegs.io.hw.blend_cfg_color_write_mask
    }
    // Per-triangle facing, same busy/idle split as texConfig.en above: while
    // the sequencer is rendering, use its real per-triangle value; otherwise
    // (idle / legacy direct-poke path) default true, the historical
    // behaviour front-face-only builds already depended on.
    rast.io.frontFacing.foreach(_ := Mux(s.io.busy, s.io.frontFacingOverride, true.B))
    // STENCIL_CFG / STENCIL_FRONT / STENCIL_BACK (Step 50 item 10). Reset 0
    // means stencil disabled -- the historical no-stencil behaviour.
    rast.io.stencilCfg.foreach { st =>
      st.enable := rdlRegs.io.hw.stencil_cfg_enable.asBool
      st.front.compareOp   := rdlRegs.io.hw.stencil_cfg_front_compare_op
      st.front.failOp      := rdlRegs.io.hw.stencil_cfg_front_fail_op
      st.front.passOp      := rdlRegs.io.hw.stencil_cfg_front_pass_op
      st.front.depthFailOp := rdlRegs.io.hw.stencil_cfg_front_depth_fail_op
      st.front.compareMask := rdlRegs.io.hw.stencil_front_compare_mask
      st.front.writeMask   := rdlRegs.io.hw.stencil_front_write_mask
      st.front.reference   := rdlRegs.io.hw.stencil_front_reference
      st.back.compareOp    := rdlRegs.io.hw.stencil_cfg_back_compare_op
      st.back.failOp       := rdlRegs.io.hw.stencil_cfg_back_fail_op
      st.back.passOp       := rdlRegs.io.hw.stencil_cfg_back_pass_op
      st.back.depthFailOp  := rdlRegs.io.hw.stencil_cfg_back_depth_fail_op
      st.back.compareMask  := rdlRegs.io.hw.stencil_back_compare_mask
      st.back.writeMask    := rdlRegs.io.hw.stencil_back_write_mask
      st.back.reference    := rdlRegs.io.hw.stencil_back_reference
    }
    // SCISSOR_X / SCISSOR_Y (Step 50). Reset enable=0 means every fragment
    // passes -- the historical behaviour, and deliberately not "an empty
    // rectangle", which would blank the frame.
    rast.io.scissor.enable := rdlRegs.io.hw.scissor_y_enable.asBool
    rast.io.scissor.x0     := rdlRegs.io.hw.scissor_x_x0
    rast.io.scissor.x1     := rdlRegs.io.hw.scissor_x_x1
    rast.io.scissor.y0     := rdlRegs.io.hw.scissor_y_y0
    rast.io.scissor.y1     := rdlRegs.io.hw.scissor_y_y1
    // SAMPLER_CFG (Step 50). Reset 0 = NEAREST, the historical behaviour.
    rast.io.texFilterLinear.foreach(_ := rdlRegs.io.hw.sampler_cfg_filter_linear.asBool)
    rast.io.texAddrModeU.foreach(_ := rdlRegs.io.hw.sampler_cfg_addr_mode_u)
    rast.io.texAddrModeV.foreach(_ := rdlRegs.io.hw.sampler_cfg_addr_mode_v)
    rast.io.texBorder.foreach(_ := rdlRegs.io.hw.sampler_cfg_border_color)
    rast.io.uniformPageReg := rdlRegs.io.hw.control_uniform_write_page
  }

  private def wireTileBuffer(): Unit = {
    // MMIO write to tile_ctrl: set read index (triggers BRAM read) or clear
    val ctrlWriting = bus.is_writing && bus.address === BorgGpuRegs.tile_ctrl_offset
    val tileReadIdx = rdlRegs.io.hw.tile_ctrl_read_idx
    
    val seqClear = s.io.iter.clear
    tile.io.clear.en := rdlRegs.io.hw.tile_ctrl_clear.asBool || seqClear

    // Wire clear color: sequencer-driven or MMIO-driven (tile_bz shadow).
    // Sequencer clear color format: lo = {B[31:16], Z[15:0]}, hi = {R[31:16], G[15:0]}.
    tile.io.clear.color.r := Mux(seqClear, s.io.mmio.clearColorHi(31, 16), 0.U)
    tile.io.clear.color.g := Mux(seqClear, s.io.mmio.clearColorHi(15, 0), 0.U)
    tile.io.clear.color.b := Mux(seqClear, s.io.mmio.clearColorLo(31, 16), 0.U)
    tile.io.clear.color.z := Mux(seqClear, s.io.mmio.clearColorLo(15, 0), 0x7BFF.U(16.W))

    // Two-step protocol: shadow BZ written first, RG write triggers tile buffer write.
    // tile_bz_b/tile_bz_z are captured by the RDL (tile_bz_b_reg/tile_bz_z_reg) and
    // exposed via rdlRegs.io.hw.tile_bz_b/.tile_bz_z — no duplicate shadow needed (Step 26.5).

    val mmioTileWriteEn = bus.is_writing && bus.address === BorgGpuRegs.tile_rg_offset
    tile.io.write.en  := mmioTileWriteEn || rast.io.tileWrite.en
    tile.io.write.idx := Mux(rast.io.tileWrite.en, rast.io.tileWrite.idx, tileReadIdx)
    
    val writeColor = Wire(new ColorZ(16))
    writeColor.r := bus.data_in(31, 16)
    writeColor.g := bus.data_in(15, 0)
    writeColor.b := rdlRegs.io.hw.tile_bz_b   // from RDL tile_bz_b_reg (Step 26.5)
    writeColor.z := rdlRegs.io.hw.tile_bz_z   // from RDL tile_bz_z_reg (Step 26.5)
    tile.io.write.data := Mux(rast.io.tileWrite.en, rast.io.tileWrite.data, writeColor)
    // MSAA coverage deltas, computed once per triangle by the setup shader and
    // latched in BorgSequencer (Step 50.2b).  Before the first triangle's setup
    // completes these read as zero, which puts every sample's threshold at ±0 —
    // all four samples test at the pixel centre, i.e. 4× degenerates to 1×
    // rather than to anything invalid.
    rast.io.covDelta.foreach(_ := s.io.covDelta.get)
    io.covDeltaDebug.foreach(_ := s.io.covDelta.get)

    // Coverage: the rasterizer supplies a per-sample mask from the depth test;
    // an MMIO poke has no coverage concept and writes every sample (same
    // all-samples semantics as a clear).
    tile.io.write.coverage := Mux(rast.io.tileWrite.en,
                                  rast.io.tileWrite.coverage,
                                  Fill(cfg.samples, 1.U(1.W)))

    // Read port: flusher > dispatcher depth-test > MMIO CTRL.
    // Flusher and dispatcher never fire simultaneously (flusher waits for
    // autoRunStall to drop).  Mux required for correctness.
    tile.io.read.idx := Mux(f.io.read.en, f.io.read.idx,
                           Mux(rast.io.tileRead.en, rast.io.tileRead.idx,
                              Mux(ctrlWriting, bus.data_in(3, 0), tileReadIdx)))
    tile.io.read.en  := f.io.read.en || rast.io.tileRead.en || ctrlWriting

    // Feed tile read data back to both flusher and dispatcher.
    rast.io.tileRead.data := tile.io.read.data

    // Stencil plane (Step 50 item 10). It shares the colour plane's index,
    // enable and clear sequence -- the mux above already selected them --
    // so only the data paths need wiring here.
    rast.io.stencilRead.foreach(_ := tile.io.stencilRead.get)
    tile.io.stencilWrite.foreach(_ := rast.io.stencilWrite.get)
    tile.io.stencilWriteMask.foreach(_ := rast.io.stencilWriteMask.get)
    tile.io.stencilClear.foreach(_ := rdlRegs.io.hw.plane_clear_stencil)

    // Destination-alpha plane (Step 50 item 9). Same piggyback on the colour
    // plane's index/enable/clear as the stencil plane.
    rast.io.alphaRead.foreach(_ := tile.io.alphaRead.get)
    tile.io.alphaWrite.foreach(_ := rast.io.alphaWrite.get)
    tile.io.alphaWriteMask.foreach(_ := rast.io.alphaWriteMask.get)
    tile.io.alphaClear.foreach(_ := rdlRegs.io.hw.plane_clear_alpha)

    // --- Multi-pass MSAA control (BorgConfig.msaaMultiPass) --------------
    // The sequencer renders the tile once per sample and folds each finished
    // pass into the accumulator; `resolve` switches the read port to the
    // average for the flush.
    tile.io.pass.foreach { p =>
      val sp = s.io.pass.get
      p.sampleIdx  := sp.sampleIdx
      p.accumEn    := sp.accumEn
      p.accumFirst := sp.accumFirst
      p.resolve    := sp.resolve
      sp.accumBusy := p.accumBusy
    }
  }

  /** Step 25.4.1: Wire BorgTileFlusher with real DRAM writes.
    *
    * - `start`        : driven by `rast.io.tileComplete`.
    * - `fbBase`/`zbBase` : absolute DRAM byte addresses (decoded from nogen regs).
    * - `fbWidthLog2`  : log2(width) stored in the lower 4 bits of FLUSH_WIDTH reg.
    *                    Firmware writes `__builtin_ctz(BORG_FB_WIDTH)`.
    * - `status_flush_busy` : fed back into STATUS bit 4.
    * - tile read port: muxed in wireTileBuffer() above.
    */
  private def wireFlusher(): Unit = {
    val flushTileBaseReg = RegInit(0.U(25.W))
    when(bus.is_writing && bus.address === BorgGpuRegs.flush_fb_base_offset) {
      flushTileBaseReg := bus.data_in(24, 0)
    }

    val flushPending = RegInit(false.B)
    // Legacy per-tile flush: only active when sequencer is idle.
    // When the sequencer is busy, it manages flushes via s.io.flusher.trigger.
    when(rast.io.tileComplete && !s.io.busy) { flushPending := true.B }

    val seqFlushActive = s.io.flusher.trigger

    when(flushPending && !rast.io.autoRunStall && !seqFlushActive) {
      f.io.start   := true.B
      flushPending := false.B
    }.elsewhen(seqFlushActive) {
      f.io.start   := true.B
      when(flushPending) { flushPending := false.B }
    }.otherwise {
      f.io.start := false.B
    }

    f.io.read.data := tile.io.read.data
    f.io.tileBase  := Mux(seqFlushActive, s.io.flusher.base, flushTileBaseReg)

    // Depth-attachment write-out (only present at cfg.hasDepthFlush). The
    // FLUSH_ZB_BASE register has existed in the register map since Step
    // 25.3h but was never wired to anything -- the flusher simply never
    // wrote Z. It is decoded here exactly like FLUSH_FB_BASE above (both
    // are `nogen` regs read straight off the raw bus).
    //
    // Enable convention: a nonzero zb_base means a depth attachment is
    // bound. Firmware that never writes the register leaves it at its
    // reset value of 0 and gets the historical colour-only flush, so no
    // firmware change is needed to keep existing targets working.
    f.io.depthBase.foreach { p =>
      val flushDepthBaseReg = RegInit(0.U(25.W))
      when(bus.is_writing && bus.address === BorgGpuRegs.flush_zb_base_offset) {
        flushDepthBaseReg := bus.data_in(24, 0)
      }
      p := flushDepthBaseReg
      f.io.depthEn.get := flushDepthBaseReg =/= 0.U
    }

    s.io.flusher.busy := f.io.busy
    rdlRegs.io.hw.status_flush_busy := (flushPending || f.io.busy).asUInt
  }

  // @doc:mmio
  private def wireMmioRead(): Unit = {
    val fifo = Module(new BorgCommandFIFO(cfg.fifoDepth, cfg.coordWidth))

    val writeCmd = bus.is_writing && bus.address === BorgGpuRegs.cmd_enqueue_offset
    val isEnqueue = RegNext(writeCmd, false.B)
    
    val seqEnqueue = s.io.iter.enqueue.valid
    fifo.io.enq.valid := isEnqueue || seqEnqueue
    fifo.io.enq.bits.tileOrigin.x := Mux(seqEnqueue, s.io.iter.enqueue.bits.x, rdlRegs.io.hw.cmd_enqueue_tile_x(cfg.coordWidth - 1, 0))
    fifo.io.enq.bits.tileOrigin.y := Mux(seqEnqueue, s.io.iter.enqueue.bits.y, rdlRegs.io.hw.cmd_enqueue_tile_y(cfg.coordWidth - 1, 0))

    rast.io.cmdPop <> fifo.io.deq
    // A compute dispatch reads uniform page 0 -- the page its workgroup IDs are written to.
    core.io.uniformPage := Mux(computeBusy, 0.U,
                           Mux(s.io.busy, s.io.uniformWritePage, rast.io.uniformPage))
    core.io.seqBusy     := s.io.seqShaderActive

    // O8: use RDL's internal readAddr (RegNext of address) instead of a duplicate register.
    val read_addr_del = RegNext(bus.address)

    // RDL Iter state injection
    rdlRegs.io.hw.iter_x := rast.io.iter.x
    rdlRegs.io.hw.iter_y := rast.io.iter.y
    rdlRegs.io.hw.iter_valid := rast.io.iterValid
    rdlRegs.io.hw.iter_inside_flag := rast.io.insideFlag

    // RDL tile fields are hw=r (Step 21.0 O1): no shadow register feedback needed.
    // MMIO reads of TILE_RG/TILE_BZ are proxied via data_out MuxCase below.

    val stsFifoFull = !fifo.io.enq.ready
    rdlRegs.io.hw.status_idle := !core.io.status.running
    // Sticky divergence flag (STATUS bit 6) -- see BorgCore.wireBranch.
    rdlRegs.io.hw.status_branch_divergent := core.io.branchDivergent
    rdlRegs.io.hw.status_exec_fault       := core.io.execFault
    rdlRegs.io.hw.status_fifo_full := stsFifoFull

    // =========================================================================
    // Texture Fetch Hardware (Step 16.3 / Step 21.2)
    // =========================================================================
    // The legacy autonomous single-texel fetch this Morton pipeline used to
    // feed (rast.io.texConfig.mortonIndex, driving the dispatcher's now-
    // removed sTexFetch state) is gone -- texturing is exclusively FTEX-
    // inline now, which computes and clamps its own Morton index internally
    // per texel request (BorgShaderDispatcher's ftexMortonIndex). The
    // tex_addr MMIO register (morton/raw_u/raw_v) is kept, tied to zero,
    // rather than removed from the RDL map -- it was never read by firmware
    // and removing it would renumber every register after it.
    rdlRegs.io.hw.tex_addr_morton := 0.U
    rdlRegs.io.hw.tex_addr_raw_u  := 0.U
    rdlRegs.io.hw.tex_addr_raw_v  := 0.U

    val rdl_read_data = rdlRegs.io.bus.readData
    // tile_rg/tile_bz readback arms kept unconditionally: needed by the test harness
    // (readTilePixel) and by the CPU flush path (hasFlusher=false).
    val data_out = MuxCase(rdl_read_data, Seq(
      (read_addr_del < 128.U) -> core.io.regReadData,
      // MMIO tile readback is a debug/test path: it reports sample 0.  (The
      // resolved value lives in DRAM after the flush; the harness that uses
      // these arms writes and reads single samples.)
      (read_addr_del === BorgGpuRegs.tile_rg_offset) -> Cat(tile.io.read.data(0).r, tile.io.read.data(0).g),
      (read_addr_del === BorgGpuRegs.tile_bz_offset) -> Cat(tile.io.read.data(0).b, tile.io.read.data(0).z),
      // Repurpose the write-only SEQ_TRIGGER address for reading seqDoneSticky.
      // Firmware reads this after triggering with triCount=0 to detect
      // whether the sequencer hardware is present.
      (read_addr_del === BorgGpuRegs.seq_trigger_offset) -> seqDoneSticky.asUInt
    ) ++ computeDoneSticky.map { done =>
      // A build without compute leaves this address to rdl_read_data, which
      // returns 0 for a nogen register: `present` reads back clear.
      // Bit 3: barrier_fault (sticky, cleared by the next dispatch's start --
      // see BorgComputeSequencer's class doc). Bits 2:0: present/busy/done.
      (read_addr_del === BorgGpuRegs.compute_ctrl_offset) ->
        Cat(compute.get.io.barrierFault, true.B, computeBusy, done)
    })

    // Resp drive: data_out is combinational from read_addr_del (= RegNext of
    // bus.address), which is stable from the cycle after req.fire onward —
    // exactly when mmioRespPending first asserts.
    io.mmio.resp.valid := mmioRespPending && !rast.io.autoRunStall
    io.mmio.resp.bits  := data_out
  }
  // @doc:end

  private def wireDMA(): Unit = {
    val dmaBaseReg   = RegInit(0.U(20.W))
    val dmaLenReg    = RegInit(0.U(6.W))
    val dmaDestReg   = RegInit(0.U(2.W))
    val dmaOffsetReg = RegInit(0.U(6.W))
    val dmaStartPulse = WireDefault(false.B)

    when(bus.is_writing && bus.address === BorgGpuRegs.dma_dram_offset) {
      dmaBaseReg := bus.data_in(19, 0)
    }
    when(bus.is_writing && bus.address === BorgGpuRegs.dma_config_offset) {
      dmaLenReg    := bus.data_in(6, 1)
      dmaDestReg   := bus.data_in(8, 7)
      dmaOffsetReg := bus.data_in(14, 9)
      dmaStartPulse := bus.data_in(0)
    }

    val mmioDesc = Wire(new DMADescriptor)
    mmioDesc.baseAddr := dmaBaseReg
    mmioDesc.length   := dmaLenReg
    mmioDesc.dest     := dmaDestReg
    mmioDesc.offset   := dmaOffsetReg

    // DMA mux: sequencer > MMIO
    d.io.start     := Mux(s.io.busy, s.io.dma.start, dmaStartPulse)
    d.io.desc      := Mux(s.io.busy, s.io.dma.desc,  mmioDesc)
    s.io.dma.busy  := d.io.busy
    rdlRegs.io.hw.status_dma_busy := d.io.busy.asUInt
  }

  /** Step 29.0/29.1/29.2: Wire BorgSequencer MMIO decode, status bit, and
    * core/pipeline snoop interfaces.
    *
    * MMIO registers (all nogen — decoded directly from bus):
    * - `seq_desc_base`   (0x220): 20-bit DRAM descriptor address.
    * - `seq_trigger`     (0x224): singlepulse start (bit 0).
    * - `seq_vert_addr`   (0x228): 20-bit DRAM address of vertex shader binary.
    * - `seq_vert_len`    (0x22C): vertex shader length in 32-bit words (6 bits).
    * - `seq_setup_addr`  (0x230): 20-bit DRAM address of setup shader (Step 29.2).
    * - `seq_setup_len`   (0x234): setup shader length in 32-bit words (Step 29.2).
    *
    * Step 29.1 additions:
    * - CoreStatus and PipeWrite snooped from BorgCore (broadcast, no mux needed).
    * - DMA and CoreTrigger muxed in wireDMA() and wireCore() respectively.
    *
    * Step 29.2 additions:
    * - Uniform write port muxed in wireCore() (sequencer > DMA).
    */
  /** One pipeline stage on the sequencer's static MMIO configuration when
    * `pipelineSeqConfig` is set. These paths run from the bus decode to
    * BorgSequencer across the die -- seqSetupBaseReg measured 1,939 um, and
    * the design's antenna violators have a 715 um median span against 47 um
    * for all nets. They are point-to-point, so replication and dontTouch
    * cannot help; a register splits each net in two and, having a single
    * consumer, cannot be merged away.
    *
    * Every field is static configuration and `start` is delayed with them, so
    * what the sequencer sees is unchanged apart from one cycle of absolute
    * latency after the MMIO write.
    */
  private def seqCfgPipe[T <: chisel3.Data](x: T): T =
    if (cfg.pipelineSeqConfig) RegNext(x) else x

  private def wireSequencer(): Unit = {
    val seqDescBaseReg   = RegInit(0.U(20.W))
      val seqVertAddrReg   = RegInit(0.U(20.W))
      val seqVertLenReg    = RegInit(0.U(6.W))
      val seqSetupAddrReg  = RegInit(0.U(20.W))
      val seqSetupLenReg   = RegInit(0.U(6.W))
      val seqInvWidthReg   = RegInit(0.U(cfg.totalBits.W))
      val seqStartPulse    = WireDefault(false.B)
      val seqTriCountReg   = RegInit(0.U(5.W))
      val seqRastAddrReg   = RegInit(0.U(20.W))
      val seqRastLenReg    = RegInit(0.U(6.W))
      val seqFragAddrReg   = RegInit(0.U(20.W))
      val seqFragLenReg    = RegInit(0.U(6.W))
      val seqClearLoReg    = RegInit(0.U(32.W))
      val seqClearHiReg    = RegInit(0.U(32.W))
      val seqFbBaseReg     = RegInit(0.U(25.W))
      val seqTilesPerRowReg = RegInit(0.U(10.W))
      val seqBinBaseReg     = RegInit(0.U(25.W))
      val seqBinRowBytesReg = RegInit(0.U(20.W))
      val seqSetupBaseReg   = RegInit(0.U(25.W))

      when(bus.is_writing && bus.address === BorgGpuRegs.seq_desc_base_offset)    { seqDescBaseReg := bus.data_in(19, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_trigger_offset) {
        seqStartPulse := bus.data_in(0)
        seqDoneSticky := false.B   // clear sticky on new trigger
      }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_vert_addr_offset)    { seqVertAddrReg := bus.data_in(19, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_vert_len_offset)     { seqVertLenReg := bus.data_in(5, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_setup_addr_offset)   { seqSetupAddrReg := bus.data_in(19, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_setup_len_offset)    { seqSetupLenReg := bus.data_in(5, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_inv_width_offset)    { seqInvWidthReg := bus.data_in(cfg.totalBits - 1, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_tri_count_offset)    { seqTriCountReg := bus.data_in(4, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_rast_addr_offset)    { seqRastAddrReg := bus.data_in(19, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_rast_len_offset)     { seqRastLenReg := bus.data_in(5, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_frag_addr_offset)    { seqFragAddrReg := bus.data_in(19, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_frag_len_offset)     { seqFragLenReg := bus.data_in(5, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_clear_lo_offset)     { seqClearLoReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_clear_hi_offset)     { seqClearHiReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_fb_base_offset)      { seqFbBaseReg := bus.data_in(24, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_tiles_per_row_offset){ seqTilesPerRowReg := bus.data_in(9, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_bin_base_offset)     { seqBinBaseReg := bus.data_in(24, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_bin_row_bytes_offset){ seqBinRowBytesReg := bus.data_in(19, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_setup_base_offset)   { seqSetupBaseReg := bus.data_in(24, 0) }

      s.io.mmio.start           := seqCfgPipe(seqStartPulse)
      s.io.mmio.descBase        := seqCfgPipe(seqDescBaseReg)
      s.io.mmio.vertShaderAddr  := seqCfgPipe(seqVertAddrReg)
      s.io.mmio.vertShaderLen   := seqCfgPipe(seqVertLenReg)
      s.io.mmio.setupShaderAddr := seqCfgPipe(seqSetupAddrReg)
      s.io.mmio.setupShaderLen  := seqCfgPipe(seqSetupLenReg)
      s.io.mmio.seqInvWidth     := seqCfgPipe(seqInvWidthReg)
      s.io.mmio.triCount        := seqCfgPipe(seqTriCountReg)
      s.io.mmio.rastShaderAddr  := seqCfgPipe(seqRastAddrReg)
      s.io.mmio.rastShaderLen   := seqCfgPipe(seqRastLenReg)
      s.io.mmio.fragShaderAddr  := seqCfgPipe(seqFragAddrReg)
      s.io.mmio.fragShaderLen   := seqCfgPipe(seqFragLenReg)
      s.io.mmio.clearColorLo    := seqCfgPipe(seqClearLoReg)
      s.io.mmio.clearColorHi    := seqCfgPipe(seqClearHiReg)
      s.io.mmio.fbBase          := seqCfgPipe(seqFbBaseReg)
      // seqTilesPerRowReg is the full RDL register width (10 bits); the
      // sequencer's tilesPerRow/fbWidthTiles/fbHeightTiles ports may be
      // narrower (BorgConfig.Wafer) -- see SeqMmioIO's tileRowWidth comment.
      s.io.mmio.tilesPerRow     := seqCfgPipe(seqTilesPerRowReg(s.io.mmio.tilesPerRow.getWidth - 1, 0))
      s.io.mmio.binBase         := seqCfgPipe(seqBinBaseReg)
      // seqBinRowBytesReg is the full RDL register width (20 bits); the
      // sequencer's binRowBytes port may be narrower -- see SeqMmioIO's
      // binRowBytesWidth comment.
      s.io.mmio.binRowBytes     := seqCfgPipe(seqBinRowBytesReg(s.io.mmio.binRowBytes.getWidth - 1, 0))
      s.io.mmio.setupBase       := seqCfgPipe(seqSetupBaseReg)
      s.io.mmio.fbWidthTiles    := seqCfgPipe(seqTilesPerRowReg(s.io.mmio.fbWidthTiles.getWidth - 1, 0))
      s.io.mmio.fbHeightTiles   := seqCfgPipe(seqTilesPerRowReg(s.io.mmio.fbHeightTiles.getWidth - 1, 0))  // square framebuffer assumption
      s.io.mmio.fragUsesFragPos := seqCfgPipe(rdlRegs.io.hw.tex_config_frag_uses_fragpos)
      // CULL_CFG (Step 50). Reset 2/0 = cull back faces with the historical
      // winding convention, so firmware that never writes it sees no change.
      s.io.mmio.cullMode        := seqCfgPipe(rdlRegs.io.hw.cull_cfg_cull_mode)
      s.io.mmio.frontFaceInvert := seqCfgPipe(rdlRegs.io.hw.cull_cfg_front_face_invert.asBool)
      s.io.iter.complete        := rast.io.tileComplete
      s.io.iter.stall           := rast.io.autoRunStall
      // Dispatcher pipeline idle — sequencer waits for this before flushing
      // to prevent the "last pixel race" (flusher reads slot 15 before
      // dispatcher writes it).
      s.io.iter.dispatcherIdle  := rast.io.dispatcherPhase === 0.U  // sIdle = 0

      // CoreStatus and PipeWrite: broadcast snoop
      s.io.coreStatus.running        := core.io.status.running
      s.io.coreStatus.autoRunPending := core.io.status.autoRunPending
      // Sequencer snoops lane 0 (vertex/setup shaders are scalar, single-lane).
      s.io.pipeWrite.en   := core.io.pipeWrite(0).en
      s.io.pipeWrite.addr := core.io.pipeWrite(0).addr
      s.io.pipeWrite.data := core.io.pipeWrite(0).data

      // Latch sticky done when sequencer pulses io.done
      when(s.io.done) { seqDoneSticky := true.B }

      rdlRegs.io.hw.status_seq_busy := s.io.busy.asUInt
  }

  /** Step 32.2: Wire BorgBinner — sequencer-driven geometry pass binning.
    *
    * The sequencer drives the binner's start/triIndex/bbox/clearCounts
    * during its new sBinTri state. When no sequencer is present (shouldn't
    * happen in practice — hasBinner implies hasSequencer), inputs are tied
    * to safe defaults.
    *
    * The binner's GpuMemIO write port is muxed into the DRAM arbitration
    * in wireRasterizer().
    */
  private def wireBinner(): Unit = {
    b.io.start       := s.io.binner.start
    b.io.triIndex    := s.io.binner.triIndex
    b.io.bbox        := s.io.binner.bbox
    b.io.clearCounts := s.io.binner.clearCounts
    b.io.binBase     := s.io.mmio.binBase
    b.io.binRowBytes := s.io.mmio.binRowBytes
    b.io.tilesPerRow := s.io.mmio.tilesPerRow
    s.io.binner.busy := b.io.busy
    b.io.countReadAddr := s.io.binner.countReadAddr
    b.io.countReadEn   := s.io.binner.countReadEn
    s.io.binner.countReadData := b.io.countReadData

    // GpuMem feedback
    b.io.gpuMem.data  := io.gpuMem.data
    b.io.gpuMem.ready := io.gpuMem.ready && b.io.busy && !d.io.busy && !f.io.busy
    b.io.gpuMem.waccept := false.B   // binner writes single words, never bursts

    // Store ready
    s.io.store.ready := io.gpuMem.ready && s.io.store.active &&
                        !d.io.busy && !f.io.busy && !b.io.busy
  }
}
