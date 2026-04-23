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
  val gpuRead = new GpuReadIO
}

class RegFileCopyIO(width: Int) extends Bundle {
  val readAddr  = Input(UInt(log2Ceil(32).W))
  val readEn    = Input(Bool())
  val readData  = Output(UInt(width.W))
  val writeAddr = Input(UInt(log2Ceil(32).W))
  val writeEn   = Input(Bool())
  val writeData = Input(UInt(width.W))
}

/** Single-copy register file with exactly 1 read + 1 write port.
  * Each instance gets a unique Verilog module name to prevent CIRCT
  * deduplication, ensuring yosys can infer iCE40 Block RAMs.
  */
class RegFileCopy(width: Int, instName: String) extends Module {
  override def desiredName = instName

  val io = IO(new RegFileCopyIO(width))

  val mem = SyncReadMem(32, UInt(width.W))
  io.readData := mem.read(io.readAddr, io.readEn)

  when(io.writeEn) {
    mem.write(io.writeAddr, io.writeData)
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

  wireBus()
  wireRdlRegs()
  wireCore()
  wireRasterizer()
  wireTileBuffer()
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
    core.io.coreTrigger <> rast.io.coreTrigger

    core.io.controlStart     := rdlRegs.io.hw.control_start
    core.io.controlReset     := rdlRegs.io.hw.control_reset_pipeline
    core.io.controlStartPC   := rdlRegs.io.hw.control_start_pc
    core.io.uniformWritePage := rdlRegs.io.hw.control_uniform_write_page

    // CoordLut/RcpLut init port — only used during simulation; synthesis uses $readmemh
    core.io.coordWriteEn    := false.B
    core.io.coordWriteIsRcp := false.B
    core.io.coordWriteAddr  := 0.U
    core.io.coordWriteData  := 0.U

    // DMA write ports (Step 22.1) — only wired when hasDMA=true
    dma match {
      case Some(d) =>
        core.io.dmaImemWrite    <> d.io.imemWrite
        core.io.dmaUniformWrite <> d.io.uniformWrite
      case None =>
        core.io.dmaImemWrite.en   := false.B
        core.io.dmaImemWrite.addr := 0.U
        core.io.dmaImemWrite.data := 0.U
        core.io.dmaUniformWrite.en   := false.B
        core.io.dmaUniformWrite.addr := 0.U
        core.io.dmaUniformWrite.data := 0.U
    }
  }

  private def wireRasterizer(): Unit = {
    rast.io.advance   := bus.is_writing && bus.address === BorgGpuRegs.iter_offset

    // Pipeline write-back snoop
    rast.io.pipeWrite <> core.io.pipeWrite

    // Core state feedback
    rast.io.coreStatus <> core.io.status

    // GPU read port: DMA arbitration when hasDMA=true, direct to rast otherwise.
    dma match {
      case Some(d) =>
        io.gpuRead.req  := Mux(d.io.busy, d.io.gpuRead.req, rast.io.gpuRead.req)
        io.gpuRead.addr := Mux(d.io.busy, d.io.gpuRead.addr, rast.io.gpuRead.addr)
        rast.io.gpuRead.data  := io.gpuRead.data
        rast.io.gpuRead.ready := io.gpuRead.ready && !d.io.busy
        d.io.gpuRead.data     := io.gpuRead.data
        d.io.gpuRead.ready    := io.gpuRead.ready && d.io.busy
      case None =>
        // No DMA: rast has exclusive gpuRead access
        rast.io.gpuRead <> io.gpuRead
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
    
    tile.io.clearEn := rdlRegs.io.hw.tile_ctrl_clear

    // Two-step protocol: shadow BZ -> write RG triggers
    val tileShadowB = RegInit(0.U(16.W))
    val tileShadowZ = RegInit(0.U(16.W))
    when(bus.is_writing && bus.address === BorgGpuRegs.tile_bz_offset) {
      tileShadowB := bus.data_in(31, 16)
      tileShadowZ := bus.data_in(15, 0)
    }
    
    val mmioTileWriteEn = bus.is_writing && bus.address === BorgGpuRegs.tile_rg_offset
    tile.io.writeEn  := mmioTileWriteEn || rast.io.tileWrite.en
    tile.io.writeIdx := Mux(rast.io.tileWrite.en, rast.io.tileWrite.idx, tileReadIdx)
    
    val writeColor = Wire(new ColorZ(16))
    writeColor.r := bus.data_in(31, 16)
    writeColor.g := bus.data_in(15, 0)
    writeColor.b := tileShadowB
    writeColor.z := tileShadowZ
    tile.io.writeData := Mux(rast.io.tileWrite.en, rast.io.tileWrite.data, writeColor)

    // Read port: trigger BRAM read when CTRL is written
    tile.io.readIdx := Mux(ctrlWriting, bus.data_in(3, 0), tileReadIdx)
    tile.io.readEn  := ctrlWriting
  }

  private def wireMmioRead(): Unit = {
    val fifo = Module(new BorgCommandFIFO(cfg.fifoDepth, cfg.coordWidth))

    val writeCmd = bus.is_writing && bus.address === BorgGpuRegs.cmd_enqueue_offset
    val isEnqueue = RegNext(writeCmd, false.B)
    
    fifo.io.enq.valid := isEnqueue
    fifo.io.enq.bits.tileOrigin.x := rdlRegs.io.hw.cmd_enqueue_tile_x(cfg.coordWidth - 1, 0)
    fifo.io.enq.bits.tileOrigin.y := rdlRegs.io.hw.cmd_enqueue_tile_y(cfg.coordWidth - 1, 0)

    rast.io.cmdPop <> fifo.io.deq
    core.io.uniformPage := rast.io.uniformPage

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
    io.data_out := MuxCase(rdl_read_data, Seq(
      (read_addr_del < 128.U) -> core.io.regReadData,
      (read_addr_del === BorgGpuRegs.tile_rg_offset) -> Cat(tile.io.readData.r, tile.io.readData.g),
      (read_addr_del === BorgGpuRegs.tile_bz_offset) -> Cat(tile.io.readData.b, tile.io.readData.z)
    ))

    val read_ready_del = RegNext(bus.is_reading, false.B)
    io.data_ready := Mux(rast.io.autoRunStall, false.B, (io.data_read_n === 3.U) || read_ready_del)
    io.uo_out := 0.U
    io.user_interrupt := false.B
  }

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

        val desc = Wire(new DMADescriptor)
        desc.baseAddr := dmaBaseReg
        desc.length   := dmaLenReg
        desc.dest     := dmaDestReg
        desc.offset   := dmaOffsetReg
        d.io.start := dmaStartPulse
        d.io.desc  := desc
        rdlRegs.io.hw.status_dma_busy := d.io.busy.asUInt

      case None =>
        // hasDMA=false: DMA RDL registers have nogen=true, no hw ports.
        rdlRegs.io.hw.status_dma_busy := 0.U
    }
  }
}
