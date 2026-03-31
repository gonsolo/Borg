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

  val config = FloatConfig.FP16

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
    core.io.address.poke(addr.U)
    core.io.data_in.poke(data.U)
    core.io.is_writing.poke(true.B)
    core.io.is_reading.poke(false.B)
    core.clock.step(1)
    core.io.is_writing.poke(false.B)
    core.clock.step(1)
  }

  /** Read a register from BorgCore via the regReadData output. */
  def readReg(core: BorgCore, regIdx: Int): BigInt = {
    val addr = MmioMap.BORG_REG_OFFSET + regIdx * 4
    core.io.address.poke(addr.U)
    core.io.is_reading.poke(true.B)
    core.io.is_writing.poke(false.B)
    core.clock.step(1)
    val result = core.io.regReadData.peek().litValue
    core.io.is_reading.poke(false.B)
    result
  }

  /** Write a register via MMIO. */
  def writeReg(core: BorgCore, regIdx: Int, bits: BigInt): Unit =
    writeCore(core, MmioMap.BORG_REG_OFFSET + regIdx * 4, bits)

  /** Write an instruction to IMEM. */
  def writeImem(core: BorgCore, slot: Int, instr: BigInt): Unit =
    writeCore(core, MmioMap.BORG_IMEM_OFFSET + slot * 4, instr)

  /** Reset the core (bit 1 of control register). */
  def resetCore(core: BorgCore): Unit =
    writeCore(core, MmioMap.BORG_CONTROL_OFFSET, 2)

  /** Start execution and poll status until idle. */
  def startAndWait(core: BorgCore): Unit = {
    writeCore(core, MmioMap.BORG_CONTROL_OFFSET, 1)
    // Poll status: bit 1 = idle
    var idle = false
    var watchdog = 0
    while (!idle && watchdog < 200) {
      core.io.address.poke(MmioMap.BORG_CONTROL_OFFSET.U)
      core.io.is_reading.poke(true.B)
      core.io.is_writing.poke(false.B)
      core.clock.step(1)
      val status = core.io.statusReg.peek().litValue
      idle = (status & 2) != 0
      watchdog += 1
    }
    core.io.is_reading.poke(false.B)
    utest.assert(idle)
  }

  /** Set default idle state on all inputs. */
  def idleInputs(core: BorgCore): Unit = {
    core.io.address.poke(0.U)
    core.io.data_in.poke(0.U)
    core.io.is_writing.poke(false.B)
    core.io.is_reading.poke(false.B)
    core.io.iterX.poke(0.U)
    core.io.iterY.poke(0.U)
    core.io.triggerShader.poke(false.B)
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
        resetCore(core)

        // Set iterX=5, iterY=10 (pixel centers: 5.5, 10.5)
        core.io.iterX.poke(5.U)
        core.io.iterY.poke(10.U)

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
  }
}
