// Copyright Michael Bell 2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVCpuIO extends Bundle {
  val clk = Input(Clock())
  val rstn = Input(Bool())

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
}

class TinyQVCpuBlackBox extends BlackBox {
  val io = IO(new TinyQVCpuIO)
  override def desiredName = "tinyqv_cpu"
}

class TinyQVMemCtrlIO extends Bundle {
  val clk = Input(Clock())
  val rstn = Input(Bool())

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

class TinyQVMemCtrlBlackBox extends BlackBox {
  val io = IO(new TinyQVMemCtrlIO)
  override def desiredName = "tinyqv_mem_ctrl"
}

class TinyQV extends RawModule {
  override val desiredName = "tinyQV"

  val clk = IO(Input(Clock()))
  val rstn = IO(Input(Bool()))

  val data_addr = IO(Output(UInt(28.W)))
  val data_write_n = IO(Output(UInt(2.W)))
  val data_read_n = IO(Output(UInt(2.W)))
  val data_read_complete = IO(Output(Bool()))
  val data_out = IO(Output(UInt(32.W)))    

  val data_ready = IO(Input(Bool()))
  val data_in = IO(Input(UInt(32.W)))

  val interrupt_req = IO(Input(UInt(16.W)))
  val time_pulse = IO(Input(Bool()))

  val spi_data_in = IO(Input(UInt(4.W)))
  val spi_data_out = IO(Output(UInt(4.W)))
  val spi_data_oe = IO(Output(UInt(4.W)))
  val spi_clk_out = IO(Output(Bool()))

  val spi_flash_select = IO(Output(Bool()))
  val spi_ram_a_select = IO(Output(Bool()))
  val spi_ram_b_select = IO(Output(Bool()))

  val debug_instr_complete = IO(Output(Bool()))
  val debug_instr_ready = IO(Output(Bool()))
  val debug_instr_valid = IO(Output(Bool()))
  val debug_fetch_restart = IO(Output(Bool()))
  val debug_data_ready = IO(Output(Bool()))
  val debug_interrupt_pending = IO(Output(Bool()))
  val debug_branch = IO(Output(Bool()))
  val debug_early_branch = IO(Output(Bool()))
  val debug_ret = IO(Output(Bool()))
  val debug_reg_wen = IO(Output(Bool()))
  val debug_counter_0 = IO(Output(Bool()))
  val debug_data_continue = IO(Output(Bool()))
  val debug_stall_txn = IO(Output(Bool()))
  val debug_stop_txn = IO(Output(Bool()))
  val debug_rd = IO(Output(UInt(4.W)))

  // Internal wiring
  val rst_reg_n = withClock(clk) { RegNext(rstn) }

  val cpu = Module(new TinyQVCpuBlackBox())
  val mem = Module(new TinyQVMemCtrlBlackBox())

  cpu.io.clk := clk
  cpu.io.rstn := rst_reg_n
  mem.io.clk := clk
  mem.io.rstn := rstn

  // Memory/MMIO Decoding
  val qv_data_addr = cpu.io.data_addr
  val is_mem = qv_data_addr(27, 25) === 0.U

  // CPU data interface
  val qv_data_ready = Mux(is_mem, mem.io.data_ready, data_ready)
  val qv_data_from_read = Mux(is_mem, mem.io.data_from_read, data_in)

  cpu.io.data_ready := qv_data_ready
  cpu.io.data_in := qv_data_from_read

  // Mem data interface
  mem.io.data_addr := qv_data_addr(24, 0)
  mem.io.data_write_n := Mux(is_mem, cpu.io.data_write_n, 3.U(2.W))
  mem.io.data_read_n := Mux(is_mem, cpu.io.data_read_n, 3.U(2.W))
  mem.io.data_to_write := cpu.io.data_out
  mem.io.data_continue := cpu.io.data_continue

  // Top-level mappings
  data_addr := qv_data_addr
  data_write_n := Mux(!is_mem, cpu.io.data_write_n, 3.U(2.W))
  data_read_n := Mux(!is_mem, cpu.io.data_read_n, 3.U(2.W))
  data_read_complete := Mux(!is_mem, cpu.io.data_read_complete, false.B)
  data_out := cpu.io.data_out

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
  cpu.io.interrupt_req := interrupt_req
  cpu.io.time_pulse := time_pulse

  // SPI interface
  mem.io.spi_data_in := spi_data_in
  spi_data_out := mem.io.spi_data_out
  spi_data_oe := mem.io.spi_data_oe
  spi_flash_select := mem.io.spi_flash_select
  spi_ram_a_select := mem.io.spi_ram_a_select
  spi_ram_b_select := mem.io.spi_ram_b_select
  spi_clk_out := mem.io.spi_clk_out

  // Debug signals
  debug_instr_complete := cpu.io.debug_instr_complete
  debug_instr_ready := mem.io.instr_ready
  debug_instr_valid := cpu.io.debug_instr_valid
  debug_fetch_restart := cpu.io.instr_fetch_restart
  debug_data_ready := qv_data_ready
  debug_interrupt_pending := cpu.io.debug_interrupt_pending
  debug_branch := cpu.io.debug_branch
  debug_early_branch := cpu.io.debug_early_branch
  debug_ret := cpu.io.debug_ret
  debug_reg_wen := cpu.io.debug_reg_wen
  debug_counter_0 := cpu.io.debug_counter_0
  debug_data_continue := cpu.io.data_continue
  debug_stall_txn := mem.io.debug_stall_txn
  debug_stop_txn := mem.io.debug_stop_txn
  debug_rd := cpu.io.debug_rd
}
