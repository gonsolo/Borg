// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

object InstrType extends ChiselEnum {
  val none, load, aluImm, auipc, store, aluReg, lui, branch, jalr, jal, system = Value
}

class TinyQVDecodeIO(val regAddrBits: Int) extends Bundle {
  val instr = Input(UInt(32.W))
  val imm = Output(UInt(32.W))
  val instrType = Output(InstrType())
  val instr_len = Output(UInt(2.W))
  val alu_op = Output(UInt(4.W))
  val mem_op = Output(UInt(3.W))
  val rs1 = Output(UInt(regAddrBits.W))
  val rs2 = Output(UInt(regAddrBits.W))
  val rd = Output(UInt(regAddrBits.W))
  val additional_mem_ops = Output(UInt(3.W))
  val mem_op_increment_reg = Output(Bool())
}

/** Combinational instruction decoder for RV32I (32-bit only).
  *
  * Decodes a 32-bit instruction word into pipeline control signals:
  *   - '''Instruction type''' (`InstrType` enum): load, store, ALU, branch, JAL, etc.
  *   - '''Immediate''' (sign-extended to 32 bits, format-specific encoding)
  *   - '''ALU/memory operation''' (4-bit opcode, 3-bit mem width)
  *   - '''Register indices''' (rs1, rs2, rd — 4-bit for RV32E with 16 registers)
  *
  * The C (compressed) extension was removed in Step 17.3 to save ~400-500 LUTs
  * on the iCE40 UP5K. All instructions are 32-bit aligned.
  *
  * This is a [[RawModule]] (no clock/reset) — purely combinational logic.
  *
  * @param regAddrBits register address width (default 4 for RV32E's 16 registers)
  */
class TinyQVDecode(val regAddrBits: Int = 4) extends RawModule {
  val io = IO(new TinyQVDecodeIO(regAddrBits))

  // 32-bit Immediates
  val instr = io.instr

  val uImm = Cat(instr(31), instr(30, 12), 0.U(12.W))
  val iImm = Cat(Fill(21, instr(31)), instr(30, 20))
  val sImm = Cat(Fill(21, instr(31)), instr(30, 25), instr(11, 7))
  val bImm = Cat(Fill(20, instr(31)), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
  val jImm = Cat(Fill(12, instr(31)), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))

  // Default assignments
  io.instrType := InstrType.none
  io.imm := 0.U
  io.alu_op := 0.U
  io.mem_op := 0.U
  io.rs1 := 0.U
  io.rs2 := 0.U
  io.rd := 0.U
  io.additional_mem_ops := 0.U
  io.mem_op_increment_reg := true.B

  // 32-bit instructions must have bits[1:0] == 11.
  // Without this guard, garbled buffer data (from partial instruction fetch
  // interrupts) can decode as valid load/store/branch, causing random execution.
  when(instr(1, 0) === 3.U) {
    switch(instr(6, 2)) {
      is("b00000".U) { io.instrType := InstrType.load }
      is("b00100".U) { io.instrType := InstrType.aluImm }
      is("b00101".U) { io.instrType := InstrType.auipc }
      is("b01000".U) { io.instrType := InstrType.store }
      is("b01100".U) { io.instrType := InstrType.aluReg }
      is("b01101".U) { io.instrType := InstrType.lui }
      is("b11000".U) { io.instrType := InstrType.branch }
      is("b11001".U) { io.instrType := InstrType.jalr }
      is("b11011".U) { io.instrType := InstrType.jal }
      is("b11100".U) { io.instrType := InstrType.system }
    }
  }

  io.imm := MuxCase(iImm, Seq(
    (io.instrType === InstrType.auipc || io.instrType === InstrType.lui) -> uImm,
    (io.instrType === InstrType.store) -> sImm,
    (io.instrType === InstrType.branch) -> bImm,
    (io.instrType === InstrType.jal) -> jImm
  ))

  when(io.instrType === InstrType.load || io.instrType === InstrType.auipc || io.instrType === InstrType.store || io.instrType === InstrType.jalr || io.instrType === InstrType.jal) {
    io.alu_op := 0.U // ADD
  } .elsewhen(io.instrType === InstrType.branch) {
    io.alu_op := Cat(0.U(1.W), !instr(14), instr(14, 13))
  } .elsewhen(instr(26) && io.instrType === InstrType.aluReg && instr(27)) {
    io.alu_op := Cat(3.U(2.W), instr(26), instr(13)) // CZERO
  } .otherwise {
    val bit30 = instr(30) && (instr(5) || instr(13, 12) === 1.U)
    io.alu_op := Cat(bit30, instr(14, 12))
  }

  io.mem_op := instr(14, 12)

  io.rs1 := instr(15 + regAddrBits - 1, 15)
  io.rs2 := instr(20 + regAddrBits - 1, 20)
  io.rd  := instr(7 + regAddrBits - 1, 7)

  // All instructions are 32-bit (2 halfwords)
  io.instr_len := 2.U
}
