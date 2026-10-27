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
                  readBack: Seq[Int], quad: Boolean = false): (Int, Seq[BigInt]) = {
    var result = (0, Seq.empty[BigInt])
    simulate(new BorgCore(cfg)) { core =>
      idleInputs(core)
      if (quad) pokeQuad(core)
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

  private def withMem(cfg: BorgConfig, prog: Seq[BigInt], readBack: Seq[Int],
                      mem: scala.collection.mutable.Map[BigInt, BigInt]): Seq[BigInt] = {
    var result = Seq.empty[BigInt]
    simulate(new BorgCore(cfg)) { core =>
      idleInputs(core)
      resetCore(core)
      (prog :+ BigInt(0)).zipWithIndex.foreach { case (instr, slot) => writeImem(core, slot, instr) }
      startAndWaitWithMem(core, mem)
      result = readBack.map(readReg(core, _))
    }
    result
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

    utest.test("dependent_instruction_issues_at_write_back") {
      val prog = (3 to 10).map(rd => Instructions.IADD(rd - 1, 2, rd))
      val (_, cp, r) = both("8 chained IADDs", Seq(2 -> BigInt(3)), prog, Seq(10))
      utest.assert(r == Seq(BigInt(27)))   // r3 = r2 + r2, then + r2 per step
      // Each IADD issues while the one it reads is in write-back.
      utest.assert(cp == prog.length * piped.cBusyLoad + 1)
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

    utest.test("write_after_write_keeps_program_order") {
      // An FP result and an integer one to the same register, both orders.
      val prog = Seq(Instructions.ADD(7, 7, 3), Instructions.IADD(1, 2, 3),
                     Instructions.IADD(1, 2, 4), Instructions.ADD(7, 7, 4))
      val (_, _, r) = both("FADD/IADD to one register", ints :+ (7 -> floatToBits(1.0f)), prog, Seq(3, 4))
      utest.assert(r == Seq(BigInt(8), floatToBits(2.0f)))
    }

    utest.test("taken_branch_right_after_an_independent_instruction") {
      val prog = Seq(Opcodes.ADDI(3, 0, 7), Opcodes.BEQ(8, 0, 0), Opcodes.ADDI(4, 0, 99), Opcodes.ADDI(5, 0, 5))
      val (_, _, r) = both("ADDI, taken BEQ", Seq(4 -> BigInt(0), 5 -> BigInt(0)), prog, Seq(3, 4, 5))
      utest.assert(r == Seq(BigInt(7), BigInt(0), BigInt(5)))
    }

    utest.test("load_feeds_the_next_instruction") {
      val prog = Seq(Opcodes.ADDI(1, 0, 0x100), Opcodes.LW(2, 1, 0), Instructions.IADD(2, 2, 3),
                     Opcodes.SW(4, 1, 3), Opcodes.ADDI(5, 0, 1))
      val mems = Seq(piped, unpiped).map { cfg =>
        val mem = scala.collection.mutable.Map(BigInt(0x100) -> BigInt(21))
        (withMem(cfg, prog, Seq(2, 3, 5), mem), mem.get(0x104))
      }
      println(s"  LW, dependent IADD, SW: ${mems.head}")
      utest.assert(mems.head == mems(1))
      utest.assert(mems.head == (Seq(BigInt(21), BigInt(42), BigInt(1)), Some(BigInt(42))))
    }

    utest.test("expush_leaves_the_instruction_before_it_unmasked") {
      // r5 = 0, 1, 0, 1 across the quad: EXPUSH masks lane 0, the only one readable. r8's ADD is
      // still in flight when EXPUSH changes the mask; r6's issues after it.
      val prog = Seq(Instructions.F2I(30, 5)) ++ (10 to 14).map(Instructions.ADD(7, 7, _)) ++
        Seq(Instructions.ADD(7, 7, 8), Instructions.EXPUSH(rs1 = 5), Instructions.ADD(7, 7, 6), Instructions.EXPOP())
      val ops = Seq(6 -> floatToBits(9.0f), 8 -> floatToBits(9.0f), 7 -> floatToBits(1.0f))
      val res = Seq(SIMT, SIMT.copy(pipelined = false)).map(run(_, ops, prog, Seq(6, 8), quad = true))
      println(s"  lane 0 r6, r8 = ${res.map(_._2.map(b => bitsToFloat(b)))}, cycles ${res.map(_._1)}")
      utest.assert(res(0)._2 == res(1)._2)
      utest.assert(res(0)._2 == Seq(floatToBits(9.0f), floatToBits(2.0f)))
    }
  }
}
