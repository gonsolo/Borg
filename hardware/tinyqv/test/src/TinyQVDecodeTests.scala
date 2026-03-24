// Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object TinyQVDecodeTests extends TestSuite {

  def withDecode(testFn: TinyQVDecode => Unit): Unit = {
    simulate(new TinyQVDecode()) { dut =>
      testFn(dut)
    }
  }

  // Helper to build a minimal 32-bit instruction with given opcode[6:2] field
  // Format: instr[6:2] = opcode, instr[1:0] = 11 (marks 32-bit)
  def mkInstr32(opcode5: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, funct3: Int = 0, funct7: Int = 0): Long = {
    val instr: Long =
      ((funct7 & 0x7F).toLong << 25) |
      ((rs2 & 0x1F).toLong << 20) |
      ((rs1 & 0x1F).toLong << 15) |
      ((funct3 & 0x7).toLong << 12) |
      ((rd & 0x1F).toLong << 7) |
      ((opcode5 & 0x1F).toLong << 2) |
      0x3L  // bits[1:0] = 11
    instr
  }

  val tests = Tests {

    utest.test("32-bit LOAD (LW) decodes correctly") {
      withDecode { dut =>
        // LW x1, 0(x2): opcode=00000, rd=1, rs1=2, funct3=010, imm=0
        val instr = mkInstr32(0x00, rd = 1, rs1 = 2, funct3 = 2)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.load.litValue)
        utest.assert(dut.io.instr_len.peek().litValue == 2) // 2 halfwords
        utest.assert(dut.io.rs1.peek().litValue == 2)
        utest.assert(dut.io.rd.peek().litValue == 1)
      }
    }

    utest.test("32-bit STORE (SW) decodes correctly") {
      withDecode { dut =>
        // SW x3, 0(x4): opcode=01000, rs1=4, rs2=3, funct3=010
        val instr = mkInstr32(0x08, rs1 = 4, rs2 = 3, funct3 = 2)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.store.litValue)
        utest.assert(dut.io.rs1.peek().litValue == 4)
        utest.assert(dut.io.rs2.peek().litValue == 3)
      }
    }

    utest.test("32-bit ALU-IMM (ADDI) decodes correctly") {
      withDecode { dut =>
        // ADDI x1, x2, 5: opcode=00100, rd=1, rs1=2, funct3=000, imm=5
        val imm = 5
        val instr = mkInstr32(0x04, rd = 1, rs1 = 2, funct3 = 0) | ((imm & 0xFFF).toLong << 20)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.aluImm.litValue)
        utest.assert(dut.io.rd.peek().litValue == 1)
        utest.assert(dut.io.rs1.peek().litValue == 2)
        utest.assert(dut.io.imm.peek().litValue == 5)
      }
    }

    utest.test("32-bit ALU-REG (ADD) decodes correctly") {
      withDecode { dut =>
        // ADD x1, x2, x3: opcode=01100, rd=1, rs1=2, rs2=3, funct3=000, funct7=0
        val instr = mkInstr32(0x0C, rd = 1, rs1 = 2, rs2 = 3, funct3 = 0, funct7 = 0)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.aluReg.litValue)
        utest.assert(dut.io.rd.peek().litValue == 1)
        utest.assert(dut.io.rs1.peek().litValue == 2)
        utest.assert(dut.io.rs2.peek().litValue == 3)
      }
    }

    utest.test("32-bit LUI decodes correctly") {
      withDecode { dut =>
        // LUI x5, 0x12345: opcode=01101, rd=5
        val imm20 = 0x12345
        val instr: Long = (imm20.toLong << 12) | (5L << 7) | (0x0D << 2) | 0x3
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.lui.litValue)
        utest.assert(dut.io.rd.peek().litValue == 5)
        utest.assert(dut.io.imm.peek().litValue == (0x12345L << 12))
      }
    }

    utest.test("32-bit BRANCH (BEQ) decodes correctly") {
      withDecode { dut =>
        // BEQ x1, x2, offset: opcode=11000, rs1=1, rs2=2, funct3=000
        val instr = mkInstr32(0x18, rs1 = 1, rs2 = 2, funct3 = 0)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.branch.litValue)
        utest.assert(dut.io.rs1.peek().litValue == 1)
        utest.assert(dut.io.rs2.peek().litValue == 2)
      }
    }

    utest.test("32-bit JAL decodes correctly") {
      withDecode { dut =>
        // JAL x1, 0: opcode=11011, rd=1
        val instr = mkInstr32(0x1B, rd = 1)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.jal.litValue)
        utest.assert(dut.io.rd.peek().litValue == 1)
      }
    }

    utest.test("32-bit JALR decodes correctly") {
      withDecode { dut =>
        // JALR x1, x2, 0: opcode=11001, rd=1, rs1=2
        val instr = mkInstr32(0x19, rd = 1, rs1 = 2)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.jalr.litValue)
        utest.assert(dut.io.rd.peek().litValue == 1)
        utest.assert(dut.io.rs1.peek().litValue == 2)
      }
    }

    utest.test("32-bit SYSTEM (CSR) decodes correctly") {
      withDecode { dut =>
        // CSRRW x1, mstatus, x2: opcode=11100, rd=1, rs1=2, funct3=001
        val instr = mkInstr32(0x1C, rd = 1, rs1 = 2, funct3 = 1) | (0x300L << 20)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.system.litValue)
        utest.assert(dut.io.rd.peek().litValue == 1)
        utest.assert(dut.io.rs1.peek().litValue == 2)
      }
    }

    utest.test("32-bit AUIPC decodes correctly") {
      withDecode { dut =>
        // AUIPC x3, 0x1000: opcode=00101, rd=3
        val imm20 = 0x1000
        val instr: Long = (imm20.toLong << 12) | (3L << 7) | (0x05 << 2) | 0x3
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.auipc.litValue)
        utest.assert(dut.io.rd.peek().litValue == 3)
      }
    }

    utest.test("compressed C.NOP decodes as ALU-IMM") {
      withDecode { dut =>
        // C.NOP = 0x0001 (ADDI x0, x0, 0)
        dut.io.instr.poke(0x0001.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.aluImm.litValue)
        utest.assert(dut.io.instr_len.peek().litValue == 1) // 1 halfword
      }
    }

    utest.test("compressed C.LW decodes as load") {
      withDecode { dut =>
        // C.LW: bits[1:0]=00, bits[15:13]=010
        // C.LW rd', offset(rs1'): 010_nnn_mmm_pp_ddd_00
        val instr = 0x4000 // C.LW with rs1'=x8, rd'=x8, offset=0
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.load.litValue)
        utest.assert(dut.io.instr_len.peek().litValue == 1) // 1 halfword
      }
    }

    utest.test("compressed C.J decodes as JAL") {
      withDecode { dut =>
        // C.J: bits[1:0]=01, bits[15:13]=101 → JAL x0, offset
        // 101_xxxxxxxxxxx_01
        val instr = 0xA001 // C.J with offset 0
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.jal.litValue)
        utest.assert(dut.io.instr_len.peek().litValue == 1) // 1 halfword
        utest.assert(dut.io.rd.peek().litValue == 0) // C.J writes x0
      }
    }

    utest.test("32-bit instruction has instr_len=4") {
      withDecode { dut =>
        dut.io.instr.poke(mkInstr32(0x04).U) // ADDI
        utest.assert(dut.io.instr_len.peek().litValue == 2) // 2 halfwords
      }
    }

    utest.test("unknown instruction decodes as none") {
      withDecode { dut =>
        // opcode[6:2] = 11111 (reserved), bits[1:0]=11
        val instr = mkInstr32(0x1F)
        dut.io.instr.poke(instr.U)
        utest.assert(dut.io.instrType.peek().litValue == InstrType.none.litValue)
      }
    }
  }
}
