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

    writeAddr(borg, 64, instr)
    writeAddr(borg, 68, 0) // halt

    // Start and wait with extended timeout for nibble-serial
    writeAddr(borg, 124, 1) // start
    var status: BigInt = 0
    var waitCycles = 0
    do {
      borg.io.address.poke(124.U)
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
        writeAddr(borg, 64, encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 68, encodeInstruction(config, MUL, rs1 = 2, rs2 = 0, rd = 3))
        writeAddr(borg, 72, encodeInstruction(config, ADD, rs1 = 3, rs2 = 1, rd = 4))
        writeAddr(borg, 76, 0)

        // Start and wait with extended timeout
        writeAddr(borg, 124, 1)
        var status: BigInt = 0
        var waitCycles = 0
        do {
          borg.io.address.poke(124.U)
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

    // ===================================================================
    // Borg nibble-serial: batched 3-edge function test
    // ===================================================================
    utest.test("nibble_serial_batched_edge") {
      val config = FloatConfig.FP16
      simulate(new Borg(config, nibbleSerial = true)) { borg =>
        println("\n=== Nibble-Serial Batched Edge Test ===")
        resetAndWait(borg)

        // Load 6-instruction shader:
        //   fmul  r0, r4, r11      # r0  = dx0 * dpy0
        //   fmadd r0, r5, r10, r0  # r0  = neg_dy0*dpx0 + r0 = e0
        //   fmul  r1, r6, r13      # r1  = dx1 * dpy1
        //   fmadd r1, r7, r12, r1  # r1  = e1
        //   fmul  r2, r8, r15      # r2  = dx2 * dpy2
        //   fmadd r2, r9, r14, r2  # r2  = e2
        //   halt
        writeAddr(borg, 64, encodeInstruction(config, MUL, rs1 = 4, rs2 = 11, rd = 0))
        writeAddr(borg, 68, encodeInstruction(config, FMA(rs3 = 0), rs1 = 5, rs2 = 10, rd = 0))
        writeAddr(borg, 72, encodeInstruction(config, MUL, rs1 = 6, rs2 = 13, rd = 1))
        writeAddr(borg, 76, encodeInstruction(config, FMA(rs3 = 1), rs1 = 7, rs2 = 12, rd = 1))
        writeAddr(borg, 80, encodeInstruction(config, MUL, rs1 = 8, rs2 = 15, rd = 2))
        writeAddr(borg, 84, encodeInstruction(config, FMA(rs3 = 2), rs1 = 9, rs2 = 14, rd = 2))
        writeAddr(borg, 88, 0) // halt

        // Edge constants (r4-r9): dx0=1, neg_dy0=2, dx1=3, neg_dy1=4, dx2=0.5, neg_dy2=1.5
        writeAddr(borg, 4 * 4, floatToBits(1.0f, config))   // r4 = dx0
        writeAddr(borg, 5 * 4, floatToBits(2.0f, config))   // r5 = neg_dy0
        writeAddr(borg, 6 * 4, floatToBits(3.0f, config))   // r6 = dx1
        writeAddr(borg, 7 * 4, floatToBits(4.0f, config))   // r7 = neg_dy1
        writeAddr(borg, 8 * 4, floatToBits(0.5f, config))   // r8 = dx2
        writeAddr(borg, 9 * 4, floatToBits(1.5f, config))   // r9 = neg_dy2

        // Pixel deltas (r10-r15): dpx0=5, dpy0=6, dpx1=7, dpy1=8, dpx2=2, dpy2=3
        writeAddr(borg, 10 * 4, floatToBits(5.0f, config))  // r10 = dpx0
        writeAddr(borg, 11 * 4, floatToBits(6.0f, config))  // r11 = dpy0
        writeAddr(borg, 12 * 4, floatToBits(7.0f, config))  // r12 = dpx1
        writeAddr(borg, 13 * 4, floatToBits(8.0f, config))  // r13 = dpy1
        writeAddr(borg, 14 * 4, floatToBits(2.0f, config))  // r14 = dpx2
        writeAddr(borg, 15 * 4, floatToBits(3.0f, config))  // r15 = dpy2

        // Expected:
        //   e0 = dx0*dpy0 + neg_dy0*dpx0 = 1*6 + 2*5 = 16
        //   e1 = dx1*dpy1 + neg_dy1*dpx1 = 3*8 + 4*7 = 52
        //   e2 = dx2*dpy2 + neg_dy2*dpx2 = 0.5*3 + 1.5*2 = 4.5

        // Start and wait
        writeAddr(borg, 124, 1)
        var status: BigInt = 0
        var waitCycles = 0
        do {
          borg.io.address.poke(124.U)
          borg.io.data_read_n.poke(2.U)
          borg.io.data_write_n.poke(3.U)
          borg.clock.step(1)
          status = borg.io.data_out.peek().litValue
          waitCycles += 1
        } while ((status & 2) == 0 && waitCycles < 1000)
        borg.io.data_read_n.poke(3.U)

        Predef.assert((status & 2) != 0, s"Batched edge did not complete in $waitCycles cycles")
        println(s"  Batched edge completed in $waitCycles cycles")

        assertResult(readAddr(borg, 0, config), 16.0f, config, "e0 = dx0*dpy0 + neg_dy0*dpx0")
        assertResult(readAddr(borg, 4, config), 52.0f, config, "e1 = dx1*dpy1 + neg_dy1*dpx1")
        assertResult(readAddr(borg, 8, config), 4.5f, config, "e2 = dx2*dpy2 + neg_dy2*dpx2")

        println("=== Nibble-Serial Batched Edge Test Passed ===\n")
      }
    }
  }
}
