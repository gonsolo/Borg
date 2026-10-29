// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*
import hutt.HuttBus

/** The shader processor's ports: a single-outstanding 10-bit MMIO bus whose reads and writes
  * respond one cycle after `req.fire`, and the GPU memory port.
  */
class BorgIO(val cfg: BorgConfig) extends Bundle with BorgMmioIf {
  val mmio = Flipped(new HuttBus(10))

  val gpuMem = new GpuMemIO

}

/** The Borg shader processor: wires the core, rasterizer, tile buffer, flusher, loader, DMA,
  * sequencer, binner and the RDL register block. hardware/rdl/borg.rdl is the address map.
  */
class Borg(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgIO(cfg))
  dontTouch(io)

  val bus = Wire(new BorgBusIO())

  // Flusher before tile: arcilator evaluates instances in declaration order, and tile's read_en
  // depends combinationally on the flusher's.
  // Each block gets its own registered reset copy: one shared reset net grew a repeater tree
  // that placement could not legalize. All copies release on the same cycle; a chain would
  // stagger release and lose registers written right after reset.
  private def resetCopy(name: String): Bool = {
    val r = RegNext(reset.asBool, true.B)
    r.suggestName(s"rstCopy_$name")
    dontTouch(r)
    r
  }

  val core      = withReset(resetCopy("core"))   { Module(new BorgCore(cfg)) }
  val rast      = withReset(resetCopy("rast"))   { Module(new BorgRasterizer(cfg)) }
  // RAW64 needs the tile's extension word, which rides the alpha plane's machinery.
  private val rawExt = cfg.hasBlend
  val flusher   = withReset(resetCopy("flush"))  { Module(new BorgTileFlusher(16, cfg.samples, cfg.hasDepthFlush, cfg.hasBlend, cfg.hasStencil, cfg.tileDepthBits, rawExt)) }   // before tile — see note above
  // loadOp = LOAD: the flusher's reverse; shares its reset copy.
  val loader    = withReset(resetCopy("flush"))  { Module(new BorgTileLoader(cfg.hasDepthFlush, cfg.hasStencil, cfg.tileDepthBits, cfg.samples)) }
  val tile      = withReset(resetCopy("tile"))   { Module(new BorgTileBuffer(16, cfg.samples, cfg.hasStencil, cfg.hasBlend, cfg.tileDepthBits, rawExt)) }
  val rdlRegs   = withReset(resetCopy("regs"))   { Module(new BorgGpuRegs()) }
  val dma       = withReset(resetCopy("dma"))    { Module(new BorgDMA(cfg)) }
  val sequencer = withReset(resetCopy("seq"))    { Module(new BorgSequencer(cfg)) }
  val binner    = withReset(resetCopy("bin"))    { Module(new BorgBinner(cfg.maxBinTiles, cfg.maxTrianglesPerTile, cfg.coordWidth)) }
  val compute   = Option.when(cfg.hasCompute) {
    withReset(resetCopy("comp")) { Module(new BorgComputeSequencer(cfg)) }
  }
  private val computeBusy = compute.map(_.io.busy).getOrElse(false.B)
  // Set when a dispatch completes, cleared by the next start (COMPUTE_CTRL bit 0).
  val computeDoneSticky = Option.when(cfg.hasCompute)(RegInit(false.B))

  // Set by the sequencer's done pulse, cleared by the next SEQ_TRIGGER write.
  val seqDoneSticky = RegInit(false.B)

  private def d = dma
  private def f = flusher
  private def ld = loader
  private def s = sequencer
  // Render window, decoded in wireSequencer: tile rows (binner clamp) and
  // the window's origin in pixels (FragCoord, scissor).
  private val windowRows   = WireDefault(0.U(12.W))
  private val pixelOriginX = WireDefault(0.U(14.W))
  private val pixelOriginY = WireDefault(0.U(14.W))
  // The current pass's colour attachment, decoded in wireDraw.
  private val attBase     = WireDefault(0.U(GpuMemIO.AddrBits.W))
  private val attFormat   = WireDefault(0.U(FlushFormat.Bits.W))
  private val attSrgb     = WireDefault(false.B)
  private val attClearRG  = WireDefault(0.U(32.W))
  private val attClearB   = WireDefault(0.U(16.W))
  private val attClearA   = WireDefault(0.U(8.W))
  private val attClearExt = WireDefault(0.U(32.W))    // RAW64's word 1
  private val attLoad     = WireDefault(false.B)
  private val attFirst    = WireDefault(true.B)
  private val attLast     = WireDefault(true.B)
  private val tileLoadColor = WireDefault(false.B)
  private def b = binner

  // Declared before the wire* calls: Scala initializes vals in declaration order.
  val mmioRespPending = RegInit(false.B)
  val mmioReqAddrReg  = RegEnable(io.mmio.req.bits.addr,  io.mmio.req.fire)
  val mmioReqDataReg  = RegEnable(io.mmio.req.bits.data,  io.mmio.req.fire)

  private val zBits = cfg.tileDepthBits
  private def depthFromFp16(z16: UInt): UInt =
    if (zBits == 32) Fp16Fp32.widen(z16) else z16
  // For the FP16-only MMIO debug path.
  private def depthToFp16(z: UInt): UInt =
    if (zBits == 32) Fp16Fp32.narrow(z) else z
  val depthD32Reg     = RegInit(false.B)        // DEPTH_FORMAT
  val clearDepthReg   = RegInit(0.U(32.W))      // CLEAR_DEPTH (FP32)
  val clearDepthSet   = RegInit(false.B)        // CLEAR_DEPTH written since reset
  val attachMsReg     = RegInit(false.B)
  // SAMPLE_MASK_CFG: [samples-1:0] static mask (reset all ones), [4] alpha to
  // coverage, [5] the shader's own mask (sample_mask), [6] rasterizationSamples = 1.
  val smCfg = RegInit(((1 << cfg.samples) - 1).U(7.W))
  private def singleSample: Bool = smCfg(6) && (cfg.samples > 1).B
  private def msScale(offset: UInt): UInt =
    if (cfg.samples > 1) Mux(attachMsReg, offset * cfg.samples.U, offset) else offset
  // CLEAR_DEPTH once written: a Vulkan clear like 0.3 rounded to FP16 moves by many D16 steps.
  private def seqClearDepth: UInt =
    if (zBits == 32) Mux(clearDepthSet, clearDepthReg, depthFromFp16(sequencer.io.mmio.clearColorLo(15, 0)))
    else sequencer.io.mmio.clearColorLo(15, 0)

  wireBus()
  wireRdlRegs()
  wireCore()
  wireRasterizer()
  wireTileBuffer()
  wireFlusher()
  private val perfIssueRead = WireDefault(0.U(32.W))   // wirePerf's, for wireMmioRead
  wireSequencer()
  wireBinner()
  wireMmioRead()
  wireDMA()
  wirePerf()
  wireCompute()

  // COMPUTE_* are nogen in the RDL, so a build without compute has no storage for them.
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

  // Counters reset when the sequencer starts and freeze when it stops, so firmware reads the
  // last frame's breakdown. They overlap: a stalled flush cycle counts in flush and stall.
  private def wirePerf(): Unit = {
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
    val st = core.io.issueStats
    val issue = Seq(st.issued, st.waitReg, st.waitBranch, st.waitDrain, st.waitEmpty, st.waitUnit, st.drains)
      .map(counter)
    val sel = RegInit(0.U(3.W))
    when(bus.is_writing && bus.address === BorgGpuRegs.perf_issue_offset) { sel := bus.data_in(2, 0) }
    perfIssueRead := VecInit(issue :+ 0.U(32.W))(sel)
  }

  private def wireBus(): Unit = {
    // Holds addr/data for the cycle after req.fire, for the RegNext'd read paths.
    bus.address    := Mux(io.mmio.req.fire, io.mmio.req.bits.addr, mmioReqAddrReg)
    bus.data_in    := Mux(io.mmio.req.fire, io.mmio.req.bits.data, mmioReqDataReg)
    bus.is_writing := io.mmio.req.fire &&  io.mmio.req.bits.write
    bus.is_reading := io.mmio.req.fire && !io.mmio.req.bits.write

    // Render-list register writes, never while a response is pending (its read data follows the
    // held address). SEQ_TRIGGER is not a draw's to write.
    val play = s.io.regWrite
    play.ready := !mmioRespPending
    when(play.fire) {
      bus.address    := play.bits.addr
      bus.data_in    := play.bits.data
      bus.is_writing := play.bits.addr =/= BorgGpuRegs.seq_trigger_offset
      bus.is_reading := false.B
    }

    io.mmio.req.ready := !mmioRespPending && !rast.io.autoRunStall && !play.valid

    when(io.mmio.req.fire) { mmioRespPending := true.B }
    when(io.mmio.resp.fire) { mmioRespPending := false.B }
  }

  private def wireCore(): Unit = {
    core.io.bus <> bus
    core.io.iter       := rast.io.shaderIter    // latched pre-advance position for coordLut

    // The sequencer wins while it asserts a trigger. Compute never overlaps a render.
    val nonSeqTrigger = compute.map { c =>
      Mux(c.io.coreTrigger.valid, c.io.coreTrigger, rast.io.coreTrigger)
    }.getOrElse(rast.io.coreTrigger)
    core.io.coreTrigger.valid  := Mux(s.io.coreTrigger.valid, true.B,
                                                nonSeqTrigger.valid)
    core.io.coreTrigger.pc     := Mux(s.io.coreTrigger.valid, s.io.coreTrigger.pc,
                                                nonSeqTrigger.pc)
    core.io.coreTrigger.isRast := Mux(s.io.coreTrigger.valid, s.io.coreTrigger.isRast,
                                                nonSeqTrigger.isRast)
    core.io.coreTrigger.isSetup := s.io.coreTrigger.valid && s.io.coreTrigger.isSetup
    // Sequencers and compute run on slot 0.
    val otherTrigger = s.io.coreTrigger.valid || compute.map(_.io.coreTrigger.valid).getOrElse(false.B)
    core.io.triggerSlot := Mux(otherTrigger, 0.U(cfg.slotBits.W), rast.io.triggerSlot)
    // ids, record and drawMode: see wireDraw.
    core.io.barrier.foreach(compute.get.io.barrier := _)

    core.io.control.start            := rdlRegs.io.hw.control_start
    core.io.control.reset            := rdlRegs.io.hw.control_reset_pipeline
    core.io.control.startPC          := rdlRegs.io.hw.control_start_pc
    core.io.control.uniformWritePage := Mux(s.io.busy,
                                            s.io.uniformWritePage,
                                            rdlRegs.io.hw.control_uniform_write_page)

    core.io.dmaImemWrite <> d.io.imemWrite
    // u31 is firmware-owned. The setup reload DMAs 32 words whose word 31 only the snoop needs,
    // so the DMA never writes uniform slot 31.
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
    s.io.dma.uniformSnoop.en   := d.io.uniformWrite.en
    s.io.dma.uniformSnoop.addr := d.io.uniformWrite.addr(2, 0)
    s.io.dma.uniformSnoop.data := d.io.uniformWrite.data
    s.io.dma.snoop             := d.io.snoop

    rast.io.zTestReq := core.io.zTestReq
    rast.io.zTestSlot := core.io.zTestSlot
    core.io.zTestDone := rast.io.zTestDone
    core.io.laneHelper := rast.io.laneHelper
  }

  private def wireRasterizer(): Unit = {
    rast.io.advance   := (bus.is_writing && bus.address === BorgGpuRegs.iter_offset) ||
                         s.io.iter.iterate

    rast.io.csrWrite <> core.io.csrWrite
    rast.io.csrSlot := core.io.csrSlot
    rast.io.coreStatus <> core.io.slotStatus
    rast.io.iterSlot   := core.io.iterSlot
    rast.io.helperSlot := core.io.helperSlot

    // GPU memory priority: DMA > flusher > loader > binner/setup store > core.
    val geoBusy  = b.io.busy || s.io.store.active
    val geoReq   = Mux(b.io.busy, b.io.gpuMem.req,   s.io.store.req)
    val geoAddr  = Mux(b.io.busy, b.io.gpuMem.addr,  s.io.store.addr)
    val geoWr    = Mux(b.io.busy, b.io.gpuMem.wr,    true.B)
    val geoWdata = Mux(b.io.busy, b.io.gpuMem.wdata, s.io.store.wdata)

    val coreMem = core.io.memBusy
    def coreOr(sel: GpuMemIO => UInt, fallback: UInt): UInt = Mux(coreMem, sel(core.io.gpuMem), fallback)
    def coreOrB(sel: GpuMemIO => Bool, fallback: Bool): Bool = Mux(coreMem, sel(core.io.gpuMem), fallback)

    io.gpuMem.req   := Mux(d.io.busy, d.io.gpuMem.req,
                       Mux(f.io.busy, f.io.gpuMem.req,
                       Mux(ld.io.busy, ld.io.gpuMem.req,
                       Mux(geoBusy, geoReq, coreOrB(_.req, false.B)))))
    io.gpuMem.addr  := Mux(d.io.busy, d.io.gpuMem.addr,
                       Mux(f.io.busy, f.io.gpuMem.addr,
                       Mux(ld.io.busy, ld.io.gpuMem.addr,
                       Mux(geoBusy, geoAddr, coreOr(_.addr, 0.U)))))
    io.gpuMem.wr    := Mux(d.io.busy, false.B,  // DMA only reads — never assert wr
                       Mux(f.io.busy, f.io.gpuMem.wr,
                       Mux(ld.io.busy, false.B,  // the loader only reads
                       Mux(geoBusy, geoWr, coreOrB(_.wr, false.B)))))
    // Every memory behind this port stores a write word as one halfword. The setup store and
    // STORE write wider words, so they go out as a two-beat burst, low halfword first.
    val wordData = Mux(geoBusy, geoWdata, coreOr(_.wdata, 0.U))
    val wideWr   = !d.io.busy && !f.io.busy && !ld.io.busy &&
      Mux(geoBusy, !b.io.busy, coreOrB(_.wr, false.B))
    // Registered, so no combinational path from waccept back into wdata.
    val upperHalf = RegInit(false.B)
    when(io.gpuMem.ready) { upperHalf := false.B }
      .elsewhen(wideWr && io.gpuMem.waccept) { upperHalf := true.B }
    val splitData = Mux(upperHalf, wordData(31, 16), wordData(15, 0))
    io.gpuMem.wdata := Mux(f.io.busy, f.io.gpuMem.wdata,
                       Mux(wideWr, splitData, wordData))
    locally {
      val g = core.io.gpuMem
      g.data    := io.gpuMem.data
      g.ready   := io.gpuMem.ready && !d.io.busy && !f.io.busy && !ld.io.busy && !geoBusy && coreMem
      g.waccept := false.B
    }
    core.io.root := rdlRegs.io.hw.root_addr
    io.gpuMem.wlen  := Mux(f.io.busy, f.io.gpuMem.wlen, Mux(wideWr, 2.U, 1.U))
    f.io.gpuMem.data  := io.gpuMem.data
    f.io.gpuMem.ready := io.gpuMem.ready && !d.io.busy && f.io.busy
    // waccept is only high in the flusher's own burst; a f.io.busy guard would loop through
    // io_read_en, which arcilator mishandles.
    f.io.gpuMem.waccept := io.gpuMem.waccept
    d.io.gpuMem.data  := io.gpuMem.data
    d.io.gpuMem.ready := io.gpuMem.ready && d.io.busy
    d.io.gpuMem.waccept := false.B
    ld.io.gpuMem.data  := io.gpuMem.data
    ld.io.gpuMem.ready := io.gpuMem.ready && ld.io.busy && !d.io.busy && !f.io.busy
    ld.io.gpuMem.waccept := false.B

    rast.io.fragPcReg      := rdlRegs.io.hw.frag_pc_frag_pc
    rast.io.depthCompareOp := rdlRegs.io.hw.depth_cfg_compare_op
    rast.io.depthUnorm     := !depthD32Reg
    rast.io.rawColor       := FlushFormat.isRaw(attFormat)
    rast.io.srgbColor      := attSrgb
    rast.io.depthWriteEn   := rdlRegs.io.hw.depth_cfg_write_en.asBool
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
    // Front-facing outside a sequencer render (the MMIO pixel path).
    rast.io.frontFacing.foreach(_ := Mux(s.io.busy, s.io.frontFacingOverride, true.B))
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
    // Reset enable=0 passes every fragment rather than meaning an empty rectangle.
    rast.io.scissor.enable := rdlRegs.io.hw.scissor_y_enable.asBool
    rast.io.scissor.x0     := rdlRegs.io.hw.scissor_x_x0
    rast.io.scissor.x1     := rdlRegs.io.hw.scissor_x_x1
    rast.io.scissor.y0     := rdlRegs.io.hw.scissor_y_y0
    rast.io.scissor.y1     := rdlRegs.io.hw.scissor_y_y1
    rast.io.uniformPageReg := rdlRegs.io.hw.control_uniform_write_page
  }

  private def wireTileBuffer(): Unit = {
    val ctrlWriting = bus.is_writing && bus.address === BorgGpuRegs.tile_ctrl_offset
    val tileReadIdx = rdlRegs.io.hw.tile_ctrl_read_idx
    
    val seqClear = s.io.iter.clear
    tile.io.clear.en := rdlRegs.io.hw.tile_ctrl_clear.asBool || seqClear

    tile.io.clear.color.r := Mux(seqClear, attClearRG(31, 16), 0.U)
    tile.io.clear.color.g := Mux(seqClear, attClearRG(15, 0), 0.U)
    tile.io.clear.color.b := Mux(seqClear, attClearB, 0.U)
    when(bus.is_writing && bus.address === BorgGpuRegs.depth_format_offset) {
      depthD32Reg := bus.data_in(0) && (zBits == 32).B
    }
    when(bus.is_writing && bus.address === BorgGpuRegs.clear_depth_offset) {
      clearDepthReg := bus.data_in
      clearDepthSet := true.B
    }
    tile.io.clear.color.z := Mux(seqClear, seqClearDepth, depthFromFp16(0x7BFF.U(16.W)))

    // MMIO tile writes: TILE_BZ first, then the TILE_RG write commits the entry.
    val mmioTileWriteEn = bus.is_writing && bus.address === BorgGpuRegs.tile_rg_offset
    // The loader writes before any fragment of the tile runs, so it never contends.
    val ldWrite = ld.io.write.en
    tile.io.write.en  := mmioTileWriteEn || rast.io.tileWrite.en || ldWrite
    // A fragment failing stencil or depth writes no colour but still updates stencil.
    val rastWrites = rast.io.tileWrite.en ||
      rast.io.stencilWriteMask.map(_ =/= 0.U).getOrElse(false.B)
    tile.io.write.idx := Mux(ldWrite, ld.io.write.idx,
                         Mux(rastWrites, rast.io.tileWrite.idx, tileReadIdx))
    
    val writeColor = Wire(new ColorZ(16, zBits))
    writeColor.r := bus.data_in(31, 16)
    writeColor.g := bus.data_in(15, 0)
    writeColor.b := rdlRegs.io.hw.tile_bz_b
    writeColor.z := depthFromFp16(rdlRegs.io.hw.tile_bz_z)   // MMIO speaks FP16
    tile.io.write.data := Mux(ldWrite, ld.io.write.data,
                          Mux(rast.io.tileWrite.en, rast.io.tileWrite.data, writeColor))
    // Zero before the first setup, which only degenerates MSAA to one sample at the centre.
    rast.io.covDelta.foreach(_ := s.io.covDelta.get)

    // An MMIO poke writes every sample, like a clear.
    tile.io.write.coverage := Mux(ldWrite, ld.io.write.coverage,
                              Mux(rast.io.tileWrite.en, rast.io.tileWrite.coverage,
                                  Fill(cfg.samples, 1.U(1.W))))

    // Flusher and dispatcher never read together: the flusher waits for autoRunStall to drop.
    tile.io.read.idx := Mux(f.io.read.en, f.io.read.idx,
                           Mux(rast.io.tileRead.en, rast.io.tileRead.idx,
                              Mux(ctrlWriting, bus.data_in(3, 0), tileReadIdx)))
    tile.io.read.en  := f.io.read.en || rast.io.tileRead.en || ctrlWriting

    rast.io.tileRead.data := tile.io.read.data

    // Stencil, alpha and extension planes share the colour plane's index, enable and clear.
    rast.io.stencilRead.foreach(_ := tile.io.stencilRead.get)
    tile.io.stencilWrite.foreach(_ := Mux(ldWrite, ld.io.write.stencil, rast.io.stencilWrite.get))
    tile.io.stencilWriteMask.foreach(_ := Mux(ldWrite, ld.io.write.coverage,
                                              rast.io.stencilWriteMask.get))
    tile.io.stencilClear.foreach(_ := rdlRegs.io.hw.plane_clear_stencil)

    rast.io.alphaRead.foreach(_ := tile.io.alphaRead.get)
    tile.io.alphaWrite.foreach(_ := Mux(ldWrite, ld.io.write.alpha, rast.io.alphaWrite.get))
    tile.io.alphaWriteMask.foreach(_ := ldWrite || rast.io.alphaWriteMask.get)
    tile.io.alphaClear.foreach(_ := attClearA)

    rast.io.extRead.foreach(_ := tile.io.extRead.get)
    tile.io.extWrite.foreach(_ := Mux(ldWrite, ld.io.write.ext, rast.io.extWrite.get))
    tile.io.extActive.foreach(_ := FlushFormat.is64(attFormat))
    tile.io.extClear.foreach(_ := attClearExt)
  }

  private def wireFlusher(): Unit = {
    val flushTileBaseReg = RegInit(0.U(GpuMemIO.AddrBits.W))
    when(bus.is_writing && bus.address === BorgGpuRegs.flush_fb_base_offset) {
      flushTileBaseReg := bus.data_in
    }

    val flushPending = RegInit(false.B)
    // MMIO-path tile flush; the sequencer triggers its own.
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
    f.io.alpha.foreach(_ := tile.io.alphaRead.get)
    f.io.ext.foreach(_ := tile.io.extRead.get)
    f.io.format    := attFormat
    f.io.srgb      := attSrgb
    f.io.colorOff  := rdlRegs.io.hw.flush_format_nocolor
    // The sequencer's tile offset is in 32-byte units, one RGB565 tile.
    val seqTileOffset = s.io.flusher.tileOffset
    val colourOffset  = msScale(FlushFormat.scaleTile(attFormat, seqTileOffset))
    f.io.tileBase  := Mux(seqFlushActive, attBase + colourOffset, flushTileBaseReg)

    // A nonzero FLUSH_ZB_BASE binds a depth attachment. A D32 tile is twice a D16 tile.
    val depthTileOffset = msScale(Mux(depthD32Reg, seqTileOffset << 1, seqTileOffset))
    f.io.depthD32.foreach(_ := depthD32Reg)
    // Also the tile loader's depth source, so it exists in every build.
    val flushDepthBaseReg = RegInit(0.U(GpuMemIO.AddrBits.W))
    when(bus.is_writing && bus.address === BorgGpuRegs.flush_zb_base_offset) {
      flushDepthBaseReg := bus.data_in
    }
    f.io.depthBase.foreach { p =>
      p := Mux(seqFlushActive, flushDepthBaseReg + depthTileOffset, flushDepthBaseReg)
      // With several colour attachments every pass renders the same depth;
      // store it once, in the last pass, so no earlier pass's load sees it.
      f.io.depthEn.get := flushDepthBaseReg =/= 0.U && (!seqFlushActive || attLast)
    }

    // S8 stencil: 16 bytes per tile, half a depth tile's offset; nonzero base = bound.
    val stencilBaseReg = RegInit(0.U(GpuMemIO.AddrBits.W))
    when(bus.is_writing && bus.address === BorgGpuRegs.flush_sb_base_offset) {
      stencilBaseReg := bus.data_in
    }
    val stencilTileBase = stencilBaseReg + msScale(seqTileOffset >> 1)

    when(bus.is_writing && bus.address === BorgGpuRegs.attach_ms_offset) {
      attachMsReg := bus.data_in(0) && (cfg.samples > 1).B
    }
    f.io.msStore.foreach(_ := attachMsReg)
    ld.io.msLoad.foreach(_ := attachMsReg)
    s.io.mmio.singleSample := singleSample
    f.io.stencil.foreach(_ := tile.io.stencilRead.get)
    f.io.stencilBase.foreach(_ := Mux(seqFlushActive, stencilTileBase, stencilBaseReg))
    f.io.stencilEn.foreach(_ := stencilBaseReg =/= 0.U && (!seqFlushActive || attLast))

    // Tile load: the clear values fill every aspect that is not loaded.
    val tileLoadReg = RegInit(0.U.asTypeOf(new TileLoadAspects))
    val tileKeepReg = RegInit(false.B)
    when(bus.is_writing && bus.address === BorgGpuRegs.tile_load_offset) {
      tileLoadReg.color   := bus.data_in(0)
      tileLoadReg.depth   := bus.data_in(1)
      tileLoadReg.stencil := bus.data_in(2)
      tileKeepReg         := bus.data_in(3)
    }
    // A later part of a partial render loads every aspect the last part stored.
    val aspects = WireDefault(tileLoadReg)
    aspects.depth   := tileLoadReg.depth || s.io.loadAll && flushDepthBaseReg =/= 0.U
    aspects.stencil := tileLoadReg.stencil || s.io.loadAll && stencilBaseReg =/= 0.U
    s.io.mmio.tileKeep := tileKeepReg || s.io.loadAll
    tileLoadColor := tileLoadReg.color
    s.io.mmio.tileLoad := attLoad || aspects.depth || aspects.stencil
    ld.io.start       := s.io.flusher.loadStart
    s.io.flusher.loadBusy := ld.io.busy
    ld.io.aspects     := aspects
    ld.io.aspects.color := attLoad
    ld.io.format      := attFormat
    ld.io.srgb        := attSrgb
    ld.io.colorBase   := attBase + colourOffset
    ld.io.depthBase.foreach(_ := flushDepthBaseReg + depthTileOffset)
    ld.io.depthD32.foreach(_ := depthD32Reg)
    ld.io.stencilBase.foreach(_ := stencilTileBase)
    ld.io.clearColor.r := attClearRG(31, 16)
    ld.io.clearColor.g := attClearRG(15, 0)
    ld.io.clearColor.b := attClearB
    ld.io.clearColor.z := seqClearDepth
    ld.io.clearAlpha   := attClearA
    ld.io.clearStencil := rdlRegs.io.hw.plane_clear_stencil
    ld.io.clearExt     := attClearExt

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

    val read_addr_del = RegNext(bus.address)

    rdlRegs.io.hw.iter_x := rast.io.iter.x
    rdlRegs.io.hw.iter_y := rast.io.iter.y
    rdlRegs.io.hw.iter_valid := rast.io.iterValid
    rdlRegs.io.hw.iter_inside_flag := rast.io.insideFlag

    val stsFifoFull = !fifo.io.enq.ready
    rdlRegs.io.hw.status_idle := !core.io.status.running
    rdlRegs.io.hw.status_branch_divergent := core.io.branchDivergent
    rdlRegs.io.hw.status_exec_fault       := core.io.execFault
    rdlRegs.io.hw.status_fifo_full := stsFifoFull

    // Unused, tied to zero: removing it from the RDL would renumber every later register.
    rdlRegs.io.hw.tex_addr_morton := 0.U
    rdlRegs.io.hw.tex_addr_raw_u  := 0.U
    rdlRegs.io.hw.tex_addr_raw_v  := 0.U

    // Occlusion queries count passed samples of triangles in the window; on the MMIO pixel path
    // every fragment counts.
    val occEnable = RegInit(false.B)
    val occFirst  = RegInit(0.U(16.W))
    val occLast   = RegInit(0xFFFF.U(16.W))
    val occCount  = RegInit(0.U(32.W))
    when(bus.is_writing && bus.address === BorgGpuRegs.occ_tri_range_offset) {
      occFirst := bus.data_in(15, 0)
      occLast  := bus.data_in(31, 16)
    }
    val tri = s.io.curTriIndex
    val inWindow = !s.io.busy || (tri >= occFirst && tri < occLast)
    when(bus.is_writing && bus.address === BorgGpuRegs.occ_ctrl_offset) {
      occEnable := bus.data_in(0)
      when(bus.data_in(1)) { occCount := 0.U }
    }.elsewhen(occEnable && inWindow && attFirst) {
      occCount := occCount + rast.io.occSamples
    }

    val rdl_read_data = rdlRegs.io.bus.readData
    val data_out = MuxCase(rdl_read_data, Seq(
      (read_addr_del < 128.U) -> core.io.regReadData,
      // Debug readback for the test harness (readTilePixel); reports sample 0.
      (read_addr_del === BorgGpuRegs.tile_rg_offset) -> Cat(tile.io.read.data(0).r, tile.io.read.data(0).g),
      (read_addr_del === BorgGpuRegs.tile_bz_offset) -> Cat(tile.io.read.data(0).b, depthToFp16(tile.io.read.data(0).z)),
      // The write-only SEQ_TRIGGER reads back seqDoneSticky.
      (read_addr_del === BorgGpuRegs.seq_trigger_offset) -> seqDoneSticky,
      (read_addr_del === BorgGpuRegs.occ_count_offset)   -> occCount,
      (read_addr_del === BorgGpuRegs.occ_ctrl_offset)    -> occEnable.asUInt,
      (read_addr_del === BorgGpuRegs.occ_tri_range_offset) -> Cat(occLast, occFirst),
      (read_addr_del === BorgGpuRegs.perf_issue_offset)  -> perfIssueRead
    ) ++ computeDoneSticky.map { done =>
      // Without compute this nogen register reads 0, so `present` is clear.
      (read_addr_del === BorgGpuRegs.compute_ctrl_offset) ->
        Cat(compute.get.io.barrierFault, true.B, computeBusy, done)
    })

    io.mmio.resp.valid := mmioRespPending && !rast.io.autoRunStall
    io.mmio.resp.bits  := data_out
  }
  // @doc:end

  private def wireDMA(): Unit = {
    val dmaBaseReg   = RegInit(0.U(GpuMemIO.AddrBits.W))
    val dmaLenReg    = RegInit(0.U(6.W))
    val dmaDestReg   = RegInit(0.U(2.W))
    val dmaOffsetReg = RegInit(0.U(6.W))
    val dmaStartPulse = WireDefault(false.B)

    when(bus.is_writing && bus.address === BorgGpuRegs.dma_dram_offset) {
      dmaBaseReg := bus.data_in
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

    d.io.start     := Mux(s.io.busy, s.io.dma.start, dmaStartPulse)
    d.io.desc      := Mux(s.io.busy, s.io.dma.desc,  mmioDesc)
    s.io.dma.busy  := d.io.busy

    // A DMA into IMEM sets the I-cache's code base: source A to word i gives A - 4*i, and flushes.
    // Taken at the first IMEM write: at the start pulse the descriptor's offset may still be stale.
    // The core lets that write win over the flush, so its line stays valid.
    locally {
      val codeBaseReg  = RegInit(0.U(GpuMemIO.AddrBits.W))
      val awaitFirst   = RegInit(false.B)
      val flush = WireDefault(false.B)
      when(d.io.start) { awaitFirst := true.B }
      when(d.io.imemWrite.en && awaitFirst) {
        codeBaseReg := d.io.gpuMem.addr - (d.io.imemWrite.addr << 2)
        awaitFirst  := false.B
        flush := true.B
      }
      when(!d.io.busy && !d.io.start) { awaitFirst := false.B }  // a non-IMEM transfer ended
      when(bus.is_writing && bus.address === BorgGpuRegs.code_base_offset) {
        codeBaseReg := bus.data_in
        flush := true.B
      }
      core.io.codeBase := codeBaseReg
      core.io.icacheFlush := flush
    }
    rdlRegs.io.hw.status_dma_busy := d.io.busy.asUInt
  }

  // Splits the long point-to-point nets from the bus decode to the sequencer (antenna
  // violators). `start` is delayed with the static fields, so only latency changes.
  private def seqCfgPipe[T <: chisel3.Data](x: T): T =
    RegNext(x)

  private def wireSequencer(): Unit = {
      val seqVertAddrReg   = RegInit(0.U(GpuMemIO.AddrBits.W))
      val seqVertLenReg    = RegInit(0.U(6.W))
      val seqStartPulse    = WireDefault(false.B)
      val seqFragAddrReg   = RegInit(0.U(GpuMemIO.AddrBits.W))
      val seqFragLenReg    = RegInit(0.U(6.W))
      val seqClearLoReg    = RegInit(0.U(32.W))
      val seqClearHiReg    = RegInit(0.U(32.W))
      val seqFbBaseReg     = RegInit(0.U(GpuMemIO.AddrBits.W))
      val seqTilesPerRowReg = RegInit(0.U(10.W))
      val seqBinBaseReg     = RegInit(0.U(GpuMemIO.AddrBits.W))
      val seqBinRowBytesReg = RegInit(0.U(20.W))
      val seqSetupBaseReg   = RegInit(0.U(GpuMemIO.AddrBits.W))

      when(bus.is_writing && bus.address === BorgGpuRegs.seq_trigger_offset) {
        seqStartPulse := bus.data_in(0)
        seqDoneSticky := false.B
      }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_vert_addr_offset)    { seqVertAddrReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_vert_len_offset)     { seqVertLenReg := bus.data_in(5, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_frag_addr_offset)    { seqFragAddrReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_frag_len_offset)     { seqFragLenReg := bus.data_in(5, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_clear_lo_offset)     { seqClearLoReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_clear_hi_offset)     { seqClearHiReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_fb_base_offset)      { seqFbBaseReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_tiles_per_row_offset){ seqTilesPerRowReg := bus.data_in(9, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_bin_base_offset)     { seqBinBaseReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_bin_row_bytes_offset){ seqBinRowBytesReg := bus.data_in(19, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_setup_base_offset)   { seqSetupBaseReg := bus.data_in }
      val renderListReg = RegInit(0.U(GpuMemIO.AddrBits.W))
      when(bus.is_writing && bus.address === BorgGpuRegs.render_list_offset)      { renderListReg := bus.data_in }
      s.io.mmio.listBase := seqCfgPipe(renderListReg)

      s.io.mmio.start           := seqCfgPipe(seqStartPulse)
      s.io.mmio.vertShaderAddr  := seqCfgPipe(seqVertAddrReg)
      s.io.mmio.vertShaderLen   := seqCfgPipe(seqVertLenReg)
      s.io.mmio.fragShaderAddr  := seqCfgPipe(seqFragAddrReg)
      s.io.mmio.fragShaderLen   := seqCfgPipe(seqFragLenReg)
      s.io.mmio.clearColorLo    := seqCfgPipe(seqClearLoReg)
      s.io.mmio.clearColorHi    := seqCfgPipe(seqClearHiReg)
      s.io.mmio.fbBase          := seqCfgPipe(seqFbBaseReg)
      // The sequencer's ports may be narrower than the RDL registers.
      s.io.mmio.tilesPerRow     := seqCfgPipe(seqTilesPerRowReg(s.io.mmio.tilesPerRow.getWidth - 1, 0))
      s.io.mmio.binBase         := seqCfgPipe(seqBinBaseReg)
      s.io.mmio.binRowBytes     := seqCfgPipe(seqBinRowBytesReg(s.io.mmio.binRowBytes.getWidth - 1, 0))
      s.io.mmio.setupBase       := seqCfgPipe(seqSetupBaseReg)
      s.io.mmio.fbWidthTiles    := seqCfgPipe(seqTilesPerRowReg(s.io.mmio.fbWidthTiles.getWidth - 1, 0))
      // Render window: SEQ_TILE_ROWS 0 = square, FB_PITCH 0 = the window's width.
      val tileRowsReg = RegInit(0.U(12.W)); val originReg = RegInit(0.U(32.W)); val pitchReg = RegInit(0.U(12.W))
      when(bus.is_writing && bus.address === BorgGpuRegs.seq_tile_rows_offset) { tileRowsReg := bus.data_in(11, 0) }
      when(bus.is_writing && bus.address === BorgGpuRegs.fb_origin_offset)     { originReg := bus.data_in }
      when(bus.is_writing && bus.address === BorgGpuRegs.fb_pitch_offset)      { pitchReg := bus.data_in(11, 0) }
      val rows = Mux(tileRowsReg === 0.U, seqTilesPerRowReg, tileRowsReg)
      s.io.mmio.fbHeightTiles   := seqCfgPipe(rows(s.io.mmio.fbHeightTiles.getWidth - 1, 0))
      s.io.mmio.fbOriginX       := seqCfgPipe(originReg(11, 0))
      s.io.mmio.fbOriginY       := seqCfgPipe(originReg(27, 16))
      s.io.mmio.fbPitch         := seqCfgPipe(Mux(pitchReg === 0.U, seqTilesPerRowReg, pitchReg))
      windowRows := rows
      pixelOriginX := Cat(originReg(11, 0), 0.U(2.W)); pixelOriginY := Cat(originReg(27, 16), 0.U(2.W))
      // CULL_CFG. Reset 2/0 = cull back faces, front counter-clockwise.
      s.io.mmio.cullMode        := seqCfgPipe(rdlRegs.io.hw.cull_cfg_cull_mode)
      s.io.mmio.frontFaceInvert := seqCfgPipe(rdlRegs.io.hw.cull_cfg_front_face_invert.asBool)
      s.io.iter.complete        := rast.io.tileComplete
      s.io.iter.stall           := rast.io.autoRunStall
      // Flushing waits for it, or the flusher reads slot 15 before the dispatcher writes it.
      s.io.iter.dispatcherIdle  := rast.io.dispatcherIdle

      s.io.coreStatus.running        := core.io.status.running
      s.io.coreStatus.autoRunPending := core.io.status.autoRunPending
      wireDraw()

      when(s.io.done) { seqDoneSticky := true.B }

      rdlRegs.io.hw.status_seq_busy := s.io.busy.asUInt
  }

  /** The draw front end's registers and connections (docs/B1_geometry_front_end.md). */
  private def wireDraw(): Unit = {
    val cfgReg   = RegInit(0x100.U(32.W))      // record_shift reset 8, like the RDL
    val words    = Seq(BorgGpuRegs.draw_vertex_count_offset, BorgGpuRegs.draw_instance_count_offset,
                       BorgGpuRegs.draw_first_vertex_offset, BorgGpuRegs.draw_first_instance_offset,
                       BorgGpuRegs.draw_vertex_offset_offset, BorgGpuRegs.draw_index_base_offset,
                       BorgGpuRegs.viewport_sx_offset, BorgGpuRegs.viewport_sy_offset,
                       BorgGpuRegs.viewport_ox_offset, BorgGpuRegs.viewport_oy_offset,
                       BorgGpuRegs.depth_scale_offset, BorgGpuRegs.depth_offset_offset,
                       BorgGpuRegs.draw_vs_const_offset, BorgGpuRegs.draw_fs_const_offset,
                       BorgGpuRegs.depth_bias_const_offset, BorgGpuRegs.depth_bias_slope_offset)
    val w = words.map(_ => RegInit(0.U(32.W)))
    // A sequencer render is always a draw; DRAW_CFG's mode only picks the MMIO pixel path's program.
    val drawMode = cfgReg(0) || s.io.busy
    when(bus.is_writing && bus.address === BorgGpuRegs.draw_cfg_offset) { cfgReg := bus.data_in }
    for ((r, off) <- w.zip(words)) when(bus.is_writing && bus.address === off) { r := bus.data_in }
    s.io.mmio.recordShift := cfgReg(9, 6)
    s.io.mmio.vsConstBase := w(12)
    s.io.mmio.fsConstBase := w(13)
    val d = s.io.draw
    d.topology := cfgReg(2, 1); d.indexType := cfgReg(4, 3); d.restart := cfgReg(5)
    d.recordShift := cfgReg(9, 6)
    d.vertexCount := w(0); d.instanceCount := w(1); d.firstVertex := w(2); d.firstInstance := w(3)
    d.vertexOffset := w(4); d.indexBase := w(5)
    for (i <- 0 until 4) d.viewport(i) := w(6 + i)
    d.depthScale := w(10); d.depthOffset := w(11)
    d.singleSample := singleSample
    d.depthBiasConst := w(14); d.depthBiasSlope := w(15); d.depthD32 := depthD32Reg
    s.io.csrWriteLanes := core.io.csrWrite
    core.io.drawMode := drawMode
    core.io.drawSingle := (cfg.samples == 1).B || singleSample
    core.io.setupShort := ((cfg.samples == 1).B || singleSample) && w(14) === 0.U && w(15) === 0.U
    rast.io.drawMode := drawMode
    rast.io.topLeft := s.io.topLeft
    // The walker owns r30/r31 while it runs a vertex shader, compute otherwise.
    val computeIds = compute.map(_.io.lanes).getOrElse(0.U.asTypeOf(core.io.ids))
    core.io.ids := Mux(s.io.ids.mode, s.io.ids, computeIds)
    core.io.record := s.io.record

    core.io.descWritten := bus.is_writing && bus.address === BorgGpuRegs.desc_flush_offset

    when(bus.is_writing && bus.address === BorgGpuRegs.sample_mask_cfg_offset) { smCfg := bus.data_in(6, 0) }
    rast.io.sampleCfg.mask       := smCfg(cfg.samples - 1, 0)
    rast.io.sampleCfg.alphaToCov := smCfg(4)
    rast.io.sampleCfg.shaderMask := smCfg(5)
    rast.io.sampleCfg.single     := singleSample
    core.io.laneCoverage := rast.io.laneCoverage
    core.io.laneDst := rast.io.laneDst
    core.io.pixelOrigin.x := pixelOriginX; core.io.pixelOrigin.y := pixelOriginY
    // Colour attachments 1..3; attachment 0 uses the SEQ_* and FLUSH_* registers.
    val attCfg = RegInit(0.U(8.W)); val attFmtReg = RegInit(0.U(12.W))
    val attBases = Seq.fill(3)(RegInit(0.U(GpuMemIO.AddrBits.W)))
    val attRG = Seq.fill(3)(RegInit(0.U(32.W))); val attBA = Seq.fill(3)(RegInit(0.U(32.W)))
    val attExt = Seq.fill(4)(RegInit(0.U(32.W)))
    for ((r, off) <- attExt.zip(Seq(BorgGpuRegs.att_clear_ext0_offset, BorgGpuRegs.att_clear_ext1_offset,
                                    BorgGpuRegs.att_clear_ext2_offset, BorgGpuRegs.att_clear_ext3_offset)))
      when(bus.is_writing && bus.address === off) { r := bus.data_in }
    when(bus.is_writing && bus.address === BorgGpuRegs.att_cfg_offset)    { attCfg := bus.data_in(7, 0) }
    when(bus.is_writing && bus.address === BorgGpuRegs.att_format_offset) { attFmtReg := bus.data_in(11, 0) }
    for ((r, off) <- attBases.zip(Seq(BorgGpuRegs.att_base1_offset, BorgGpuRegs.att_base2_offset, BorgGpuRegs.att_base3_offset)))
      when(bus.is_writing && bus.address === off) { r := bus.data_in }
    for ((r, off) <- attRG.zip(Seq(BorgGpuRegs.att_clear_rg1_offset, BorgGpuRegs.att_clear_rg2_offset, BorgGpuRegs.att_clear_rg3_offset)))
      when(bus.is_writing && bus.address === off) { r := bus.data_in }
    for ((r, off) <- attBA.zip(Seq(BorgGpuRegs.att_clear_ba1_offset, BorgGpuRegs.att_clear_ba2_offset, BorgGpuRegs.att_clear_ba3_offset)))
      when(bus.is_writing && bus.address === off) { r := bus.data_in }
    // RAW128 clears: words 2 and 3 of attachments 0..3, raw.
    val attW2 = Seq.fill(4)(RegInit(0.U(32.W))); val attW3 = Seq.fill(4)(RegInit(0.U(32.W)))
    for ((r, off) <- attW2.zip(Seq(BorgGpuRegs.att_clear_w2_0_offset, BorgGpuRegs.att_clear_w2_1_offset,
                                   BorgGpuRegs.att_clear_w2_2_offset, BorgGpuRegs.att_clear_w2_3_offset)) ++
                     attW3.zip(Seq(BorgGpuRegs.att_clear_w3_0_offset, BorgGpuRegs.att_clear_w3_1_offset,
                                   BorgGpuRegs.att_clear_w3_2_offset, BorgGpuRegs.att_clear_w3_3_offset)))
      when(bus.is_writing && bus.address === off) { r := bus.data_in }
    // One pass per attachment, two for RAW128 (its slices).
    val count = attCfg(1, 0) +& 1.U
    val fmts = Seq(rdlRegs.io.hw.flush_format_format, attFmtReg(2, 0), attFmtReg(5, 3), attFmtReg(8, 6))
    val nSlices = fmts.zipWithIndex.map { case (f, k) => Mux(k.U < count, 1.U(2.W) + FlushFormat.is128(f), 0.U(2.W)) }
    val firstPass = nSlices.scanLeft(0.U(4.W))(_ +& _).map(_(3, 0))      // pass of each attachment's slice 0
    val passes = firstPass(4)
    val pass = s.io.attPass
    val att = PriorityMux((3 to 1 by -1).map(k => (k.U < count && pass >= firstPass(k)) -> k.U(2.W)) :+ (true.B -> 0.U(2.W)))
    val slice = pass =/= VecInit(firstPass.take(4))(att)
    s.io.mmio.attCount := passes
    def sel[T <: Data](first: T, rest: Seq[T]): T = MuxLookup(att, first)((1 to 3).map(k => k.U -> rest(k - 1)))
    attBase    := sel(s.io.mmio.fbBase, attBases) + Mux(slice, 128.U, 0.U)
    attFormat  := sel(fmts(0), fmts.drop(1))
    attSrgb    := sel(rdlRegs.io.hw.flush_format_srgb, Seq(attFmtReg(9), attFmtReg(10), attFmtReg(11)))
    // RAW128 slice 1 clears to words 2 and 3, word 2 split into the UNORM8 channels it rides.
    val w2 = VecInit(attW2)(att)
    attClearRG := Mux(slice, Cat(ColorQuantize.dequantize8(w2(7, 0)), ColorQuantize.dequantize8(w2(15, 8))),
                  sel(s.io.mmio.clearColorHi, attRG))
    attClearB  := Mux(slice, ColorQuantize.dequantize8(w2(23, 16)), sel(s.io.mmio.clearColorLo(31, 16), attBA.map(_(31, 16))))
    attClearA  := Mux(slice, w2(31, 24), sel(rdlRegs.io.hw.plane_clear_alpha, attBA.map(_(7, 0))))
    attClearExt := Mux(slice, VecInit(attW3)(att), sel(attExt(0), attExt.drop(1)))
    attLoad    := sel(tileLoadColor, Seq(attCfg(2), attCfg(3), attCfg(4))) || s.io.loadAll
    attFirst   := pass === 0.U
    attLast    := pass === passes - 1.U
    core.io.attIndex := Cat(slice, att)
    rast.io.pixelOrigin.x := pixelOriginX; rast.io.pixelOrigin.y := pixelOriginY
  }

  private def wireBinner(): Unit = {
    b.io.start       := s.io.binner.start
    b.io.triIndex    := s.io.binner.triIndex
    b.io.bbox        := s.io.binner.bbox
    b.io.clearCounts := s.io.binner.clearCounts
    b.io.binBase     := s.io.mmio.binBase
    b.io.binRowBytes := s.io.mmio.binRowBytes
    b.io.tilesPerRow := s.io.mmio.tilesPerRow
    b.io.tileRows    := windowRows(b.io.tileRows.getWidth - 1, 0)
    s.io.binner.busy := b.io.busy
    b.io.countReadAddr := s.io.binner.countReadAddr
    b.io.countReadEn   := s.io.binner.countReadEn
    s.io.binner.countReadData := b.io.countReadData
    s.io.binner.full := b.io.full

    b.io.gpuMem.data  := io.gpuMem.data
    b.io.gpuMem.ready := io.gpuMem.ready && b.io.busy && !d.io.busy && !f.io.busy
    b.io.gpuMem.waccept := false.B   // binner writes single words, never bursts

    s.io.store.ready := io.gpuMem.ready && s.io.store.active &&
                        !d.io.busy && !f.io.busy && !b.io.busy
  }
}
