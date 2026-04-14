// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package soc

import chisel3._
import chisel3.util._

/** Fast-simulation memory controller variant.
  *
  * Implements the same [[MemoryControllerIO]] as [[MemoryController]] but bypasses
  * QSPI serialization entirely. Uses SyncReadMem arrays that the C++ Verilator
  * model can access directly, accelerating simulation by ~25× (1 instr/2 cycles
  * vs ~50 cycles over real QSPI).
  *
  * SPI outputs are tied off — the fast sim never drives the physical bus.
  */
class MemoryControllerSim extends Module {
  val io = IO(new MemoryControllerIO)

  // Unified simulation memories — SyncReadMem so CIRCT emits external SRAM
  // modules whose backing arrays are directly accessible from C++.
  val sim_flash_ext = SyncReadMem(1048576, UInt(8.W))  // 1 MB flash/firmware
  val sim_psram_ext = SyncReadMem(8388608, UInt(8.W))  // 8 MB PSRAM

  // Dummy write ports to prevent CIRCT from constant-folding the memories away.
  // The never-asserted write-enable keeps the memory alive while guaranteeing
  // the synthesised logic is unchanged.
  val flash_wen = WireDefault(false.B)
  dontTouch(flash_wen)
  when(flash_wen) {
    sim_flash_ext.write(0.U(20.W), 0.U(8.W))
    sim_psram_ext.write(0.U(23.W), 0.U(8.W))
  }

  // ---------------------------------------------------------------------------
  // Instruction Fetch (fast path — 1 word every 2 cycles)
  // ---------------------------------------------------------------------------
  val instr_active = RegInit(false.B)
  val started      = RegInit(false.B)
  val stopped      = RegInit(false.B)

  val instr_addr_byte = Cat(io.instrFetch.instr_addr, 0.U(1.W))
  val fetch_en_0 = (io.instrFetch.instr_fetch_restart || instr_active) &&
                   !io.instrFetch.instr_fetch_stall
  val fetch_en_1 = RegNext(fetch_en_0)

  val flash_rd0 = sim_flash_ext.read(instr_addr_byte(19, 0),       fetch_en_0)
  val flash_rd1 = sim_flash_ext.read(instr_addr_byte(19, 0) + 1.U, fetch_en_0)

  when(io.instrFetch.instr_fetch_restart) { instr_active := true.B }

  // started/stopped MUST be single-cycle pulses (matching real MemoryController).
  // The CPU uses these to toggle instr_fetch_running in an elsewhen chain.
  started := io.instrFetch.instr_fetch_restart
  stopped := false.B  // We never stop the instruction stream in sim mode

  io.instrFetch.instr_fetch_started := started
  io.instrFetch.instr_fetch_stopped := stopped

  // Self-throttle: after delivering a word, skip one cycle to prevent buffer
  // overflow without creating a combinational loop with the CPU's stall signal.
  val instr_ready_out = Wire(Bool())
  val just_delivered  = RegNext(instr_ready_out, false.B)
  instr_ready_out     := instr_active && fetch_en_1 && !just_delivered

  io.instrFetch.instr_ready := instr_ready_out
  io.instrFetch.instr_data  := Cat(flash_rd1, flash_rd0)

  // ---------------------------------------------------------------------------
  // CPU Data Path (PSRAM — fast bypass)
  // ---------------------------------------------------------------------------
  val psram_read_active  = RegInit(false.B)
  val psram_write_active = RegInit(false.B)
  dontTouch(psram_write_active)  // Keep alive for C++ write tracking
  val psram_read_data    = RegInit(0.U(32.W))

  val is_psram_addr = io.cpu_addr(24, 23) === 2.U

  // Isolated read ports to avoid FIRRTL synchronous-memory trapping
  val psram_addr_wire = io.cpu_addr(22, 0)
  val r0 = sim_psram_ext(psram_addr_wire)
  val r1 = sim_psram_ext(psram_addr_wire + 1.U)
  val r2 = sim_psram_ext(psram_addr_wire + 2.U)
  val r3 = sim_psram_ext(psram_addr_wire + 3.U)

  // Capture memory value on cycle 2 to compensate for inferred clock enable
  val read_latch = RegNext(
    io.cpu_read_n =/= 3.U && !psram_read_active && is_psram_addr, false.B
  )
  when(read_latch) { psram_read_data := Cat(r3, r2, r1, r0) }

  when(io.cpu_read_n =/= 3.U && !psram_read_active && is_psram_addr) {
    psram_read_active := true.B
  } .elsewhen(io.cpu_write_n =/= 3.U && !psram_write_active && is_psram_addr) {
    psram_write_active := true.B
    val w_addr = io.cpu_addr(22, 0)
    val w_data = io.cpu_data_out
    when(io.cpu_write_n === 2.U) {  // Word
      sim_psram_ext(w_addr + 2.U) := w_data(23, 16)
      sim_psram_ext(w_addr + 3.U) := w_data(31, 24)
    }
    when(io.cpu_write_n === 1.U || io.cpu_write_n === 2.U) {  // Halfword or Word
      sim_psram_ext(w_addr + 1.U) := w_data(15, 8)
    }
    // Byte, Halfword, Word all write byte 0
    sim_psram_ext(w_addr) := w_data(7, 0)
  }

  // Clear active flags once the CPU drops the request
  when(psram_read_active  && io.cpu_read_n  === 3.U) { psram_read_active  := false.B }
  when(psram_write_active && io.cpu_write_n === 3.U) { psram_write_active := false.B }

  io.cpu_ready   := psram_read_active || psram_write_active
  io.cpu_data_in := psram_read_data

  // ---------------------------------------------------------------------------
  // GPU Read Port — fast bypass (Step 19.2)
  // ---------------------------------------------------------------------------
  val gpu_read_pending = RegInit(false.B)
  val gpu_addr_reg     = RegInit(0.U(16.W))

  // Latch address on new request
  when(io.gpu_read_req && !gpu_read_pending) {
    gpu_addr_reg    := io.gpu_addr
    gpu_read_pending := true.B
  }

  // Read 4 bytes from PSRAM at latched address
  val gpu_r0 = sim_psram_ext(gpu_addr_reg)
  val gpu_r1 = sim_psram_ext(gpu_addr_reg + 1.U)
  val gpu_r2 = sim_psram_ext(gpu_addr_reg + 2.U)
  val gpu_r3 = sim_psram_ext(gpu_addr_reg + 3.U)

  // Assemble and drive outputs
  io.gpu_data       := Cat(gpu_r3, gpu_r2, gpu_r1, gpu_r0)
  io.gpu_read_ready := gpu_read_pending

  // Clear pending when requestor drops req
  when(gpu_read_pending && !io.gpu_read_req) {
    gpu_read_pending := false.B
  }

  // ---------------------------------------------------------------------------
  // SPI outputs — tied off; fast sim bypasses QSPI entirely
  // ---------------------------------------------------------------------------
  io.spi_data_out     := 0.U
  io.spi_data_oe      := 0.U
  io.spi_clk_out      := false.B
  io.spi_flash_select := false.B
  io.spi_ram_a_select := false.B
  io.spi_ram_b_select := false.B

  io.debug_stall_txn := false.B
  io.debug_stop_txn  := false.B
}
