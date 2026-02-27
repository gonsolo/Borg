// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import ujson._
import os._

object BorgTests extends TestSuite {

  def floatToBits(f: Float): BigInt = {
    BigInt(java.lang.Float.floatToRawIntBits(f)) & 0xffffffffL
  }

  def bitsToFloat(b: BigInt): Float = {
    java.lang.Float.intBitsToFloat(b.toInt)
  }

  def writeAddr(borg: Borg, addr: Int, bits: BigInt): Unit = {
    borg.io.address.poke(addr.U)
    borg.io.data_in.poke(bits.U)
    borg.io.data_write_n.poke(2.U)
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
  }

  def readAddr(borg: Borg, addr: Int): Float = {
    borg.io.address.poke(addr.U)
    borg.io.data_read_n.poke(2.U)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
    val res = bitsToFloat(borg.io.data_out.peek().litValue)
    borg.io.data_read_n.poke(3.U)
    res
  }

  def runBasicMathTest(borg: Borg, a: Float, b: Float, epsilon: Float): Unit = {
    // 1. Reset PC and stop execution
    writeAddr(borg, 60, 2) // Bit 1 = Reset PC

    // 2. Load operands into registers 0 and 1
    writeAddr(borg, 0, floatToBits(a))
    writeAddr(borg, 4, floatToBits(b))

    // 3. Setup Addition Instruction in imem(0)
    // opcode/funct7 = 0x00 (Add), rs1 = reg0, rs2 = reg1, rd = reg2
    // RISC-V like format: funct7(7) | rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)
    // We only use funct7, rs2, rs1, rd.
    val rd = 2
    val rs1 = 0
    val rs2 = 1
    val add_instr = (0x00 << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7)
    writeAddr(borg, 32, BigInt(add_instr)) // imem(0)

    // Halt instruction (zero) in imem(1)
    writeAddr(borg, 36, 0)

    // 4. Start execution
    writeAddr(borg, 60, 1) // Bit 0 = Start

    // 5. Wait for Halted bit (status address 16, bit 1)
    var status: BigInt = 0
    do {
      borg.io.address.poke(16.U)
      borg.io.data_read_n.poke(2.U)
      borg.io.data_write_n.poke(3.U)
      borg.clock.step(1)
      status = borg.io.data_out.peek().litValue
    } while ((status & 2) == 0)
    borg.io.data_read_n.poke(3.U)

    // 6. Read result from rf(2) (addr 8)
    val addActual = readAddr(borg, 8)
    val expectedSum = a + b

    val r0_val = readAddr(borg, 0)
    val r1_val = readAddr(borg, 4)
    val r2_val = readAddr(borg, 8)
    val r3_val = readAddr(borg, 12)
    println(
      f"Debug Registers -> rf0: $r0_val%8.2f, rf1: $r1_val%8.2f, rf2: $r2_val%8.2f, rf3: $r3_val%8.2f"
    )

    // 7. Report results to console
    println(
      f"Check: $a%8.2f + $b%8.2f -> Actual: $addActual%8.2f (Exp: $expectedSum%8.2f)"
    )

    utest.assert(math.abs(addActual - expectedSum) < epsilon)
  }

  val tests = Tests {
    utest.test("programmable_operand_batch_test") {
      // Load data from JSON
      val projectRoot =
        sys.env.get("PROJECT_ROOT").map(os.Path(_)).getOrElse(os.pwd)
      val jsonFile = projectRoot / ".." / "data" / "test_cases.json"
      val data = ujson.read(os.read(jsonFile))

      val epsilon = data("epsilon").num.toFloat
      val pairs =
        data("pairs").arr.map(p => (p(0).num.toFloat, p(1).num.toFloat))

      simulate(new Borg) { borg =>
        println("\n--- Starting Programmable Adder Batch ---")
        pairs.foreach { case (a, b) =>
          runBasicMathTest(borg, a, b, epsilon)
        }
        println("--- All Tests Passed ---\n")
      }
    }
  }
}
