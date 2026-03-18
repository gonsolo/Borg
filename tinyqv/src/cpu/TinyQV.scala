// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class tinyQVIO extends Bundle {
  val data_addr = Output(UInt(28.W))
  val data_write_n = Output(UInt(2.W))
  val data_read_n = Output(UInt(2.W))
  val data_read_complete = Output(Bool())
  val data_out = Output(UInt(32.W))

  val data_ready = Input(Bool())
  val data_in = Input(UInt(32.W))

  val interrupt_req = Input(UInt(16.W))
  val time_pulse = Input(Bool())

  val spi_data_in = Input(UInt(4.W))
  val spi_data_out = Output(UInt(4.W))
  val spi_data_oe = Output(UInt(4.W))
  val spi_clk_out = Output(Bool())
  val spi_flash_select = Output(Bool())
  val spi_ram_a_select = Output(Bool())
  val spi_ram_b_select = Output(Bool())

  val debug_instr_complete = Output(Bool())
  val debug_instr_ready = Output(Bool())
  val debug_instr_valid = Output(Bool())
  val debug_fetch_restart = Output(Bool())
  val debug_data_ready = Output(Bool())
  val debug_interrupt_pending = Output(Bool())
  val debug_branch = Output(Bool())
  val debug_early_branch = Output(Bool())
  val debug_ret = Output(Bool())
  val debug_reg_wen = Output(Bool())
  val debug_counter_0 = Output(Bool())
  val debug_data_continue = Output(Bool())
  val debug_stall_txn = Output(Bool())
  val debug_stop_txn = Output(Bool())
  val debug_rd = Output(UInt(4.W))
}
class TinyQVCpuIO extends Bundle {
  val instr_addr = Output(UInt(23.W))
  val instr_fetch_restart = Output(Bool())
  val instr_fetch_stall = Output(Bool())

  val instr_fetch_started = Input(Bool())
  val instr_fetch_stopped = Input(Bool())
  val instr_data_in = Input(UInt(16.W))
  val instr_ready = Input(Bool())

  val interrupt_req = Input(UInt(16.W))

  val data_addr = Output(UInt(28.W))
  val data_write_n = Output(UInt(2.W))
  val data_read_n = Output(UInt(2.W))
  val data_read_complete = Output(Bool())
  val data_out = Output(UInt(32.W))
  val data_continue = Output(Bool())

  val data_ready = Input(Bool())
  val data_in = Input(UInt(32.W))

  val time_pulse = Input(Bool())

  val debug_instr_complete = Output(Bool())
  val debug_instr_valid = Output(Bool())
  val debug_interrupt_pending = Output(Bool())
  val debug_branch = Output(Bool())
  val debug_early_branch = Output(Bool())
  val debug_ret = Output(Bool())
  val debug_reg_wen = Output(Bool())
  val debug_counter_0 = Output(Bool())
  val debug_rd = Output(UInt(4.W))
  val debug_pc = Output(UInt(32.W))
  val debug_imm = Output(UInt(32.W))
  val debug_counter_hi = Output(UInt(3.W))
}

class TinyQVMemCtrlIO extends Bundle {
  val instr_addr = Input(UInt(23.W))
  val instr_fetch_restart = Input(Bool())
  val instr_fetch_stall = Input(Bool())

  val instr_fetch_started = Output(Bool())
  val instr_fetch_stopped = Output(Bool())
  val instr_data = Output(UInt(16.W))
  val instr_ready = Output(Bool())

  val data_addr = Input(UInt(25.W))
  val data_write_n = Input(UInt(2.W))
  val data_read_n = Input(UInt(2.W))
  val data_to_write = Input(UInt(32.W))
  val data_continue = Input(Bool())

  val data_ready = Output(Bool())
  val data_from_read = Output(UInt(32.W))

  val spi_data_in = Input(UInt(4.W))
  val spi_data_out = Output(UInt(4.W))
  val spi_data_oe = Output(UInt(4.W))
  val spi_flash_select = Output(Bool())
  val spi_ram_a_select = Output(Bool())
  val spi_ram_b_select = Output(Bool())
  val spi_clk_out = Output(Bool())

  val debug_stall_txn = Output(Bool())
  val debug_stop_txn = Output(Bool())
}

class TinyQV extends Module {

  val io = FlatIO(new tinyQVIO)

  // Internal wiring — synchronized reset (delays release by one cycle)
  val rst_reg_n = RegNext(true.B, false.B)

  val cpu = withReset(!rst_reg_n) { Module(new TinyQVCpu(16, 4)) }
  val mem = Module(new TinyQVMemCtrl())

  cpu.io.interrupt_req := io.interrupt_req

  // Memory/MMIO Decoding
  val qv_data_addr = cpu.io.data_addr
  val is_mem = qv_data_addr(27, 25) === 0.U

  // CPU data interface
  val qv_data_ready = Mux(is_mem, mem.io.data_ready, io.data_ready)
  val qv_data_from_read = Mux(is_mem, mem.io.data_from_read, io.data_in)

  cpu.io.data_ready := qv_data_ready
  cpu.io.data_in := qv_data_from_read

  // Mem data interface
  mem.io.data_addr := qv_data_addr(24, 0)
  mem.io.data_write_n := Mux(is_mem, cpu.io.data_write_n, 3.U(2.W))
  mem.io.data_read_n := Mux(is_mem, cpu.io.data_read_n, 3.U(2.W))
  mem.io.data_to_write := cpu.io.data_out
  mem.io.data_continue := cpu.io.data_continue

  // Top-level mappings
  io.data_addr := qv_data_addr
  io.data_write_n := Mux(!is_mem, cpu.io.data_write_n, 3.U(2.W))
  io.data_read_n := Mux(!is_mem, cpu.io.data_read_n, 3.U(2.W))
  io.data_read_complete := Mux(!is_mem, cpu.io.data_read_complete, false.B)
  io.data_out := cpu.io.data_out

  // CPU - Mem instruction wiring
  val cpu_instr_addr = cpu.io.instr_addr
  val instr_addr = WireInit(cpu_instr_addr)
  instr_addr.suggestName("instr_addr")
  dontTouch(instr_addr)

  mem.io.instr_addr := instr_addr
  mem.io.instr_fetch_restart := cpu.io.instr_fetch_restart
  mem.io.instr_fetch_stall := cpu.io.instr_fetch_stall

  cpu.io.instr_fetch_started := mem.io.instr_fetch_started
  cpu.io.instr_fetch_stopped := mem.io.instr_fetch_stopped
  cpu.io.instr_data_in := mem.io.instr_data
  cpu.io.instr_ready := mem.io.instr_ready

  // Interrupts and Time
  cpu.io.interrupt_req := io.interrupt_req
  cpu.io.time_pulse := io.time_pulse

  // SPI interface
  mem.io.spi_data_in := io.spi_data_in
  io.spi_data_out := mem.io.spi_data_out
  io.spi_data_oe := mem.io.spi_data_oe
  io.spi_flash_select := mem.io.spi_flash_select
  io.spi_ram_a_select := mem.io.spi_ram_a_select
  io.spi_ram_b_select := mem.io.spi_ram_b_select
  io.spi_clk_out := mem.io.spi_clk_out

  // Debug signals
  io.debug_instr_complete := cpu.io.debug_instr_complete
  io.debug_instr_ready := mem.io.instr_ready
  io.debug_instr_valid := cpu.io.debug_instr_valid
  io.debug_fetch_restart := cpu.io.instr_fetch_restart
  io.debug_data_ready := qv_data_ready
  io.debug_interrupt_pending := cpu.io.debug_interrupt_pending
  io.debug_branch := cpu.io.debug_branch
  io.debug_early_branch := cpu.io.debug_early_branch
  io.debug_ret := cpu.io.debug_ret
  io.debug_reg_wen := cpu.io.debug_reg_wen
  io.debug_counter_0 := cpu.io.debug_counter_0
  io.debug_data_continue := cpu.io.data_continue
  io.debug_stall_txn := mem.io.debug_stall_txn
  io.debug_stop_txn := mem.io.debug_stop_txn
  io.debug_rd := cpu.io.debug_rd
}
