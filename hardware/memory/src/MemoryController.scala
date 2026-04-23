// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package memory

import chisel3._
import chisel3.util._
import tinyqv.cpu.{InstrFetchIO, MemBusIO}
import borg.GpuReadIO

/** IO bundle for the SoC-level memory controller.
  *
  * Arbitrates the QSPI bus between three requestors:
  *   - CPU instruction fetch (via [[InstrFetchIO]])
  *   - CPU data read/write (loads, stores, PSRAM framebuffer)
  *   - GPU read (autonomous texel fetch — Step 19.2)
  */
class MemoryControllerIO extends Bundle {
  // CPU instruction fetch interface
  val instrFetch = Flipped(new InstrFetchIO)

  // CPU data bus (QSPI region: addr[27:25] == 0 on the TinyQV side)
  val cpuData = Flipped(new MemBusIO)

  // GPU read port (slave end — Flipped so Output/Input directions are from the GPU master's view)
  val gpuRead = Flipped(new GpuReadIO)

  // SPI/QSPI pins — MemoryController is the sole owner of the physical bus;
  // neither TinyQV nor Borg have any knowledge of QSPI.
  val qspiPins = new QspiPinsIO

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

  val data_txn_n = io.cpuData.writeN & io.cpuData.readN

  val qspi_busy       = Wire(Bool())
  val qspi_data_req   = Wire(Bool())
  val qspi_data_ready = Wire(Bool())
  val qspi_data_out   = Wire(UInt(8.W))

  val is_instr  = instr_active || start_instr
  val is_gpu    = gpu_active || start_gpu_read
  val txn_len   = Mux(is_instr, 1.U(2.W), data_txn_len)
  val addr_in = WireDefault(io.cpuData.addr(24, 0))
  when(is_instr) { addr_in := Cat(0.U(1.W), io.instrFetch.instr_addr, 0.U(1.W)) }
  when(is_gpu)   { addr_in := Cat(2.U(2.W), 0.U(3.W), io.gpuRead.addr) }

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
        when(data_txn_n =/= 3.U || io.gpuRead.req) { stop_txn := true.B }
      }
    } .elsewhen(
      (qspi_data_ready || qspi_data_req) &&
      qspi_data_byte_idx === data_txn_len &&
      !continue_txn
    ) {
      stop_txn := true.B
    }
  } .otherwise {
    when(io.cpuData.readN =/= 3.U) {
      start_read := true.B
    } .elsewhen(io.cpuData.writeN =/= 3.U) {
      start_write := true.B
    } .elsewhen(io.gpuRead.req) {
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
  } .elsewhen(io.cpuData.writeN =/= 3.U && (data_stall || start_write)) {
    for (i <- 0 until 4) {
      qspi_data_buf(i) := io.cpuData.dataOut(i * 8 + 7, i * 8)
    }
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
      ((io.cpuData.readN =/= 3.U && !data_ready) || io.cpuData.writeN =/= 3.U)
    ) {
      data_stall   := false.B
      continue_txn := io.cpuData.dataContinue
    }
  } .otherwise {
    data_stall := false.B
    when(start_gpu_read) {
      continue_txn := false.B
    } .elsewhen(start_write || start_read) {
      continue_txn := io.cpuData.dataContinue
    }
  }

  // QspiController instantiation
  val q_ctrl = Module(new QspiController())
  q_ctrl.io.spi_data_in   := io.qspiPins.dataIn
  io.qspiPins.dataOut     := q_ctrl.io.spi_data_out
  io.qspiPins.dataOe      := q_ctrl.io.spi_data_oe
  io.qspiPins.clkOut      := q_ctrl.io.spi_clk_out
  io.qspiPins.flashSelect := q_ctrl.io.spi_flash_select
  io.qspiPins.ramASelect  := q_ctrl.io.spi_ram_a_select
  io.qspiPins.ramBSelect  := q_ctrl.io.spi_ram_b_select

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
    (io.cpuData.writeN =/= 3.U &&
      ((data_stall && qspi_data_byte_idx === 0.U) || start_write))
  )
  io.cpuData.ready   := data_ready
  io.cpuData.dataIn := Mux(
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
  io.gpuRead.ready := gpu_active && qspi_data_ready && qspi_data_byte_idx === data_txn_len
  io.gpuRead.data  := Cat(
    qspi_data_out,
    qspi_data_buf(2),
    qspi_data_buf(1),
    qspi_data_buf(0)
  )

  io.debug_stall_txn := stall_txn
  io.debug_stop_txn  := stop_txn
}
