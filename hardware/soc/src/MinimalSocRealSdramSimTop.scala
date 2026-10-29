// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import memory.{SdramBackend, SdramChipModel}

/** MinimalSocSimTop with the real SDRAM path (SdramBackend, SdramController, pins) into
  * SdramChipModel, whose JEDEC protocol gives the variable read timing SdramBackendSim hides.
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
    // 32MB, borg.dts's memory region; a smaller model aliases the kernel's high writes.
    Module(new SdramChipModel(addrBits = 24, readLatency = 2))
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

  // Debug probes for boot debugging; see Hutt.scala's dbg* ports.
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

  val dbg_cycle_counter = IO(Output(UInt(64.W)))
  val dbg_mtime         = IO(Output(UInt(64.W)))
  dbg_cycle_counter := cpu.io.dbgCycleCounter
  dbg_mtime         := clint.io.dbgMtime

  val dbg_s1 = IO(Output(UInt(64.W)))
  val dbg_a5 = IO(Output(UInt(64.W)))
  val dbg_s3 = IO(Output(UInt(64.W)))
  val dbg_s5 = IO(Output(UInt(64.W)))
  dbg_s1 := cpu.io.dbgS1
  dbg_a5 := cpu.io.dbgA5
  dbg_s3 := cpu.io.dbgS3
  dbg_s5 := cpu.io.dbgS5

  val dbg_s2 = IO(Output(UInt(64.W)))
  dbg_s2 := cpu.io.dbgS2

  val dbg_a0 = IO(Output(UInt(64.W)))
  dbg_a0 := cpu.io.dbgA0

  val dbg_ra = IO(Output(UInt(64.W)))
  dbg_ra := cpu.io.dbgRa

  // 0 = U, 1 = S, 3 = M.
  val dbg_priv = IO(Output(UInt(2.W)))
  dbg_priv := cpu.io.dbgPrivLevel

  val dbg_trap_seq       = IO(Output(UInt(32.W)))
  val dbg_trap_from_pc   = IO(Output(UInt(64.W)))
  val dbg_trap_cause     = IO(Output(UInt(64.W)))
  val dbg_trap_to_priv   = IO(Output(UInt(2.W)))
  val dbg_trap_mtvec     = IO(Output(UInt(64.W)))
  val dbg_trap_stvec     = IO(Output(UInt(64.W)))
  val dbg_trap_target_pc = IO(Output(UInt(64.W)))
  dbg_trap_seq       := cpu.io.dbgTrapSeq
  dbg_trap_from_pc   := cpu.io.dbgTrapFromPc
  dbg_trap_cause     := cpu.io.dbgTrapCause
  dbg_trap_to_priv   := cpu.io.dbgTrapToPriv
  dbg_trap_mtvec     := cpu.io.dbgTrapMtvec
  dbg_trap_stvec     := cpu.io.dbgTrapStvec
  dbg_trap_target_pc := cpu.io.dbgTrapTargetPc

  val dbg_x18_write_seq = IO(Output(UInt(32.W)))
  val dbg_x18_write_pc  = IO(Output(UInt(64.W)))
  val dbg_x18_write_val = IO(Output(UInt(64.W)))
  dbg_x18_write_seq := cpu.io.dbgX18WriteSeq
  dbg_x18_write_pc  := cpu.io.dbgX18WritePc
  dbg_x18_write_val := cpu.io.dbgX18WriteVal

  val dbg_pc         = IO(Output(UInt(64.W)))
  val dbg_reg_wen    = IO(Output(Bool()))
  val dbg_reg_waddr  = IO(Output(UInt(5.W)))
  val dbg_reg_wdata  = IO(Output(UInt(64.W)))
  dbg_pc        := cpu.io.dbgPc
  dbg_reg_wen   := cpu.io.dbgRegWen
  dbg_reg_waddr := cpu.io.dbgRegWAddr
  dbg_reg_wdata := cpu.io.dbgRegWData

  val dbg_state     = IO(Output(UInt(4.W)))
  val dbg_wbexec_en = IO(Output(Bool()))
  val dbg_d_rd      = IO(Output(UInt(5.W)))
  val dbg_instr     = IO(Output(UInt(32.W)))
  dbg_state     := cpu.io.dbgState
  dbg_wbexec_en := cpu.io.dbgWbExecEn
  dbg_d_rd      := cpu.io.dbgDRd
  dbg_instr     := cpu.io.dbgInstr

  val dbg_ic_state         = IO(Output(UInt(3.W)))
  val dbg_ic_addr_reg      = IO(Output(UInt(23.W)))
  val dbg_ic_is_hit        = IO(Output(Bool()))
  val dbg_ic_fill_seq      = IO(Output(UInt(32.W)))
  val dbg_ic_fill_word_addr = IO(Output(UInt(23.W)))
  val dbg_ic_fill_data     = IO(Output(UInt(32.W)))
  dbg_ic_state         := iCache.io.dbgState
  dbg_ic_addr_reg      := iCache.io.dbgAddrReg
  dbg_ic_is_hit        := iCache.io.dbgIsHit
  dbg_ic_fill_seq      := iCache.io.dbgFillSeq
  dbg_ic_fill_word_addr := iCache.io.dbgFillWordAddr
  dbg_ic_fill_data     := iCache.io.dbgFillData

  // Raw dq_in against the backend's and MemoryController's captured halfwords.
  val dbg_dq_in       = IO(Output(UInt(16.W)))
  val dbg_be_readword = IO(Output(UInt(16.W)))
  val dbg_mem_hw0     = IO(Output(UInt(16.W)))
  val dbg_mem_hw1     = IO(Output(UInt(16.W)))
  dbg_dq_in       := backend.io.sdramPins.dq_in
  dbg_be_readword := backend.io.debug_readWord
  dbg_mem_hw0     := mem.io.debug_hw0
  dbg_mem_hw1     := mem.io.debug_hw1

  // The chip model's own decode of each command.
  val dbg_chip_is_read  = IO(Output(Bool()))
  val dbg_chip_is_act   = IO(Output(Bool()))
  val dbg_chip_ba       = IO(Output(UInt(2.W)))
  val dbg_chip_addr_pin = IO(Output(UInt(13.W)))
  val dbg_chip_col      = IO(Output(UInt(9.W)))
  val dbg_chip_open_row = IO(Output(UInt(13.W)))
  val dbg_chip_acc_addr = IO(Output(UInt(22.W)))
  val dbg_chip_rd_data  = IO(Output(UInt(16.W)))
  dbg_chip_is_read  := chip.dbgIsRead
  dbg_chip_is_act   := chip.dbgIsAct
  dbg_chip_ba       := chip.dbgBa
  dbg_chip_addr_pin := chip.dbgAddrPin
  dbg_chip_col      := chip.dbgCol
  dbg_chip_open_row := chip.dbgOpenRow
  dbg_chip_acc_addr := chip.dbgAccAddr
  dbg_chip_rd_data  := chip.dbgRdData

  val dbg_chip_write_seq      = IO(Output(UInt(32.W)))
  val dbg_chip_write_acc_addr = IO(Output(UInt(22.W)))
  val dbg_chip_write_data     = IO(Output(UInt(16.W)))
  val dbg_chip_write_dqm      = IO(Output(UInt(2.W)))
  dbg_chip_write_seq      := chip.dbgWriteSeq
  dbg_chip_write_acc_addr := chip.dbgWriteAccAddr
  dbg_chip_write_data     := chip.dbgWriteData
  dbg_chip_write_dqm      := chip.dbgWriteDqm

  val dbg_store_seq       = IO(Output(UInt(32.W)))
  val dbg_store_pc        = IO(Output(UInt(64.W)))
  val dbg_store_phys_addr = IO(Output(UInt(64.W)))
  val dbg_store_data      = IO(Output(UInt(64.W)))
  dbg_store_seq       := cpu.io.dbgStoreSeq
  dbg_store_pc        := cpu.io.dbgStorePc
  dbg_store_phys_addr := cpu.io.dbgStorePhysAddr
  dbg_store_data      := cpu.io.dbgStoreData
}
