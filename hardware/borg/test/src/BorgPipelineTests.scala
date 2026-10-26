// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._
import BorgCoreTestHelpers._

/** cfg.pipelined (docs/B9_pipeline.md, step 1): the next instruction decodes in this one's
  * write-back cycle unless it reads this one's rd. Each test runs the same program with and
  * without the overlap and compares results and cycles. */
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
    utest.test("independent_instructions_overlap_write_back") {
      val prog = (3 to 10).map(rd => Instructions.IADD(1, 2, rd))
      val (cu, cp, r) = both("8 independent IADDs", ints, prog, 3 to 10)
      utest.assert(r.forall(_ == 8))
      // One cycle per instruction, and the HALT behind the last one.
      utest.assert(cu - cp == prog.length)
    }

    utest.test("dependent_instruction_waits_for_write_back") {
      val prog = (3 to 10).map(rd => Instructions.IADD(rd - 1, 2, rd))
      val (cu, cp, r) = both("8 chained IADDs", Seq(2 -> BigInt(3)), prog, Seq(10))
      utest.assert(r == Seq(BigInt(27)))   // r3 = r2 + r2, then + r2 per step
      // Only the HALT overlaps: every IADD reads the register the one before writes.
      utest.assert(cu - cp == 1)
    }

    utest.test("branch_target_with_the_early_fetch") {
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
