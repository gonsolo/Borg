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
    UInt(6.W)
  ) // 64-word address space (byte-addressed internally by shifting)
  val data_in = Input(UInt(config.totalBits.W))
  val data_write_n = Input(UInt(2.W)) // 0b10 for write
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(config.totalBits.W))
  val data_ready = Output(Bool())
  val uo_out = Output(UInt(8.W))
  val user_interrupt = Output(Bool())
}

/** Borg is a minimal shading processor with instruction memory and a program
  * counter. It executes floating-point FMA instructions in a 4-cycle
  * pipeline.
  */
class Borg(val config: FloatConfig = FloatConfig.FP32) extends Module {
  val io = IO(new BorgIO(config))
  dontTouch(io)

  // --- Storage ---
  // registerFile: 8 general-purpose registers for floating-point data (r0-r7)
  val registerFile = SyncReadMem(8, UInt(config.totalBits.W))

  // instructionMemory: 6 words of instruction memory to store the shader program
  val instructionMemory = SyncReadMem(6, UInt(config.totalBits.W))

  // programCounter: Points to the current instruction in instructionMemory
  val programCounter = RegInit(0.U(3.W))

  // running: Status flag indicating if the processor is currently executing a program
  val running = RegInit(false.B)

  // --- Pipeline Control ---
  val busy_counter = RegInit(0.U(3.W))
  val is_busy = busy_counter > 0.U

  val is_writing = io.data_write_n === 2.U
  val is_reading = io.data_read_n === 2.U

  // --- Stage Variables (Combinational) ---

  // --- Instruction Memory Read Logic (1-Cycle Latency) ---
  val nextPC =
    Mux(is_busy && busy_counter === 1.U, programCounter + 1.U, programCounter)
  val fetchedInstruction = instructionMemory.read(nextPC)

  // --- Instruction Decoding ---
  // 3-bit register indices (r0-r7) for both FP32 and FP16
  // FP16: [15:14]=op [13:12]=rs3/ext [11:8]=rs2 [7:4]=rs1 [3:0]=rd
  val rs1_idx = if (config.totalBits >= 20) fetchedInstruction(19, 15)(2, 0) else fetchedInstruction(6, 4)
  val rs2_idx = if (config.totalBits >= 25) fetchedInstruction(24, 20)(2, 0) else fetchedInstruction(10, 8)
  val rd_idx = if (config.totalBits >= 32) fetchedInstruction(11, 7)(2, 0) else fetchedInstruction(2, 0)

  // Operation type: ADD, MUL, FMA, FNEG, FSTEP
  // FP32: bit 2 = FMA flag; funct7[28:25] = 0x0→ADD, 0x4→MUL, 0x6→FNEG, 0x8→FSTEP (when not FMA)
  // FP16: bits[15:14] = 00→ADD, 01→MUL, 10→FMA, 11→extended
  //       For extended (11): bits[13:12] = 00→FNEG, 01→FSTEP
  val is_fma = if (config.totalBits >= 32) fetchedInstruction(2) else fetchedInstruction(15, 14) === 2.U
  val is_mul = if (config.totalBits >= 32) {
    !fetchedInstruction(2) && fetchedInstruction(28, 25) === 0x4.U
  } else {
    fetchedInstruction(15, 14) === 1.U
  }
  val is_fneg = if (config.totalBits >= 32) {
    !fetchedInstruction(2) && fetchedInstruction(28, 25) === 0x6.U
  } else {
    fetchedInstruction(15, 14) === 3.U && fetchedInstruction(13, 12) === 0.U
  }
  val is_fstep = if (config.totalBits >= 32) {
    !fetchedInstruction(2) && fetchedInstruction(28, 25) === 0x8.U
  } else {
    fetchedInstruction(15, 14) === 3.U && fetchedInstruction(13, 12) === 1.U
  }

  // rs3 index for FMA (third source register, 2-bit for FP16 → r0-r3 only)
  val rs3_idx = if (config.totalBits >= 32) fetchedInstruction(31, 27)(2, 0) else fetchedInstruction(13, 12)

  // --- Fetch & Execute Logic ---
  when(running && !is_busy) {
    when(fetchedInstruction === 0.U) {
      running := false.B
    }.otherwise {
      busy_counter := 4.U
    }
  }.elsewhen(is_busy) {
    busy_counter := busy_counter - 1.U
    when(busy_counter === 1.U) {
      programCounter := programCounter + 1.U
    }
  }

  // Handle Control (shared address 60: read=status, write=control)
  when(is_writing && io.address === 60.U) {
    when(io.data_in(0)) { running := true.B }
    when(io.data_in(1)) {
      programCounter := 0.U
      running := false.B
      busy_counter := 0.U
    }
  }

  // --- Register File State Access ---
  // Port A: Pipeline RS1 (Word index 0-3)
  val rs1_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
  val rs1_en_del = RegNext(rs1_en, false.B)
  val recA_raw_in = registerFile.read(rs1_idx, rs1_en)
  val recA_raw = Mux(rs1_en_del, recA_raw_in, 0.U)

  // Port B: Pipeline RS2
  val rs2_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
  val rs2_en_del = RegNext(rs2_en, false.B)
  val recB_raw_in = registerFile.read(rs2_idx, rs2_en)
  val recB_raw = Mux(rs2_en_del, recB_raw_in, 0.U)

  // Port C: MMIO Register Access / RS3 for FMA
  // During execution, this port reads rs3; when idle, it serves MMIO reads/writes.
  val mmio_reg_en = !running && !is_busy && (is_reading || is_writing)
  val rs3_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
  val portC_addr = Mux(running || is_busy, rs3_idx, io.address(4, 2))
  val portC_en = mmio_reg_en || rs3_en
  val mmio_reg_en_del = RegNext(mmio_reg_en && is_reading, false.B)
  val rs3_en_del = RegNext(rs3_en, false.B)
  val portC_data_in = registerFile.read(portC_addr, portC_en)
  val mmio_reg_data = Mux(mmio_reg_en_del, portC_data_in, 0.U)
  val recC_raw = Mux(rs3_en_del, portC_data_in, 0.U)

  // --- ALU: Floating Point FMA ---
  val recA = recFNFromFN(config.exp, config.sig, recA_raw)
  val recB = recFNFromFN(config.exp, config.sig, recB_raw)
  val recC = recFNFromFN(config.exp, config.sig, recC_raw)

  // Constants for operation muxing
  val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)
  val recOne = recFNFromFN(config.exp, config.sig, one_fn)
  val recZero = recFNFromFN(config.exp, config.sig, 0.U(config.totalBits.W))

  // Latch op type when execution starts, hold through the 4-cycle pipeline
  val is_mul_reg = RegInit(false.B)
  val is_fma_reg = RegInit(false.B)
  val is_fneg_reg = RegInit(false.B)
  val is_fstep_reg = RegInit(false.B)
  when(running && !is_busy && fetchedInstruction =/= 0.U) {
    is_mul_reg := is_mul
    is_fma_reg := is_fma
    is_fneg_reg := is_fneg
    is_fstep_reg := is_fstep
  }

  val fma = Module(new MulAddRecFN(config.exp, config.sig))
  // FNEG uses op=2: op(1)=1 negates product. -(a*b)+c. With a=1.0, b=rs1, c=0.0 → -rs1
  fma.io.op := Mux(is_fneg_reg, 2.U, 0.U)
  // ADD: a=1.0, b=rs1, c=rs2     → 1.0*rs1 + rs2 = rs1+rs2
  // MUL: a=rs1,  b=rs2, c=0.0    → rs1*rs2 + 0.0 = rs1*rs2
  // FMA: a=rs1,  b=rs2, c=rs3    → rs1*rs2 + rs3
  // FNEG: a=1.0, b=rs1, c=0.0    → -(1.0*rs1) + 0.0 = -rs1
  fma.io.a := Mux(is_mul_reg || is_fma_reg, recA, recOne)
  fma.io.b := Mux(is_mul_reg || is_fma_reg, recB, recA)
  fma.io.c := Mux(is_fma_reg, recC, Mux(is_mul_reg || is_fneg_reg, recZero, recB))
  fma.io.roundingMode := 0.U
  fma.io.detectTininess := 1.U

  // Write-back: At busy_counter 1 (Cycle 4 of 4)
  val mmio_reg_write = is_writing && io.address < 32.U
  val pipe_reg_write = running && is_busy && busy_counter === 1.U
  val reg_w_en = mmio_reg_write || pipe_reg_write
  val reg_w_addr = Mux(pipe_reg_write, rd_idx, io.address(4, 2))

  // FSTEP: output 0.0 if rs1 <= 0, else 1.0 (sign-preserving step function)
  // Compatible with existing C edge test: positive+nonzero = outside
  val fstep_is_negative_or_zero = recA_raw(config.totalBits - 1) || (recA_raw === 0.U)
  val fstep_result = Mux(fstep_is_negative_or_zero, 0.U(config.totalBits.W), one_fn)

  val fma_result = fNFromRecFN(config.exp, config.sig, fma.io.out)
  val reg_w_data =
    Mux(pipe_reg_write, Mux(is_fstep_reg, fstep_result, fma_result), io.data_in)

  when(reg_w_en) {
    registerFile.write(reg_w_addr, reg_w_data)
  }

  // IMEM Write (addresses 32–52, 6 words)
  when(is_writing && io.address >= 32.U && io.address < 56.U) {
    instructionMemory.write(io.address(4, 2), io.data_in)
  }

  // --- Memory-Mapped Read Logic ---
  val read_addr_del = RegInit(0.U(6.W))
  read_addr_del := io.address

  val status_reg = Cat(0.U((config.totalBits - 2).W), !running, 0.U(1.W))

  io.data_out := Mux(read_addr_del < 32.U, mmio_reg_data,
    Mux(read_addr_del === 60.U, status_reg, 0.U)
  )

  val read_ready_del = RegNext(is_reading, false.B)
  io.data_ready := (io.data_read_n === 3.U) || read_ready_del
  io.uo_out := 0.U
  io.user_interrupt := false.B
}
