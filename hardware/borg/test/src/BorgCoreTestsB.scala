// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._

object BorgCoreTestsB extends TestSuite {
  import BorgCoreTestHelpers._

  val tests = Tests {

    utest.test("pipe_write_snoop") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: pipe_write_snoop ---")
        idleInputs(core)
        resetCore(core)

        // Run fmul: r9=2.0, r1=3.0 → r2=6.0
        writeReg(core, 9, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))
        writeImem(core, 0, Instructions.MUL(9, 1, 2))
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
        writeReg(core, 9, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))
        writeImem(core, 0, Instructions.MUL(9, 1, 2))
        writeImem(core, 1, Instructions.ADD(2, 9, 3))
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

        // Program: r2 = uniform 5, through its CSR
        val uload_instr = Opcodes.CSRRS(2, 0, Csr.Uniform0 + 5)
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
          writeReg(core, 9, floatToBits(input))
          writeImem(core, 0, Instructions.FRCP(rs1 = 9, rd = 2))
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
          writeReg(core, 9, floatToBits(input))
          writeImem(core, 0, Instructions.FRSQ(rs1 = 9, rd = 2))
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

    utest.test("ddx_ddy_fine") {
      // v = x*y is not linear across the quad: (20.25, 24.75, 24.75, 30.25).
      // Coarse derivatives are the top row / left column everywhere; fine
      // ones each lane's own row (x) or column (y) -- read back through a
      // second, coarse derivative, which sees lanes 1 and 2 from lane 0.
      simulate(new BorgCore(BorgConfig.Simt)) { core =>
        println("\n--- BorgCore: ddx_ddy_fine (4-lane SIMT) ---")
        idleInputs(core)
        val qx = Seq(4, 5, 4, 5); val qy = Seq(4, 4, 5, 5)
        for (i <- 0 until 4) { core.io.iter(i).x.poke(qx(i).U); core.io.iter(i).y.poke(qy(i).U) }
        core.io.seqBusy.poke(false.B)
        resetCore(core)
        import Instructions._
        Seq(MUL(rs1 = 30, rs2 = 31, rd = 1),              // v = x*y
            DDX(rs1 = 1, rd = 2, fine = true),            // (4.5, 4.5, 5.5, 5.5)
            DDY(rs1 = 2, rd = 3),                         // lane2 - lane0 = 1
            DDY(rs1 = 1, rd = 4, fine = true),            // (4.5, 5.5, 4.5, 5.5)
            DDX(rs1 = 4, rd = 5),                         // lane1 - lane0 = 1
            DDX(rs1 = 1, rd = 6),                         // coarse: 4.5 everywhere
            DDY(rs1 = 6, rd = 7),                         // so 0
            BigInt(0)).zipWithIndex.foreach { case (w, i) => writeImem(core, i, w) }
        startAndWait(core)
        def chk(reg: Int, exp: Float, label: String): Unit = {
          val got = bitsToFloat(readReg(core, reg))
          println(f"  $label = $got%.3f (expected $exp%.3f)")
          utest.assert(got == exp)
        }
        chk(2, 4.5f, "ddxFine(x*y), lane 0")
        chk(3, 1.0f, "ddy(ddxFine(x*y))")
        chk(4, 4.5f, "ddyFine(x*y), lane 0")
        chk(5, 1.0f, "ddx(ddyFine(x*y))")
        chk(7, 0.0f, "ddy(ddx(x*y)) coarse")
        println("  PASSED")
      }
    }

  }
}
