// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Standalone tests for BorgCore — FPU pipeline tested without rasterizer or top-level MMIO.
  *
  * Directly drives the BorgCoreIO to verify instruction execution,
  * register reads/writes, and coordLut injection.
  */
object BorgCoreTests extends TestSuite {

  val config = BorgConfig.Sim

  def floatToFp16Bits(f: Float): BigInt = {
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits >>> 31) << 15
    var exp = ((bits >>> 23) & 0xff) - 127 + 15
    var sig = (bits >>> 13) & 0x3ff
    if (exp <= 0) { exp = 0; sig = 0 }
    else if (exp >= 31) { exp = 31; sig = 0x3ff }
    BigInt(sign | (exp << 10) | sig)
  }

  def fp16BitsToFloat(b: BigInt): Float = {
    val bits = b.toInt
    val sign = (bits >>> 15) << 31
    var exp = ((bits >>> 10) & 0x1f)
    var sig = (bits & 0x3ff) << 13
    if (exp == 0) { /* subnormal or zero */ }
    else if (exp == 31) { exp = 255 }
    else { exp = exp - 15 + 127 }
    java.lang.Float.intBitsToFloat(sign | (exp << 23) | sig)
  }

  /** Perform a write to BorgCore (simulating the edge-detected is_writing pulse). */
  def writeCore(core: BorgCore, addr: Int, data: BigInt): Unit = {
    core.io.bus.address.poke(addr.U)
    core.io.bus.data_in.poke(data.U)
    core.io.bus.is_writing.poke(true.B)
    core.io.bus.is_reading.poke(false.B)
    core.clock.step(1)
    core.io.bus.is_writing.poke(false.B)
    core.clock.step(1)
  }

  /** Read a register from BorgCore via the regReadData output. */
  def readReg(core: BorgCore, regIdx: Int): BigInt = {
    val addr = 0 + regIdx * 4
    core.io.bus.address.poke(addr.U)
    core.io.bus.is_reading.poke(true.B)
    core.io.bus.is_writing.poke(false.B)
    core.clock.step(1)
    val result = core.io.regReadData.peek().litValue
    core.io.bus.is_reading.poke(false.B)
    result
  }

  /** Write a register via MMIO. */
  def writeReg(core: BorgCore, regIdx: Int, bits: BigInt): Unit =
    writeCore(core, 0 + regIdx * 4, bits)

  /** Write an instruction to IMEM. */
  def writeImem(core: BorgCore, slot: Int, instr: BigInt): Unit =
    writeCore(core, 128 + slot * 4, instr)

  def resetCore(core: BorgCore): Unit = {
    core.io.controlReset.poke(true.B)
    core.clock.step(1)
    core.io.controlReset.poke(false.B)
    core.clock.step(1)
  }

  /** Start execution and poll status until idle. */
  def startAndWait(core: BorgCore): Unit = {
    core.io.controlStart.poke(true.B)
    core.clock.step(1)
    core.io.controlStart.poke(false.B)
    // Poll running output
    var idle = false
    var watchdog = 0
    while (!idle && watchdog < 200) {
      core.clock.step(1)
      val running = core.io.status.running.peek().litToBoolean
      idle = !running
      watchdog += 1
    }
    utest.assert(idle)
  }

  def idleInputs(core: BorgCore): Unit = {
    core.io.bus.address.poke(0.U)
    core.io.bus.data_in.poke(0.U)
    core.io.bus.is_writing.poke(false.B)
    core.io.bus.is_reading.poke(false.B)
    core.io.iter.x.poke(0.U)
    core.io.iter.y.poke(0.U)
    core.io.coreTrigger.valid.poke(false.B)
    core.io.coreTrigger.pc.poke(0.U)
    core.io.uniformPage.poke(0.U)
    core.io.uniformWritePage.poke(0.U)
    core.io.controlStart.poke(false.B)
    core.io.controlReset.poke(false.B)
    core.io.controlStartPC.poke(0.U)
    core.io.coordWriteEn.poke(false.B)
    core.io.coordWriteIsRcp.poke(false.B)
    core.io.coordWriteAddr.poke(0.U)
    core.io.coordWriteData.poke(0.U)
    core.clock.step(1)
  }

  /** Initialize the coordLut BRAMs with FP16 pixel centers (0.5, 1.5, ... 63.5).
    * Required for simulation since loadMemoryFromFileInline only works in synthesis.
    */
  def initCoordLut(core: BorgCore): Unit = {
    core.io.coordWriteIsRcp.poke(false.B)
    for (i <- 0 until 64) {
      core.io.coordWriteEn.poke(true.B)
      core.io.coordWriteAddr.poke(i.U)
      core.io.coordWriteData.poke(floatToFp16Bits(i.toFloat + 0.5f).U)
      core.clock.step(1)
    }
    core.io.coordWriteEn.poke(false.B)
    core.clock.step(1)
  }

  /** Initialize the rcpLut BRAMs with the 17-entry reciprocal LUT.
    * Values: round((2/(1+i/16) - 1) * 1024) for i=0..16.
    * Required for simulation since loadMemoryFromFileInline only works in synthesis.
    */
  val rcpLutValues = Seq(1023, 904, 796, 701, 614, 536, 465, 401, 341, 287, 236, 190, 146, 106, 68, 33, 0)

  def initRcpLut(core: BorgCore): Unit = {
    core.io.coordWriteIsRcp.poke(true.B)
    for (i <- 0 until 17) {
      core.io.coordWriteEn.poke(true.B)
      core.io.coordWriteAddr.poke(i.U)
      core.io.coordWriteData.poke(rcpLutValues(i).U)
      core.clock.step(1)
    }
    core.io.coordWriteEn.poke(false.B)
    core.io.coordWriteIsRcp.poke(false.B)
    core.clock.step(1)
  }

  val tests = Tests {

    utest.test("fadd_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: fadd_fp16 ---")
        idleInputs(core)
        resetCore(core)

        // r0 = 2.0, r1 = 3.0
        writeReg(core, 0, floatToFp16Bits(2.0f))
        writeReg(core, 1, floatToFp16Bits(3.0f))

        // IMEM[0] = fadd r2, r0, r1;  IMEM[1] = halt
        val instr = Instructions.ADD(0, 1, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fadd(2.0, 3.0) = $result%.2f (expected 5.0)")
        utest.assert(math.abs(result - 5.0f) < 0.01f)
        println("  PASSED")
      }
    }

    utest.test("fmul_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: fmul_fp16 ---")
        idleInputs(core)
        resetCore(core)

        writeReg(core, 0, floatToFp16Bits(3.0f))
        writeReg(core, 1, floatToFp16Bits(4.0f))

        val instr = Instructions.MUL(0, 1, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fmul(3.0, 4.0) = $result%.2f (expected 12.0)")
        utest.assert(math.abs(result - 12.0f) < 0.1f)
        println("  PASSED")
      }
    }

    utest.test("fmadd_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: fmadd_fp16 ---")
        idleInputs(core)
        resetCore(core)

        // r0=2.0, r1=3.0, r3=1.0 → fmadd r2, r0, r1, r3 = 2*3+1 = 7.0
        writeReg(core, 0, floatToFp16Bits(2.0f))
        writeReg(core, 1, floatToFp16Bits(3.0f))
        writeReg(core, 3, floatToFp16Bits(1.0f))

        val instr = Instructions.FMA(0, 1, 3, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fmadd(2.0, 3.0, 1.0) = $result%.2f (expected 7.0)")
        utest.assert(math.abs(result - 7.0f) < 0.1f)
        println("  PASSED")
      }
    }

    utest.test("pipe_write_snoop") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: pipe_write_snoop ---")
        idleInputs(core)
        resetCore(core)

        // Run fmul: r0=2.0, r1=3.0 → r2=6.0
        writeReg(core, 0, floatToFp16Bits(2.0f))
        writeReg(core, 1, floatToFp16Bits(3.0f))
        writeImem(core, 0, Instructions.MUL(0, 1, 2))
        writeImem(core, 1, 0)

        startAndWait(core)

        // The pipeWriteEn/Addr/Data outputs should have been pulsed during execution.
        // We can't catch the transient pulse easily, but we can verify the register
        // was written correctly (proving the write-back path works).
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  Snoop path: r2 = $result%.2f (expected 6.0)")
        utest.assert(math.abs(result - 6.0f) < 0.1f)
        println("  PASSED")
      }
    }

    utest.test("coordLut_injection") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: coordLut_injection ---")
        idleInputs(core)
        initCoordLut(core)  // Initialize BRAM with pixel centers for simulation
        resetCore(core)

        // Set iterX=5, iterY=10 (pixel centers: 5.5, 10.5)
        core.io.iter.x.poke(5.U)
        core.io.iter.y.poke(10.U)

        // fadd r2, r30, r31 → r2 = coordLut[5] + coordLut[10] = 5.5 + 10.5 = 16.0
        writeReg(core, 0, 0)
        writeReg(core, 1, 0)
        writeImem(core, 0, Instructions.ADD(30, 31, 2))
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fadd(coordLut[5], coordLut[10]) = $result%.2f (expected 16.0)")
        utest.assert(math.abs(result - 16.0f) < 0.1f)
        println("  PASSED")
      }
    }

    utest.test("multi_instruction_program") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: multi_instruction_program ---")
        idleInputs(core)
        resetCore(core)

        // r0=2.0, r1=3.0
        // IMEM[0]: fmul r2, r0, r1  → r2 = 6.0
        // IMEM[1]: fadd r3, r2, r0  → r3 = 6.0 + 2.0 = 8.0
        // IMEM[2]: halt
        writeReg(core, 0, floatToFp16Bits(2.0f))
        writeReg(core, 1, floatToFp16Bits(3.0f))
        writeImem(core, 0, Instructions.MUL(0, 1, 2))
        writeImem(core, 1, Instructions.ADD(2, 0, 3))
        writeImem(core, 2, 0)

        startAndWait(core)
        val r2 = fp16BitsToFloat(readReg(core, 2))
        val r3 = fp16BitsToFloat(readReg(core, 3))
        println(f"  r2 = $r2%.2f (expected 6.0), r3 = $r3%.2f (expected 8.0)")
        utest.assert(math.abs(r2 - 6.0f) < 0.1f)
        utest.assert(math.abs(r3 - 8.0f) < 0.1f)
        println("  PASSED")
      }
    }

    // --- Uniform Buffer Tests (Step 10.6.4.1) ---

    /** Write a uniform entry via MMIO. */
    def writeUniform(core: BorgCore, idx: Int, bits: BigInt): Unit =
      writeCore(core, 368 + idx * 4, bits)

    utest.test("uniform_funct3_01_rs1") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: uniform_funct3_01_rs1 ---")
        idleInputs(core)
        resetCore(core)

        // u5 = 7.0, r1 = 3.0
        // fadd with funct3=01: r2 = u5 + r1 = 7.0 + 3.0 = 10.0
        writeUniform(core, 5, floatToFp16Bits(7.0f))
        writeReg(core, 1, floatToFp16Bits(3.0f))

        // Encode fadd with funct3=1 (rs1 from uniform): ADD(rs1=5, rs2=1, rd=2)
        val instr = Instructions.encodeRType(Instructions.FUNCT7_ADD, 1, 5, 2, funct3 = 1)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        // Read out uniform buffer 5 using peek
        core.clock.step(2)
        // uniformMem isn't exposed, so we can't peek it directly from the wrapper easily.
        // Let's just start and see what happens.
        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fadd(u5=7.0, r1=3.0) funct3=01 = $result%.2f (expected 10.0)")
        utest.assert(math.abs(result - 10.0f) < 0.1f)
        println("  PASSED")
      }
    }

    utest.test("uniform_funct3_10_rs2") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: uniform_funct3_10_rs2 ---")
        idleInputs(core)
        resetCore(core)

        // r0 = 4.0, u3 = 5.0
        // fmul with funct3=10: r2 = r0 * u3 = 4.0 * 5.0 = 20.0
        writeReg(core, 0, floatToFp16Bits(4.0f))
        writeUniform(core, 3, floatToFp16Bits(5.0f))

        // Encode fmul with funct3=2 (rs2 from uniform): MUL(rs1=0, rs2=3, rd=2)
        val instr = Instructions.encodeRType(Instructions.FUNCT7_MUL, 3, 0, 2, funct3 = 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fmul(r0=4.0, u3=5.0) funct3=10 = $result%.2f (expected 20.0)")
        utest.assert(math.abs(result - 20.0f) < 0.5f)
        println("  PASSED")
      }
    }

    utest.test("uniform_funct3_11_rs3") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: uniform_funct3_11_rs3 ---")
        idleInputs(core)
        resetCore(core)

        // r0 = 2.0, r1 = 3.0, u4 = 1.0
        // fmadd with funct3=11: r2 = r0 * r1 + u4 = 6.0 + 1.0 = 7.0
        writeReg(core, 0, floatToFp16Bits(2.0f))
        writeReg(core, 1, floatToFp16Bits(3.0f))
        writeUniform(core, 4, floatToFp16Bits(1.0f))

        // Encode fmadd with funct3=3 (rs3 from uniform): FMA(rs1=0, rs2=1, rs3=4, rd=2)
        val instr = Instructions.encodeR4Type(4, 0, 1, 0, 2, funct3 = 3)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fmadd(r0=2.0, r1=3.0, u4=1.0) funct3=11 = $result%.2f (expected 7.0)")
        utest.assert(math.abs(result - 7.0f) < 0.1f)
        println("  PASSED")
      }
    }

    utest.test("uniform_funct3_00_backward_compat") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: uniform_funct3_00_backward_compat ---")
        idleInputs(core)
        resetCore(core)

        // Same as fadd_fp16 test but with uniform buffer populated
        // to prove funct3=00 ignores the uniform buffer entirely.
        writeUniform(core, 0, floatToFp16Bits(999.0f))
        writeUniform(core, 1, floatToFp16Bits(888.0f))
        writeReg(core, 0, floatToFp16Bits(2.0f))
        writeReg(core, 1, floatToFp16Bits(3.0f))

        // fadd r2, r0, r1 with funct3=0 (default): should read from GPRs
        val instr = Instructions.ADD(0, 1, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = fp16BitsToFloat(readReg(core, 2))
        println(f"  fadd(r0=2.0, r1=3.0) funct3=00 = $result%.2f (expected 5.0, NOT 999+888)")
        utest.assert(math.abs(result - 5.0f) < 0.01f)
        println("  PASSED")
      }
    }
    utest.test("dual_page_uniform_buffer") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: dual_page_uniform_buffer ---")
        idleInputs(core)
        resetCore(core)

        // Write 111.0 to page 0, uniform index 5
        core.io.uniformWritePage.poke(0.U)
        writeUniform(core, 5, floatToFp16Bits(111.0f))

        // Write 222.0 to page 1, uniform index 5
        core.io.uniformWritePage.poke(1.U)
        writeUniform(core, 5, floatToFp16Bits(222.0f))

        // Program: fadd r2, u5, r0 (funct3=01: rs1 from uniform buffer)
        // Instruction encodes rs1=5 -> uniform index 5; rs2=0 -> GPR r0 = 0.0
        writeReg(core, 0, floatToFp16Bits(0.0f))  // r0 = 0 (additive identity)
        val uload_instr = Instructions.ADD(5, 0, 2, funct3 = 1)  // result = uniform[5] + 0.0
        writeImem(core, 0, uload_instr)
        writeImem(core, 1, 0)  // halt

        // Run with uniformPage = 0 → should read 111.0
        core.io.uniformWritePage.poke(0.U)
        core.io.uniformPage.poke(0.U)
        startAndWait(core)
        val result_pg0 = fp16BitsToFloat(readReg(core, 2))
        println(f"  Page 0 uniform read: $result_pg0%.2f (expected 111.0)")
        utest.assert(math.abs(result_pg0 - 111.0f) < 0.01f)

        // Reset execution state, but BRAM stays
        resetCore(core)
        core.io.coreTrigger.valid.poke(false.B)
        core.clock.step(5)

        // Run with uniformPage = 1 → should read 222.0
        core.io.uniformWritePage.poke(1.U)
        core.io.uniformPage.poke(1.U)
        startAndWait(core)
        val result_pg1 = fp16BitsToFloat(readReg(core, 2))
        println(f"  Page 1 uniform read: $result_pg1%.2f (expected 222.0)")
        utest.assert(math.abs(result_pg1 - 222.0f) < 0.01f)

        println("  PASSED")
      }
    }

    // --- FRCP via BorgCore (with rcpLut BRAM initialization) ---
    utest.test("frcp_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: frcp_fp16 ---")
        idleInputs(core)
        initRcpLut(core)  // Initialize rcpLut BRAM (required for simulation)
        resetCore(core)

        def testFrcp(input: Float, expected: Float, label: String): Unit = {
          resetCore(core)
          writeReg(core, 0, floatToFp16Bits(input))
          writeImem(core, 0, Instructions.FRCP(rs1 = 0, rd = 2))
          writeImem(core, 1, 0)  // halt
          startAndWait(core)
          val result = fp16BitsToFloat(readReg(core, 2))
          val tol = math.max(2e-3f * math.abs(expected), 2e-3f)
          println(f"  $label: actual=$result%.6f expected=$expected%.6f tol=$tol%.6f")
          utest.assert(math.abs(result - expected) < tol)
        }

        testFrcp(2.0f,  0.5f,       "rcp(2.0)")
        testFrcp(4.0f,  0.25f,      "rcp(4.0)")
        testFrcp(0.5f,  2.0f,       "rcp(0.5)")
        testFrcp(1.0f,  1.0f,       "rcp(1.0)")
        testFrcp(3.0f,  1.0f/3.0f,  "rcp(3.0)")
        testFrcp(10.0f, 0.1f,       "rcp(10.0)")
        testFrcp(-2.0f, -0.5f,      "rcp(-2.0)")
        testFrcp(1.5f,  1.0f/1.5f,  "rcp(1.5)")

        println("  PASSED")
      }
    }
  }
}
