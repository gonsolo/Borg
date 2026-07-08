// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import memory.SdramBackendSim

/** Verilator-only top-level module for booting real firmware (OpenSBI/Linux)
  * against the Borg-free MinimalSoC — the counterpart of asic/tt's
  * BorgSimTop, but for MinimalSoCLogic instead of the full SoCLogic.
  *
  * Exists to answer a question the ULX3S hardware silent-boot investigation
  * couldn't answer by itself: is a genuine full-chip timing-closure/logic
  * bug in the CPU (Hutt), or is it specific to MinimalSoCLogic's own
  * UART/CLINT/MemoryController wiring (untested end-to-end anywhere before
  * this) — vs. something that only shows up on real silicon. A behavioral
  * SdramBackendSim (same MemBackendIO contract as real hardware) plus a
  * host debug backdoor lets the C++ harness preload firmware directly
  * (bypassing FlashBootLoader's slow byte-serial SPI read, which the real
  * hardware LEDs already showed completing — boot_done=1) and just watch
  * whether OpenSBI/Linux actually executes and produces UART output.
  */
class MinimalSocSimTop(val CLOCK_MHZ: Int) extends RawModule with MinimalSoCLogic {
  val ui_in  = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val ena    = IO(Input(Bool()))
  val clk    = IO(Input(Clock()))
  val rst_n  = IO(Input(Bool()))

  // Host backdoor into the behavioral SDRAM (firmware preload + readback).
  val dbg_we    = IO(Input(Bool()))
  val dbg_waddr = IO(Input(UInt(24.W)))
  val dbg_wdata = IO(Input(UInt(16.W)))
  val dbg_raddr = IO(Input(UInt(24.W)))
  val dbg_rdata = IO(Output(UInt(16.W)))

  // MinimalSoCLogic abstract members.
  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // Match ULX3S hardware: RV64IMAC Hutt core, no Borg.
  override def xlen: Int = 64

  val uo_out_val = wireSoC()

  val sdram = withClockAndReset(clk, !soc_rst_reg_n) {
    Module(new SdramBackendSim(words = 0x1000000, rdDelay = 4, wrDelay = 2, dbg = true))
  }
  sdram.io.backend <> mem.io.backend

  val d = sdram.dbgIO.get
  d.we      := dbg_we
  d.waddr   := dbg_waddr
  d.wdata   := dbg_wdata
  d.raddr   := dbg_raddr
  dbg_rdata := d.rdata

  uo_out := uo_out_val

  val dbg_pc = IO(Output(UInt(64.W)))
  dbg_pc := cpu.io.dbgPc

  val dbg_mtime     = IO(Output(UInt(64.W)))
  val dbg_mtimecmp  = IO(Output(UInt(64.W)))
  val dbg_timer_irq = IO(Output(Bool()))
  val dbg_mtimecmp_write_seq = IO(Output(UInt(32.W)))
  dbg_mtime     := clint.io.dbgMtime
  dbg_mtimecmp  := clint.io.dbgMtimecmp
  dbg_timer_irq := clint.io.timerIrq
  dbg_mtimecmp_write_seq := clint.io.dbgMtimecmpWriteSeq

  val dbg_ptw_fill_seq   = IO(Output(UInt(32.W)))
  val dbg_ptw_fill_va    = IO(Output(UInt(64.W)))
  val dbg_ptw_fill_ppn   = IO(Output(UInt(44.W)))
  val dbg_ptw_fill_level = IO(Output(UInt(2.W)))
  dbg_ptw_fill_seq   := cpu.io.dbgPtwFillSeq
  dbg_ptw_fill_va    := cpu.io.dbgPtwFillVA
  dbg_ptw_fill_ppn   := cpu.io.dbgPtwFillPPN
  dbg_ptw_fill_level := cpu.io.dbgPtwFillLevel

  val dbg_store_seq        = IO(Output(UInt(32.W)))
  val dbg_store_pc         = IO(Output(UInt(64.W)))
  val dbg_store_phys_addr  = IO(Output(UInt(28.W)))
  val dbg_store_va         = IO(Output(UInt(64.W)))
  val dbg_store_data       = IO(Output(UInt(64.W)))
  dbg_store_seq       := cpu.io.dbgStoreSeq
  dbg_store_pc        := cpu.io.dbgStorePc
  dbg_store_phys_addr := cpu.io.dbgStorePhysAddr
  dbg_store_va        := cpu.io.dbgStoreVA
  dbg_store_data       := cpu.io.dbgStoreData
}
