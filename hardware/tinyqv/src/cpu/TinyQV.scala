// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class tinyQVIO extends Bundle {
  // ---------------------------------------------------------------------------
  // MMIO peripheral bus — external SoC peripherals (addr[27:25] != 0)
  // ---------------------------------------------------------------------------
  val data_addr          = Output(UInt(28.W))
  val data_write_n       = Output(UInt(2.W))
  val data_read_n        = Output(UInt(2.W))
  val data_read_complete = Output(Bool())
  val data_out           = Output(UInt(32.W))
  val data_ready         = Input(Bool())
  val data_in            = Input(UInt(32.W))

  val interrupt_req = Input(UInt(16.W))
  val time_pulse    = Input(Bool())

  // ---------------------------------------------------------------------------
  // Instruction fetch interface — wired to MemoryController by SoCLogic
  // ---------------------------------------------------------------------------
  val instr_addr          = Output(UInt(23.W))
  val instr_fetch_restart = Output(Bool())
  val instr_fetch_stall   = Output(Bool())
  val instr_fetch_started = Input(Bool())
  val instr_fetch_stopped = Input(Bool())
  val instr_data          = Input(UInt(16.W))
  val instr_ready         = Input(Bool())

  // ---------------------------------------------------------------------------
  // Memory data bus — QSPI region (addr[27:25] == 0); wired to MemoryController
  // ---------------------------------------------------------------------------
  val mem_addr          = Output(UInt(25.W))
  val mem_write_n       = Output(UInt(2.W))
  val mem_read_n        = Output(UInt(2.W))
  val mem_data_out      = Output(UInt(32.W))
  val mem_data_continue = Output(Bool())
  val mem_ready         = Input(Bool())
  val mem_data_in       = Input(UInt(32.W))

  // ---------------------------------------------------------------------------
  // Debug signals
  // ---------------------------------------------------------------------------
  val debug_instr_complete    = Output(Bool())
  val debug_instr_ready       = Output(Bool())
  val debug_instr_valid       = Output(Bool())
  val debug_fetch_restart     = Output(Bool())
  val debug_data_ready        = Output(Bool())
  val debug_interrupt_pending = Output(Bool())
  val debug_branch            = Output(Bool())
  val debug_early_branch      = Output(Bool())
  val debug_ret               = Output(Bool())
  val debug_reg_wen           = Output(Bool())
  val debug_counter_0         = Output(Bool())
  val debug_data_continue     = Output(Bool())
  val debug_rd                = Output(UInt(4.W))
}

// Instruction fetch interface (CPU perspective: outputs to memory, inputs from memory)
class InstrFetchIO extends Bundle {
  val instr_addr          = Output(UInt(23.W))
  val instr_fetch_restart = Output(Bool())
  val instr_fetch_stall   = Output(Bool())
  val instr_fetch_started = Input(Bool())
  val instr_fetch_stopped = Input(Bool())
  val instr_data          = Input(UInt(16.W))
  val instr_ready         = Input(Bool())
}

class TinyQVCpuIO extends Bundle {
  val instrFetch = new InstrFetchIO

  val interrupt_req = Input(UInt(16.W))

  val data_addr          = Output(UInt(28.W))
  val data_write_n       = Output(UInt(2.W))
  val data_read_n        = Output(UInt(2.W))
  val data_read_complete = Output(Bool())
  val data_out           = Output(UInt(32.W))
  val data_continue      = Output(Bool())

  val data_ready = Input(Bool())
  val data_in    = Input(UInt(32.W))

  val time_pulse = Input(Bool())

  val debug_instr_complete    = Output(Bool())
  val debug_instr_valid       = Output(Bool())
  val debug_interrupt_pending = Output(Bool())
  val debug_branch            = Output(Bool())
  val debug_early_branch      = Output(Bool())
  val debug_ret               = Output(Bool())
  val debug_reg_wen           = Output(Bool())
  val debug_counter_0         = Output(Bool())
  val debug_rd                = Output(UInt(4.W))
  val debug_pc                = Output(UInt(32.W))
  val debug_imm               = Output(UInt(32.W))
  val debug_counter_hi        = Output(UInt(3.W))
}

/** TinyQV top-level — pure CPU; no knowledge of QSPI, SPI, or PSRAM.
  *
  * Instantiates [[TinyQVCpu]] and routes traffic based on address decode:
  *   - '''QSPI region''' (addr[27:25] == 0): routed to `mem_*` ports → MemoryController
  *   - '''Peripheral region''' (addr[27:25] != 0): routed to `data_*` ports → SoCLogic
  *
  * Instruction fetch is exposed as dedicated `instr_*` ports so SoCLogic can
  * wire them directly to the MemoryController.
  */
class TinyQV(val programFile: String = "") extends Module {

  val io = FlatIO(new tinyQVIO)

  // Synchronized reset — delays release by one clock cycle
  val rst_reg_n = RegNext(true.B, false.B)
  val cpu = withReset(!rst_reg_n) { Module(new TinyQVCpu(16, 4)) }

  // Debug wire for instruction address (used by cocotb tests via hierarchical access)
  val instr_addr = WireInit(cpu.io.instrFetch.instr_addr)
  instr_addr.suggestName("instr_addr")
  dontTouch(instr_addr)

  // Address decode: QSPI region vs MMIO peripherals
  val is_mem = cpu.io.data_addr(27, 25) === 0.U

  // ---------------------------------------------------------------------------
  // Instruction fetch — pass through to top-level boundary
  // ---------------------------------------------------------------------------
  io.instr_addr          := cpu.io.instrFetch.instr_addr
  io.instr_fetch_restart := cpu.io.instrFetch.instr_fetch_restart
  io.instr_fetch_stall   := cpu.io.instrFetch.instr_fetch_stall

  cpu.io.instrFetch.instr_fetch_started := io.instr_fetch_started
  cpu.io.instrFetch.instr_fetch_stopped := io.instr_fetch_stopped
  cpu.io.instrFetch.instr_data          := io.instr_data
  cpu.io.instrFetch.instr_ready         := io.instr_ready

  // ---------------------------------------------------------------------------
  // Memory data bus (QSPI region → MemoryController)
  // ---------------------------------------------------------------------------
  io.mem_addr          := cpu.io.data_addr(24, 0)
  io.mem_write_n       := Mux(is_mem, cpu.io.data_write_n, 3.U(2.W))
  io.mem_read_n        := Mux(is_mem, cpu.io.data_read_n,  3.U(2.W))
  io.mem_data_out      := cpu.io.data_out
  io.mem_data_continue := cpu.io.data_continue

  // ---------------------------------------------------------------------------
  // MMIO peripheral bus (SoCLogic-facing)
  // ---------------------------------------------------------------------------
  io.data_addr          := cpu.io.data_addr
  io.data_write_n       := Mux(!is_mem, cpu.io.data_write_n, 3.U(2.W))
  io.data_read_n        := Mux(!is_mem, cpu.io.data_read_n,  3.U(2.W))
  io.data_read_complete := Mux(!is_mem, cpu.io.data_read_complete, false.B)
  io.data_out           := cpu.io.data_out

  // Data mux back to CPU
  cpu.io.data_ready := Mux(is_mem, io.mem_ready,   io.data_ready)
  cpu.io.data_in    := Mux(is_mem, io.mem_data_in, io.data_in)

  // Other CPU inputs
  cpu.io.interrupt_req := io.interrupt_req
  cpu.io.time_pulse    := io.time_pulse

  // ---------------------------------------------------------------------------
  // Debug outputs
  // ---------------------------------------------------------------------------
  io.debug_instr_complete    := cpu.io.debug_instr_complete
  io.debug_instr_ready       := io.instr_ready
  io.debug_instr_valid       := cpu.io.debug_instr_valid
  io.debug_fetch_restart     := cpu.io.instrFetch.instr_fetch_restart
  io.debug_data_ready        := Mux(is_mem, io.mem_ready, io.data_ready)
  io.debug_interrupt_pending := cpu.io.debug_interrupt_pending
  io.debug_branch            := cpu.io.debug_branch
  io.debug_early_branch      := cpu.io.debug_early_branch
  io.debug_ret               := cpu.io.debug_ret
  io.debug_reg_wen           := cpu.io.debug_reg_wen
  io.debug_counter_0         := cpu.io.debug_counter_0
  io.debug_data_continue     := cpu.io.data_continue
  io.debug_rd                := cpu.io.debug_rd
}
