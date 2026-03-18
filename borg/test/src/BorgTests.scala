// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import ujson._
import os._

object BorgTests extends TestSuite {

  // --- Float conversion helpers ---

  def floatToBits(f: Float, config: FloatConfig): BigInt = {
    if (config == FloatConfig.FP32) {
      BigInt(java.lang.Float.floatToRawIntBits(f)) & 0xffffffffL
    } else {
      val bits = java.lang.Float.floatToRawIntBits(f)
      val sign = (bits >>> 31) << 15
      var exp = ((bits >>> 23) & 0xff) - 127 + 15
      var sig = (bits >>> 13) & 0x3ff
      if (exp <= 0) { exp = 0; sig = 0 }
      else if (exp >= 31) { exp = 31; sig = 0x3ff }
      BigInt(sign | (exp << 10) | sig)
    }
  }

  def bitsToFloat(b: BigInt, config: FloatConfig): Float = {
    if (config == FloatConfig.FP32) {
      java.lang.Float.intBitsToFloat(b.toInt)
    } else {
      val bits = b.toInt
      val sign = (bits >>> 15) << 31
      var exp = ((bits >>> 10) & 0x1f)
      var sig = (bits & 0x3ff) << 13
      if (exp == 0) { /* subnormal or zero */ }
      else if (exp == 31) { exp = 255 }
      else { exp = exp - 15 + 127 }
      java.lang.Float.intBitsToFloat(sign | (exp << 23) | sig)
    }
  }

  def isClose(actual: Float, expected: Float, config: FloatConfig): Boolean = {
    val relEps = if (config == FloatConfig.FP16) 1e-3f else 1e-6f
    val tolerance = math.max(relEps * math.abs(expected), relEps)
    math.abs(actual - expected) < tolerance
  }

  // --- Low-level bus helpers ---

  def writeAddr(borg: Borg, addr: Int, bits: BigInt): Unit = {
    borg.io.address.poke(addr.U)
    borg.io.data_in.poke(bits.U)
    borg.io.data_write_n.poke(2.U)
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
  }

  def readAddr(borg: Borg, addr: Int, config: FloatConfig): Float = {
    borg.io.address.poke(addr.U)
    borg.io.data_read_n.poke(2.U)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
    val res = bitsToFloat(borg.io.data_out.peek().litValue, config)
    borg.io.data_read_n.poke(3.U)
    res
  }

  // --- Instruction encoding ---

  sealed trait Op
  case object ADD extends Op
  case object MUL extends Op
  case class FMA(rs3: Int) extends Op
  case object FNEG extends Op
  case object FSTEP extends Op

  def encodeInstruction(config: FloatConfig, op: Op, rs1: Int, rs2: Int, rd: Int): BigInt = {
    if (config.totalBits >= 32) op match {
      case ADD     => BigInt((0x00 << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7))
      case MUL     => BigInt((0x4  << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7))
      case FMA(r3) => BigInt((r3  << 27) | (rs2 << 20) | (rs1 << 15) | (rd << 7) | (1 << 2))
      case FNEG    => BigInt((0x6  << 25) | (rs1 << 15) | (rd << 7))
      case FSTEP   => BigInt((0x8  << 25) | (rs1 << 15) | (rd << 7))
    } else op match {
      case ADD     => BigInt((rs2 << 8) | (rs1 << 4) | rd)
      case MUL     => BigInt((1 << 14) | (rs2 << 8) | (rs1 << 4) | rd)
      case FMA(r3) => BigInt((2 << 14) | (r3 << 12) | (rs2 << 8) | (rs1 << 4) | rd)
      case FNEG    => BigInt((3 << 14) | (rs1 << 4) | rd)
      case FSTEP   => BigInt((3 << 14) | (1 << 12) | (rs1 << 4) | rd)
    }
  }

  // --- Execution helpers ---

  def resetAndWait(borg: Borg): Unit = writeAddr(borg, 60, 2)

  def startAndWaitForHalt(borg: Borg): Unit = {
    writeAddr(borg, 60, 1)
    var status: BigInt = 0
    do {
      borg.io.address.poke(60.U)
      borg.io.data_read_n.poke(2.U)
      borg.io.data_write_n.poke(3.U)
      borg.clock.step(1)
      status = borg.io.data_out.peek().litValue
    } while ((status & 2) == 0)
    borg.io.data_read_n.poke(3.U)
  }

  def assertResult(actual: Float, expected: Float, config: FloatConfig, label: String): Unit = {
    println(f"Check: $label -> Actual: $actual%8.2f (Exp: $expected%8.2f)")
    if (expected.isInfinite || expected.isNaN) {
      utest.assert(actual.isInfinite || actual.isNaN)
    } else {
      utest.assert(isClose(actual, expected, config))
    }
  }

  // --- Test runners ---

  def runTest(borg: Borg, config: FloatConfig, op: Op, a: Float, b: Float, c: Float = 0f): Unit = {
    resetAndWait(borg)

    // Load operands
    writeAddr(borg, 0, floatToBits(a, config))
    writeAddr(borg, 4, floatToBits(b, config))
    val (instr, rdAddr, expected, label) = op match {
      case ADD =>
        (encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2),
          8, a + b, f"$a%8.2f + $b%8.2f")
      case MUL =>
        (encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2),
          8, a * b, f"$a%8.2f * $b%8.2f")
      case FNEG =>
        (encodeInstruction(config, FNEG, rs1 = 0, rs2 = 0, rd = 2),
          8, -a, f"fneg($a%8.2f)")
      case FSTEP =>
        val expected = if (a <= 0f) 0.0f else 1.0f
        (encodeInstruction(config, FSTEP, rs1 = 0, rs2 = 0, rd = 2),
          8, expected, f"fstep($a%8.2f)")
      case fma: FMA =>
        writeAddr(borg, 12, floatToBits(c, config))
        (encodeInstruction(config, FMA(rs3 = 3), rs1 = 0, rs2 = 1, rd = 2),
          8, a * b + c, f"$a%8.2f * $b%8.2f + $c%8.2f")
    }

    // Write instruction and halt
    writeAddr(borg, 32, instr)
    writeAddr(borg, 36, 0)

    startAndWaitForHalt(borg)
    assertResult(readAddr(borg, rdAddr, config), expected, config, label)
  }

  // --- Test suites ---

  val FP16_MAX = 65504f

  // Runs a batch of op/pairs on an already-open borg simulator instance
  def runBatch(borg: Borg, config: FloatConfig, op: Op, pairs: Seq[(Float, Float)]): Unit = {
    val tag = s"${config.getClass.getSimpleName.replace("$", "")} ${op.getClass.getSimpleName.replace("$", "")}"
    println(s"\n--- Starting $tag Batch ---")
    pairs.foreach { case (a, b) =>
      op match {
        case FNEG =>
          runTest(borg, config, FNEG, a, 0f)
        case ADD => runTest(borg, config, ADD, a, b)
        case MUL =>
          if (config != FloatConfig.FP16 || math.abs(a * b) <= FP16_MAX)
            runTest(borg, config, MUL, a, b)
        case FMA(_) =>
          Seq(1.0f, -0.5f).foreach { c =>
            if (config != FloatConfig.FP16 || math.abs(a * b + c) <= FP16_MAX)
              runTest(borg, config, FMA(3), a, b, c)
          }
        case FSTEP =>
          runTest(borg, config, FSTEP, a, 0f)
      }
    }
    println(s"--- $tag Tests Passed ---\n")
  }

  val tests = Tests {
    val projectRoot = sys.env.get("PROJECT_ROOT").map(os.Path(_)).getOrElse(os.pwd)
    val jsonFile = projectRoot / "data" / "test_cases.json"
    val data = ujson.read(os.read(jsonFile))
    val pairs = data("pairs").arr.map(p => (p(0).num.toFloat, p(1).num.toFloat)).toSeq

    // All FP32 tests share one simulate() call — one Chisel compile
    utest.test("fp32_tests") {
      simulate(new Borg(FloatConfig.FP32)) { borg =>
        runBatch(borg, FloatConfig.FP32, ADD, pairs)
        runBatch(borg, FloatConfig.FP32, MUL, pairs)
        runBatch(borg, FloatConfig.FP32, FMA(3), pairs)
        runBatch(borg, FloatConfig.FP32, FNEG, pairs)

        // Phase 1 test: verify high registers (4-7) work
        println("\n--- Testing registers 4-7 ---")
        resetAndWait(borg)
        writeAddr(borg, 16, floatToBits(3.0f, FloatConfig.FP32))  // reg4
        writeAddr(borg, 20, floatToBits(7.0f, FloatConfig.FP32))  // reg5
        // ADD: rd=6, rs1=4, rs2=5 → reg6 = 3.0 + 7.0 = 10.0
        val instr = encodeInstruction(FloatConfig.FP32, ADD, rs1 = 4, rs2 = 5, rd = 6)
        writeAddr(borg, 32, instr)  // imem[0]
        writeAddr(borg, 36, 0)      // halt
        startAndWaitForHalt(borg)
        val result = readAddr(borg, 24, FloatConfig.FP32)  // reg6
        assertResult(result, 10.0f, FloatConfig.FP32, "3.0 + 7.0 (high regs)")
        println("--- High register test passed ---\n")
      }
    }

    // All FP16 tests share one simulate() call — one Chisel compile
    utest.test("fp16_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        runBatch(borg, config, ADD, pairs)
        runBatch(borg, config, MUL, pairs)
        runBatch(borg, config, FMA(3), pairs)
        // FP16 FNEG: host negates values (XOR sign bit) before loading into Borg registers

        // FP16 FSTEP tests (inverted: <=0 -> 0.0, >0 -> 1.0)
        println("\n--- FP16 FSTEP Tests ---")
        // Negative -> 0.0
        runTest(borg, config, FSTEP, -2.0f, 0f)
        runTest(borg, config, FSTEP, -0.5f, 0f)
        // Positive -> 1.0
        runTest(borg, config, FSTEP, 1.0f, 0f)
        runTest(borg, config, FSTEP, 0.001f, 0f)
        // Zero -> 0.0 (zero is <= 0)
        runTest(borg, config, FSTEP, 0.0f, 0f)
        println("--- FP16 FSTEP Tests Passed ---\n")

        // Rotation shader test — mirrors borg_rotate.c exactly
        println("\n--- FP16 Rotation Shader Test ---")
        val instrFmulCX  = encodeInstruction(config, MUL, rs1 = 2, rs2 = 3, rd = 0)
        val instrFmaddRX = encodeInstruction(config, FMA(0), rs1 = 4, rs2 = 6, rd = 0)
        val instrFmulSX  = encodeInstruction(config, MUL, rs1 = 5, rs2 = 3, rd = 1)
        val instrFmaddRY = encodeInstruction(config, FMA(1), rs1 = 2, rs2 = 6, rd = 1)

        println(f"  IMEM[0] fmul  r0,r2,r3:     0x${instrFmulCX}%04X")
        println(f"  IMEM[1] fmadd r0,r4,r6,r0:  0x${instrFmaddRX}%04X")
        println(f"  IMEM[2] fmul  r1,r5,r3:     0x${instrFmulSX}%04X")
        println(f"  IMEM[3] fmadd r1,r2,r6,r1:  0x${instrFmaddRY}%04X")

        case class RotTest(label: String, cos: BigInt, x: BigInt, nsin: BigInt, sin: BigInt, y: BigInt, expRx: Float, expRy: Float)
        val rotTests = Seq(
          RotTest("angle=0, v=(1,0)",    0x3C00, 0x3C00, 0x8000, 0x0000, 0x0000, 1.0f, 0.0f),
          RotTest("angle=pi/4, v=(1,1)", 0x39A8, 0x3C00, 0xB9A8, 0x39A8, 0x3C00, 0.0f, 1.414f),
        )
        for (rt <- rotTests) {
          println(f"\n  Test: ${rt.label}")
          resetAndWait(borg)
          writeAddr(borg, 32, instrFmulCX)
          writeAddr(borg, 36, instrFmaddRX)
          writeAddr(borg, 40, instrFmulSX)
          writeAddr(borg, 44, instrFmaddRY)
          writeAddr(borg, 48, 0)  // halt
          writeAddr(borg, 8,  rt.cos)   // r2 = cos
          writeAddr(borg, 12, rt.x)     // r3 = x
          writeAddr(borg, 16, rt.nsin)  // r4 = -sin
          writeAddr(borg, 20, rt.sin)   // r5 = sin
          writeAddr(borg, 24, rt.y)     // r6 = y
          resetAndWait(borg)
          startAndWaitForHalt(borg)
          for (r <- 0 until 8) {
            val addr = r * 4
            val bits = {
              borg.io.address.poke(addr.U)
              borg.io.data_read_n.poke(2.U)
              borg.io.data_write_n.poke(3.U)
              borg.clock.step(1)
              val v = borg.io.data_out.peek().litValue
              borg.io.data_read_n.poke(3.U)
              v
            }
            println(f"    r$r = 0x${bits}%04X (${bitsToFloat(bits, config)}%.4f)")
          }
          val rx = readAddr(borg, 0, config)
          val ry = readAddr(borg, 4, config)
          println(f"  Result: rx=$rx%.4f (exp ${rt.expRx}%.4f), ry=$ry%.4f (exp ${rt.expRy}%.4f)")
        }
        println("\n--- FP16 Rotation Shader Test Done ---\n")

        // Minimal test: single FMUL + halt, dump ALL registers
        println("\n--- FP16 Single MUL Register Dump ---")
        resetAndWait(borg)
        writeAddr(borg, 8,  0x4000)  // r2 = 2.0
        writeAddr(borg, 12, 0x4200)  // r3 = 3.0
        val singleInstr = encodeInstruction(config, MUL, rs1 = 2, rs2 = 3, rd = 0)
        writeAddr(borg, 32, singleInstr)
        writeAddr(borg, 36, 0)
        println(f"  Instruction: fmul r0, r2, r3 = 0x${singleInstr}%04X")
        resetAndWait(borg)
        startAndWaitForHalt(borg)
        println("  After execution:")
        for (r <- 0 until 8) {
          val bits = {
            borg.io.address.poke((r * 4).U)
            borg.io.data_read_n.poke(2.U)
            borg.io.data_write_n.poke(3.U)
            borg.clock.step(1)
            val v = borg.io.data_out.peek().litValue
            borg.io.data_read_n.poke(3.U)
            v
          }
          val loaded = r match {
            case 2 => " (loaded 2.0)"
            case 3 => " (loaded 3.0)"
            case 0 => " (expected 6.0)"
            case _ => ""
          }
          println(f"    r$r = 0x${bits}%04X (${bitsToFloat(bits, config)}%.4f)$loaded")
        }
        println("--- Done ---\n")
      }
    }

    // =====================================================================
    // NEW TEST GROUP 1: Individual instruction isolation (FP16)
    // Quick, targeted verification of each op with known inputs.
    // =====================================================================
    utest.test("individual_instruction_fp16_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== Individual Instruction FP16 Tests ===")

        // ADD: 1.0 + 2.0 = 3.0
        runTest(borg, config, ADD, 1.0f, 2.0f)
        // ADD: -3.0 + 3.0 = 0.0
        runTest(borg, config, ADD, -3.0f, 3.0f)
        // ADD: 0.5 + 0.25 = 0.75
        runTest(borg, config, ADD, 0.5f, 0.25f)

        // MUL: 3.0 × 4.0 = 12.0
        runTest(borg, config, MUL, 3.0f, 4.0f)
        // MUL: -2.0 × 3.0 = -6.0
        runTest(borg, config, MUL, -2.0f, 3.0f)
        // MUL: 0.5 × 0.5 = 0.25
        runTest(borg, config, MUL, 0.5f, 0.5f)

        // FMA: 2.0 × 3.0 + 1.0 = 7.0
        runTest(borg, config, FMA(3), 2.0f, 3.0f, 1.0f)
        // FMA: 4.0 × 0.5 + (-2.0) = 0.0
        runTest(borg, config, FMA(3), 4.0f, 0.5f, -2.0f)

        // FNEG: -(5.0) = -5.0
        runTest(borg, config, FNEG, 5.0f, 0f)
        // FNEG: -(-3.0) = 3.0
        runTest(borg, config, FNEG, -3.0f, 0f)

        // FSTEP: -1.0 → 0.0
        runTest(borg, config, FSTEP, -1.0f, 0f)
        // FSTEP: +1.0 → 1.0
        runTest(borg, config, FSTEP, 1.0f, 0f)

        println("=== Individual Instruction FP16 Tests Passed ===\n")
      }
    }

    // =====================================================================
    // NEW TEST GROUP 2: Edge-case inputs (FP16)
    // Tests boundary values: zero, near-overflow, very small, neg-zero.
    // =====================================================================
    utest.test("edge_case_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== Edge Case Tests ===")

        // --- Zero behavior ---
        // MUL: 0.0 × 42.0 = 0.0
        runTest(borg, config, MUL, 0.0f, 42.0f)
        // ADD: 0.0 + 0.0 = 0.0
        runTest(borg, config, ADD, 0.0f, 0.0f)
        // ADD: 0.0 + 7.5 = 7.5
        runTest(borg, config, ADD, 0.0f, 7.5f)

        // --- Near-max FP16 values ---
        // ADD: 30000 + 30000 = 60000 (within FP16 max 65504)
        runTest(borg, config, ADD, 30000f, 30000f)
        // MUL: 100 × 100 = 10000
        runTest(borg, config, MUL, 100f, 100f)

        // --- Very small values ---
        // ADD: 0.01 + 0.01 ≈ 0.02
        runTest(borg, config, ADD, 0.01f, 0.01f)
        // MUL: 0.1 × 0.1 = 0.01
        runTest(borg, config, MUL, 0.1f, 0.1f)

        // --- FNEG of zero ---
        // FNEG(0.0) — hardware XORs sign bit
        runTest(borg, config, FNEG, 0.0f, 0f)

        // --- FSTEP boundaries ---
        // FSTEP(0.0) → 0.0 (zero is ≤ 0)
        runTest(borg, config, FSTEP, 0.0f, 0f)
        // FSTEP with small positive FP16 value → 1.0
        runTest(borg, config, FSTEP, 0.01f, 0f)
        // FSTEP with small negative → 0.0
        runTest(borg, config, FSTEP, -0.01f, 0f)

        // --- Mixed sign operations ---
        // ADD: -100.0 + 100.0 = 0.0
        runTest(borg, config, ADD, -100.0f, 100.0f)
        // MUL: -1.0 × -1.0 = 1.0
        runTest(borg, config, MUL, -1.0f, -1.0f)

        println("=== Edge Case Tests Passed ===\n")
      }
    }

    // =====================================================================
    // NEW TEST GROUP 3: MMIO register read/write round-trip (FP16)
    // Verifies the memory-mapped interface for register file and control.
    // =====================================================================
    utest.test("mmio_register_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== MMIO Register Tests ===")

        // --- Round-trip: write and read back all 8 registers ---
        resetAndWait(borg)
        val testValues = Seq(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f)
        for (r <- 0 until 8) {
          writeAddr(borg, r * 4, floatToBits(testValues(r), config))
        }
        for (r <- 0 until 8) {
          val readBack = readAddr(borg, r * 4, config)
          assertResult(readBack, testValues(r), config, s"reg$r round-trip")
        }
        println("  Register round-trip: PASSED")

        // --- Status register: idle when not running ---
        borg.io.address.poke(60.U)
        borg.io.data_read_n.poke(2.U)
        borg.io.data_write_n.poke(3.U)
        borg.clock.step(1)
        val status = borg.io.data_out.peek().litValue
        borg.io.data_read_n.poke(3.U)
        // Bit 1 = idle flag (should be set when not running)
        Predef.assert((status & 2) != 0, s"Expected idle bit set, got status=$status")
        println(s"  Status register (idle): 0x${status.toString(16)} — PASSED")

        // --- Verify IMEM write → execute → correct result ---
        // Write r0=10.0, r1=5.0, program: ADD r2=r0+r1, check r2=15.0
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(10.0f, config))   // r0
        writeAddr(borg, 4, floatToBits(5.0f, config))    // r1
        val addInstr = encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2)
        writeAddr(borg, 32, addInstr)  // imem[0]
        writeAddr(borg, 36, 0)         // halt
        startAndWaitForHalt(borg)
        val r2 = readAddr(borg, 8, config)
        assertResult(r2, 15.0f, config, "IMEM write → execute (10+5)")
        println("  IMEM write → execute: PASSED")

        println("=== MMIO Register Tests Passed ===\n")
      }
    }

    // =====================================================================
    // NEW TEST GROUP 4: Pipeline / multi-instruction programs (FP16)
    // Tests data-dependency chains and full IMEM capacity.
    // =====================================================================
    utest.test("pipeline_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== Pipeline Tests ===")

        // --- Test 1: 3-instruction chain with data dependencies ---
        // r0=2.0, r1=3.0
        // imem[0]: ADD r2 = r0 + r1      → 5.0
        // imem[1]: MUL r3 = r2 × r0      → 10.0
        // imem[2]: ADD r4 = r3 + r1      → 13.0
        // imem[3]: halt
        println("  Test 1: 3-instruction dependency chain")
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(2.0f, config))   // r0
        writeAddr(borg, 4, floatToBits(3.0f, config))    // r1
        val i0 = encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2)
        val i1 = encodeInstruction(config, MUL, rs1 = 2, rs2 = 0, rd = 3)
        val i2 = encodeInstruction(config, ADD, rs1 = 3, rs2 = 1, rd = 4)
        writeAddr(borg, 32, i0)
        writeAddr(borg, 36, i1)
        writeAddr(borg, 40, i2)
        writeAddr(borg, 44, 0)  // halt
        startAndWaitForHalt(borg)
        assertResult(readAddr(borg, 8, config), 5.0f, config, "chain: r2 = 2+3")
        assertResult(readAddr(borg, 12, config), 10.0f, config, "chain: r3 = 5×2")
        assertResult(readAddr(borg, 16, config), 13.0f, config, "chain: r4 = 10+3")
        println("  Dependency chain: PASSED")

        // --- Test 2: Full 6-instruction IMEM (max capacity) ---
        // r0=1.0
        // imem[0]: ADD r1 = r0 + r0      → 2.0
        // imem[1]: ADD r2 = r1 + r0      → 3.0
        // imem[2]: ADD r3 = r2 + r0      → 4.0
        // imem[3]: ADD r4 = r3 + r0      → 5.0
        // imem[4]: ADD r5 = r4 + r0      → 6.0
        // imem[5]: halt
        println("  Test 2: Full IMEM capacity (5 instructions + halt)")
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(1.0f, config))
        writeAddr(borg, 32, encodeInstruction(config, ADD, rs1 = 0, rs2 = 0, rd = 1))
        writeAddr(borg, 36, encodeInstruction(config, ADD, rs1 = 1, rs2 = 0, rd = 2))
        writeAddr(borg, 40, encodeInstruction(config, ADD, rs1 = 2, rs2 = 0, rd = 3))
        writeAddr(borg, 44, encodeInstruction(config, ADD, rs1 = 3, rs2 = 0, rd = 4))
        writeAddr(borg, 48, encodeInstruction(config, ADD, rs1 = 4, rs2 = 0, rd = 5))
        writeAddr(borg, 52, 0)  // halt in slot 5
        startAndWaitForHalt(borg)
        assertResult(readAddr(borg, 4, config), 2.0f, config, "imem full: r1=2")
        assertResult(readAddr(borg, 8, config), 3.0f, config, "imem full: r2=3")
        assertResult(readAddr(borg, 12, config), 4.0f, config, "imem full: r3=4")
        assertResult(readAddr(borg, 16, config), 5.0f, config, "imem full: r4=5")
        assertResult(readAddr(borg, 20, config), 6.0f, config, "imem full: r5=6")
        println("  Full IMEM: PASSED")

        // --- Test 3: Reset clears execution state ---
        // After running a program, reset, load a different program, run again
        println("  Test 3: Reset-and-rerun")
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(100.0f, config))
        writeAddr(borg, 4, floatToBits(50.0f, config))
        writeAddr(borg, 32, encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 36, 0)
        startAndWaitForHalt(borg)
        assertResult(readAddr(borg, 8, config), 5000.0f, config, "pre-reset: 100×50")

        // Now reset and run a different computation
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(7.0f, config))
        writeAddr(borg, 4, floatToBits(3.0f, config))
        writeAddr(borg, 32, encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 36, 0)
        startAndWaitForHalt(borg)
        assertResult(readAddr(borg, 8, config), 10.0f, config, "post-reset: 7+3")
        println("  Reset-and-rerun: PASSED")

        // --- Test 4: Mixed operation types in single program ---
        // r0=4.0, r1=2.0
        // imem[0]: MUL r2 = r0 × r1       → 8.0
        // imem[1]: ADD r3 = r2 + r0        → 12.0
        // imem[2]: FNEG r4 = -r3           → -12.0
        // imem[3]: halt
        println("  Test 4: Mixed ops (MUL → ADD → FNEG)")
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(4.0f, config))
        writeAddr(borg, 4, floatToBits(2.0f, config))
        writeAddr(borg, 32, encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 36, encodeInstruction(config, ADD, rs1 = 2, rs2 = 0, rd = 3))
        writeAddr(borg, 40, encodeInstruction(config, FNEG, rs1 = 3, rs2 = 0, rd = 4))
        writeAddr(borg, 44, 0)
        startAndWaitForHalt(borg)
        assertResult(readAddr(borg, 8, config), 8.0f, config, "mixed: r2=4×2")
        assertResult(readAddr(borg, 12, config), 12.0f, config, "mixed: r3=8+4")
        assertResult(readAddr(borg, 16, config), -12.0f, config, "mixed: r4=-12")
        println("  Mixed ops: PASSED")

        println("=== Pipeline Tests Passed ===\n")
      }
    }
  }
}
