// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgCoreTestsB extends TestSuite {
  import BorgCoreTestHelpers._

  val tests = Tests {

    utest.test("pipe_write_snoop") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: pipe_write_snoop ---")
        idleInputs(core)
        resetCore(core)

        // Run fmul: r0=2.0, r1=3.0 → r2=6.0
        writeReg(core, 0, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))
        writeImem(core, 0, Instructions.MUL(0, 1, 2))
        writeImem(core, 1, 0)

        startAndWait(core)

        // The pipeWriteEn/Addr/Data outputs should have been pulsed during execution.
        // We can't catch the transient pulse easily, but we can verify the register
        // was written correctly (proving the write-back path works).
        val result = bitsToFloat(readReg(core, 2))
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
        core.io.iter(0).x.poke(5.U)
        core.io.iter(0).y.poke(10.U)

        // fadd r2, r30, r31 → r2 = coordLut[5] + coordLut[10] = 5.5 + 10.5 = 16.0
        writeReg(core, 0, 0)
        writeReg(core, 1, 0)
        writeImem(core, 0, Instructions.ADD(30, 31, 2))
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
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
        writeReg(core, 0, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))
        writeImem(core, 0, Instructions.MUL(0, 1, 2))
        writeImem(core, 1, Instructions.ADD(2, 0, 3))
        writeImem(core, 2, 0)

        startAndWait(core)
        val r2 = bitsToFloat(readReg(core, 2))
        val r3 = bitsToFloat(readReg(core, 3))
        println(f"  r2 = $r2%.2f (expected 6.0), r3 = $r3%.2f (expected 8.0)")
        utest.assert(math.abs(r2 - 6.0f) < 0.1f)
        utest.assert(math.abs(r3 - 8.0f) < 0.1f)
        println("  PASSED")
      }
    }

    utest.test("uniform_funct3_01_rs1") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: uniform_funct3_01_rs1 ---")
        idleInputs(core)
        resetCore(core)

        // u5 = 7.0, r1 = 3.0
        // fadd with funct3=01: r2 = u5 + r1 = 7.0 + 3.0 = 10.0
        writeUniform(core, 5, floatToBits(7.0f))
        writeReg(core, 1, floatToBits(3.0f))

        // Encode fadd with funct3=1 (rs1 from uniform): ADD(rs1=5, rs2=1, rd=2)
        val instr = Instructions.encodeRType(Instructions.FUNCT7_ADD, 1, 5, 2, funct3 = 1)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        // Read out uniform buffer 5 using peek
        core.clock.step(2)
        // uniformMem isn't exposed, so we can't peek it directly from the wrapper easily.
        // Let's just start and see what happens.
        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
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
        writeReg(core, 0, floatToBits(4.0f))
        writeUniform(core, 3, floatToBits(5.0f))

        // Encode fmul with funct3=2 (rs2 from uniform): MUL(rs1=0, rs2=3, rd=2)
        val instr = Instructions.encodeRType(Instructions.FUNCT7_MUL, 3, 0, 2, funct3 = 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
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
        writeReg(core, 0, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))
        writeUniform(core, 4, floatToBits(1.0f))

        // Encode fmadd with funct3=3 (rs3 from uniform): FMA(rs1=0, rs2=1, rs3=4, rd=2)
        val instr = Instructions.encodeR4Type(4, 0, 1, 0, 2, funct3 = 3)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
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
        writeUniform(core, 0, floatToBits(999.0f))
        writeUniform(core, 1, floatToBits(888.0f))
        writeReg(core, 0, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))

        // fadd r2, r0, r1 with funct3=0 (default): should read from GPRs
        val instr = Instructions.ADD(0, 1, 2)
        writeImem(core, 0, instr)
        writeImem(core, 1, 0)

        startAndWait(core)
        val result = bitsToFloat(readReg(core, 2))
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
        core.io.control.uniformWritePage.poke(0.U)
        writeUniform(core, 5, floatToBits(111.0f))

        // Write 222.0 to page 1, uniform index 5
        core.io.control.uniformWritePage.poke(1.U)
        writeUniform(core, 5, floatToBits(222.0f))

        // Program: fadd r2, u5, r0 (funct3=01: rs1 from uniform buffer)
        // Instruction encodes rs1=5 -> uniform index 5; rs2=0 -> GPR r0 = 0.0
        writeReg(core, 0, floatToBits(0.0f))  // r0 = 0 (additive identity)
        val uload_instr = Instructions.ADD(5, 0, 2, funct3 = 1)  // result = uniform[5] + 0.0
        writeImem(core, 0, uload_instr)
        writeImem(core, 1, 0)  // halt

        // Run with uniformPage = 0 → should read 111.0
        core.io.control.uniformWritePage.poke(0.U)
        core.io.uniformPage.poke(0.U)
        startAndWait(core)
        val result_pg0 = bitsToFloat(readReg(core, 2))
        println(f"  Page 0 uniform read: $result_pg0%.2f (expected 111.0)")
        utest.assert(math.abs(result_pg0 - 111.0f) < 0.01f)

        // Reset execution state, but BRAM stays
        resetCore(core)
        core.io.coreTrigger.valid.poke(false.B)
        core.clock.step(5)

        // Run with uniformPage = 1 → should read 222.0
        core.io.control.uniformWritePage.poke(1.U)
        core.io.uniformPage.poke(1.U)
        startAndWait(core)
        val result_pg1 = bitsToFloat(readReg(core, 2))
        println(f"  Page 1 uniform read: $result_pg1%.2f (expected 222.0)")
        utest.assert(math.abs(result_pg1 - 222.0f) < 0.01f)

        println("  PASSED")
      }
    }

    utest.test("frcp_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: frcp_fp16 ---")
        idleInputs(core)
        resetCore(core)

        def testFrcp(input: Float, expected: Float, label: String): Unit = {
          resetCore(core)
          writeReg(core, 0, floatToBits(input))
          writeImem(core, 0, Instructions.FRCP(rs1 = 0, rd = 2))
          writeImem(core, 1, 0)  // halt
          startAndWait(core)
          val result = bitsToFloat(readReg(core, 2))
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

    utest.test("frsq_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: frsq_fp16 ---")
        idleInputs(core)
        resetCore(core)

        def testFrsq(input: Float, expected: Float, label: String): Unit = {
          resetCore(core)
          writeReg(core, 0, floatToBits(input))
          writeImem(core, 0, Instructions.FRSQ(rs1 = 0, rd = 2))
          writeImem(core, 1, 0) // halt
          startAndWait(core)
          val result = bitsToFloat(readReg(core, 2))
          val tol = math.max(5e-3f * math.abs(expected), 5e-3f)
          println(f"  $label: actual=$result%.6f expected=$expected%.6f tol=$tol%.6f")
          utest.assert(math.abs(result - expected) < tol)
        }

        testFrsq(1.0f,   1.0f,        "rsqrt(1.0)")
        testFrsq(4.0f,   0.5f,        "rsqrt(4.0)")   // even E, power of two
        testFrsq(0.25f,  2.0f,        "rsqrt(0.25)")  // even E, < 1
        testFrsq(2.0f,   0.707107f,   "rsqrt(2.0)")   // odd E (parity region 1)
        testFrsq(3.0f,   0.577350f,   "rsqrt(3.0)")   // odd E, interpolated
        testFrsq(9.0f,   0.333333f,   "rsqrt(9.0)")
        testFrsq(16.0f,  0.25f,       "rsqrt(16.0)")
        testFrsq(100.0f, 0.1f,        "rsqrt(100.0)")
        testFrsq(0.5f,   1.414214f,   "rsqrt(0.5)")

        println("  PASSED")
      }
    }

    utest.test("ddx_ddy_quad") {
      // 4-lane SIMT cross-lane quad derivatives. Zero existing coverage for the
      // Simt config — this harness drives the 2×2 quad and checks ddx/ddy of both
      // the per-lane coordinate registers and a computed GPR.
      simulate(new BorgCore(BorgConfig.Simt)) { core =>
        println("\n--- BorgCore: ddx_ddy_quad (4-lane SIMT) ---")
        idleInputs(core)
        // 2×2 quad: lane0=(4,4) TL, lane1=(5,4) TR, lane2=(4,5) BL, lane3=(5,5) BR.
        val qx = Seq(4, 5, 4, 5); val qy = Seq(4, 4, 5, 5)
        for (i <- 0 until 4) { core.io.iter(i).x.poke(qx(i).U); core.io.iter(i).y.poke(qy(i).U) }
        core.io.seqBusy.poke(false.B) // r30/r31 = per-lane pixel centre (x+0.5, y+0.5)
        resetCore(core)

        writeReg(core, 6, floatToBits(3.0f)) // broadcast constant 3.0

        // r1 = coordX·3 (differs per lane), then derivatives.
        writeImem(core, 0, Instructions.MUL(rs1 = 30, rs2 = 6, rd = 1)) // r1 = coordX*3
        writeImem(core, 1, Instructions.DDX(rs1 = 1,  rd = 7))          // ddx(coordX*3) = 3
        writeImem(core, 2, Instructions.DDX(rs1 = 30, rd = 2))          // ddx(coordX)   = 1
        writeImem(core, 3, Instructions.DDY(rs1 = 31, rd = 3))          // ddy(coordY)   = 1
        writeImem(core, 4, Instructions.DDX(rs1 = 31, rd = 4))          // ddx(coordY)   = 0
        writeImem(core, 5, Instructions.DDY(rs1 = 30, rd = 5))          // ddy(coordX)   = 0
        writeImem(core, 6, 0)                                            // halt
        startAndWait(core)

        def chk(reg: Int, exp: Float, label: String): Unit = {
          val got = bitsToFloat(readReg(core, reg))
          println(f"  $label = $got%.3f (expected $exp%.3f)")
          utest.assert(math.abs(got - exp) < 0.01f)
        }
        chk(7, 3.0f, "ddx(coordX*3)")
        chk(2, 1.0f, "ddx(coordX)")
        chk(3, 1.0f, "ddy(coordY)")
        chk(4, 0.0f, "ddx(coordY)")
        chk(5, 0.0f, "ddy(coordX)")
        println("  PASSED")
      }
    }

  }
}
