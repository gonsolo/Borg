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
        writeReg(core, 0, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))

        // IMEM[0] = fadd r2, r0, r1;  IMEM[1] = halt
        val instr = Instructions.ADD(0, 1, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
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
      simulate(new BorgCore(BorgConfig.Default)) { core => // Default is FP32
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
      simulate(new BorgCore(BorgConfig.Default)) { core => // Default is FP32
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

    utest.test("isub_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: isub_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 100)
        writeReg(core, 1, 30)
        writeImem(core, 0, Instructions.ISUB(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(s"  isub(100, 30) = $r (expected 70)")
        utest.assert(r == 70)
        println("  PASSED")
      }
    }

    utest.test("isub_int16_underflow_wraps") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: isub_int16_underflow_wraps ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 5)
        writeReg(core, 1, 10)
        writeImem(core, 0, Instructions.ISUB(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2) & intMask
        println(s"  isub(5, 10) = 0x${r.toString(16)} (expect 0x${intBits(-5).toString(16)})")
        utest.assert(r == intBits(-5))
        println("  PASSED")
      }
    }

    utest.test("iand_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: iand_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 0xF0)
        writeReg(core, 1, 0x3C)
        writeImem(core, 0, Instructions.IAND(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(f"  iand(0xF0, 0x3C) = 0x$r%X (expected 0x30)")
        utest.assert(r == 0x30)
        println("  PASSED")
      }
    }

    utest.test("ior_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: ior_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 0xF0)
        writeReg(core, 1, 0x0F)
        writeImem(core, 0, Instructions.IOR(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(f"  ior(0xF0, 0x0F) = 0x$r%X (expected 0xFF)")
        utest.assert(r == 0xFF)
        println("  PASSED")
      }
    }

    utest.test("ixor_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: ixor_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 0xFF)
        writeReg(core, 1, 0x0F)
        writeImem(core, 0, Instructions.IXOR(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)
        val r = readReg(core, 2).toInt
        println(f"  ixor(0xFF, 0x0F) = 0x$r%X (expected 0xF0)")
        utest.assert(r == 0xF0)
        println("  PASSED")
      }
    }

    utest.test("islt_int16_signed") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: islt_int16_signed ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, intBits(-1))
        writeReg(core, 1, 1)
        writeReg(core, 2, 5)
        writeReg(core, 3, 5)
        // r4 = (r0 <s r1); r5 = (r2 <s r3)
        writeImem(core, 0, Instructions.ISLT(0, 1, 4))
        writeImem(core, 1, Instructions.ISLT(2, 3, 5))
        writeImem(core, 2, 0)
        startAndWait(core)
        val ltTrue  = readReg(core, 4).toInt
        val ltFalse = readReg(core, 5).toInt
        println(s"  islt(-1, 1) = $ltTrue (expected 1), islt(5, 5) = $ltFalse (expected 0)")
        utest.assert(ltTrue == 1)
        utest.assert(ltFalse == 0)
        println("  PASSED")
      }
    }

    utest.test("iseq_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: iseq_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 42)
        writeReg(core, 1, 42)
        writeReg(core, 2, 43)
        // r3 = (r0 == r1); r4 = (r0 == r2)
        writeImem(core, 0, Instructions.ISEQ(0, 1, 3))
        writeImem(core, 1, Instructions.ISEQ(0, 2, 4))
        writeImem(core, 2, 0)
        startAndWait(core)
        val eqTrue  = readReg(core, 3).toInt
        val eqFalse = readReg(core, 4).toInt
        println(s"  iseq(42, 42) = $eqTrue (expected 1), iseq(42, 43) = $eqFalse (expected 0)")
        utest.assert(eqTrue == 1)
        utest.assert(eqFalse == 0)
        println("  PASSED")
      }
    }

    utest.test("i2f_int16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: i2f_int16 ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 35)              // int 35
        writeReg(core, 1, intBits(-5))     // int -5, two's complement at the datapath width
        writeImem(core, 0, Instructions.I2F(0, 2))
        writeImem(core, 1, Instructions.I2F(1, 3))
        writeImem(core, 2, 0)
        startAndWait(core)
        val pos = bitsToFloat(readReg(core, 2))
        val neg = bitsToFloat(readReg(core, 3))
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
        writeReg(core, 0, floatToBits(35.0f))
        writeReg(core, 1, floatToBits(-5.0f))
        writeImem(core, 0, Instructions.F2I(0, 2))
        writeImem(core, 1, Instructions.F2I(1, 3))
        writeImem(core, 2, 0)
        startAndWait(core)
        val pos = readReg(core, 2).toInt
        val neg = readReg(core, 3) & intMask
        println(s"  f2i(35.0) = $pos, f2i(-5.0) = 0x${neg.toString(16)} (expect 0x${intBits(-5).toString(16)})")
        utest.assert(pos == 35)
        utest.assert(neg == intBits(-5))
        println("  PASSED")
      }
    }

    utest.test("fmul_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: fmul_fp16 ---")
        idleInputs(core)
        resetCore(core)

        writeReg(core, 0, floatToBits(3.0f))
        writeReg(core, 1, floatToBits(4.0f))

        val instr = Instructions.MUL(0, 1, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
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
        writeReg(core, 0, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))
        writeReg(core, 3, floatToBits(1.0f))

        val instr = Instructions.FMA(0, 1, 3, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
        println(f"  fmadd(2.0, 3.0, 1.0) = $result%.2f (expected 7.0)")
        utest.assert(math.abs(result - 7.0f) < 0.1f)
        println("  PASSED")
      }
    }

  }
}
