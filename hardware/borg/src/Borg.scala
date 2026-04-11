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
class BorgIO(val config: FloatConfig = FloatConfig.FP32) extends Bundle {
  val address = Input(
    UInt(10.W)
  ) // 1024-byte address space (byte-addressed internally by shifting)
  val data_in = Input(UInt(32.W))  // 32-bit data for IMEM writes; register writes use low config.totalBits
  val data_write_n = Input(UInt(2.W)) // 0b10 for write
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(32.W))  // 32-bit: register reads, tile buffer reads are packed 32-bit
  val data_ready = Output(Bool())
  val uo_out = Output(UInt(8.W))
  val user_interrupt = Output(Bool())
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
class Borg(val config: FloatConfig = FloatConfig.FP32) extends Module {
  val io = IO(new BorgIO(config))
  dontTouch(io)

  // --- Edge detection for bus signals ---
  val is_writing = io.data_write_n === 2.U && RegNext(io.data_write_n) =/= 2.U
  val is_reading = io.data_read_n === 2.U

  // --- Sub-modules ---
  val core = Module(new BorgCore(config))
  val rast = Module(new BorgRasterizer(config))
  val tile = Module(new BorgTileBuffer())
  val rdlRegs = Module(new BorgGpuRegs()) // Auto-generated RDL register block

  rdlRegs.io.bus.address   := io.address
  rdlRegs.io.bus.writeData := io.data_in
  rdlRegs.io.bus.writeEn   := is_writing
  rdlRegs.io.bus.readEn    := is_reading

  // Sub-modules


  wireCore()
  wireRasterizer()
  wireTileBuffer()
  wireMmioRead()

  private def wireCore(): Unit = {
    core.io.bus.address    := io.address
    core.io.bus.data_in    := io.data_in
    core.io.bus.is_writing := is_writing
    core.io.bus.is_reading := is_reading
    core.io.iter       := rast.io.shaderIter    // latched pre-advance position for coordLut
    core.io.triggerShaderValid := rast.io.triggerCoreValid
    core.io.triggerShaderPC    := rast.io.triggerCorePC

    core.io.controlStart     := rdlRegs.io.hw.control_start
    core.io.controlReset     := rdlRegs.io.hw.control_reset_pipeline
    core.io.controlStartPC   := rdlRegs.io.hw.control_start_pc
    core.io.uniformWritePage := rdlRegs.io.hw.control_uniform_write_page

    // CoordLut/RcpLut init port — only used during simulation; synthesis uses $readmemh
    core.io.coordWriteEn    := false.B
    core.io.coordWriteIsRcp := false.B
    core.io.coordWriteAddr  := 0.U
    core.io.coordWriteData  := 0.U
  }

  private def wireRasterizer(): Unit = {
    rast.io.advance   := is_writing && io.address === BorgGpuRegs.iter_offset

    // Pipeline write-back snoop
    rast.io.pipeWriteEn   := core.io.pipeWriteEn
    rast.io.pipeWriteAddr := core.io.pipeWriteAddr
    rast.io.pipeWriteData := core.io.pipeWriteData

    // Core state feedback
    rast.io.coreRunning        := core.io.running
    rast.io.coreAutoRunPending := core.io.autoRunPending
  }

  private def wireTileBuffer(): Unit = {
    // MMIO write to tile_ctrl: set read index (triggers BRAM read) or clear
    val ctrlWriting = is_writing && io.address === BorgGpuRegs.tile_ctrl_offset
    val tileReadIdx = rdlRegs.io.hw.tile_ctrl_read_idx
    
    tile.io.clearEn := rdlRegs.io.hw.tile_ctrl_clear

    // Two-step protocol: shadow BZ -> write RG triggers
    val tileShadowB = RegInit(0.U(16.W))
    val tileShadowZ = RegInit(0.U(16.W))
    when(is_writing && io.address === BorgGpuRegs.tile_bz_offset) {
      tileShadowB := io.data_in(31, 16)
      tileShadowZ := io.data_in(15, 0)
    }
    
    val mmioTileWriteEn = is_writing && io.address === BorgGpuRegs.tile_rg_offset
    tile.io.writeEn  := mmioTileWriteEn || rast.io.tileWriteEn
    tile.io.writeIdx := Mux(rast.io.tileWriteEn, rast.io.tileWriteIdx, tileReadIdx)
    
    val writeColor = Wire(new ColorZ(16))
    writeColor.r := io.data_in(31, 16)
    writeColor.g := io.data_in(15, 0)
    writeColor.b := tileShadowB
    writeColor.z := tileShadowZ
    tile.io.writeData := Mux(rast.io.tileWriteEn, rast.io.tileWriteData, writeColor)

    // Read port: trigger BRAM read when CTRL is written
    tile.io.readIdx := Mux(ctrlWriting, io.data_in(3, 0), tileReadIdx)
    tile.io.readEn  := ctrlWriting
    tile.io.peekZIdx := Mux(ctrlWriting, io.data_in(3, 0), tileReadIdx)
  }

  private def wireMmioRead(): Unit = {
    val fifo = Module(new BorgCommandFIFO())

    val writeCmd = is_writing && io.address === BorgGpuRegs.cmd_enqueue_offset
    val isEnqueue = RegNext(writeCmd, false.B)
    
    fifo.io.enq.valid := isEnqueue
    fifo.io.enq.bits.uniformPage := rdlRegs.io.hw.cmd_enqueue_uniform_page
    fifo.io.enq.bits.fragPC := rdlRegs.io.hw.cmd_enqueue_frag_pc
    fifo.io.enq.bits.bbox.min.x := rdlRegs.io.hw.cmd_enqueue_bbox_min_x
    fifo.io.enq.bits.bbox.min.y := rdlRegs.io.hw.cmd_enqueue_bbox_min_y
    fifo.io.enq.bits.bbox.max.x := rdlRegs.io.hw.cmd_enqueue_bbox_max_x
    fifo.io.enq.bits.bbox.max.y := rdlRegs.io.hw.cmd_enqueue_bbox_max_y

    rast.io.cmdPop <> fifo.io.deq
    core.io.uniformPage := rast.io.uniformPage

    val read_addr_del = RegInit(0.U(10.W))
    read_addr_del := io.address

    // RDL Iter state injection
    rdlRegs.io.hw.iter_x := rast.io.iter.x
    rdlRegs.io.hw.iter_y := rast.io.iter.y
    rdlRegs.io.hw.iter_valid := rast.io.iterValid
    rdlRegs.io.hw.iter_inside_flag := rast.io.insideFlag

    // RDL Tile state injection
    // To prevent Yosys from allocating 64 redundant logic cells for PeakRDL shadow registers,
    // we tie the hardware read-port to 0. We already manually proxy the tile reads via data_out MuxCase.
    rdlRegs.io.hw.tile_rg_red_in := 0.U
    rdlRegs.io.hw.tile_rg_g_in := 0.U
    rdlRegs.io.hw.tile_bz_b_in := 0.U
    rdlRegs.io.hw.tile_bz_z_in := 0.U

    val stsFifoFull = !fifo.io.enq.ready
    rdlRegs.io.hw.status_idle := !core.io.running
    rdlRegs.io.hw.status_fifo_full := stsFifoFull

    // =========================================================================
    // Texture Fetch Hardware (Step 16.3)
    // =========================================================================
    // RDL handles tex_uv write path and tex_addr read path.
    // Here we wire the combinational fp16→uint6 + Morton pipeline.
    val tex_x = Fp16ToUint6(rdlRegs.io.hw.tex_uv_u)
    val tex_y = Fp16ToUint6(rdlRegs.io.hw.tex_uv_v)
    val morton_index = MortonEncode(tex_x, tex_y)

    rdlRegs.io.hw.tex_addr_morton := morton_index
    rdlRegs.io.hw.tex_addr_raw_u  := tex_x
    rdlRegs.io.hw.tex_addr_raw_v  := tex_y

    val rdl_read_data = rdlRegs.io.bus.readData
    io.data_out := MuxCase(rdl_read_data, Seq(
      (read_addr_del < 128.U) -> core.io.regReadData,
      (read_addr_del === BorgGpuRegs.tile_rg_offset) -> Cat(tile.io.readData.r, tile.io.readData.g),
      (read_addr_del === BorgGpuRegs.tile_bz_offset) -> Cat(tile.io.readData.b, tile.io.readData.z)
    ))

    val read_ready_del = RegNext(is_reading, false.B)
    io.data_ready := Mux(rast.io.autoRunStall, false.B, (io.data_read_n === 3.U) || read_ready_del)
    io.uo_out := 0.U
    io.user_interrupt := false.B
  }
}
