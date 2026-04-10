// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

// Use the same IO Bundle as defined in TinyQV.scala
// If they are in the same package, they are visible.

// QspiControllerSimSimIO is no longer needed — QspiControllerSimSim now uses an inline Bundle

/** Memory controller — arbitrates QSPI bus between instruction fetch and data.
  *
  * Manages a single [[QspiControllerSimSim]] instance shared by:
  *   - '''Instruction fetch:''' streaming 16-bit instruction words from flash
  *   - '''Data read/write:''' byte/halfword/word PSRAM access for loads and stores
  *
  * Data transactions take priority over instruction fetch. Multi-beat
  * (continued) transactions are supported for load/store-multiple sequences.
  * The controller reassembles byte-wide QSPI data into the CPU's 32-bit
  * data bus and handles stall/stop signaling for instruction prefetch.
  */
class TinyQVMemCtrlSim extends Module {
  val io = IO(new TinyQVMemCtrlIO)
    // Unified Simulation Memory (Firmware/Flash mapped at 0x0)
    // NOTE: Uses SyncReadMem with dummy write port so CIRCT emits
    // an external SRAM module whose Memory array is C++-accessible.
    val sim_mem_ext = SyncReadMem(1048576, UInt(8.W))
    // PSRAM mapped at 0x01000000
    val sim_psram_ext = Mem(8388608, UInt(8.W))

    // --- Dummy write port to prevent CIRCT constant-folding the flash ---
    // A never-asserted write enable keeps the memory alive while guaranteeing
    // the synthesised logic is unchanged.
    val flash_wen = WireDefault(false.B)
    dontTouch(flash_wen) // Prevent optimiser from proving it constant
    when(flash_wen) {
      sim_mem_ext.write(0.U(20.W), 0.U(8.W))
    }

    val instr_active = RegInit(false.B)
    val started = RegInit(false.B)
    val stopped = RegInit(false.B)

    // Instruction Fetch Instant Interface
    val instr_addr_byte = Cat(io.instrFetch.instr_addr, 0.U(1.W))
    val fetch_en_0 = (io.instrFetch.instr_fetch_restart || instr_active) && !io.instrFetch.instr_fetch_stall
    val fetch_en_1 = RegNext(fetch_en_0)

    val flash_rd0 = sim_mem_ext.read(instr_addr_byte(19, 0), fetch_en_0)
    val flash_rd1 = sim_mem_ext.read(instr_addr_byte(19, 0) + 1.U, fetch_en_0)

    when(io.instrFetch.instr_fetch_restart) {
      instr_active := true.B
    }

    // started/stopped MUST be single-cycle pulses (matching real MemCtrl lines 88-90).
    // The CPU uses these to toggle instr_fetch_running in an elsewhen chain (Cpu.scala:308-310).
    // If started latches high, the CPU can never clear instr_fetch_running.
    started := io.instrFetch.instr_fetch_restart
    stopped := false.B  // We never stop the instruction stream in sim mode

    io.instrFetch.instr_fetch_started := started
    io.instrFetch.instr_fetch_stopped := stopped
    // Self-throttle: after delivering a word, skip one cycle.
    // This prevents buffer overflow without creating a combinational cycle
    // with the CPU's stall signal (which depends on instr_ready).
    // Still ~25x faster than QSPI (1 word/2 cycles vs 1 word/~50 cycles).
    val instr_ready_out = Wire(Bool())
    val just_delivered = RegNext(instr_ready_out, false.B)
    instr_ready_out := instr_active && fetch_en_1 && !just_delivered
    io.instrFetch.instr_ready := instr_ready_out
    io.instrFetch.instr_data := Cat(flash_rd1, flash_rd0)

    // Data Routing (PSRAM Framebuffer) Sequential State
    val psram_read_active = RegInit(false.B)
    val psram_write_active = RegInit(false.B)
    val psram_read_data = Reg(UInt(32.W))

    when (io.data_read_n =/= 3.U && !psram_read_active) {
      // Intercept all data reads in simulation to bypass QSPI
      psram_read_active := true.B
      
      val psram_addr = io.data_addr(22, 0)
      val r0 = sim_psram_ext(psram_addr)
      val r1 = sim_psram_ext(psram_addr + 1.U)
      val r2 = sim_psram_ext(psram_addr + 2.U)
      val r3 = sim_psram_ext(psram_addr + 3.U)
      psram_read_data := Cat(r3, r2, r1, r0)
    } .elsewhen (io.data_write_n =/= 3.U && !psram_write_active) {
      // Intercept all data writes to bypass QSPI
      psram_write_active := true.B
      
      val psram_write_addr = io.data_addr(22, 0)
      val w_data = io.data_out

      when (io.data_write_n(1)) { // Upper 16 bits active (Word)
        sim_psram_ext(psram_write_addr + 2.U) := w_data(23, 16)
        sim_psram_ext(psram_write_addr + 3.U) := w_data(31, 24)
      }
      when (io.data_write_n(0) || io.data_write_n(1)) { // Lower 16 bits active (Word or Hafword)
        sim_psram_ext(psram_write_addr + 1.U) := w_data(15, 8)
      }
      // Byte, Halfword, Word all write byte 0
      sim_psram_ext(psram_write_addr) := w_data(7, 0)
    }

    when (psram_read_active) {
      psram_read_active := false.B
    }
    when (psram_write_active) {
      psram_write_active := false.B
    }

    // Module Outputs
    io.data_ready := psram_read_active || psram_write_active
    io.data_in := psram_read_data

    // Tie off unused QSPI outputs since fast sim bypasses them completely
    io.spi_data_out := 0.U
    io.spi_data_oe := 0.U
    io.spi_clk_out := false.B
    io.spi_flash_select := false.B
    io.spi_ram_a_select := false.B
    io.spi_ram_b_select := false.B

    io.debug_stall_txn := false.B
    io.debug_stop_txn := false.B
}
