// Simulation-only fast memory controller — replaces TinyQVMemCtrl for Verilator.
// Responds to reads in 2 cycles and writes in 1 cycle using SyncReadMem,
// bypassing the ~80-cycle QSPI serialization overhead.
//
// Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

/** Fast simulation memory controller.
  *
  * Same [[TinyQVMemCtrlIO]] interface as [[TinyQVMemCtrl]] but uses
  * on-chip [[SyncReadMem]] arrays instead of QSPI serialization.
  *
  * Memory map (matches QspiController conventions):
  *   - addr_in[24] == 0 → flash (instruction fetch)
  *   - addr_in[24] == 1 → PSRAM (data read/write)
  *
  * Timing:
  *   - Instruction fetch: 1 cycle latency (read 2 bytes)
  *   - Data read: 2 cycles (address latch → data ready)
  *   - Data write: 1 cycle (immediate acknowledgement)
  */
class TinyQVMemCtrlSim extends Module {
  val io = IO(new TinyQVMemCtrlIO)

  // --- Unified Memory Array ---
  // To prevent Firrtl from optimizing away read-only memories, we use a single
  // unified simulation memory read/written by the data ports.
  // 512KB total: 0x00000-0x3FFFF -> Flash (256KB), 0x40000-0x7FFFF -> PSRAM (256KB)
  val sim_mem = SyncReadMem(524288, UInt(8.W))

  // --- Instruction Fetch ---
  // The CPU requests 16-bit instructions at word-aligned addresses.
  // instr_addr is a 23-bit word address; byte address = instr_addr << 1.
  val instr_byte_addr = Cat(io.instrFetch.instr_addr, 0.U(1.W))

  // Pipeline registers for instruction fetch
  val instr_started_reg = RegInit(false.B)
  val instr_ready_reg = RegInit(false.B)
  
  // Read ports for flash (always active, we just capture the output)
  val fetch_en_0 = io.instrFetch.instr_fetch_restart && !io.instrFetch.instr_fetch_stall
  val fetch_en_1 = RegNext(fetch_en_0)
  
  val flash_rd0 = sim_mem.read(instr_byte_addr(17,0), fetch_en_0)
  val instr_addr_reg = RegEnable(instr_byte_addr, fetch_en_0)
  val flash_rd1 = sim_mem.read(instr_addr_reg(17,0) + 1.U, fetch_en_1)

  val instr_byte0_reg = RegEnable(flash_rd0, fetch_en_1)

  when(fetch_en_0) {
    instr_started_reg := true.B
    instr_ready_reg := false.B
  }.elsewhen(fetch_en_1) {
    instr_started_reg := false.B
    instr_ready_reg := true.B
  }.otherwise {
    instr_started_reg := false.B
    instr_ready_reg := false.B
  }

  io.instrFetch.instr_fetch_started := instr_started_reg
  io.instrFetch.instr_fetch_stopped := false.B
  io.instrFetch.instr_data := Cat(flash_rd1, instr_byte0_reg)
  io.instrFetch.instr_ready := instr_ready_reg

  // --- Data Read/Write ---
  val data_txn_n = io.data_write_n & io.data_read_n

  // Transaction length: 0=byte, 1=halfword, 3=word
  val data_txn_len = Cat(data_txn_n(1), data_txn_n(1) | data_txn_n(0))

  // FSM for data transactions
  val sIdle :: sRead1 :: Nil = Enum(2)
  val dstate = RegInit(sIdle)
  val data_ready_reg = RegInit(false.B)
  val data_addr_reg = Reg(UInt(18.W))
  val data_len_reg = Reg(UInt(2.W))
  val read_buf = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))

  // PSRAM byte address: offset by 0x40000 (256KB) to put it in the upper half of sim_mem
  val psram_byte_addr = io.data_addr(17, 0) + 0x40000.U

  // Read ports (active during sRead1)
  val rd_active = dstate === sRead1
  val rd0 = sim_mem.read(data_addr_reg, rd_active)
  val rd1 = sim_mem.read(data_addr_reg + 1.U, rd_active)
  val rd2 = sim_mem.read(data_addr_reg + 2.U, rd_active)
  val rd3 = sim_mem.read(data_addr_reg + 3.U, rd_active)

  data_ready_reg := false.B

  switch(dstate) {
    is(sIdle) {
      when(io.data_read_n =/= 3.U) {
        // Start data read: latch address
        data_addr_reg := psram_byte_addr
        data_len_reg := data_txn_len
        dstate := sRead1
      }.elsewhen(io.data_write_n =/= 3.U) {
        // Data write: immediate
        val wa = psram_byte_addr
        sim_mem.write(wa, io.data_out(7, 0))
        when(data_txn_len >= 1.U) {
          sim_mem.write(wa + 1.U, io.data_out(15, 8))
        }
        when(data_txn_len >= 3.U) {
          sim_mem.write(wa + 2.U, io.data_out(23, 16))
          sim_mem.write(wa + 3.U, io.data_out(31, 24))
        }
        data_ready_reg := true.B
        // Stay in sIdle for continued transactions
      }
    }
    is(sRead1) {
      // Data arrives from SyncReadMem this cycle
      read_buf(0) := rd0
      read_buf(1) := rd1
      read_buf(2) := rd2
      read_buf(3) := rd3
      data_ready_reg := true.B
      dstate := sIdle
    }
  }

  io.data_ready := data_ready_reg
  io.data_in := Cat(read_buf(3), read_buf(2), read_buf(1), read_buf(0))

  // --- SPI outputs: tied off (not used in simulation) ---
  io.spi_data_out := 0.U
  io.spi_data_oe := 0.U
  io.spi_clk_out := false.B
  io.spi_flash_select := true.B   // Deselected (active low)
  io.spi_ram_a_select := true.B
  io.spi_ram_b_select := true.B

  io.debug_stall_txn := false.B
  io.debug_stop_txn := false.B
}
