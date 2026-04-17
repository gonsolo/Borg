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

  def writeAddr(borg: Borg, addr: Int, data: BigInt): Unit = {
    borg.io.address.poke(addr.U)
    borg.io.data_in.poke(data.U)
    borg.io.data_write_n.poke(2.U)
    borg.clock.step(1)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
  }

  def readAddr(borg: Borg, addr: Int, config: FloatConfig): Float = {
    borg.io.address.poke(addr.U)
    borg.io.data_read_n.poke(2.U)
    borg.clock.step(1)
    borg.clock.step(1) // WAIT FOR PIPELINE TO FINISH MUX AND LATCH
    
    val bits = borg.io.data_out.peek().litValue
    
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    
    bitsToFloat(bits, config)
  }

  // --- Instruction encoding ---

  sealed trait Op
  case object ADD extends Op
  case object MUL extends Op
  case class FMA(rs3: Int) extends Op
  case object FNEG extends Op
  case object FSTEP extends Op
  case object FRCP extends Op

  def encodeInstruction(config: FloatConfig, op: Op, rs1: Int, rs2: Int, rd: Int): BigInt = {
    // 32-bit RISC-V R-type / R4-type encoding (Single Source of Truth)
    val instVal: BigInt = op match {
      case ADD     => Instructions.ADD(rs1, rs2, rd)
      case MUL     => Instructions.MUL(rs1, rs2, rd)
      case FMA(r3) => Instructions.FMA(rs1, rs2, r3, rd)
      case FNEG    => Instructions.FNEG(rs1, rd)
      case FSTEP   => Instructions.FSTEP(rs1, rd)
      case FRCP    => Instructions.FRCP(rs1, rd)
    }
    instVal
  }

  // --- Execution helpers ---

  def resetAndWait(borg: Borg): Unit = {
    borg.reset.poke(true.B)
    borg.clock.step(2)
    borg.reset.poke(false.B)
    borg.io.data_write_n.poke(3.U)
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    writeAddr(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)
  }

  def waitForHalt(borg: Borg, maxCycles: Int = 1000): Unit = {
    var status: BigInt = 0
    var cycles = 0
    
    // Explicit initial step to ensure any pending combinational triggers from 
    // immediately preceding MMIO writes (e.g. advance triggers) resolve cleanly
    // before we start polling the control registers.
    borg.clock.step(1)
    
    do {
      borg.io.address.poke(BorgGpuRegs.status_offset)
      borg.io.data_read_n.poke(2.U)
      borg.io.data_write_n.poke(3.U)
      borg.clock.step(1)
      status = borg.io.data_out.peek().litValue
      cycles += 1
    } while ((status & 2) == 0 && cycles < maxCycles)
    
    borg.io.data_read_n.poke(3.U)
    utest.assert(cycles < maxCycles) // Ensure we didn't time out
  }

  def startAndWaitForHalt(borg: Borg): Unit = {
    writeAddr(borg, BorgGpuRegs.control_offset.litValue.toInt, 1)
    waitForHalt(borg)
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
    val confirm_r0 = readAddr(borg, 0, config)
    
    writeAddr(borg, 4, floatToBits(b, config))
    val confirm_r1 = readAddr(borg, 4, config)

    val a_eff = bitsToFloat(floatToBits(a, config), config)
    val b_eff = bitsToFloat(floatToBits(b, config), config)
    val c_eff = bitsToFloat(floatToBits(c, config), config)

    val (instr, rdAddr, expected, label) = op match {
      case ADD =>
        (encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2),
          8, a_eff + b_eff, f"$a_eff%8.2f + $b_eff%8.2f")
      case MUL =>
        (encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2),
          8, a_eff * b_eff, f"$a_eff%8.2f * $b_eff%8.2f")
      case FNEG =>
        (encodeInstruction(config, FNEG, rs1 = 0, rs2 = 0, rd = 2),
          8, -a_eff, f"-$a_eff%8.2f")
      case FSTEP =>
        (encodeInstruction(config, FSTEP, rs1 = 0, rs2 = 0, rd = 2), // rs1 edge, rs2 not used
          8, if (a_eff > 0.0f) 1.0f else 0.0f, f"step($a_eff%8.2f)")
      case FRCP =>
        (encodeInstruction(config, FRCP, rs1 = 0, rs2 = 0, rd = 2),
          8, 1.0f / a_eff, f"rcp($a_eff%8.2f)")
      case FMA(rs3) =>
        (encodeInstruction(config, FMA(rs3), rs1 = 0, rs2 = 1, rd = 3),
          12, a_eff * b_eff + c_eff, f"$a_eff%8.2f * $b_eff%8.2f + $c_eff%8.2f")
    }

    op match {
      case FMA(rs3) =>
        writeAddr(borg, rs3 * 4, floatToBits(c, config))
      case _ =>
    }
    // Write instruction and halt
    writeAddr(borg, 128, instr)
    writeAddr(borg, 132, 0) // halt

    val r0_pre = readAddr(borg, 0, config)
    val r1_pre = readAddr(borg, 4, config)
    val r2_pre = readAddr(borg, 8, config)

    startAndWaitForHalt(borg)
    
    val r0_post = readAddr(borg, 0, config)
    val r1_post = readAddr(borg, 4, config)
    val actual = readAddr(borg, rdAddr, config)
    
    // Check result
    if (op == ADD && a == 1.0f && b == 2.0f) {
        val cr0_bits = java.lang.Float.floatToRawIntBits(confirm_r0) & 0xffff
        val actual_bits = java.lang.Float.floatToRawIntBits(actual) & 0xffff
        println(s">>> MAGIC DEBUG BITS: C_r0=0x${cr0_bits.toHexString} | C_r1=$confirm_r1 | POST r0=$r0_post, r1=$r1_post, r2=$actual (bits: 0x${actual_bits.toHexString})")
    }
    println(s"TEST: $label -> Actual: $actual, Expected: $expected (Pre r0=$r0_pre, r1=$r1_pre)")
    assertResult(actual, expected, config, label)
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
        case FRCP =>
          runTest(borg, config, FRCP, a, 0f)
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
        writeAddr(borg, 128, instr)  // imem[0]
        writeAddr(borg, 132, 0)      // halt
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
          writeAddr(borg, 128, instrFmulCX)
          writeAddr(borg, 132, instrFmaddRX)
          writeAddr(borg, 136, instrFmulSX)
          writeAddr(borg, 140, instrFmaddRY)
          writeAddr(borg, 144, 0)  // halt
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
        writeAddr(borg, 128, singleInstr)
        writeAddr(borg, 132, 0)
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

        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)
        
        // --- Single tests across all operators ---
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
        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)
        
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

        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)
        
        // --- Status register: idle when not running ---
        borg.io.address.poke(BorgGpuRegs.status_offset)
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
        writeAddr(borg, 128, addInstr)  // imem[0]
        writeAddr(borg, 132, 0)         // halt
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
        writeAddr(borg, 128, i0)
        writeAddr(borg, 132, i1)
        writeAddr(borg, 136, i2)
        writeAddr(borg, 140, 0)  // halt
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
        writeAddr(borg, 128, encodeInstruction(config, ADD, rs1 = 0, rs2 = 0, rd = 1))
        writeAddr(borg, 132, encodeInstruction(config, ADD, rs1 = 1, rs2 = 0, rd = 2))
        writeAddr(borg, 136, encodeInstruction(config, ADD, rs1 = 2, rs2 = 0, rd = 3))
        writeAddr(borg, 140, encodeInstruction(config, ADD, rs1 = 3, rs2 = 0, rd = 4))
        writeAddr(borg, 144, encodeInstruction(config, ADD, rs1 = 4, rs2 = 0, rd = 5))
        writeAddr(borg, 148, 0)  // halt in slot 5
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
        writeAddr(borg, 128, encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 132, 0)
        startAndWaitForHalt(borg)
        assertResult(readAddr(borg, 8, config), 5000.0f, config, "pre-reset: 100×50")

        // Now reset and run a different computation
        resetAndWait(borg)
        writeAddr(borg, 0, floatToBits(7.0f, config))
        writeAddr(borg, 4, floatToBits(3.0f, config))
        writeAddr(borg, 128, encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 132, 0)
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
        writeAddr(borg, 128, encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2))
        writeAddr(borg, 132, encodeInstruction(config, ADD, rs1 = 2, rs2 = 0, rd = 3))
        writeAddr(borg, 136, encodeInstruction(config, FNEG, rs1 = 3, rs2 = 0, rd = 4))
        writeAddr(borg, 140, 0)
        startAndWaitForHalt(borg)
        assertResult(readAddr(borg, 8, config), 8.0f, config, "mixed: r2=4×2")
        assertResult(readAddr(borg, 12, config), 12.0f, config, "mixed: r3=8+4")
        assertResult(readAddr(borg, 16, config), -12.0f, config, "mixed: r4=-12")
        println("  Mixed ops: PASSED")

        println("=== Pipeline Tests Passed ===\n")
      }
    }

    // =====================================================================
    // FRCP tests moved to BorgCoreTests.frcp_fp16 (needs rcpLut BRAM init
    // via coordWriteIsRcp, which is only accessible at BorgCore level).
    // =====================================================================
    utest.test("frcp_tests") {
      // Stub — actual FRCP tests are in BorgCoreTests.frcp_fp16
      println("FRCP tests moved to BorgCoreTests.frcp_fp16")
    }

    // =====================================================================
    // NEW TEST GROUP 6: Edge-case / Inside Flag test (FP16)
    // Verifies that BORG_ITER_INSIDE behaves correctly over r0, r1, r2 updates
    // =====================================================================
    utest.test("inside_flag_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== Inside Flag Snoop Tests ===")

        def assertInside(expected: Boolean, label: String): Unit = {
          borg.io.address.poke(BorgGpuRegs.iter_offset)
          borg.io.data_read_n.poke(2.U)
          borg.io.data_write_n.poke(3.U)
          borg.clock.step(1)
          val iterVal = borg.io.data_out.peek().litValue
          borg.io.data_read_n.poke(3.U)
          
          val isInside = ((iterVal >> 21) & 1) == 1
          println(f"Check: $label -> Actual: $isInside (Exp: $expected)")
          utest.assert(isInside == expected)
        }

        def writeToReg(rDest: Int, value: Float): Unit = {
           borg.clock.step(10)
           writeAddr(borg, 16, floatToBits(value, config)) // r4 holds the value
           writeAddr(borg, 20, floatToBits(0.0f, config)) // r5 holds 0.0
           val instr = encodeInstruction(config, ADD, rs1 = 4, rs2 = 5, rd = rDest)
           
           resetAndWait(borg)
           // Tile-origin encoding: tile_y[19:10] | tile_x[9:0]
           val tileCmd = (0 << 10) | 0  // tile origin (0,0)
           writeAddr(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, tileCmd)
           // Wait a few cycles for the FIFO to pass the command to the rasterizer
           borg.clock.step(5)
           
           writeAddr(borg, 128, instr)
           writeAddr(borg, 132, 0) // halt
           // Trigger auto-run via BORG_ITER to enter sRast phase where snooping happens
           writeAddr(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)
           waitForHalt(borg)
           
           // Delay by asserting a dummy readAddr.
           // Because the test is poking combinationally to evaluate insideFlag, and
           // waitForHalt returns immediately upon core execution halt but before
           // auto_run_stall propagates and clears in BorgRasterizer, we need an
           // additional two test-cycles of delay to avoid a combinational race constraint.
           readAddr(borg, rDest * 4, config)
        }

        println("  Test 1: All positive (strictly inside)")
        writeToReg(0, 1.0f)
        writeToReg(1, 2.0f)
        writeToReg(2, 3.0f)
        assertInside(true, "All positive")

        println("  Test 2: One negative (outside)")
        writeToReg(1, -1.0f)
        assertInside(false, "r1 is negative")

        println("  Test 3: Zero is inside (edge tie)")
        writeToReg(0, 0.0f)
        writeToReg(1, 0.0f)
        writeToReg(2, 0.0f)
        assertInside(true, "All zero")

        println("  Test 4: Negative-Zero is inside (magnitude is 0)")
        writeAddr(borg, 16, floatToBits(0.0f, config))
        resetAndWait(borg)
        // Tile-origin encoding: tile_y[19:10] | tile_x[9:0]
        val tileCmd = (0 << 10) | 0  // tile origin (0,0)
        writeAddr(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, tileCmd)
        borg.clock.step(5)
        writeAddr(borg, 128, encodeInstruction(config, FNEG, rs1 = 4, rs2 = 4, rd = 1)) // r1 = -0.0
        writeAddr(borg, 132, 0)
        writeAddr(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)
        waitForHalt(borg)
        assertInside(true, "r1 is -0.0 (rest 0.0)")

        println("=== Inside Flag Snoop Tests Passed ===\n")
      }
    }

    // =====================================================================
    // NEW TEST GROUP 7: Auto-Run on BORG_ITER write (Step 10.4.2)
    // Verifies that writing to BORG_ITER auto-triggers the shader at PC=0
    // and that data_ready stalls until the shader completes.
    // =====================================================================
    utest.test("auto_run_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== Auto-Run Tests ===")

        // --- Test 1: Auto-run triggers shader on BORG_ITER write ---
        // Load a simple shader at PC=0: ADD r0 = r4 + r5
        println("  Test 1: Auto-run triggers shader")
        resetAndWait(borg)
        val addInstr = encodeInstruction(config, ADD, rs1 = 4, rs2 = 5, rd = 0)
        writeAddr(borg, 128, addInstr)       // imem[0]
        writeAddr(borg, 128 + 4, 0)          // imem[1] = halt

        // Load operands
        writeAddr(borg, 16, floatToBits(3.0f, config))   // r4 = 3.0
        writeAddr(borg, 20, floatToBits(7.0f, config))   // r5 = 7.0

        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)
        
        // Set up bounding box: min=(0,0), max=(2,2)
        // Tile-origin encoding: tile origin (0,0)
        val tileCmd = (0 << 10) | 0
        writeAddr(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, tileCmd)
        borg.clock.step(5)

        // Write BORG_ITER to advance — should auto-trigger shader at PC=0
        writeAddr(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)

        // Wait for auto-run to complete by bounded polling of the status register
        // It takes ~7 cycles for the rasterizer to evaluate the coordinate FPU,
        // so we wait up to 20 cycles for it to start, then wait for it to finish.
        waitForHalt(borg, 40)

        // Read r0 — should be 3.0 + 7.0 = 10.0
        val result = readAddr(borg, 0, config)
        assertResult(result, 10.0f, config, "auto-run: r0 = r4 + r5")
        println("  Auto-run shader: PASSED")

        // --- Test 2: Iterator advanced correctly after auto-run ---
        println("  Test 2: Iterator advances with auto-run")
        borg.io.address.poke(BorgGpuRegs.iter_offset)
        borg.io.data_read_n.poke(2.U)
        borg.io.data_write_n.poke(3.U)
        borg.clock.step(1)
        borg.clock.step(1)
        val iterVal = borg.io.data_out.peek().litValue
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)
        val ix = (iterVal >> 0) & 0x3FF
        val iy = (iterVal >> 10) & 0x3FF
        println(f"    Raw iterVal: 0x$iterVal%08X")
        println(f"    Iterator position: ($ix, $iy)")
        // After tile (0,0) 4×4 and one advance, should be at (1,0)
        Predef.assert(ix == 1 && iy == 0, s"Expected (1,0), got ($ix,$iy)")
        println("  Iterator advance: PASSED")

        // --- Test 3: Inside flag updated by auto-run ---
        // Write positive values to r0/r1/r2 via auto-run: r0=r1=r2 = r4+r5 where r4=3.0, r5=0.0
        println("  Test 3: Inside flag via auto-run")
        resetAndWait(borg)
        val addInstr0 = encodeInstruction(config, ADD, rs1 = 4, rs2 = 5, rd = 0)
        val addInstr1 = encodeInstruction(config, ADD, rs1 = 4, rs2 = 5, rd = 1)
        val addInstr2 = encodeInstruction(config, ADD, rs1 = 4, rs2 = 5, rd = 2)
        writeAddr(borg, 128, addInstr0)       // r0 = r4+r5 = 3.0
        writeAddr(borg, 128 + 4, addInstr1)   // r1 = r4+r5 = 3.0
        writeAddr(borg, 128 + 8, addInstr2)   // r2 = r4+r5 = 3.0
        writeAddr(borg, 128 + 12, 0)          // halt

        writeAddr(borg, 16, floatToBits(3.0f, config))   // r4 = 3.0 (positive)
        writeAddr(borg, 20, floatToBits(0.0f, config))   // r5 = 0.0
        writeAddr(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, tileCmd)
        borg.clock.step(5)

        // Write BORG_ITER — auto-runs shader, r0/r1/r2 all become 3.0 (positive → inside)
        writeAddr(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)
        borg.clock.step(30)

        // Read inside_flag
        borg.io.address.poke(BorgGpuRegs.iter_offset)
        borg.io.data_read_n.poke(2.U)
        borg.io.data_write_n.poke(3.U)
        borg.clock.step(1)
        borg.clock.step(1)
        val iterVal2 = borg.io.data_out.peek().litValue
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)
        val isInside = ((iterVal2 >> 21) & 1) == 1
        println(f"    inside_flag: $isInside (expected true)")
        Predef.assert(isInside, "Expected inside_flag=true after positive edge results")
        println("  Inside flag via auto-run: PASSED")

        println("=== Auto-Run Tests Passed ===\n")
      }
    }

    // CoordLut functional tests are in BorgCoreTests.coordLut_injection
    // (where the BRAM write port is accessible for initialization).
    // This test only verifies the MMIO read path for r30/r31 works
    // without asserting specific values (BRAM is uninitialized in simulation).
    utest.test("coordlut_tests") {
      simulate(new Borg(FloatConfig.FP16)) { borg =>
        borg.reset.poke(true.B)
        borg.clock.step(5)
        borg.reset.poke(false.B)
        borg.clock.step(5)

        println("\n=== Test: coordLut MMIO Read Path ===")

        val tileCmd2 = (10 << 10) | 10  // tile origin (10,10)
        writeAddr(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, tileCmd2)
        borg.clock.step(5)

        writeAddr(borg, 128, 0)
        
        borg.io.address.poke(BorgGpuRegs.iter_offset)
        borg.io.data_in.poke(1.U)
        borg.io.data_write_n.poke(2.U)
        borg.clock.step(1)
        borg.io.data_write_n.poke(3.U)
        borg.clock.step(1)

        var status: BigInt = 0
        do {
            borg.io.address.poke(BorgGpuRegs.status_offset)
            borg.io.data_read_n.poke(2.U)
            borg.io.data_write_n.poke(3.U)
            borg.clock.step(1)
            status = borg.io.data_out.peek().litValue
        } while ((status & 2) == 0)
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)

        borg.io.address.poke((0 + 30 * 4).U)
        borg.io.data_read_n.poke(2.U)
        borg.clock.step(1)
        val r30_val = borg.io.data_out.peek().litValue
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)

        borg.io.address.poke((0 + 31 * 4).U)
        borg.io.data_read_n.poke(2.U)
        borg.clock.step(1)
        val r31_val = borg.io.data_out.peek().litValue
        borg.io.data_read_n.poke(3.U)
        borg.clock.step(1)

        println(f"  Read r30 (x=10):       0x$r30_val%04x (BRAM path ok)")
        println(f"  Read r31 (y=10):       0x$r31_val%04x (BRAM path ok)")
        println("=== coordLut MMIO Read Path Tests Passed ===\n")
      }
    }

    // =====================================================================
    // Step 11.2: Tile Buffer MMIO Round-Trip Test
    // Verifies write/read through the BORG_TILE_CTRL/RG/BZ MMIO interface.
    // Two-step write protocol: write BZ (shadows B/Z), then write RG (fires).
    // =====================================================================
    utest.test("tile_buffer_mmio_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== Tile Buffer MMIO Tests ===")

        // Helper: read raw 32-bit value from MMIO address
        def readRaw(addr: Int): BigInt = {
          borg.io.address.poke(addr.U)
          borg.io.data_read_n.poke(2.U)
          borg.io.data_write_n.poke(3.U)
          borg.clock.step(1)
          borg.clock.step(1)
          val v = borg.io.data_out.peek().litValue
          borg.io.data_read_n.poke(3.U)
          borg.clock.step(1)
          v
        }

        // Helper: write full pixel (R, G, B, Z) to tile buffer at index idx
        // Protocol: (1) CTRL=idx, (2) BZ={B,Z}, (3) RG={R,G} → triggers write
        def writeTilePixel(idx: Int, r: Int, g: Int, b: Int, z: Int): Unit = {
          writeAddr(borg, BorgGpuRegs.tile_ctrl_offset.litValue.toInt, idx & 0xF)
          val bzPacked = (BigInt(b & 0xFFFF) << 16) | BigInt(z & 0xFFFF)
          writeAddr(borg, BorgGpuRegs.tile_bz_offset.litValue.toInt, bzPacked)
          val rgPacked = (BigInt(r & 0xFFFF) << 16) | BigInt(g & 0xFFFF)
          writeAddr(borg, BorgGpuRegs.tile_rg_offset.litValue.toInt, rgPacked)
        }

        // Helper: read tile pixel at index idx → (R, G, B, Z)
        def readTilePixel(idx: Int): (Int, Int, Int, Int) = {
          writeAddr(borg, BorgGpuRegs.tile_ctrl_offset.litValue.toInt, idx & 0xF)
          borg.clock.step(2) // settle for BRAM read
          val rg = readRaw(BorgGpuRegs.tile_rg_offset.litValue.toInt)
          val bz = readRaw(BorgGpuRegs.tile_bz_offset.litValue.toInt)
          val r = ((rg >> 16) & 0xFFFF).toInt
          val g = (rg & 0xFFFF).toInt
          val b = ((bz >> 16) & 0xFFFF).toInt
          val z = (bz & 0xFFFF).toInt
          (r, g, b, z)
        }

        // Init
        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.reset.poke(true.B)
        borg.clock.step(2)
        borg.reset.poke(false.B)
        borg.clock.step(20)  // wait for tile buffer BRAM auto-clear (16 cycles)

        // --- Test 1: Full 4-channel pixel write and read-back ---
        println("  Test 1: Full pixel (R,G,B,Z) round-trip")
        writeTilePixel(0, r=0x3C00, g=0x4000, b=0x4200, z=0x1234)
        val (r1, g1, b1, z1) = readTilePixel(0)
        println(f"    Index 0: R=0x$r1%04X G=0x$g1%04X B=0x$b1%04X Z=0x$z1%04X")
        Predef.assert(r1 == 0x3C00, s"R mismatch: got 0x${r1.toHexString}")
        Predef.assert(g1 == 0x4000, s"G mismatch: got 0x${g1.toHexString}")
        Predef.assert(b1 == 0x4200, s"B mismatch: got 0x${b1.toHexString}")
        Predef.assert(z1 == 0x1234, s"Z mismatch: got 0x${z1.toHexString}")
        println("    PASSED")

        // --- Test 2: Z initial value at untouched index ---
        println("  Test 2: Z initial value at untouched index")
        val (_, _, _, z2) = readTilePixel(15)
        println(f"    Index 15: Z=0x$z2%04X (expect 0x7BFF)")
        utest.assert(z2 == 0x7BFF)
        println("    PASSED")

        // --- Test 3: Multi-index independence ---
        println("  Test 3: Multi-index independence")
        writeTilePixel(5, r=0xAAAA, g=0xBBBB, b=0xCCCC, z=0xDDDD)
        val (r5, g5, b5, z5) = readTilePixel(5)
        println(f"    Index 5: R=0x$r5%04X G=0x$g5%04X B=0x$b5%04X Z=0x$z5%04X")
        utest.assert(r5 == 0xAAAA && g5 == 0xBBBB && b5 == 0xCCCC && z5 == 0xDDDD)
        // Index 0 should still have its original values
        val (r0, g0, b0, z0) = readTilePixel(0)
        println(f"    Index 0: R=0x$r0%04X G=0x$g0%04X B=0x$b0%04X Z=0x$z0%04X (unchanged)")
        utest.assert(r0 == 0x3C00 && g0 == 0x4000 && b0 == 0x4200 && z0 == 0x1234)
        println("    PASSED")

        // --- Test 4: Clear resets Z to 0x7BFF and RGB to 0 ---
        println("  Test 4: Clear tile buffer")
        writeAddr(borg, BorgGpuRegs.tile_ctrl_offset.litValue.toInt, 0x10)  // bit 4 = clear
        borg.clock.step(20)  // wait for sequential RGB clear
        val (rc, gc, bc, zc) = readTilePixel(0)
        println(f"    After clear idx 0: R=0x$rc%04X G=0x$gc%04X B=0x$bc%04X Z=0x$zc%04X")
        Predef.assert(zc == 0x7BFF, s"Z not cleared: got 0x${zc.toHexString}")
        Predef.assert(rc == 0 && gc == 0 && bc == 0, s"RGB not cleared: R=$rc G=$gc B=$bc")
        val (rc5, gc5, bc5, zc5) = readTilePixel(5)
        println(f"    After clear idx 5: R=0x$rc5%04X G=0x$gc5%04X B=0x$bc5%04X Z=0x$zc5%04X")
        utest.assert(zc5 == 0x7BFF && rc5 == 0 && gc5 == 0 && bc5 == 0)
        println("    PASSED")

        // --- Test 5: Write after clear works ---
        println("  Test 5: Write after clear")
        writeTilePixel(7, r=0x1111, g=0x2222, b=0x3333, z=0x4444)
        val (rw, gw, bw, zw) = readTilePixel(7)
        println(f"    Index 7: R=0x$rw%04X G=0x$gw%04X B=0x$bw%04X Z=0x$zw%04X")
        utest.assert(rw == 0x1111 && gw == 0x2222 && bw == 0x3333 && zw == 0x4444)
        println("    PASSED")

        println("=== Tile Buffer MMIO Tests Passed ===\n")
      }
    }

    // =====================================================================
    // NEW TEST GROUP: Texture Fetch Hardware (Step 16.3)
    // Tests fp16_to_uint6, Morton encoding, and MMIO round-trip.
    // =====================================================================
    utest.test("tex_fetch_tests") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n=== Texture Fetch Tests ===")

        // Reset and settle — ensures all RegInit values are applied before
        // any combinational read of tex_addr (no longer gated by shadow reg).
        borg.reset.poke(true.B)
        borg.clock.step(2)
        borg.reset.poke(false.B)
        borg.clock.step(2)

        // Helper: read raw 32-bit value from MMIO address
        def readRaw(addr: Int): BigInt = {
          borg.io.address.poke(addr.U)
          borg.io.data_read_n.poke(2.U)
          borg.io.data_write_n.poke(3.U)
          borg.clock.step(1)
          borg.clock.step(1)
          val v = borg.io.data_out.peek().litValue
          borg.io.data_read_n.poke(3.U)
          borg.clock.step(1)
          v
        }

        /** Drive FP16 UV values through the rasterizer's pipeline snoop path.
          * The Morton encoder reads from rast.io.fragU/fragV, which are driven by
          * the rasterizer's frag_r/frag_g registers. These are snooped from pipeline
          * writes to r26 (outU) and r27 (outV) during the sFrag phase only.
          *
          * To test from the top level, we need a two-shader pipeline:
          * 1. Rast shader (PC=0): sets r0/r1/r2 = +1.0 so inside_flag = true
          * 2. Frag shader (PC=4): writes r26=u, r27=v (snooped as fragU/fragV)
          * The rasterizer chains sRast → sFrag when inside=true and fragPC != 0.
          */
        def testTexFetch(u_fp16: Int, v_fp16: Int, expU6: Int, expV6: Int, label: String): Unit = {
          resetAndWait(borg)

          // Load values into source registers
          writeAddr(borg, 16, BigInt(u_fp16 & 0xFFFF))   // r4 = u
          writeAddr(borg, 20, BigInt(v_fp16 & 0xFFFF))   // r5 = v
          writeAddr(borg, 24, BigInt(0))                   // r6 = 0.0
          writeAddr(borg, 28, BigInt(0x3C00))              // r7 = 1.0 (positive, for edges)

          // Rast shader at PC=0: set all edges inside (r0=r1=r2 = +1.0), then halt
          val setR0 = encodeInstruction(config, ADD, rs1 = 7, rs2 = 6, rd = 0)  // r0 = 1.0+0 = 1.0
          val setR1 = encodeInstruction(config, ADD, rs1 = 7, rs2 = 6, rd = 1)  // r1 = 1.0
          val setR2 = encodeInstruction(config, ADD, rs1 = 7, rs2 = 6, rd = 2)  // r2 = 1.0
          writeAddr(borg, 128 + 0*4, setR0)   // IMEM[0]
          writeAddr(borg, 128 + 1*4, setR1)   // IMEM[1]
          writeAddr(borg, 128 + 2*4, setR2)   // IMEM[2]
          writeAddr(borg, 128 + 3*4, 0)       // IMEM[3] = halt (rast shader ends)

          // Frag shader at PC=4: write r26=u, r27=v, then halt
          val addU = encodeInstruction(config, ADD, rs1 = 4, rs2 = 6, rd = 26) // r26 = u
          val addV = encodeInstruction(config, ADD, rs1 = 5, rs2 = 6, rd = 27) // r27 = v
          writeAddr(borg, 128 + 4*4, addU)    // IMEM[4]
          writeAddr(borg, 128 + 5*4, addV)    // IMEM[5]
          writeAddr(borg, 128 + 6*4, 0)       // IMEM[6] = halt (frag shader ends)

          // Enqueue tile command + write frag_pc to dedicated register
          val fragPC = 4
          writeAddr(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, fragPC)
          val tileCmd = (0 << 10) | 0  // tile origin (0,0)
          writeAddr(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, tileCmd)
          borg.clock.step(5)

          // Trigger auto-run via BORG_ITER write
          // Pipeline: sRast(PC=0) → sFrag(PC=4, snoops r26/r27) → sTileWrite → sIdle
          writeAddr(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)
          // Wait for rast shader to halt, then allow time for rasterizer FSM
          // to chain into sFrag and re-trigger the core before polling again.
          waitForHalt(borg, 60)
          borg.clock.step(3)  // rasterizer FSM: detect halt → check inside → trigger frag
          waitForHalt(borg, 60)  // wait for frag shader to halt
          borg.clock.step(5)  // settle through sTileWrite → sIdle

          // Read tex_addr: {raw_v[31:24], raw_u[23:16], morton[15:0]}
          val texAddrVal = readRaw(BorgGpuRegs.tex_addr_offset.litValue.toInt)
          val mortonVal = (texAddrVal & 0xFFFF).toInt
          val gotU8 = ((texAddrVal >> 16) & 0xFF).toInt
          val gotV8 = ((texAddrVal >> 24) & 0xFF).toInt

          // Compute expected Morton (8-bit interleave)
          var expMorton = 0
          for (bit <- 0 until 8) {
            expMorton |= ((expU6 >> bit) & 1) << (bit * 2)
            expMorton |= ((expV6 >> bit) & 1) << (bit * 2 + 1)
          }

          println(f"  $label: u8=$gotU8 (exp $expU6), v8=$gotV8 (exp $expV6), morton=0x$mortonVal%04X (exp 0x$expMorton%04X)")
          utest.assert(gotU8 == expU6)
          utest.assert(gotV8 == expV6)
          utest.assert(mortonVal == expMorton)
        }

        // FP16 constants (sign=0, exp=e, mant=m)
        // 0.0  = 0x0000
        // 1.0  = 0x3C00 (exp=15, mant=0)
        // 2.0  = 0x4000 (exp=16, mant=0)
        // 4.0  = 0x4400 (exp=17, mant=0)
        // 10.0 = 0x4900 (exp=18, mant=0x100)
        // 32.0 = 0x5000 (exp=19, mant=0)
        // 63.0 = 0x53E0 (exp=20, mant=0x3E0)
        // 100.0= 0x5640 (exp=21, mant=0x240) → clamps to 63

        testTexFetch(0x0000, 0x0000, 0, 0,   "zero, zero")
        testTexFetch(0x3C00, 0x3C00, 1, 1,   "1.0, 1.0")
        testTexFetch(0x4000, 0x4000, 2, 2,   "2.0, 2.0")
        testTexFetch(0x4400, 0x4400, 4, 4,   "4.0, 4.0")
        testTexFetch(0x4900, 0x4900, 10, 10, "10.0, 10.0")
        testTexFetch(0x5000, 0x5000, 32, 32, "32.0, 32.0")
        testTexFetch(0x53E0, 0x53E0, 63, 63, "63.0, 63.0")
        testTexFetch(0x5640, 0x5640, 100, 100, "100.0, 100.0")
        testTexFetch(0xC000, 0x0000, 0,  0,  "neg(-2.0), zero")
        testTexFetch(0x4000, 0x53E0, 2,  63, "2.0, 63.0")

        println("=== Texture Fetch Tests Passed ===\n")
      }
    }
  }
}
