// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import borg.BorgConfig
import memory.SdramBackendSim

/** Verilator top that boots OpenSBI/Linux on MinimalSoCLogic, the counterpart
  * of BorgSimTop. The harness preloads firmware through the SDRAM backdoor,
  * skipping FlashBootLoader. */
class MinimalSocSimTop(val CLOCK_MHZ: Int, override val borgCfg: Option[BorgConfig] = None) extends RawModule with MinimalSoCLogic {
  val ui_in  = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val ena    = IO(Input(Bool()))
  val clk    = IO(Input(Clock()))
  val rst_n  = IO(Input(Bool()))

  // Host backdoor into the SDRAM model: firmware preload and readback.
  val dbg_we    = IO(Input(Bool()))
  val dbg_waddr = IO(Input(UInt(24.W)))
  val dbg_wdata = IO(Input(UInt(16.W)))
  val dbg_raddr = IO(Input(UInt(24.W)))
  val dbg_rdata = IO(Output(UInt(16.W)))

  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // Matches the RV64 Linux bitstream.
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

  val dbg_x1_write_seq = IO(Output(UInt(32.W)))
  val dbg_x1_write_pc  = IO(Output(UInt(64.W)))
  val dbg_x1_write_val = IO(Output(UInt(64.W)))
  val dbg_satp         = IO(Output(UInt(64.W)))
  val dbg_priv_level   = IO(Output(UInt(2.W)))
  dbg_x1_write_seq := cpu.io.dbgX1WriteSeq
  dbg_x1_write_pc  := cpu.io.dbgX1WritePc
  dbg_x1_write_val := cpu.io.dbgX1WriteVal
  dbg_satp         := cpu.io.dbgSatp
  dbg_priv_level   := cpu.io.dbgPrivLevel

  val dbg_sepc_write_seq = IO(Output(UInt(32.W)))
  val dbg_sepc_write_pc  = IO(Output(UInt(64.W)))
  val dbg_sepc_write_val = IO(Output(UInt(64.W)))
  dbg_sepc_write_seq := cpu.io.dbgSepcWriteSeq
  dbg_sepc_write_pc  := cpu.io.dbgSepcWritePc
  dbg_sepc_write_val := cpu.io.dbgSepcWriteVal

  val dbg_load_seq        = IO(Output(UInt(32.W)))
  val dbg_load_pc         = IO(Output(UInt(64.W)))
  val dbg_load_va         = IO(Output(UInt(64.W)))
  val dbg_load_phys_addr  = IO(Output(UInt(64.W)))
  val dbg_load_rd         = IO(Output(UInt(5.W)))
  val dbg_load_val        = IO(Output(UInt(64.W)))
  dbg_load_seq       := cpu.io.dbgLoadSeq
  dbg_load_pc        := cpu.io.dbgLoadPc
  dbg_load_va        := cpu.io.dbgLoadVA
  dbg_load_phys_addr := cpu.io.dbgLoadPhysAddr
  dbg_load_rd        := cpu.io.dbgLoadRd
  dbg_load_val       := cpu.io.dbgLoadVal

  val dbg_sfence_seq = IO(Output(UInt(32.W)))
  val dbg_sfence_pc  = IO(Output(UInt(64.W)))
  dbg_sfence_seq := cpu.io.dbgSfenceSeq
  dbg_sfence_pc  := cpu.io.dbgSfencePc
}
