// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

sealed abstract class FloatConfig(val exp: Int, val sig: Int) {
  def totalBits: Int = 1 + exp + (sig - 1)
}

object FloatConfig {
  case object FP16 extends FloatConfig(5, 11)
  case object FP32 extends FloatConfig(8, 24)
}

/** BorgIO defines the interface for the shading processor. It uses
  * memory-mapped I/O for register and instruction memory access.
  */
class BorgIO(val cfg: BorgConfig) extends Bundle {
  val address = Input(
    UInt(10.W)
  ) // 1024-byte address space (byte-addressed internally by shifting)
  val data_in = Input(UInt(32.W))  // 32-bit data for IMEM writes; register writes use low cfg.totalBits
  val data_write_n = Input(UInt(2.W)) // 0b10 for write
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(32.W))  // 32-bit: register reads, tile buffer reads are packed 32-bit
  val data_ready = Output(Bool())
  val uo_out = Output(UInt(8.W))
  val user_interrupt = Output(Bool())

  // GPU read port (Step 19.2: sTexFetch → MemoryController)
  val gpuMem = new GpuMemIO
}

class RegFileCopyIO(width: Int) extends Bundle {
  val rd = Flipped(new MemReadPort(log2Ceil(32), width))
  val wr = Flipped(new MemWritePort(log2Ceil(32), width))
}

/** Single-copy register file with exactly 1 read + 1 write port.
  * Each instance gets a unique Verilog module name to prevent CIRCT
  * deduplication, ensuring yosys can infer iCE40 Block RAMs.
  */
class RegFileCopy(width: Int, instName: String) extends Module {
  override def desiredName = instName

  val io = IO(new RegFileCopyIO(width))

  val mem = SyncReadMem(32, UInt(width.W))
  io.rd.data := mem.read(io.rd.addr, io.rd.en)

  when(io.wr.en) {
    mem.write(io.wr.addr, io.wr.data)
  }
}

/** Borg — minimal FP16 shading processor with 4-cycle FMA pipeline.
  *
  * This is a thin integration wrapper that composes BorgCore (FPU pipeline)
  * and BorgRasterizer (pixel iterator) and wires the top-level MMIO read mux.
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
  *   - Registers 0–124 (32 words): read/write register file r0–r31
  *   - IMEM 128–248 (31 usable words): write instruction memory (32-bit)
  *   - Control/Status 252: write bit 0 = start, bit 1 = reset; read bit 1 = idle
  */
object Borg {
  /** Allow tests to instantiate [[Borg]] with a [[FloatConfig]] directly,
    * e.g. `new Borg(FloatConfig.FP16)`.  Maps to [[BorgConfig.Sim]] with the
    * requested float format and simulation-appropriate defaults.
    */
  def apply(fp: FloatConfig): Borg = new Borg(BorgConfig.Sim.copy(fp = fp))
}

class Borg(val cfg: BorgConfig = BorgConfig.Sim) extends Module {
  /** Auxiliary constructor: allows `new Borg(FloatConfig.FP16)` in tests. */
  def this(fp: FloatConfig) = this(BorgConfig.Sim.copy(fp = fp))

  val io = IO(new BorgIO(cfg))
  dontTouch(io)

  // --- Unified Bus Bundle ---
  val bus = Wire(new BorgBusIO())

  // --- Sub-modules ---
  val core = Module(new BorgCore(cfg))
  val rast = Module(new BorgRasterizer(cfg))
  val tile = Module(new BorgTileBuffer())
  val rdlRegs = Module(new BorgGpuRegs()) // Auto-generated RDL register block
  val dma = if (cfg.hasDMA) Some(Module(new BorgDMA)) else None
  val flusher = if (cfg.hasFlusher) Some(Module(new BorgTileFlusher())) else None
  val sequencer = if (cfg.hasSequencer) Some(Module(new BorgSequencer(cfg))) else None
  val binner = if (cfg.hasBinner) Some(Module(new BorgBinner())) else None

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


  private def wireRdlRegs(): Unit = {
    rdlRegs.io.bus.address   := bus.address
    rdlRegs.io.bus.writeData := bus.data_in
    rdlRegs.io.bus.writeEn   := bus.is_writing
    rdlRegs.io.bus.readEn    := bus.is_reading
  }

  private def wireBus(): Unit = {
    bus.address    := io.address
    bus.data_in    := io.data_in
    bus.is_writing := io.data_write_n === 2.U && RegNext(io.data_write_n) =/= 2.U
    bus.is_reading := io.data_read_n === 2.U
  }

  private def wireCore(): Unit = {
    core.io.bus <> bus
    core.io.iter       := rast.io.shaderIter    // latched pre-advance position for coordLut

    // CoreTrigger mux: sequencer takes priority ONLY when it is actively
    // asserting coreTrigger.valid (sRunVert, sRunSetup).  During tile iteration
    // (sWaitRast), the sequencer does NOT trigger the core — the dispatcher
    // does via rast.io.coreTrigger.  Using s.io.busy as the mux select would
    // block the dispatcher's trigger while the sequencer is busy, causing a
    // deadlock (autoRunStall never clears because the core never starts).
    sequencer match {
      case Some(s) =>
        core.io.coreTrigger.valid := Mux(s.io.coreTrigger.valid, true.B,
                                                    rast.io.coreTrigger.valid)
        core.io.coreTrigger.pc    := Mux(s.io.coreTrigger.valid, s.io.coreTrigger.pc,
                                                    rast.io.coreTrigger.pc)
      case None =>
        core.io.coreTrigger <> rast.io.coreTrigger
    }

    core.io.control.start            := rdlRegs.io.hw.control_start
    core.io.control.reset            := rdlRegs.io.hw.control_reset_pipeline
    core.io.control.startPC          := rdlRegs.io.hw.control_start_pc
    // Step 29.3: uniformWritePage mux — sequencer > MMIO.
    // The sequencer drives this during sStageUniforms for ping-pong (Step 13.4).
    sequencer match {
      case Some(s) =>
        core.io.control.uniformWritePage := Mux(s.io.busy,
                                               s.io.uniformWritePage,
                                               rdlRegs.io.hw.control_uniform_write_page)
      case None =>
        core.io.control.uniformWritePage := rdlRegs.io.hw.control_uniform_write_page
    }

    // CoordLut/RcpLut init port — only used during simulation; synthesis uses $readmemh
    core.io.lutInit.en    := false.B
    core.io.lutInit.isRcp := false.B
    core.io.lutInit.addr  := 0.U
    core.io.lutInit.data  := 0.U

    // DMA write ports (Step 22.1) — only wired when hasDMA=true.
    // Steps 29.2/29.3: Uniform write port is shared between DMA and sequencer;
    // sequencer takes priority (they never contend in practice).
    // Step 29.3: DMA's uniform write stream is also snooped by the sequencer
    // to capture color/z data during the vertex DMA phase.
    dma match {
      case Some(d) =>
        core.io.dmaImemWrite <> d.io.imemWrite
        sequencer match {
          case Some(s) =>
            core.io.dmaUniformWrite.en   := d.io.uniformWrite.en || s.io.uniformWrite.en
            core.io.dmaUniformWrite.addr := Mux(s.io.uniformWrite.en,
                                                s.io.uniformWrite.addr,
                                                d.io.uniformWrite.addr)
            core.io.dmaUniformWrite.data := Mux(s.io.uniformWrite.en,
                                                s.io.uniformWrite.data,
                                                d.io.uniformWrite.data)
            // Snoop: sequencer observes what DMA writes to the uniform buffer
            s.io.dmaUniformSnoop.en   := d.io.uniformWrite.en
            s.io.dmaUniformSnoop.addr := d.io.uniformWrite.addr(2, 0)  // low 3 bits = word offset 0-7
            s.io.dmaUniformSnoop.data := d.io.uniformWrite.data
            s.io.dmaSnoop             := d.io.snoop
          case None =>
            core.io.dmaUniformWrite <> d.io.uniformWrite
        }
      case None =>
        core.io.dmaImemWrite.en   := false.B
        core.io.dmaImemWrite.addr := 0.U
        core.io.dmaImemWrite.data := 0.U
        sequencer match {
          case Some(s) =>
            core.io.dmaUniformWrite.en   := s.io.uniformWrite.en
            core.io.dmaUniformWrite.addr := s.io.uniformWrite.addr
            core.io.dmaUniformWrite.data := s.io.uniformWrite.data
            s.io.dmaUniformSnoop.en   := false.B
            s.io.dmaUniformSnoop.addr := 0.U
            s.io.dmaUniformSnoop.data := 0.U
          case None =>
            core.io.dmaUniformWrite.en   := false.B
            core.io.dmaUniformWrite.addr := 0.U
            core.io.dmaUniformWrite.data := 0.U
        }
    }
  }

  private def wireRasterizer(): Unit = {
    rast.io.advance   := (bus.is_writing && bus.address === BorgGpuRegs.iter_offset) ||
                         sequencer.map(_.io.iteratePixels).getOrElse(false.B)

    // Pipeline write-back snoop
    rast.io.pipeWrite <> core.io.pipeWrite

    // Core state feedback
    rast.io.coreStatus <> core.io.status

    // GPU memory port: arbitration (Step 25.3g, Step 32.2 binner addition).
    // Priority: DMA > Flusher > Binner > Rast (texFetch).
    // In practice there is no contention: DMA runs between triangles,
    // flusher runs after a tile is complete, binner runs during geometry pass,
    // texFetch runs during per-pixel rasterization.  The mux is for correctness.
    //
    // Step 25.2: wr/wdata added for GPU write path.  DMA is read-only, so
    // write signals come from flusher, binner, or rast (priority order).
    val binnerBusy = binner.map(_.io.busy).getOrElse(false.B)
    val binnerReq  = binner.map(_.io.gpuMem.req).getOrElse(false.B)
    val binnerAddr = binner.map(_.io.gpuMem.addr).getOrElse(0.U)
    val binnerWr   = binner.map(_.io.gpuMem.wr).getOrElse(false.B)
    val binnerWdata = binner.map(_.io.gpuMem.wdata).getOrElse(0.U)

    (dma, flusher) match {
      case (Some(d), Some(f)) =>
        // 4-way mux: DMA > Flusher > Binner > Rast
        io.gpuMem.req   := Mux(d.io.busy, d.io.gpuMem.req,
                           Mux(f.io.busy, f.io.gpuMem.req,
                           Mux(binnerBusy, binnerReq,
                               rast.io.gpuMem.req)))
        io.gpuMem.addr  := Mux(d.io.busy, d.io.gpuMem.addr,
                           Mux(f.io.busy, f.io.gpuMem.addr,
                           Mux(binnerBusy, binnerAddr,
                               rast.io.gpuMem.addr)))
        io.gpuMem.wr    := Mux(f.io.busy, f.io.gpuMem.wr,
                           Mux(binnerBusy, binnerWr,
                               rast.io.gpuMem.wr))   // DMA never writes
        io.gpuMem.wdata := Mux(f.io.busy, f.io.gpuMem.wdata,
                           Mux(binnerBusy, binnerWdata,
                               rast.io.gpuMem.wdata))
        rast.io.gpuMem.data     := io.gpuMem.data
        rast.io.gpuMem.ready    := io.gpuMem.ready && !d.io.busy && !f.io.busy && !binnerBusy
        f.io.gpuMem.data  := io.gpuMem.data
        f.io.gpuMem.ready := io.gpuMem.ready && !d.io.busy && f.io.busy
        d.io.gpuMem.data        := io.gpuMem.data
        d.io.gpuMem.ready       := io.gpuMem.ready && d.io.busy

      case (Some(d), None) =>
        // 3-way mux: DMA > Binner > Rast (no flusher on FPGA until Step 25.4)
        io.gpuMem.req   := Mux(d.io.busy, d.io.gpuMem.req,
                           Mux(binnerBusy, binnerReq,
                               rast.io.gpuMem.req))
        io.gpuMem.addr  := Mux(d.io.busy, d.io.gpuMem.addr,
                           Mux(binnerBusy, binnerAddr,
                               rast.io.gpuMem.addr))
        io.gpuMem.wr    := Mux(binnerBusy, binnerWr,
                               rast.io.gpuMem.wr)       // DMA never writes
        io.gpuMem.wdata := Mux(binnerBusy, binnerWdata,
                               rast.io.gpuMem.wdata)
        rast.io.gpuMem.data  := io.gpuMem.data
        rast.io.gpuMem.ready := io.gpuMem.ready && !d.io.busy && !binnerBusy
        d.io.gpuMem.data     := io.gpuMem.data
        d.io.gpuMem.ready    := io.gpuMem.ready && d.io.busy

      case (None, Some(f)) =>
        // 3-way mux: Flusher > Binner > Rast (no DMA)
        io.gpuMem.req   := Mux(f.io.busy, f.io.gpuMem.req,
                           Mux(binnerBusy, binnerReq,
                               rast.io.gpuMem.req))
        io.gpuMem.addr  := Mux(f.io.busy, f.io.gpuMem.addr,
                           Mux(binnerBusy, binnerAddr,
                               rast.io.gpuMem.addr))
        io.gpuMem.wr    := Mux(f.io.busy, f.io.gpuMem.wr,
                           Mux(binnerBusy, binnerWr,
                               rast.io.gpuMem.wr))
        io.gpuMem.wdata := Mux(f.io.busy, f.io.gpuMem.wdata,
                           Mux(binnerBusy, binnerWdata,
                               rast.io.gpuMem.wdata))
        rast.io.gpuMem.data     := io.gpuMem.data
        rast.io.gpuMem.ready    := io.gpuMem.ready && !f.io.busy && !binnerBusy
        f.io.gpuMem.data  := io.gpuMem.data
        f.io.gpuMem.ready := io.gpuMem.ready && f.io.busy

      case (None, None) =>
        // 2-way mux: Binner > Rast (FPGA without DMA or flusher)
        io.gpuMem.req   := Mux(binnerBusy, binnerReq, rast.io.gpuMem.req)
        io.gpuMem.addr  := Mux(binnerBusy, binnerAddr, rast.io.gpuMem.addr)
        io.gpuMem.wr    := Mux(binnerBusy, binnerWr, rast.io.gpuMem.wr)
        io.gpuMem.wdata := Mux(binnerBusy, binnerWdata, rast.io.gpuMem.wdata)
        rast.io.gpuMem.data  := io.gpuMem.data
        rast.io.gpuMem.ready := io.gpuMem.ready && !binnerBusy
    }

    // Texture configuration — wired from MMIO TEX_CONFIG register (Step 21.2)
    rast.io.texConfig.baseAddr := rdlRegs.io.hw.tex_config_base_addr
    rast.io.texConfig.en       := rdlRegs.io.hw.tex_config_en.asBool

    // frag_pc and uniform_page from dedicated registers
    rast.io.fragPcReg      := rdlRegs.io.hw.frag_pc_frag_pc
    rast.io.uniformPageReg := rdlRegs.io.hw.control_uniform_write_page
  }

  private def wireTileBuffer(): Unit = {
    // MMIO write to tile_ctrl: set read index (triggers BRAM read) or clear
    val ctrlWriting = bus.is_writing && bus.address === BorgGpuRegs.tile_ctrl_offset
    val tileReadIdx = rdlRegs.io.hw.tile_ctrl_read_idx
    
    tile.io.clear.en := rdlRegs.io.hw.tile_ctrl_clear.asBool ||
                        sequencer.map(_.io.tileCtrlClear).getOrElse(false.B)

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

    // Read port: flusher > dispatcher depth-test > MMIO CTRL.
    // Flusher and dispatcher never fire simultaneously (flusher waits for
    // autoRunStall to drop).  Mux required for correctness.
    flusher match {
      case Some(f) =>
        tile.io.read.idx := Mux(f.io.read.en, f.io.read.idx,
                               Mux(rast.io.tileRead.en, rast.io.tileRead.idx,
                                  Mux(ctrlWriting, bus.data_in(3, 0), tileReadIdx)))
        tile.io.read.en  := f.io.read.en || rast.io.tileRead.en || ctrlWriting
      case None =>
        tile.io.read.idx := Mux(rast.io.tileRead.en, rast.io.tileRead.idx,
                               Mux(ctrlWriting, bus.data_in(3, 0), tileReadIdx))
        tile.io.read.en  := rast.io.tileRead.en || ctrlWriting
    }

    // Feed tile read data back to both flusher and dispatcher.
    rast.io.tileRead.data := tile.io.read.data
  }

  /** Step 25.4.1: Wire BorgTileFlusher with real PSRAM writes.
    *
    * - `start`        : driven by `rast.io.tileComplete`.
    * - `fbBase`/`zbBase` : absolute PSRAM byte addresses (decoded from nogen regs).
    * - `fbWidthLog2`  : log2(width) stored in the lower 4 bits of FLUSH_WIDTH reg.
    *                    Firmware writes `__builtin_ctz(BORG_FB_WIDTH)`.
    * - `status_flush_busy` : fed back into STATUS bit 4.
    * - tile read port: muxed in wireTileBuffer() above.
    */
  private def wireFlusher(): Unit = {
    // flushTileBaseReg and flushPending are inside case Some(f) so they are
    // eliminated at elaboration time when hasFlusher=false.
    flusher match {
      case Some(f) =>
        // Single shadow register: firmware writes tileBase per tile.
        val flushTileBaseReg = RegInit(0.U(20.W))
        when(bus.is_writing && bus.address === BorgGpuRegs.flush_fb_base_offset) {
          flushTileBaseReg := bus.data_in(19, 0)
        }

        // Deferred flusher start: tileComplete and pixelReady fire on the same
        // cycle (the last iterator advance).  The dispatcher still needs to run
        // the last pixel's shader pipeline and write to the tile SRAM before the
        // flusher bulk-reads it.  Latch tileComplete and fire start only when
        // autoRunStall drops (dispatcher finished the last tile write).
        val flushPending = RegInit(false.B)
        when(rast.io.tileComplete) {
          flushPending := true.B
        }

        val seqFlushActive = sequencer.map(_.io.flushTrigger).getOrElse(false.B)

        when(flushPending && !rast.io.autoRunStall && !seqFlushActive) {
          f.io.start   := true.B
          flushPending := false.B
        }.elsewhen(seqFlushActive) {
          f.io.start   := true.B
          // Suppress the CPU-path pending latch when sequencer owns the flush,
          // so it cannot double-start the flusher after the sequencer finishes.
          when(flushPending) { flushPending := false.B }
        }.otherwise {
          f.io.start := false.B
        }

        f.io.read.data := tile.io.read.data
        f.io.tileBase  := Mux(seqFlushActive,
                              sequencer.map(_.io.flushBase).getOrElse(0.U),
                              flushTileBaseReg)

        // Sequencer waits only for the actual flusher DMA, not the CPU-path
        // flushPending latch (set by rast.io.tileComplete during seq iteration).
        sequencer.foreach(_.io.flushBusy := f.io.busy)

        // MMIO STATUS.flush_busy reflects both paths for firmware polling.
        rdlRegs.io.hw.status_flush_busy := (flushPending || f.io.busy).asUInt

      case None =>
        rdlRegs.io.hw.status_flush_busy := 0.U
    }
  }

  // @doc:mmio
  private def wireMmioRead(): Unit = {
    val fifo = Module(new BorgCommandFIFO(cfg.fifoDepth, cfg.coordWidth))

    val writeCmd = bus.is_writing && bus.address === BorgGpuRegs.cmd_enqueue_offset
    val isEnqueue = RegNext(writeCmd, false.B)
    
    val seqEnqueue = sequencer.map(_.io.enqueueTile.valid).getOrElse(false.B)
    val seqTileX   = sequencer.map(_.io.enqueueTile.bits.x).getOrElse(0.U)
    val seqTileY   = sequencer.map(_.io.enqueueTile.bits.y).getOrElse(0.U)

    fifo.io.enq.valid := isEnqueue || seqEnqueue
    fifo.io.enq.bits.tileOrigin.x := Mux(seqEnqueue, seqTileX, rdlRegs.io.hw.cmd_enqueue_tile_x(cfg.coordWidth - 1, 0))
    fifo.io.enq.bits.tileOrigin.y := Mux(seqEnqueue, seqTileY, rdlRegs.io.hw.cmd_enqueue_tile_y(cfg.coordWidth - 1, 0))

    rast.io.cmdPop <> fifo.io.deq
    // Step 30.1d: uniformPage mux — sequencer overrides when busy.
    // seqBusy gates the coord mux: r30/r31 return 0 during sequencer shader runs.
    sequencer match {
      case Some(s) =>
        core.io.uniformPage := Mux(s.io.busy, s.io.uniformWritePage, rast.io.uniformPage)
        core.io.seqBusy     := s.io.seqShaderActive
      case None =>
        core.io.uniformPage := rast.io.uniformPage
        core.io.seqBusy     := false.B
    }

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
    rdlRegs.io.hw.status_fifo_full := stsFifoFull

    // =========================================================================
    // Texture Fetch Hardware (Step 16.3 / Step 21.2)
    // =========================================================================
    // Morton encoder: always uses the rasterizer's snooped fragment U/V.
    // (The CPU TEX_UV register is preserved for MMIO compatibility but is
    //  no longer routed into the Morton pipeline — saves ~32 LUTs.)
    val tex_x = Fp16ToUint8(rast.io.fragU)
    val tex_y = Fp16ToUint8(rast.io.fragV)
    val morton_index = MortonEncode(tex_x, tex_y)

    rdlRegs.io.hw.tex_addr_morton := morton_index
    rdlRegs.io.hw.tex_addr_raw_u  := tex_x
    rdlRegs.io.hw.tex_addr_raw_v  := tex_y

    // Wire morton_index to rasterizer for sTexFetch (Step 19.2)
    rast.io.texConfig.mortonIndex := morton_index

    val rdl_read_data = rdlRegs.io.bus.readData
    // tile_rg/tile_bz readback arms kept unconditionally: needed by the test harness
    // (readTilePixel) and by the CPU flush path (hasFlusher=false).
    io.data_out := MuxCase(rdl_read_data, Seq(
      (read_addr_del < 128.U) -> core.io.regReadData,
      (read_addr_del === BorgGpuRegs.tile_rg_offset) -> Cat(tile.io.read.data.r, tile.io.read.data.g),
      (read_addr_del === BorgGpuRegs.tile_bz_offset) -> Cat(tile.io.read.data.b, tile.io.read.data.z)
    ))

    val read_ready_del = RegNext(bus.is_reading, false.B)
    io.data_ready := Mux(rast.io.autoRunStall, false.B, (io.data_read_n === 3.U) || read_ready_del)
    io.uo_out := 0.U
    io.user_interrupt := false.B
  }
  // @doc:end

  private def wireDMA(): Unit = {
    dma match {
      case Some(d) =>
        // DMA RDL registers have nogen=true (no hw ports generated).
        // Decode the DMA descriptor fields directly from the MMIO bus.
        // These registers mirror dma_psram @ 0x210 and dma_config @ 0x214.
        val dmaBaseReg   = RegInit(0.U(20.W))
        val dmaLenReg    = RegInit(0.U(6.W))
        val dmaDestReg   = RegInit(0.U(2.W))
        val dmaOffsetReg = RegInit(0.U(6.W))
        val dmaStartPulse = WireDefault(false.B)

        when(bus.is_writing && bus.address === BorgGpuRegs.dma_psram_offset) {
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

        // DMA mux: sequencer > MMIO.  When sequencer is busy, it owns DMA.
        sequencer match {
          case Some(s) =>
            d.io.start := Mux(s.io.busy, s.io.dmaStart, dmaStartPulse)
            d.io.desc  := Mux(s.io.busy, s.io.dmaDesc,  mmioDesc)
            s.io.dmaBusy := d.io.busy
          case None =>
            d.io.start := dmaStartPulse
            d.io.desc  := mmioDesc
        }
        rdlRegs.io.hw.status_dma_busy := d.io.busy.asUInt

      case None =>
        // hasDMA=false: DMA RDL registers have nogen=true, no hw ports.
        rdlRegs.io.hw.status_dma_busy := 0.U
        // If sequencer exists without DMA (should not happen), tie off dmaBusy
        sequencer.foreach(_.io.dmaBusy := false.B)
    }
  }

  /** Step 29.0/29.1/29.2: Wire BorgSequencer MMIO decode, status bit, and
    * core/pipeline snoop interfaces.
    *
    * MMIO registers (all nogen — decoded directly from bus):
    * - `seq_desc_base`   (0x220): 20-bit PSRAM descriptor address.
    * - `seq_trigger`     (0x224): singlepulse start (bit 0).
    * - `seq_vert_addr`   (0x228): 20-bit PSRAM address of vertex shader binary.
    * - `seq_vert_len`    (0x22C): vertex shader length in 32-bit words (6 bits).
    * - `seq_setup_addr`  (0x230): 20-bit PSRAM address of setup shader (Step 29.2).
    * - `seq_setup_len`   (0x234): setup shader length in 32-bit words (Step 29.2).
    *
    * Step 29.1 additions:
    * - CoreStatus and PipeWrite snooped from BorgCore (broadcast, no mux needed).
    * - DMA and CoreTrigger muxed in wireDMA() and wireCore() respectively.
    *
    * Step 29.2 additions:
    * - Uniform write port muxed in wireCore() (sequencer > DMA).
    */
  private def wireSequencer(): Unit = {
    sequencer match {
      case Some(s) =>
        val seqDescBaseReg   = RegInit(0.U(20.W))
        val seqVertAddrReg   = RegInit(0.U(20.W))
        val seqVertLenReg    = RegInit(0.U(6.W))
        val seqSetupAddrReg  = RegInit(0.U(20.W))
        val seqSetupLenReg   = RegInit(0.U(6.W))
        val seqInvWidthReg   = RegInit(0.U(16.W))  // Step 30.1c
        val seqStartPulse    = WireDefault(false.B)
        // Step 31: multi-triangle autonomous rendering registers
        val seqTriCountReg   = RegInit(0.U(5.W))
        val seqRastAddrReg   = RegInit(0.U(20.W))
        val seqRastLenReg    = RegInit(0.U(6.W))
        val seqFragAddrReg   = RegInit(0.U(20.W))
        val seqFragLenReg    = RegInit(0.U(6.W))
        val seqClearLoReg    = RegInit(0.U(32.W))
        val seqClearHiReg    = RegInit(0.U(32.W))
        val seqFbBaseReg     = RegInit(0.U(20.W))
        val seqTilesPerRowReg = RegInit(0.U(10.W))

        when(bus.is_writing && bus.address === BorgGpuRegs.seq_desc_base_offset) {
          seqDescBaseReg := bus.data_in(19, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_trigger_offset) {
          seqStartPulse := bus.data_in(0)
        }
        // Step 29.1: vertex shader address and length registers
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_vert_addr_offset) {
          seqVertAddrReg := bus.data_in(19, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_vert_len_offset) {
          seqVertLenReg := bus.data_in(5, 0)
        }
        // Step 29.2: setup shader address and length registers
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_setup_addr_offset) {
          seqSetupAddrReg := bus.data_in(19, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_setup_len_offset) {
          seqSetupLenReg := bus.data_in(5, 0)
        }
        // Step 30.1c: inv_width for edge normalization
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_inv_width_offset) {
          seqInvWidthReg := bus.data_in(15, 0)
        }
        // Step 31: multi-triangle autonomous rendering registers
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_tri_count_offset) {
          seqTriCountReg := bus.data_in(4, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_rast_addr_offset) {
          seqRastAddrReg := bus.data_in(19, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_rast_len_offset) {
          seqRastLenReg := bus.data_in(5, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_frag_addr_offset) {
          seqFragAddrReg := bus.data_in(19, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_frag_len_offset) {
          seqFragLenReg := bus.data_in(5, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_clear_lo_offset) {
          seqClearLoReg := bus.data_in
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_clear_hi_offset) {
          seqClearHiReg := bus.data_in
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_fb_base_offset) {
          seqFbBaseReg := bus.data_in(19, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_tiles_per_row_offset) {
          seqTilesPerRowReg := bus.data_in(9, 0)
        }

        s.io.start           := seqStartPulse
        s.io.descBase        := seqDescBaseReg
        s.io.vertShaderAddr  := seqVertAddrReg
        s.io.vertShaderLen   := seqVertLenReg
        s.io.setupShaderAddr := seqSetupAddrReg
        s.io.setupShaderLen  := seqSetupLenReg
        s.io.seqInvWidth     := seqInvWidthReg  // Step 30.1c
        // Step 31: multi-triangle autonomous rendering IO wiring
        s.io.triCount        := seqTriCountReg
        s.io.rastShaderAddr  := seqRastAddrReg
        s.io.rastShaderLen   := seqRastLenReg
        s.io.fragShaderAddr  := seqFragAddrReg
        s.io.fragShaderLen   := seqFragLenReg
        s.io.clearColorLo    := seqClearLoReg
        s.io.clearColorHi    := seqClearHiReg
        s.io.fbBase          := seqFbBaseReg
        s.io.tilesPerRow     := seqTilesPerRowReg
        // Step 32.2: Binner PSRAM layout registers
        val seqBinBaseReg     = RegInit(0.U(20.W))
        val seqBinRowBytesReg = RegInit(0.U(20.W))
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_bin_base_offset) {
          seqBinBaseReg := bus.data_in(19, 0)
        }
        when(bus.is_writing && bus.address === BorgGpuRegs.seq_bin_row_bytes_offset) {
          seqBinRowBytesReg := bus.data_in(19, 0)
        }
        s.io.binBase         := seqBinBaseReg
        s.io.binRowBytes     := seqBinRowBytesReg
        s.io.tileComplete    := rast.io.tileComplete
        s.io.autoRunStall    := rast.io.autoRunStall  // gates per-pixel advance pulses

        // CoreStatus and PipeWrite: broadcast snoop (no mux — input-only, read from core)
        s.io.coreStatus.running        := core.io.status.running
        s.io.coreStatus.autoRunPending := core.io.status.autoRunPending
        s.io.pipeWrite.en   := core.io.pipeWrite.en
        s.io.pipeWrite.addr := core.io.pipeWrite.addr
        s.io.pipeWrite.data := core.io.pipeWrite.data

        rdlRegs.io.hw.status_seq_busy := s.io.busy.asUInt

      case None =>
        rdlRegs.io.hw.status_seq_busy := 0.U
    }
  }

  /** Step 32.2: Wire BorgBinner — sequencer-driven geometry pass binning.
    *
    * The sequencer drives the binner's start/triIndex/bbox/clearCounts
    * during its new sBinTri state. When no sequencer is present (shouldn't
    * happen in practice — hasBinner implies hasSequencer), inputs are tied
    * to safe defaults.
    *
    * The binner's GpuMemIO write port is muxed into the PSRAM arbitration
    * in wireRasterizer().
    */
  private def wireBinner(): Unit = {
    binner match {
      case Some(b) =>
        sequencer match {
          case Some(s) =>
            b.io.start       := s.io.binnerStart
            b.io.triIndex    := s.io.binnerTriIndex
            b.io.bbox        := s.io.binnerBbox
            b.io.clearCounts := s.io.binnerClearCounts
            b.io.binBase     := s.io.binBase
            b.io.binRowBytes := s.io.binRowBytes
            // tilesPerRow is shared with sequencer via the same MMIO register.
            // We can't reference seqTilesPerRowReg here (it's local to wireSequencer),
            // so route it from the sequencer's tilesPerRow input.
            b.io.tilesPerRow := s.io.tilesPerRow
            s.io.binnerBusy  := b.io.busy
          case None =>
            // hasBinner=true without hasSequencer: tie off to idle defaults.
            b.io.start       := false.B
            b.io.triIndex    := 0.U
            b.io.bbox.min.x  := 0.U
            b.io.bbox.min.y  := 0.U
            b.io.bbox.max.x  := 0.U
            b.io.bbox.max.y  := 0.U
            b.io.binBase     := 0.U
            b.io.binRowBytes := 0.U
            b.io.tilesPerRow := 0.U
            b.io.clearCounts := false.B
        }

        // GpuMem feedback from the arb mux (wireRasterizer handles the request side)
        b.io.gpuMem.data  := io.gpuMem.data
        b.io.gpuMem.ready := io.gpuMem.ready && b.io.busy &&
                             !dma.map(_.io.busy).getOrElse(false.B) &&
                             !flusher.map(_.io.busy).getOrElse(false.B)

      case None =>
        // hasBinner=false: tie off sequencer's binnerBusy so it never stalls.
        sequencer.foreach(_.io.binnerBusy := false.B)
    }
  }
}

