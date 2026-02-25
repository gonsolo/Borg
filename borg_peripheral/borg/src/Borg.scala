// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

/** BorgIO defines the interface for the shading processor. It uses
  * memory-mapped I/O for register and instruction memory access.
  */
class BorgIO extends Bundle {
  val address = Input(
    UInt(6.W)
  ) // 64-word address space (byte-addressed internally by shifting)
  val data_in = Input(UInt(32.W))
  val data_write_n = Input(UInt(2.W)) // 0b10 for write
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(32.W))
  val data_ready = Output(Bool())
  val ui_in = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))
  val user_interrupt = Output(Bool())
}

/** Borg is a minimal shading processor with instruction memory and a program
  * counter. It executes floating-point addition instructions in a 4-cycle
  * pipeline.
  */
class Borg extends Module {
  val io = IO(new BorgIO)

  // --- Storage ---
  // registerFile: 4 general-purpose 32-bit registers for floating-point data
  val registerFile = SyncReadMem(4, UInt(32.W))

  // instructionMemory: 4 words of instruction memory to store the shader program
  val instructionMemory = SyncReadMem(4, UInt(32.W))

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

  // Handle Control Reset
  when(is_writing && io.address === 60.U) {
    when(io.data_in(0)) { running := true.B }
    when(io.data_in(1)) {
      programCounter := 0.U
      running := false.B
      busy_counter := 0.U
    }
  }

  // --- Instruction Pre-Fetch ---
  val rs1_idx = fetchedInstruction(19, 15)(1, 0)
  val rs2_idx = fetchedInstruction(24, 20)(1, 0)
  val rd_idx = fetchedInstruction(11, 7)(1, 0)

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

  // Port C: MMIO Register Access
  val mmio_reg_en = !running && !is_busy && (is_reading || is_writing)
  val mmio_reg_en_del = RegNext(mmio_reg_en && is_reading, false.B)
  val mmio_reg_data_in = registerFile.read(io.address(3, 2), mmio_reg_en)
  val mmio_reg_data = Mux(mmio_reg_en_del, mmio_reg_data_in, 0.U)

  // --- ALU: Floating Point Adder ---
  val recA = recFNFromFN(8, 24, recA_raw)
  val recB = recFNFromFN(8, 24, recB_raw)
  val f_add = Module(new AddRecFN(8, 24))
  f_add.io.subOp := false.B
  f_add.io.a := recA
  f_add.io.b := recB
  f_add.io.roundingMode := 0.U
  f_add.io.detectTininess := 1.U

  // Write-back: At busy_counter 1 (Cycle 4 of 4)
  val mmio_reg_write = is_writing && io.address < 16.U
  val pipe_reg_write = running && is_busy && busy_counter === 1.U
  val reg_w_en = mmio_reg_write || pipe_reg_write
  val reg_w_addr = Mux(pipe_reg_write, rd_idx, io.address(3, 2))
  val reg_w_data =
    Mux(pipe_reg_write, fNFromRecFN(8, 24, f_add.io.out), io.data_in)

  when(reg_w_en) {
    registerFile.write(reg_w_addr, reg_w_data)
  }

  // IMEM Write
  when(is_writing && io.address >= 32.U && io.address < 48.U) {
    instructionMemory.write(io.address(3, 2), io.data_in)
  }

  // --- Memory-Mapped Read Logic ---
  val read_addr_del = RegInit(0.U(6.W))
  read_addr_del := io.address

  val status_reg = Cat(0.U(30.W), !running, 0.U(1.W))

  io.data_out := MuxLookup(read_addr_del, 0.U)(
    Seq(
      0.U -> mmio_reg_data,
      4.U -> mmio_reg_data,
      8.U -> mmio_reg_data,
      12.U -> mmio_reg_data,
      16.U -> status_reg
    )
  )

  val read_ready_del = RegNext(is_reading, false.B)
  io.data_ready := (io.data_read_n === 3.U) || read_ready_del
  io.uo_out := 0.U
  io.user_interrupt := false.B
}
