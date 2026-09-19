// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import BorgCoreTestHelpers._

/** `cfg.fmaStages` equivalence.
  *
  * Raising fmaStages inserts pipeline registers inside BorgFp16Fma, which
  * means BorgCore's whole instruction countdown shifts: busy_counter loads
  * later, the three register reads and pipeEn1 move with it, and the counter
  * itself needs a wider register. BorgConfig's cRs2/cHoldC/cOperands/cPipeEn2
  * derive all of that from fmaStages -- these tests are what make that
  * derivation trustworthy.
  *
  * The contract is EQUIVALENCE, not "it runs": a deeper FMA must produce the
  * bit-identical result, only later. So every case runs the same program at
  * stages=3 and stages=4 and compares the raw register bits rather than
  * checking a tolerance, which would hide a mantissa-level pipeline bug.
  *
  * Why this file exists: fmaStages had ZERO test coverage while carrying a
  * comment warning that raising it "alone is NOT functionally correct". It
  * was a documented trap with nothing guarding it.
  */
object BorgFmaStagesTests extends TestSuite {

  private val cfg3 = BorgConfig.Test
  private val cfg4 = BorgConfig.Test.copy(fmaStages = 4)

  /** Load operands into r0..r2, run `prog`, return the raw bits of `readBack`. */
  private def runProg(cfg: BorgConfig, operands: Seq[(Int, BigInt)],
                      prog: Seq[BigInt], readBack: Seq[Int]): Seq[BigInt] = {
    var out: Seq[BigInt] = Nil
    simulate(new BorgCore(cfg)) { core =>
      idleInputs(core)
      resetCore(core)
      operands.foreach { case (i, b) => writeReg(core, i, b) }
      prog.zipWithIndex.foreach { case (instr, slot) => writeImem(core, slot, instr) }
      writeImem(core, prog.length, 0) // halt
      startAndWait(core)
      out = readBack.map(r => readReg(core, r))
    }
    out
  }

  private def bothStages(name: String, operands: Seq[(Int, BigInt)],
                         prog: Seq[BigInt], readBack: Seq[Int]): Unit = {
    val r3 = runProg(cfg3, operands, prog, readBack)
    val r4 = runProg(cfg4, operands, prog, readBack)
    println(f"  $name%-28s stages=3: ${r3.map(b => f"0x$b%x").mkString(",")}%-24s " +
            f"stages=4: ${r4.map(b => f"0x$b%x").mkString(",")}")
    readBack.zip(r3.zip(r4)).foreach { case (reg, (a, b)) =>
      utest.assert(a == b)
    }
  }

  val tests = Tests {

    // The derived constants themselves -- cheap, and they pin the table in
    // BorgConfig's comment so a future edit can't silently shift a phase.
    utest.test("phase_constants_match_the_documented_table") {
      val c3 = BorgConfig.Test
      val c4 = BorgConfig.Test.copy(fmaStages = 4)
      val c5 = BorgConfig.Test.copy(fmaStages = 5)
      // (cBusyLoad, cRs2, cRs3, cHoldC, cOperands, cPipeEn2)
      utest.assert((c3.cBusyLoad, c3.cRs3, c3.cHoldC, c3.cOperands, c3.cPipeEn2) == (7, 6, 5, 4, 3))
      utest.assert((c4.cBusyLoad, c4.cRs3, c4.cHoldC, c4.cOperands, c4.cPipeEn2) == (8, 7, 6, 5, 3))
      utest.assert((c5.cBusyLoad, c5.cRs3, c5.cHoldC, c5.cOperands, c5.cPipeEn2) == (9, 8, 7, 6, 4))
      // The counter must actually be able to hold its load value.
      Seq(c3, c4, c5).foreach { c =>
        utest.assert((1 << c.busyCounterWidth) > c.cBusyLoad)
      }
      println("  phase constants + counter width OK for stages 3/4/5")
    }

    utest.test("fma_results_are_bit_identical_across_stages") {
      println("\n--- fmaStages equivalence: arithmetic ---")
      // FMA is the op the extra stage actually splits, so it is the one that
      // matters most; ADD/MUL share the same datapath through the same regs.
      bothStages("fma(2.5, 4.0, 1.25)",
        Seq(0 -> floatToBits(2.5f), 1 -> floatToBits(4.0f), 2 -> floatToBits(1.25f)),
        Seq(Instructions.FMA(0, 1, 2, 3)), Seq(3))

      bothStages("mul(3.5, -2.25)",
        Seq(0 -> floatToBits(3.5f), 1 -> floatToBits(-2.25f)),
        Seq(Instructions.MUL(0, 1, 3)), Seq(3))

      bothStages("add(1.0, 0.0009765625)",   // tiny addend: exercises alignment
        Seq(0 -> floatToBits(1.0f), 1 -> floatToBits(0.0009765625f)),
        Seq(Instructions.ADD(0, 1, 3)), Seq(3))

      bothStages("add(1e-7, -1e-7) -> zero", // cancellation: magR == 0 path
        Seq(0 -> floatToBits(1e-7f), 1 -> floatToBits(-1e-7f)),
        Seq(Instructions.ADD(0, 1, 3)), Seq(3))
    }

    utest.test("back_to_back_instructions_do_not_overlap") {
      println("\n--- fmaStages equivalence: dependent chain ---")
      // A RAW chain is what breaks if the countdown and the FMA disagree: r3
      // must be written before the next instruction reads it. At stages=4 the
      // write-back cycle is unchanged but the result arrives later inside the
      // FMA, so an off-by-one here shows up as a stale operand.
      bothStages("chain mul->add->fma",
        Seq(0 -> floatToBits(2.0f), 1 -> floatToBits(3.0f), 2 -> floatToBits(0.5f)),
        Seq(
          Instructions.MUL(0, 1, 3),   // r3 = 6.0
          Instructions.ADD(3, 2, 4),   // r4 = 6.5   (reads r3 written last instr)
          Instructions.FMA(3, 4, 0, 5) // r5 = 6*6.5 + 2
        ),
        Seq(3, 4, 5))
    }
  }
}
