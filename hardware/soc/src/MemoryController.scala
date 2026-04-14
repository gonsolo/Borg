// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package soc

import chisel3._
import chisel3.util._
import tinyqv.cpu.{InstrFetchIO, QspiController}

/** IO bundle for the SoC-level memory controller.
  *
  * Arbitrates the QSPI bus between three requestors:
  *   - CPU instruction fetch (via [[InstrFetchIO]])
  *   - CPU data read/write (loads, stores, PSRAM framebuffer)
  *   - GPU read (autonomous texel fetch — tied off in Step 19.1, wired in Step 19.2)
  */
class MemoryControllerIO extends Bundle {
  // CPU instruction fetch interface
  val instrFetch = Flipped(new InstrFetchIO)

  // CPU data bus (QSPI region: addr[27:25] == 0 on the TinyQV side)
  val cpu_addr          = Input(UInt(25.W))
  val cpu_write_n       = Input(UInt(2.W))
  val cpu_read_n        = Input(UInt(2.W))
  val cpu_data_out      = Input(UInt(32.W))
  val cpu_data_continue = Input(Bool())
  val cpu_ready         = Output(Bool())
  val cpu_data_in       = Output(UInt(32.W))

  // GPU read port — arbiter added in Step 19.2
  val gpu_addr       = Input(UInt(16.W))  // 16-bit: 64KB texture space
  val gpu_read_req   = Input(Bool())
  val gpu_data       = Output(UInt(32.W))
  val gpu_read_ready = Output(Bool())

  // SPI/QSPI pins — MemoryController is the sole owner of the physical bus;
  // neither TinyQV nor Borg have any knowledge of QSPI.
  val spi_data_in      = Input(UInt(4.W))
  val spi_data_out     = Output(UInt(4.W))
  val spi_data_oe      = Output(UInt(4.W))
  val spi_clk_out      = Output(Bool())
  val spi_flash_select = Output(Bool())
  val spi_ram_a_select = Output(Bool())
  val spi_ram_b_select = Output(Bool())

  val debug_stall_txn = Output(Bool())
  val debug_stop_txn  = Output(Bool())
}

/** SoC-level memory controller — arbitrates the QSPI bus between the CPU and GPU.
  *
  * Manages a single [[QspiController]] instance shared by:
  *   - '''Instruction fetch:''' streaming 16-bit instruction words from flash
  *   - '''CPU data read/write:''' byte/halfword/word PSRAM access for loads and stores
  *   - '''GPU read:''' texel fetch from PSRAM (Step 19.2; tied off in Step 19.1)
  *
  * Data transactions take priority over instruction fetch. Multi-beat
  * (continued) transactions are supported for load/store-multiple sequences.
  * The controller reassembles byte-wide QSPI data into the CPU's 32-bit data
  * bus and handles stall/stop signalling for instruction prefetch.
  */
class MemoryController extends Module {
  val io = IO(new MemoryControllerIO)

  // Sequential State
  val instr_active       = RegInit(false.B)
  val gpu_active         = RegInit(false.B)
  val started            = RegInit(false.B)
  val stopped            = RegInit(false.B)
  val qspi_write_done    = RegInit(false.B)
  val qspi_data_buf      = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
  val qspi_data_byte_idx = RegInit(0.U(2.W))
  val data_txn_len       = RegInit(3.U(2.W))
  val continue_txn       = RegInit(false.B)
  val data_stall         = RegInit(false.B)

  // Combinational Logic
  val start_instr    = Wire(Bool())
  val start_read     = Wire(Bool())
  val start_write    = Wire(Bool())
  val start_gpu_read = Wire(Bool())
  val stop_txn    = Wire(Bool())
  val data_ready  = Wire(Bool())

  val data_txn_n = io.cpu_write_n & io.cpu_read_n

  val qspi_busy       = Wire(Bool())
  val qspi_data_req   = Wire(Bool())
  val qspi_data_ready = Wire(Bool())
  val qspi_data_out   = Wire(UInt(8.W))

  val is_instr  = instr_active || start_instr
  val is_gpu    = gpu_active || start_gpu_read
  val txn_len   = Mux(is_instr, 1.U(2.W), data_txn_len)
  val addr_in   = Mux(
    is_gpu,
    Cat(2.U(2.W), 0.U(7.W), io.gpu_addr),
    Mux(
      is_instr,
      Cat(0.U(1.W), io.instrFetch.instr_addr, 0.U(1.W)),
      io.cpu_addr(24, 0)
    )
  )

  val stall_txn = instr_active &&
    io.instrFetch.instr_fetch_stall &&
    !qspi_data_ready &&
    (qspi_data_byte_idx === 1.U)

  // Control FSM
  start_instr    := false.B
  start_read     := false.B
  start_write    := false.B
  start_gpu_read := false.B
  stop_txn       := false.B

  when(qspi_busy || qspi_write_done) {
    when(instr_active) {
      when(io.instrFetch.instr_fetch_restart && (!started || stall_txn)) {
        stop_txn := true.B
      } .elsewhen(
        (qspi_data_ready && qspi_data_byte_idx === 1.U) ||
        io.instrFetch.instr_fetch_stall
      ) {
        when(data_txn_n =/= 3.U || io.gpu_read_req) { stop_txn := true.B }
      }
    } .elsewhen(
      (qspi_data_ready || qspi_data_req) &&
      qspi_data_byte_idx === data_txn_len &&
      !continue_txn
    ) {
      stop_txn := true.B
    }
  } .otherwise {
    when(io.cpu_read_n =/= 3.U) {
      start_read := true.B
    } .elsewhen(io.cpu_write_n =/= 3.U) {
      start_write := true.B
    } .elsewhen(io.gpu_read_req) {
      start_gpu_read := true.B
    } .elsewhen(io.instrFetch.instr_fetch_restart) {
      start_instr := true.B
    }
  }

  // Update Sequential State
  instr_active := Mux(stop_txn, false.B, Mux(qspi_busy, instr_active, start_instr))
  gpu_active   := Mux(stop_txn, false.B, Mux(qspi_busy, gpu_active, start_gpu_read))
  started      := start_instr
  stopped      := stop_txn

  when(start_instr || start_read || start_write || start_gpu_read) {
    qspi_data_byte_idx := 0.U
  } .otherwise {
    when(qspi_data_ready || qspi_data_req) {
      qspi_data_byte_idx := Mux(
        qspi_data_byte_idx === txn_len, 0.U, qspi_data_byte_idx + 1.U
      )
    }
  }

  when(qspi_data_ready) {
    qspi_data_buf(qspi_data_byte_idx) := qspi_data_out
  } .elsewhen(io.cpu_write_n =/= 3.U && (data_stall || start_write)) {
    qspi_data_buf(0) := io.cpu_data_out(7, 0)
    qspi_data_buf(1) := io.cpu_data_out(15, 8)
    qspi_data_buf(2) := io.cpu_data_out(23, 16)
    qspi_data_buf(3) := io.cpu_data_out(31, 24)
  }

  qspi_write_done := qspi_data_req && qspi_data_byte_idx === data_txn_len

  when(start_gpu_read) {
    data_txn_len := 3.U
  } .elsewhen(start_read || start_write) {
    data_txn_len := Cat(data_txn_n(1), data_txn_n(1) | data_txn_n(0))
  }

  when(continue_txn) {
    when(
      (qspi_data_req   && qspi_data_byte_idx + 1.U === data_txn_len) ||
      (qspi_data_ready && qspi_data_byte_idx       === data_txn_len)
    ) {
      data_stall := true.B
    } .elsewhen(
      data_stall &&
      qspi_data_byte_idx === 0.U &&
      ((io.cpu_read_n =/= 3.U && !data_ready) || io.cpu_write_n =/= 3.U)
    ) {
      data_stall   := false.B
      continue_txn := io.cpu_data_continue
    }
  } .otherwise {
    data_stall := false.B
    when(start_gpu_read) {
      continue_txn := false.B
    } .elsewhen(start_write || start_read) {
      continue_txn := io.cpu_data_continue
    }
  }

  // QspiController instantiation
  val q_ctrl = Module(new QspiController())
  q_ctrl.io.spi_data_in   := io.spi_data_in
  io.spi_data_out         := q_ctrl.io.spi_data_out
  io.spi_data_oe          := q_ctrl.io.spi_data_oe
  io.spi_clk_out          := q_ctrl.io.spi_clk_out
  io.spi_flash_select     := q_ctrl.io.spi_flash_select
  io.spi_ram_a_select     := q_ctrl.io.spi_ram_a_select
  io.spi_ram_b_select     := q_ctrl.io.spi_ram_b_select

  q_ctrl.io.addr_in    := addr_in
  q_ctrl.io.data_in    := qspi_data_buf(
    qspi_data_byte_idx + Mux(q_ctrl.io.data_req, 1.U, 0.U)
  )
  q_ctrl.io.start_read  := start_read || start_instr || start_gpu_read
  q_ctrl.io.start_write := start_write
  q_ctrl.io.stall_txn   := stall_txn || data_stall
  q_ctrl.io.stop_txn    := stop_txn

  qspi_data_out   := q_ctrl.io.data_out
  qspi_data_req   := q_ctrl.io.data_req
  qspi_data_ready := q_ctrl.io.data_ready
  qspi_busy       := q_ctrl.io.busy

  // CPU Instruction Fetch Outputs
  io.instrFetch.instr_fetch_started := started
  io.instrFetch.instr_fetch_stopped := stopped
  io.instrFetch.instr_data          := Cat(qspi_data_out, qspi_data_buf(0))
  io.instrFetch.instr_ready         :=
    instr_active && qspi_data_ready && qspi_data_byte_idx === 1.U

  // CPU Data Bus Outputs
  data_ready := !instr_active && !gpu_active && (
    (qspi_data_ready && qspi_data_byte_idx === data_txn_len) ||
    (io.cpu_write_n =/= 3.U &&
      ((data_stall && qspi_data_byte_idx === 0.U) || start_write))
  )
  io.cpu_ready   := data_ready
  io.cpu_data_in := Mux(
    data_ready,
    Cat(
      qspi_data_out,
      qspi_data_buf(2),
      Mux(data_txn_len === 1.U, qspi_data_out, qspi_data_buf(1)),
      Mux(data_txn_len === 0.U, qspi_data_out, qspi_data_buf(0))
    ),
    qspi_data_buf.asUInt
  )

  // GPU Read Port — arbiter wired in Step 19.2
  io.gpu_read_ready := gpu_active && qspi_data_ready && qspi_data_byte_idx === data_txn_len
  io.gpu_data       := Cat(
    qspi_data_out,
    qspi_data_buf(2),
    qspi_data_buf(1),
    qspi_data_buf(0)
  )

  io.debug_stall_txn := stall_txn
  io.debug_stop_txn  := stop_txn
}
