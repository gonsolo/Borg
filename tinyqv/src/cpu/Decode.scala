// Copyright Michael Bell 2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVDecode(val regAddrBits: Int = 4) extends RawModule {
  override val desiredName = "tinyqv_decoder"

  val instr = IO(Input(UInt(32.W)))

  val imm = IO(Output(UInt(32.W)))

  val is_load = IO(Output(Bool()))
  val is_alu_imm = IO(Output(Bool()))
  val is_auipc = IO(Output(Bool()))
  val is_store = IO(Output(Bool()))
  val is_alu_reg = IO(Output(Bool()))
  val is_lui = IO(Output(Bool()))
  val is_branch = IO(Output(Bool()))
  val is_jalr = IO(Output(Bool()))
  val is_jal = IO(Output(Bool()))
  val is_ret = IO(Output(Bool()))
  val is_system = IO(Output(Bool()))

  val instr_len = IO(Output(UInt(2.W)))

  val alu_op = IO(Output(UInt(4.W)))
  val mem_op = IO(Output(UInt(3.W)))

  val rs1 = IO(Output(UInt(regAddrBits.W)))
  val rs2 = IO(Output(UInt(regAddrBits.W)))
  val rd = IO(Output(UInt(regAddrBits.W)))

  val additional_mem_ops = IO(Output(UInt(3.W)))
  val mem_op_increment_reg = IO(Output(Bool()))

  // 32-bit Immediates
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
  is_load := false.B
  is_alu_imm := false.B
  is_auipc := false.B
  is_store := false.B
  is_alu_reg := false.B
  is_lui := false.B
  is_branch := false.B
  is_jalr := false.B
  is_jal := false.B
  is_ret := false.B
  is_system := false.B
  imm := 0.U
  alu_op := 0.U
  mem_op := 0.U
  rs1 := 0.U
  rs2 := 0.U
  rd := 0.U
  additional_mem_ops := 0.U
  mem_op_increment_reg := true.B

  when(instr(1, 0) === 3.U) {
    // 32-bit instructions
    switch(instr(6, 2)) {
      is("b00000".U) { is_load := true.B }
      is("b00100".U) { is_alu_imm := true.B }
      is("b00101".U) { is_auipc := true.B }
      is("b01000".U) { is_store := true.B }
      is("b01100".U) { is_alu_reg := true.B }
      is("b01101".U) { is_lui := true.B }
      is("b11000".U) { is_branch := true.B }
      is("b11001".U) { is_jalr := true.B }
      is("b11011".U) { is_jal := true.B }
      is("b11100".U) { is_system := true.B }
    }

    imm := MuxCase(iImm, Seq(
      (is_auipc || is_lui) -> uImm,
      is_store -> sImm,
      is_branch -> bImm,
      is_jal -> jImm
    ))

    when(is_load || is_auipc || is_store || is_jalr || is_jal) {
      alu_op := 0.U // ADD
    } .elsewhen(is_branch) {
      alu_op := Cat(0.U(1.W), !instr(14), instr(14, 13))
    } .elsewhen(instr(26) && is_alu_reg && instr(27)) {
      alu_op := Cat(3.U(2.W), instr(26), instr(13)) // CZERO
    } .otherwise {
      val bit30 = instr(30) && (instr(5) || instr(13, 12) === 1.U)
      alu_op := Cat(bit30, instr(14, 12))
    }

    mem_op := instr(14, 12)
    when((is_load || is_store) && instr(13, 12) === 3.U) {
      mem_op := 2.U(3.W)
      additional_mem_ops := Cat(0.U(1.W), instr(14), 1.U(1.W))
    }
    when(is_store && instr(14, 12) === 6.U) {
      mem_op := 2.U(3.W)
      additional_mem_ops := Cat(0.U(1.W), instr(14), 1.U(1.W))
      mem_op_increment_reg := false.B
    }

    rs1 := instr(15 + regAddrBits - 1, 15)
    rs2 := instr(20 + regAddrBits - 1, 20)
    rd  := instr(7 + regAddrBits - 1, 7)

  } .otherwise {
    // 16-bit compressed instructions
    imm := 0.U
    mem_op := 0.U
    rs1 := 0.U
    rs2 := 0.U
    rd := 0.U

    switch(Cat(instr(1, 0), instr(15, 13))) {
      is("b00000".U) { // ADDI4SPN
        is_alu_imm := true.B
        imm := cAddi4SpImm
        rs1 := 2.U
        rd := Cat(true.B, instr(4, 2))
      }
      is("b00010".U) { // LW
        is_load := true.B
        mem_op := 2.U
        imm := cLswImm
        rs1 := Cat(true.B, instr(9, 7))
        rd := Cat(true.B, instr(4, 2))
      }
      is("b00100".U) { // Load/store byte or halfword
        imm := Mux(instr(10), cLshImm, cLsbImm)
        rs1 := Cat(true.B, instr(9, 7))
        when(instr(11)) {
          is_store := true.B
          mem_op := Cat(0.U(2.W), instr(10))
          rs2 := Cat(true.B, instr(4, 2))
        } .otherwise {
          is_load := true.B
          mem_op := Cat(!(instr(10) & instr(6)), 0.U(1.W), instr(10))
          rd := Cat(true.B, instr(4, 2))
        }
      }
      is("b00110".U) { // SW
        is_store := true.B
        mem_op := 2.U
        imm := cLswImm
        rs1 := Cat(true.B, instr(9, 7))
        rs2 := Cat(true.B, instr(4, 2))
      }
      is("b00111".U) { // SCXT
        is_store := true.B
        mem_op := 2.U
        imm := cScxtImm
        rs1 := 3.U // gp
        rs2 := Cat(instr(5), 1.U(3.W))
        additional_mem_ops := instr(4, 2)
      }
      is("b01000".U) { // ADDI
        is_alu_imm := true.B
        imm := cAluImm
        rs1 := instr(10, 7)
        rd := instr(10, 7)
      }
      is("b01001".U) { // JAL
        is_jal := true.B
        imm := cJImm
        rd := 1.U
      }
      is("b01010".U) { // LI
        is_alu_imm := true.B
        imm := cAluImm
        rs1 := 0.U
        rd := instr(10, 7)
      }
      is("b01011".U) { // ADDI16SP / LUI
        rd := instr(10, 7)
        when(instr(10, 7) === 2.U) {
          is_alu_imm := true.B
          imm := cAddi16SpImm
          rs1 := 2.U
        } .otherwise {
          is_lui := true.B
          imm := cLuiImm
        }
      }
      is("b01100".U) { // ALU
        rs1 := Cat(true.B, instr(9, 7))
        rs2 := Cat(true.B, instr(4, 2))
        rd  := Cat(true.B, instr(9, 7))
        imm := cAluImm
        when(instr(11, 10) =/= 3.U) {
          is_alu_imm := true.B
          when(instr(11) === 0.B) { // SRx
            alu_op := Cat(instr(10), 5.U(3.W))
          } .otherwise {
            alu_op := 7.U // ANDI
          }
        } .elsewhen(instr(12)) {
          is_alu_imm := true.B
          when(instr(4, 2) === 5.U) { // NOT
            alu_op := 4.U // XOR
            imm := "hffffffff".U
          } .otherwise { // ZEXT
            alu_op := 7.U // AND
            imm := Cat(0.U(16.W), Fill(8, instr(3)), "hff".U(8.W))
          }
        } .otherwise {
          is_alu_reg := true.B
          switch(instr(6, 5)) {
            is(0.U) { alu_op := 8.U } // SUB
            is(1.U) { alu_op := 4.U } // XOR
            is(2.U) { alu_op := 6.U } // OR
            is(3.U) { alu_op := 7.U } // AND
          }
        }
      }
      is("b01101".U) { // J
        is_jal := true.B
        imm := cJImm
        rd := 0.U
      }
      is("b01110".U) { // BEQZ
        is_branch := true.B
        imm := cBImm
        rs1 := Cat(true.B, instr(9, 7))
        rs2 := 0.U
        alu_op := 4.U // XOR
        mem_op := 0.U
      }
      is("b01111".U) { // BNEZ
        is_branch := true.B
        imm := cBImm
        rs1 := Cat(true.B, instr(9, 7))
        rs2 := 0.U
        alu_op := 4.U // XOR
        mem_op := 1.U
      }
      is("b10000".U) { // SLLI
        is_alu_imm := true.B
        imm := cAluImm
        rs1 := instr(10, 7)
        rd := instr(10, 7)
        alu_op := 1.U
      }
      is("b10001".U) { // LCXT
        is_load := true.B
        mem_op := 2.U
        imm := cAddi16SpImm
        rs1 := 3.U // gp
        rd := Cat(instr(10), 1.U(3.W))
        additional_mem_ops := instr(9, 7)
      }
      is("b10010".U) { // LWSP
        is_load := true.B
        mem_op := 2.U
        imm := cLwspImm
        rs1 := 2.U
        rd := instr(10, 7)
      }
      is("b10011".U) { // LWTP
        is_load := true.B
        mem_op := 2.U
        imm := cLwspImm
        rs1 := 4.U
        rd := instr(10, 7)
      }
      is("b10100".U) {
        when(instr(6, 2) === 0.U) {
          when(instr(11, 7) === 0.U) { // EBREAK
            is_system := true.B
            imm := 1.U
          } .otherwise { // J(AL)R
            when(instr(10, 7) === 1.U && !instr(12)) { is_ret := true.B }
            is_jalr := true.B
            imm := 0.U
            rs1 := instr(10, 7)
            rd := Cat(0.U(3.W), instr(12))
          }
        } .otherwise { // MV / ADD
          is_alu_reg := true.B
          rs1 := Mux(instr(12), instr(10, 7), 0.U)
          rs2 := instr(5, 2)
          rd := instr(10, 7)
        }
      }
      is("b10110".U) { // SWSP
        is_store := true.B
        mem_op := 2.U
        imm := cSwspImm
        rs1 := 2.U
        rs2 := instr(5, 2)
      }
      is("b10111".U) { // SWTP
        is_store := true.B
        mem_op := 2.U
        imm := cSwspImm
        rs1 := 4.U
        rs2 := instr(5, 2)
      }
    }
  }

  instr_len := Mux(instr(1, 0) === 3.U, 2.U, 1.U)
}
