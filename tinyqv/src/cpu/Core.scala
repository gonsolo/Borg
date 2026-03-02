// Copyright Michael Bell 2024
package tinyqv.cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.ExtModule
import chisel3.experimental.fromIntToIntParam

class TinyQVCoreIO(regAddrBits: Int) extends Bundle {
  val clk = Input(Clock())
  val rstn = Input(Bool())

  val imm = Input(UInt(4.W))
  val imm_lo = Input(UInt(12.W))

  val is_load = Input(Bool())
  val is_alu_imm = Input(Bool())
  val is_auipc = Input(Bool())
  val is_store = Input(Bool())
  val is_alu_reg = Input(Bool())
  val is_lui = Input(Bool())
  val is_branch = Input(Bool())
  val is_jalr = Input(Bool())
  val is_jal = Input(Bool())
  val is_system = Input(Bool())
  val is_interrupt = Input(Bool())
  val is_stall = Input(Bool())

  val alu_op = Input(UInt(4.W))
  val mem_op = Input(UInt(3.W))

  val rs1 = Input(UInt(regAddrBits.W))
  val rs2 = Input(UInt(regAddrBits.W))
  val rd = Input(UInt(regAddrBits.W))

  val counter = Input(UInt(3.W))
  val pc = Input(UInt(4.W))
  val next_pc = Input(UInt(4.W))
  val data_in = Input(UInt(4.W))
  val load_data_ready = Input(Bool())

  val data_out = Output(UInt(4.W))
  val addr_out = Output(UInt(28.W))
  val address_ready = Output(Bool())
  val instr_complete = Output(Bool())
  val branch = Output(Bool())

  val return_addr = Output(UInt(23.W))

  val interrupt_req = Input(UInt(16.W))
  val timer_interrupt = Input(Bool())
  val interrupt_pending = Output(Bool())

  val debug_reg_wen = Output(Bool())
  val debug_rd = Output(UInt(4.W))
}

class TinyQVCore(numRegs: Int = 16, regAddrBits: Int = 4) extends ExtModule(Map(
  "NUM_REGS" -> numRegs,
  "REG_ADDR_BITS" -> regAddrBits
)) {
  override val desiredName = "tinyqv_core"
  
  val io = FlatIO(new TinyQVCoreIO(regAddrBits))
}
