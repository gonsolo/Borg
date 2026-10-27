// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._
import BorgCoreTestHelpers._

/** cfg.pipelined (docs/B9_pipeline.md, step 1): an instruction issues every cycle unless it
  * reads a register an instruction in flight writes. Each test runs the same program with and
  * without the pipeline and compares results and cycles. */
object BorgPipelineTests extends TestSuite {

  private val piped   = BorgConfig.Test
  private val unpiped = BorgConfig.Test.copy(pipelined = false)

  /** (cycles from start until the core stops, the registers in `readBack`) */
  private def run(cfg: BorgConfig, operands: Seq[(Int, BigInt)], prog: Seq[BigInt],
                  readBack: Seq[Int]): (Int, Seq[BigInt]) = {
    var result = (0, Seq.empty[BigInt])
    simulate(new BorgCore(cfg)) { core =>
      idleInputs(core)
      resetCore(core)
      operands.foreach { case (i, b) => writeReg(core, i, b) }
      (prog :+ BigInt(0)).zipWithIndex.foreach { case (instr, slot) => writeImem(core, slot, instr) }
      core.io.control.start.poke(true.B)
      core.clock.step(1)
      core.io.control.start.poke(false.B)
      var cycles = 0
      while (core.io.status.running.peek().litToBoolean && cycles < 2000) { core.clock.step(1); cycles += 1 }
      result = (cycles, readBack.map(readReg(core, _)))
    }
    result
  }

  private def both(name: String, operands: Seq[(Int, BigInt)], prog: Seq[BigInt],
                   readBack: Seq[Int]): (Int, Int, Seq[BigInt]) = {
    val (cp, rp) = run(piped, operands, prog, readBack)
    val (cu, ru) = run(unpiped, operands, prog, readBack)
    println(f"  $name%-34s cycles $cu -> $cp, ${rp.map(b => f"0x$b%x").mkString(",")}")
    utest.assert(rp == ru)
    (cu, cp, rp)
  }

  private val ints = Seq(1 -> BigInt(5), 2 -> BigInt(3))

  val tests = Tests {
    utest.test("independent_instructions_issue_every_cycle") {
      val prog = (3 to 10).map(rd => Instructions.IADD(1, 2, rd))
      val (cu, cp, r) = both("8 independent IADDs", ints, prog, 3 to 10)
      utest.assert(r.forall(_ == 8))
      // One instruction per cycle, then the last one's way to write-back.
      utest.assert(cp == prog.length + piped.cBusyLoad)
    }

    utest.test("dependent_instruction_waits_for_write_back") {
      val prog = (3 to 10).map(rd => Instructions.IADD(rd - 1, 2, rd))
      val (cu, cp, r) = both("8 chained IADDs", Seq(2 -> BigInt(3)), prog, Seq(10))
      utest.assert(r == Seq(BigInt(27)))   // r3 = r2 + r2, then + r2 per step
      // Only the HALT overlaps: every IADD reads the register the one before writes.
      utest.assert(cu - cp == 1)
    }

    utest.test("immediate_is_no_hazard") {
      // Each ADDI's immediate (the rs2 field) equals the rd of the IADD before it.
      val prog = (3 to 9 by 2).flatMap(rd => Seq(Instructions.IADD(1, 2, rd), Opcodes.ADDI(rd + 1, 1, rd)))
      val (cu, cp, r) = both("IADD, ADDI with imm = rd before", ints, prog, 3 to 10)
      utest.assert(r == (3 to 10).map(rd => BigInt(if (rd % 2 == 1) 8 else 4 + rd)))
      utest.assert(cp == prog.length + piped.cBusyLoad)
    }

    utest.test("branch_redirects_the_fetch") {
      // r5 counts the iterations of a loop over r1 = 5 .. 1.
      val minusOne = (BigInt(1) << 32) - 1
      val prog = Seq(
        Instructions.IADD(5, 6, 5),   // r5 += 1
        Instructions.IADD(1, 2, 1),   // r1 -= 1
        Instructions.BRNZ(1, -2))
      val (_, _, r) = both("loop of 5", Seq(1 -> BigInt(5), 2 -> minusOne, 5 -> BigInt(0), 6 -> BigInt(1)), prog, Seq(1, 5))
      utest.assert(r == Seq(BigInt(0), BigInt(5)))
    }
  }
}
