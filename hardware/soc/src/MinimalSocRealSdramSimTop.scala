// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import memory.{SdramBackend, SdramChipModel}

/** Same purpose as MinimalSocSimTop, but with the REAL hardware SDRAM path
  * (MemoryController -> SdramBackend -> SdramController -> physical pins ->
  * SdramChipModel) instead of the idealized, fixed-latency SdramBackendSim.
  *
  * SdramChipModel's own docs: it models the actual JEDEC SDR protocol
  * (ACTIVATE -> READ/WRITE -> PRECHARGE, per-bank open rows, CAS latency) so
  * a co-sim through the real SdramBackend/SdramController exhibits variable
  * read timing that the behavioral SdramBackendSim flattens away — exactly
  * the gap between "boots in sim" (MinimalSocSimTop, confirmed working) and
  * "silent on real hardware" this target exists to close.
  */
class MinimalSocRealSdramSimTop(val CLOCK_MHZ: Int) extends RawModule with MinimalSoCLogic {
  val ui_in  = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val ena    = IO(Input(Bool()))
  val clk    = IO(Input(Clock()))
  val rst_n  = IO(Input(Bool()))

  // Host backdoor into the chip model's memory (firmware preload).
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

  override def xlen: Int = 64

  val uo_out_val = wireSoC()

  val backend = withClockAndReset(clk, !soc_rst_reg_n) {
    Module(new SdramBackend(CLOCK_MHZ))
  }
  backend.io.backend <> mem.io.backend

  val chip = withClockAndReset(clk, !soc_rst_reg_n) {
    Module(new SdramChipModel(addrBits = 22, readLatency = 2))
  }
  chip.io.cs_n   := backend.io.sdramPins.cs_n
  chip.io.ras_n  := backend.io.sdramPins.ras_n
  chip.io.cas_n  := backend.io.sdramPins.cas_n
  chip.io.we_n   := backend.io.sdramPins.we_n
  chip.io.cke    := backend.io.sdramPins.cke
  chip.io.ba     := backend.io.sdramPins.ba
  chip.io.addr   := backend.io.sdramPins.addr
  chip.io.dqm    := backend.io.sdramPins.dqm
  chip.io.dq_out := backend.io.sdramPins.dq_out
  chip.io.dq_oe  := backend.io.sdramPins.dq_oe
  backend.io.sdramPins.dq_in := chip.io.dq_in

  chip.dbg.we    := dbg_we
  chip.dbg.waddr := dbg_waddr
  chip.dbg.wdata := dbg_wdata
  chip.dbg.raddr := dbg_raddr
  dbg_rdata      := chip.dbg.rdata

  uo_out := uo_out_val

  // -- Debug probes: SoC/memory-backend state and CPU bus activity, for
  // future boot-sequence debugging in this harness. --------------------------
  val dbg_mem_state        = IO(Output(UInt(3.W)))
  val dbg_be_state         = IO(Output(UInt(3.W)))
  val dbg_ctrl_state       = IO(Output(UInt(3.W)))
  val dbg_ctrl_rdy         = IO(Output(Bool()))
  val dbg_instr_req_valid  = IO(Output(Bool()))
  val dbg_instr_req_ready  = IO(Output(Bool()))
  val dbg_instr_resp_valid = IO(Output(Bool()))
  val dbg_instr_addr       = IO(Output(UInt(23.W)))
  val dbg_data_req_valid   = IO(Output(Bool()))
  val dbg_data_req_ready   = IO(Output(Bool()))
  val dbg_data_req_addr    = IO(Output(UInt(28.W)))
  val dbg_data_req_write   = IO(Output(Bool()))
  val dbg_data_req_data    = IO(Output(UInt(64.W)))
  val dbg_data_resp_valid  = IO(Output(Bool()))
  val dbg_data_resp_data   = IO(Output(UInt(64.W)))
  dbg_mem_state  := mem.io.debug_state
  dbg_be_state   := backend.io.debug_be_state
  dbg_ctrl_state := backend.io.debug_ctrl_state
  dbg_ctrl_rdy   := backend.io.debug_ctrl_rdy
  dbg_instr_req_valid   := cpu.io.instr.req.valid
  dbg_instr_req_ready   := cpu.io.instr.req.ready
  dbg_instr_resp_valid  := cpu.io.instr.resp.valid
  dbg_instr_addr        := cpu.io.instr.req.bits
  dbg_data_req_valid    := cpu.io.data.req.valid
  dbg_data_req_ready    := cpu.io.data.req.ready
  dbg_data_req_addr     := cpu.io.data.req.bits.addr
  dbg_data_req_write    := cpu.io.data.req.bits.write
  dbg_data_req_data     := cpu.io.data.req.bits.data
  dbg_data_resp_valid   := cpu.io.data.resp.valid
  dbg_data_resp_data    := cpu.io.data.resp.bits

  // Chasing task #15's real-SDRAM-co-sim-only hang (kernel's misaligned-
  // access calibration never sees its own 8ms timeout): directly expose
  // Hutt's free-running cycleCounter (backing the `time` CSR) and CLINT's
  // independent mtime, to check whether either is genuinely advancing
  // during the stuck loop.
  val dbg_cycle_counter = IO(Output(UInt(64.W)))
  val dbg_mtime         = IO(Output(UInt(64.W)))
  dbg_cycle_counter := cpu.io.dbgCycleCounter
  dbg_mtime         := clint.io.dbgMtime
}
