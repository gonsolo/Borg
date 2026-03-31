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
    UInt(9.W)
  ) // 512-byte address space (byte-addressed internally by shifting)
  val data_in = Input(UInt(32.W))  // 32-bit data for IMEM writes; register writes use low config.totalBits
  val data_write_n = Input(UInt(2.W)) // 0b10 for write
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(config.totalBits.W))
  val data_ready = Output(Bool())
  val uo_out = Output(UInt(8.W))
  val user_interrupt = Output(Bool())
}

class RegFileCopyIO(width: Int) extends Bundle {
  val readAddr  = Input(UInt(log2Ceil(MmioMap.BORG_NUM_REGS).W))
  val readEn    = Input(Bool())
  val readData  = Output(UInt(width.W))
  val writeAddr = Input(UInt(log2Ceil(MmioMap.BORG_NUM_REGS).W))
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

  val mem = SyncReadMem(MmioMap.BORG_NUM_REGS, UInt(width.W))
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

  // --- BorgCore wiring ---
  core.io.address    := io.address
  core.io.data_in    := io.data_in
  core.io.is_writing := is_writing
  core.io.is_reading := is_reading
  core.io.iterX      := rast.io.iterX
  core.io.iterY      := rast.io.iterY
  core.io.triggerShader := rast.io.triggerCore

  // --- BorgRasterizer wiring ---
  rast.io.setBbox  := is_writing && io.address === MmioMap.BORG_ITER_BBOX_OFFSET.U
  rast.io.bboxData := io.data_in(23, 0)
  rast.io.advance  := is_writing && io.address === MmioMap.BORG_ITER_OFFSET.U

  // Pipeline write-back snoop
  rast.io.pipeWriteEn   := core.io.pipeWriteEn
  rast.io.pipeWriteAddr := core.io.pipeWriteAddr
  rast.io.pipeWriteData := core.io.pipeWriteData

  // Core state feedback
  rast.io.coreRunning        := core.io.running
  rast.io.coreAutoRunPending := core.io.autoRunPending

  // @doc:mmio
  // --- Read Mux ---
  val read_addr_del = RegInit(0.U(9.W))
  read_addr_del := io.address

  val iter_reg = Cat(rast.io.insideFlag, rast.io.iterValid, rast.io.iterY, rast.io.iterX)

  io.data_out := Mux(read_addr_del >= MmioMap.BORG_REG_OFFSET.U && read_addr_del < MmioMap.BORG_IMEM_OFFSET.U,
    core.io.regReadData,
    Mux(read_addr_del === MmioMap.BORG_ITER_OFFSET.U,
      iter_reg,
      Mux(read_addr_del === MmioMap.BORG_CONTROL_OFFSET.U,
        core.io.statusReg,
        0.U)))

  val read_ready_del = RegNext(is_reading, false.B)
  io.data_ready := Mux(rast.io.autoRunStall, false.B,
    (io.data_read_n === 3.U) || read_ready_del)
  io.uo_out := 0.U
  io.user_interrupt := false.B
  // @doc:end
}
