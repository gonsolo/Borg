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
  val is_ret = Output(Bool())
  val instr_len = Output(UInt(2.W))
  val alu_op = Output(UInt(4.W))
  val mem_op = Output(UInt(3.W))
  val rs1 = Output(UInt(regAddrBits.W))
  val rs2 = Output(UInt(regAddrBits.W))
  val rd = Output(UInt(regAddrBits.W))
  val additional_mem_ops = Output(UInt(3.W))
  val mem_op_increment_reg = Output(Bool())
}

class TinyQVDecode(val regAddrBits: Int = 4) extends RawModule {
  val io = IO(new TinyQVDecodeIO(regAddrBits))

  // 32-bit Immediates
  val instr = io.instr

  val uImm = Cat(instr(31), instr(30, 12), 0.U(12.W))
  val iImm = Cat(Fill(21, instr(31)), instr(30, 20))
  val sImm = Cat(Fill(21, instr(31)), instr(30, 25), instr(11, 7))
  val bImm = Cat(Fill(20, instr(31)), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
  val jImm = Cat(Fill(12, instr(31)), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))

  // Compressed (16-bit) Immediates
  val cLwspImm = Cat(0.U(24.W), instr(3, 2), instr(12), instr(6, 4), 0.U(2.W))
  val cSwspImm = Cat(0.U(24.W), instr(8, 7), instr(12, 9), 0.U(2.W))
  val cLswImm  = Cat(0.U(25.W), instr(5), instr(12, 10), instr(6), 0.U(2.W))
  val cLshImm  = Cat(0.U(30.W), instr(5), 0.U(1.W))
  val cLsbImm  = Cat(0.U(30.W), instr(5), instr(6))
  val cJImm    = Cat(Fill(21, instr(12)), instr(8), instr(10, 9), instr(6), instr(7), instr(2), instr(11), instr(5, 3), 0.U(1.W))
  val cBImm    = Cat(Fill(24, instr(12)), instr(6, 5), instr(2), instr(11, 10), instr(4, 3), 0.U(1.W))
  val cAluImm  = Cat(Fill(27, instr(12)), instr(6, 2))
  val cLuiImm  = Cat(Fill(15, instr(12)), instr(6, 2), 0.U(12.W))
  val cAddi16SpImm = Cat(Fill(23, instr(12)), instr(4, 3), instr(5), instr(2), instr(6), 0.U(4.W))
  val cAddi4SpImm  = Cat(0.U(22.W), instr(10, 7), instr(12, 11), instr(5), instr(6), 0.U(2.W))
  val cScxtImm     = Cat(Fill(23, instr(12)), instr(9, 7), instr(10), instr(11), 0.U(4.W))

  // Default assignments
  io.instrType := InstrType.none
  io.is_ret := false.B
  io.imm := 0.U
  io.alu_op := 0.U
  io.mem_op := 0.U
  io.rs1 := 0.U
  io.rs2 := 0.U
  io.rd := 0.U
  io.additional_mem_ops := 0.U
  io.mem_op_increment_reg := true.B

  when(instr(1, 0) === 3.U) {
    // 32-bit instructions
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

  } .otherwise {
    // 16-bit compressed instructions
    io.imm := 0.U
    io.mem_op := 0.U
    io.rs1 := 0.U
    io.rs2 := 0.U
    io.rd := 0.U

    switch(Cat(instr(1, 0), instr(15, 13))) {
      is("b00000".U) { // ADDI4SPN
        io.instrType := InstrType.aluImm
        io.imm := cAddi4SpImm
        io.rs1 := 2.U
        io.rd := Cat(true.B, instr(4, 2))
      }
      is("b00010".U) { // LW
        io.instrType := InstrType.load
        io.mem_op := 2.U
        io.imm := cLswImm
        io.rs1 := Cat(true.B, instr(9, 7))
        io.rd := Cat(true.B, instr(4, 2))
      }
      is("b00100".U) { // Load/store byte or halfword
        io.imm := Mux(instr(10), cLshImm, cLsbImm)
        io.rs1 := Cat(true.B, instr(9, 7))
        when(instr(11)) {
          io.instrType := InstrType.store
          io.mem_op := Cat(0.U(2.W), instr(10))
          io.rs2 := Cat(true.B, instr(4, 2))
        } .otherwise {
          io.instrType := InstrType.load
          io.mem_op := Cat(!(instr(10) & instr(6)), 0.U(1.W), instr(10))
          io.rd := Cat(true.B, instr(4, 2))
        }
      }
      is("b00110".U) { // SW
        io.instrType := InstrType.store
        io.mem_op := 2.U
        io.imm := cLswImm
        io.rs1 := Cat(true.B, instr(9, 7))
        io.rs2 := Cat(true.B, instr(4, 2))
      }
      // b00111 was SCXT (removed custom TinyQV instruction)
      is("b01000".U) { // ADDI
        io.instrType := InstrType.aluImm
        io.imm := cAluImm
        io.rs1 := instr(10, 7)
        io.rd := instr(10, 7)
      }
      is("b01001".U) { // JAL
        io.instrType := InstrType.jal
        io.imm := cJImm
        io.rd := 1.U
      }
      is("b01010".U) { // LI
        io.instrType := InstrType.aluImm
        io.imm := cAluImm
        io.rs1 := 0.U
        io.rd := instr(10, 7)
      }
      is("b01011".U) { // ADDI16SP / LUI
        io.rd := instr(10, 7)
        when(instr(10, 7) === 2.U) {
          io.instrType := InstrType.aluImm
          io.imm := cAddi16SpImm
          io.rs1 := 2.U
        } .otherwise {
          io.instrType := InstrType.lui
          io.imm := cLuiImm
        }
      }
      is("b01100".U) { // ALU
        io.rs1 := Cat(true.B, instr(9, 7))
        io.rs2 := Cat(true.B, instr(4, 2))
        io.rd  := Cat(true.B, instr(9, 7))
        io.imm := cAluImm
        when(instr(11, 10) =/= 3.U) {
          io.instrType := InstrType.aluImm
          when(instr(11) === 0.B) { // SRx
            io.alu_op := Cat(instr(10), 5.U(3.W))
          } .otherwise {
            io.alu_op := 7.U // ANDI
          }
        } .elsewhen(instr(12)) {
          io.instrType := InstrType.aluImm
          when(instr(4, 2) === 5.U) { // NOT
            io.alu_op := 4.U // XOR
            io.imm := "hffffffff".U
          } .otherwise { // ZEXT
            io.alu_op := 7.U // AND
            io.imm := Cat(0.U(16.W), Fill(8, instr(3)), "hff".U(8.W))
          }
        } .otherwise {
          io.instrType := InstrType.aluReg
          switch(instr(6, 5)) {
            is(0.U) { io.alu_op := 8.U } // SUB
            is(1.U) { io.alu_op := 4.U } // XOR
            is(2.U) { io.alu_op := 6.U } // OR
            is(3.U) { io.alu_op := 7.U } // AND
          }
        }
      }
      is("b01101".U) { // J
        io.instrType := InstrType.jal
        io.imm := cJImm
        io.rd := 0.U
      }
      is("b01110".U) { // BEQZ
        io.instrType := InstrType.branch
        io.imm := cBImm
        io.rs1 := Cat(true.B, instr(9, 7))
        io.rs2 := 0.U
        io.alu_op := 4.U // XOR
        io.mem_op := 0.U
      }
      is("b01111".U) { // BNEZ
        io.instrType := InstrType.branch
        io.imm := cBImm
        io.rs1 := Cat(true.B, instr(9, 7))
        io.rs2 := 0.U
        io.alu_op := 4.U // XOR
        io.mem_op := 1.U
      }
      is("b10000".U) { // SLLI
        io.instrType := InstrType.aluImm
        io.imm := cAluImm
        io.rs1 := instr(10, 7)
        io.rd := instr(10, 7)
        io.alu_op := 1.U
      }
      // b10001 was LCXT (removed custom TinyQV instruction)
      is("b10010".U) { // LWSP
        io.instrType := InstrType.load
        io.mem_op := 2.U
        io.imm := cLwspImm
        io.rs1 := 2.U
        io.rd := instr(10, 7)
      }
      // b10011 was LWTP (removed - conflicts with c.flwsp encoding)
      is("b10100".U) {
        when(instr(6, 2) === 0.U) {
          when(instr(11, 7) === 0.U) { // EBREAK
            io.instrType := InstrType.system
            io.imm := 1.U
          } .otherwise { // J(AL)R
            when(instr(10, 7) === 1.U && !instr(12)) { io.is_ret := true.B }
            io.instrType := InstrType.jalr
            io.imm := 0.U
            io.rs1 := instr(10, 7)
            io.rd := Cat(0.U(3.W), instr(12))
          }
        } .otherwise { // MV / ADD
          io.instrType := InstrType.aluReg
          io.rs1 := Mux(instr(12), instr(10, 7), 0.U)
          io.rs2 := instr(5, 2)
          io.rd := instr(10, 7)
        }
      }
      is("b10110".U) { // SWSP
        io.instrType := InstrType.store
        io.mem_op := 2.U
        io.imm := cSwspImm
        io.rs1 := 2.U
        io.rs2 := instr(5, 2)
      }
      // b10111 was SWTP (removed - conflicts with c.fswsp encoding)
    }
  }

  io.instr_len := Mux(instr(1, 0) === 3.U, 2.U, 1.U)
}
