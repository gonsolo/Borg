// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgCoreTestsA extends TestSuite {
  import BorgCoreTestHelpers._

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

    utest.test("iadd_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: iadd_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 100)
        writeReg(core, 1, 200)
        writeImem(core, 0, Instructions.IADD(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(s"  iadd(100, 200) = $r (expected 300)")
        utest.assert(r == 300)
        println("  PASSED")
      }
    }

    utest.test("ishl_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: ishl_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 5)
        writeReg(core, 1, 3)
        writeImem(core, 0, Instructions.ISHL(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(s"  ishl(5, 3) = $r (expected 40)")
        utest.assert(r == 40)
        println("  PASSED")
      }
    }

    utest.test("ishr_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: ishr_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 40)
        writeReg(core, 1, 3)
        writeImem(core, 0, Instructions.ISHR(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(s"  ishr(40, 3) = $r (expected 5)")
        utest.assert(r == 5)
        println("  PASSED")
      }
    }

    utest.test("ishl_int32_full_shift_range") {
      simulate(new BorgCore(BorgConfig.Fp32)) { core =>
        println("\n--- BorgCore: ishl_int32_full_shift_range ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 1)
        writeReg(core, 1, 20)
        writeImem(core, 0, Instructions.ISHL(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2)
        println(s"  ishl(1, 20) = $r (expected 1048576)")
        utest.assert(r == BigInt(1048576))
        println("  PASSED")
      }
    }

    utest.test("ishr_int32_full_shift_range") {
      simulate(new BorgCore(BorgConfig.Fp32)) { core =>
        println("\n--- BorgCore: ishr_int32_full_shift_range ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, BigInt(1) << 30)
        writeReg(core, 1, 20)
        writeImem(core, 0, Instructions.ISHR(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2)
        println(s"  ishr(1<<30, 20) = $r (expected 1024)")
        utest.assert(r == BigInt(1024))
        println("  PASSED")
      }
    }

    utest.test("imul_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: imul_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 6)
        writeReg(core, 1, 7)
        writeImem(core, 0, Instructions.IMUL(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(s"  imul(6, 7) = $r (expected 42)")
        utest.assert(r == 42)
        println("  PASSED")
      }
    }

    utest.test("i2f_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: i2f_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 35)              // int 35
        writeReg(core, 1, 0xFFFB)          // int -5 (two's complement)
        writeImem(core, 0, Instructions.I2F(0, 2))
        writeImem(core, 1, Instructions.I2F(1, 3))
        writeImem(core, 2, 0)
        startAndWait(core)
        val pos = fp16BitsToFloat(readReg(core, 2))
        val neg = fp16BitsToFloat(readReg(core, 3))
        println(f"  i2f(35) = $pos%.2f, i2f(-5) = $neg%.2f")
        utest.assert(math.abs(pos - 35.0f) < 0.01f)
        utest.assert(math.abs(neg + 5.0f) < 0.01f)
        println("  PASSED")
      }
    }

    utest.test("f2i_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: f2i_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, floatToFp16Bits(35.0f))
        writeReg(core, 1, floatToFp16Bits(-5.0f))
        writeImem(core, 0, Instructions.F2I(0, 2))
        writeImem(core, 1, Instructions.F2I(1, 3))
        writeImem(core, 2, 0)
        startAndWait(core)
        val pos = readReg(core, 2).toInt
        val neg = readReg(core, 3).toInt & 0xFFFF
        println(s"  f2i(35.0) = $pos, f2i(-5.0) = 0x${neg.toHexString} (expect 0xfffb)")
        utest.assert(pos == 35)
        utest.assert(neg == 0xFFFB)
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

  }
}
