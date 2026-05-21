// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import chisel3._
import soc.SoCLogic
import memory.SdramBackendSim

/** Verilator-only top-level module.
  *
  * Identical SoC (CPU, MemoryController, Peripherals, Borg) as
  * [[tt_um_gonsolo_borg]], but the MemoryController backend is driven by a real
  * Chisel [[SdramBackendSim]] (behavioral SDRAM with realistic latency and a
  * registered done/busy handshake) instead of QSPI or a hand-written C++ flat
  * backend.  This removes the C++ backend as a variable when debugging the
  * ULX3S SDRAM path: the GPU/MemoryController now talk to the same kind of
  * MemBackendIO backend they use on hardware.
  *
  * A `dbg_*` host backdoor (into SdramBackendSim's memory) lets the C++ harness
  * preload firmware/texture and read back the framebuffer without modeling the
  * bus.  NOT used for the ASIC GDS flow (that stays on tt_um_gonsolo_borg).
  */
class BorgSimTop(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  // Host backdoor into the behavioral SDRAM (firmware/texture load + readback).
  val dbg_we    = IO(Input(Bool()))
  val dbg_waddr = IO(Input(UInt(24.W)))
  val dbg_wdata = IO(Input(UInt(16.W)))
  val dbg_raddr = IO(Input(UInt(24.W)))
  val dbg_rdata = IO(Output(UInt(16.W)))

  // SoCLogic abstract members — identical to tt_um_gonsolo_borg.
  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // Wire up the SoC.
  val uo_out_val = wireSoC()

  // Behavioral SDRAM backend (full 24-bit word space to match real SDRAM).
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
}
