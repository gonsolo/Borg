// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Tests for the nibble-serial MulAddRecFN and Borg with nibbleSerial=true.

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object NibbleSerialTests extends TestSuite {

  // Reuse float helpers from BorgTests
  import BorgTests._

  // =========================================================================
  // Unit test: NibbleSerialMulAdd directly
  // =========================================================================

  /** Test the raw nibble-serial multiplier-accumulator. */
  def testNibbleSerialMulAdd(a: Int, b: Int, c: Int, expected: Int, label: String): Unit = {
    simulate(new NibbleSerialMulAdd(11)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)

      // Should start idle
      Predef.assert(dut.io.ready.peek().litToBoolean, s"$label: not ready at start")

      // Kick off computation
      dut.io.a.poke(a.U)
      dut.io.b.poke(b.U)
      dut.io.c.poke(c.U)
      dut.io.valid.poke(true.B)
      dut.clock.step(1)
      dut.io.valid.poke(false.B)

      // Wait for ready
      var cycles = 0
      while (!dut.io.ready.peek().litToBoolean && cycles < 20) {
        dut.clock.step(1)
        cycles += 1
      }
      Predef.assert(dut.io.ready.peek().litToBoolean, s"$label: FMA did not complete in $cycles cycles")

      val result = dut.io.result.peek().litValue.toInt
      println(f"  $label: $a * $b + $c = $result (expected $expected, cycles=$cycles)")
      Predef.assert(result == expected, f"$label: got $result, expected $expected")
    }
  }

  // =========================================================================
  // Borg integration tests with nibbleSerial=true
  // =========================================================================

  /** Run a single operation through Borg with nibbleSerial=true.
    * Extended timeout since nibble-serial takes more cycles.
    */
  def runNibbleSerialTest(borg: Borg, config: FloatConfig, op: Op, a: Float, b: Float, c: Float = 0f): Unit = {
    resetAndWait(borg)

    writeAddr(borg, 0, floatToBits(a, config))
    writeAddr(borg, 4, floatToBits(b, config))
    val (instr, rdAddr, expected, label) = op match {
      case ADD =>
        (encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2),
          8, a + b, f"NS: $a%8.2f + $b%8.2f")
      case MUL =>
        (encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2),
          8, a * b, f"NS: $a%8.2f * $b%8.2f")
      case FNEG =>
        (encodeInstruction(config, FNEG, rs1 = 0, rs2 = 0, rd = 2),
          8, -a, f"NS: fneg($a%8.2f)")
      case FSTEP =>
        val expected = if (a <= 0f) 0.0f else 1.0f
        (encodeInstruction(config, FSTEP, rs1 = 0, rs2 = 0, rd = 2),
          8, expected, f"NS: fstep($a%8.2f)")
      case fma: FMA =>
        writeAddr(borg, 12, floatToBits(c, config))
        (encodeInstruction(config, FMA(rs3 = 3), rs1 = 0, rs2 = 1, rd = 2),
          8, a * b + c, f"NS: $a%8.2f * $b%8.2f + $c%8.2f")
    }

    writeAddr(borg, 32, instr)
    writeAddr(borg, 36, 0) // halt

    // Start and wait with extended timeout for nibble-serial
    writeAddr(borg, 60, 1) // start
    var status: BigInt = 0
    var waitCycles = 0
    do {
      borg.io.address.poke(60.U)
      borg.io.data_read_n.poke(2.U)
      borg.io.data_write_n.poke(3.U)
      borg.clock.step(1)
      status = borg.io.data_out.peek().litValue
      waitCycles += 1
    } while ((status & 2) == 0 && waitCycles < 200)
    borg.io.data_read_n.poke(3.U)

    Predef.assert((status & 2) != 0, s"$label: Borg did not complete in $waitCycles cycles")
    assertResult(readAddr(borg, rdAddr, config), expected, config, label)
  }

  val tests = Tests {
    // ===================================================================
    // Raw nibble-serial multiplier tests
    // ===================================================================
    utest.test("nibble_serial_mul_add_basic") {
      println("\n=== NibbleSerialMulAdd Unit Tests ===")

      // Simple cases: a * b + c
      testNibbleSerialMulAdd(3, 5, 0, 15, "3*5+0")
      testNibbleSerialMulAdd(7, 11, 10, 87, "7*11+10")
      testNibbleSerialMulAdd(1, 1, 0, 1, "1*1+0")
      testNibbleSerialMulAdd(0, 100, 50, 50, "0*100+50")
      testNibbleSerialMulAdd(255, 255, 0, 65025, "255*255+0")

      // FP16 significand range (11-bit: 0-2047)
      testNibbleSerialMulAdd(1024, 1024, 0, 1048576, "1024*1024+0")
      testNibbleSerialMulAdd(2047, 1, 100, 2147, "2047*1+100")

      println("=== NibbleSerialMulAdd Unit Tests Passed ===\n")
    }

    // ===================================================================
    // Borg with nibbleSerial=true — individual instruction tests
    // ===================================================================
    utest.test("nibble_serial_borg_individual_fp16") {
      val config = FloatConfig.FP16
      simulate(new Borg(config, nibbleSerial = true)) { borg =>
        println("\n=== Nibble-Serial Borg FP16 Individual Tests ===")

        // ADD
        runNibbleSerialTest(borg, config, ADD, 1.0f, 2.0f)
        runNibbleSerialTest(borg, config, ADD, -3.0f, 3.0f)
        runNibbleSerialTest(borg, config, ADD, 0.5f, 0.25f)

        // MUL
        runNibbleSerialTest(borg, config, MUL, 3.0f, 4.0f)
        runNibbleSerialTest(borg, config, MUL, -2.0f, 3.0f)
        runNibbleSerialTest(borg, config, MUL, 0.5f, 0.5f)

        // FMA
        runNibbleSerialTest(borg, config, FMA(3), 2.0f, 3.0f, 1.0f)
        runNibbleSerialTest(borg, config, FMA(3), 4.0f, 0.5f, -2.0f)

        // FNEG
        runNibbleSerialTest(borg, config, FNEG, 5.0f, 0f)
        runNibbleSerialTest(borg, config, FNEG, -3.0f, 0f)

        // FSTEP
        runNibbleSerialTest(borg, config, FSTEP, -1.0f, 0f)
        runNibbleSerialTest(borg, config, FSTEP, 1.0f, 0f)
        runNibbleSerialTest(borg, config, FSTEP, 0.0f, 0f)

        println("=== Nibble-Serial Borg FP16 Individual Tests Passed ===\n")
      }
    }

    // ===================================================================
    // Borg nibble-serial: multi-instruction pipeline test
    // ===================================================================
    utest.test("nibble_serial_borg_pipeline_fp16") {
      val config = FloatConfig.FP16
      simulate(new Borg(config, nibbleSerial = true)) { borg =>
        println("\n=== Nibble-Serial Pipeline Tests ===")

        // 3-instruction chain: r0=2.0, r1=3.0
        // ADD r2=r0+r1=5.0, MUL r3=r2*r0=10.0, ADD r4=r3+r1=13.0
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(2.0f, config))
        writeAddr(borg, 4, floatToBits(3.0f, config))
        writeAddr(borg, 32, encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 36, encodeInstruction(config, MUL, rs1 = 2, rs2 = 0, rd = 3))
        writeAddr(borg, 40, encodeInstruction(config, ADD, rs1 = 3, rs2 = 1, rd = 4))
        writeAddr(borg, 44, 0)

        // Start and wait with extended timeout
        writeAddr(borg, 60, 1)
        var status: BigInt = 0
        var waitCycles = 0
        do {
          borg.io.address.poke(60.U)
          borg.io.data_read_n.poke(2.U)
          borg.io.data_write_n.poke(3.U)
          borg.clock.step(1)
          status = borg.io.data_out.peek().litValue
          waitCycles += 1
        } while ((status & 2) == 0 && waitCycles < 500)
        borg.io.data_read_n.poke(3.U)

        Predef.assert((status & 2) != 0, s"Pipeline did not complete in $waitCycles cycles")
        println(s"  Pipeline completed in $waitCycles cycles")

        assertResult(readAddr(borg, 8, config), 5.0f, config, "NS chain: r2=2+3")
        assertResult(readAddr(borg, 12, config), 10.0f, config, "NS chain: r3=5*2")
        assertResult(readAddr(borg, 16, config), 13.0f, config, "NS chain: r4=10+3")

        println("=== Nibble-Serial Pipeline Tests Passed ===\n")
      }
    }
  }
}
