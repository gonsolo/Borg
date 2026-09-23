// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgCoreTestsD extends TestSuite {
  import BorgCoreTestHelpers._

  val tests = Tests {

    utest.test("backward_branch_runs_a_real_loop") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: counted loop ---")
        idleInputs(core)
        resetCore(core)

        // The thing branches actually unlock. Sum 1.0 four times by looping,
        // counting an integer register down to zero:
        //   r0 = 4 (counter, raw int)   r1 = -1   r2 = 1.0   r3 = accumulator
        //   0: r3 = r3 + r2        accumulate
        //   1: r0 = r0 + r1        decrement (integer add on raw bits)
        //   2: BRNZ r0 -> 0        loop while the counter is non-zero
        //   3: halt
        writeReg(core, 0, 4)
        writeReg(core, 1, intBits(-1))                  // -1 at the datapath width
        writeReg(core, 2, floatToBits(1.0f))
        writeReg(core, 3, floatToBits(0.0f))
        writeImem(core, 0, Instructions.ADD(3, 2, 3))
        writeImem(core, 1, Instructions.IADD(0, 1, 0))
        writeImem(core, 2, Instructions.BRNZ(rs1 = 0, target = 0))
        writeImem(core, 3, 0)

        startAndWait(core)
        val acc = bitsToFloat(readReg(core, 3))
        val ctr = readReg(core, 0)
        println(f"  looped: r3 = $acc%.2f (expect 4.0), counter = $ctr (expect 0)")
        utest.assert(math.abs(acc - 4.0f) < 0.01f)
        utest.assert(ctr == 0)
        println("  PASSED")
      }
    }

    utest.test("brnz_polarity_is_the_inverse_of_brz") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: BRNZ polarity ---")
        idleInputs(core)
        resetCore(core)

        // Same program twice, only the condition register differs, so a
        // swapped polarity shows up as both cases behaving alike.
        for ((cond, shouldBranch) <- Seq((0, false), (1, true))) {
          resetCore(core)
          writeReg(core, 0, cond)
          writeReg(core, 1, floatToBits(1.0f))
          writeReg(core, 2, floatToBits(0.0f))
          writeImem(core, 0, Instructions.BRNZ(rs1 = 0, target = 2))
          writeImem(core, 1, Instructions.ADD(1, 1, 2))
          writeImem(core, 2, 0)
          startAndWait(core)
          val r2 = bitsToFloat(readReg(core, 2))
          val branched = math.abs(r2) < 0.01f
          println(f"  cond=$cond -> branched=$branched (expect $shouldBranch)")
          utest.assert(branched == shouldBranch)
        }
        println("  PASSED")
      }
    }

    utest.test("negative_zero_counts_as_non_zero") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: FP16 -0.0 is non-zero to a branch ---")
        idleInputs(core)
        resetCore(core)

        // The condition is a RAW bits comparison, so -0.0 (0x8000) does NOT
        // branch on BRZ -- the same convention the discard register uses.
        // Worth pinning down: an FP-aware comparison would do the opposite.
        writeReg(core, 0, 0x8000)
        writeReg(core, 1, floatToBits(1.0f))
        writeReg(core, 2, floatToBits(0.0f))
        writeImem(core, 0, Instructions.BRZ(rs1 = 0, target = 2))
        writeImem(core, 1, Instructions.ADD(1, 1, 2))
        writeImem(core, 2, 0)

        startAndWait(core)
        val r2 = bitsToFloat(readReg(core, 2))
        println(f"  r2 = $r2%.2f (expect 2.0 -- not taken, so the add ran)")
        utest.assert(math.abs(r2 - 2.0f) < 0.01f)
        println("  PASSED")
      }
    }

    utest.test("scalar_build_never_reports_branch_divergence") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: divergence flag at fragLanes=1 ---")
        idleInputs(core)
        resetCore(core)

        writeReg(core, 0, 0)
        writeImem(core, 0, Instructions.BRZ(rs1 = 0, target = 1))
        writeImem(core, 1, 0)
        startAndWait(core)

        val div = core.io.branchDivergent.peek().litToBoolean
        println(f"  branchDivergent = $div (expect false -- one lane cannot disagree)")
        utest.assert(!div)
        println("  PASSED")
      }
    }

    utest.test("expush_masks_lanes_whose_condition_is_false") {
      simulate(new BorgCore(SIMT)) { core =>
        println("\n--- BorgCore: EXPUSH masks a lane ---")
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)

        // r5 = int(r30) -> lane x coordinate: 0, 1, 0, 1 across the quad.
        // EXPUSH r5 therefore keeps lanes 1 and 3 and masks lanes 0 and 2.
        // Lane 0 is masked, so its r6 must keep the sentinel.
        writeReg(core, 6, floatToBits(9.0f))     // sentinel in every lane
        writeReg(core, 7, floatToBits(1.0f))
        writeImem(core, 0, Instructions.F2I(30, 5))
        writeImem(core, 1, Instructions.EXPUSH(rs1 = 5))
        writeImem(core, 2, Instructions.ADD(7, 7, 6))   // r6 = 2.0, masked lanes skip
        writeImem(core, 3, Instructions.EXPOP())
        writeImem(core, 4, 0)

        startAndWait(core)
        val lane0 = bitsToFloat(readReg(core, 6))
        println(f"  lane0 (x=0, condition false) r6 = $lane0%.2f (expect 9.0 -- masked)")
        utest.assert(math.abs(lane0 - 9.0f) < 0.01f)
        utest.assert(!core.io.execFault.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("expop_restores_every_lane") {
      simulate(new BorgCore(SIMT)) { core =>
        println("\n--- BorgCore: EXPOP restores ---")
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)

        // Same masked region, but the write happens AFTER EXPOP, so lane 0
        // must see it again. This is what proves the mask is restored rather
        // than latched off for the rest of the program.
        writeReg(core, 6, floatToBits(9.0f))
        writeReg(core, 7, floatToBits(1.0f))
        writeImem(core, 0, Instructions.F2I(30, 5))
        writeImem(core, 1, Instructions.EXPUSH(rs1 = 5))
        writeImem(core, 2, Instructions.EXPOP())
        writeImem(core, 3, Instructions.ADD(7, 7, 6))   // r6 = 2.0 in ALL lanes
        writeImem(core, 4, 0)

        startAndWait(core)
        val lane0 = bitsToFloat(readReg(core, 6))
        println(f"  lane0 r6 = $lane0%.2f (expect 2.0 -- mask restored)")
        utest.assert(math.abs(lane0 - 2.0f) < 0.01f)
        utest.assert(!core.io.execFault.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("exelse_runs_exactly_the_complementary_lanes") {
      simulate(new BorgCore(SIMT)) { core =>
        println("\n--- BorgCore: EXELSE ---")
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)

        // if (x != 0) r6 = 2.0  else  r6 = 3.0
        // Lane 0 has x == 0, so it takes the ELSE arm and must end at 3.0 --
        // which only happens if EXELSE re-activates it.
        writeReg(core, 6, floatToBits(9.0f))
        writeReg(core, 7, floatToBits(1.0f))
        writeReg(core, 8, floatToBits(3.0f))
        writeImem(core, 0, Instructions.F2I(30, 5))
        writeImem(core, 1, Instructions.EXPUSH(rs1 = 5))
        writeImem(core, 2, Instructions.ADD(7, 7, 6))    // then: r6 = 2.0
        writeImem(core, 3, Instructions.EXELSE())
        writeImem(core, 4, Instructions.ADD(8, 30, 6))   // else: overwritten below
        writeImem(core, 5, Instructions.EXPOP())
        writeImem(core, 6, 0)

        startAndWait(core)
        // Lane 0 took the else arm, so r6 there is 3.0 + r30(=0.5) = 3.5;
        // what matters is that it is NOT 9.0 (never ran) and NOT 2.0 (ran the
        // then-arm it should have been masked out of).
        val lane0 = bitsToFloat(readReg(core, 6))
        println(f"  lane0 r6 = $lane0%.2f (expect 3.5 -- else arm; 9.0 = never ran, 2.0 = wrong arm)")
        utest.assert(math.abs(lane0 - 3.5f) < 0.01f)
        utest.assert(!core.io.execFault.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("nested_expush_only_narrows_never_widens") {
      simulate(new BorgCore(SIMT)) { core =>
        println("\n--- BorgCore: nested EXPUSH ---")
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)

        // Outer if masks lane 0 off. The inner EXELSE must NOT bring it back:
        // the else arm is `enclosing & ~cond`, and lane 0 is not in the
        // enclosing mask at all. Inverting the full mask instead of masking
        // against the enclosing one is the classic bug here, and it would
        // show up as lane 0 executing the inner else.
        writeReg(core, 6, floatToBits(9.0f))
        writeReg(core, 7, floatToBits(1.0f))
        writeImem(core, 0, Instructions.F2I(30, 5))
        writeImem(core, 1, Instructions.EXPUSH(rs1 = 5))   // lane 0 masked off
        writeImem(core, 2, Instructions.EXPUSH(rs1 = 5))   // inner, same cond
        writeImem(core, 3, Instructions.EXELSE())          // inner else
        writeImem(core, 4, Instructions.ADD(7, 7, 6))      // lane 0 must NOT run this
        writeImem(core, 5, Instructions.EXPOP())
        writeImem(core, 6, Instructions.EXPOP())
        writeImem(core, 7, 0)

        startAndWait(core)
        val lane0 = bitsToFloat(readReg(core, 6))
        println(f"  lane0 r6 = $lane0%.2f (expect 9.0 -- inner else must not re-activate it)")
        utest.assert(math.abs(lane0 - 9.0f) < 0.01f)
        utest.assert(!core.io.execFault.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("unbalanced_mask_stack_raises_a_fault_instead_of_going_quiet") {
      simulate(new BorgCore(SIMT)) { core =>
        println("\n--- BorgCore: EXPOP underflow ---")
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)

        // EXPOP with nothing pushed. Wrong results are unavoidable; the point
        // is that the shader is detectably broken rather than silently so.
        writeImem(core, 0, Instructions.EXPOP())
        writeImem(core, 1, 0)
        startAndWait(core)

        val fault = core.io.execFault.peek().litToBoolean
        println(f"  execFault = $fault (expect true)")
        utest.assert(fault)
        println("  PASSED")
      }
    }

    utest.test("exany_implements_a_genuinely_divergent_loop") {
      simulate(new BorgCore(SIMT)) { core =>
        println("\n--- BorgCore: EXANY -- divergent while loop ---")
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)

        // while (counter != 0) { accum++; counter--; }, counter = 2 + 2*x, so
        // lanes 0/2 (x=0) run it twice and lanes 1/3 (x=1) run it four times --
        // genuinely different trip counts. BRZ/BRNZ alone cannot express this
        // loop's exit condition (it only reads lane 0's operand, and lane 0's
        // OWN counter says "done" two iterations before lane 1's does); EXANY
        // reduces the whole quad's execMask to one quad-uniform value BRNZ can
        // safely test. r9 counts total passes through the EXPUSH/EXANY/EXPOP
        // block, unconditionally (outside the mask) -- a lane-0-readable proxy
        // for "did the loop actually run long enough for the OTHER lane's
        // longer trip count", since lane 0 alone would only need 3 passes.
        writeReg(core, 1, 0)   // counter, computed below
        writeReg(core, 2, 0)   // accumulator
        writeReg(core, 7, 1)              // +1
        writeReg(core, 8, intBits(-1))    // -1
        writeReg(core, 9, 0)   // total passes

        writeReg(core, 6, 2)                                           // constant 2
        writeImem(core, 0, Instructions.F2I(30, 5))                    // r5 = int(x), 0 or 1
        writeImem(core, 1, Instructions.IMUL(5, 6, 1))                 // r1 = x*2
        writeImem(core, 2, Instructions.IADD(1, 6, 1))                 // r1 = x*2 + 2 (counter: 2 or 4)
        // LOOP (starts at instruction 3):
        writeImem(core, 3, Instructions.IADD(9, 7, 9))                 // r9 += 1 (unmasked pass counter)
        writeImem(core, 4, Instructions.EXPUSH(rs1 = 1))               // mask lanes with counter == 0
        writeImem(core, 5, Instructions.IADD(2, 7, 2))                 // r2 += 1
        writeImem(core, 6, Instructions.IADD(1, 8, 1))                 // r1 -= 1
        writeImem(core, 7, Instructions.EXANY(rd = 3))                 // r3 = any lane still active this pass
        writeImem(core, 8, Instructions.EXPOP())
        writeImem(core, 9, Instructions.BRNZ(rs1 = 3, target = 3))
        writeImem(core, 10, 0)

        core.io.control.start.poke(true.B)
        core.clock.step(1)
        core.io.control.start.poke(false.B)
        var idle = false
        var watchdog = 0
        while (!idle && watchdog < 3000) {
          core.clock.step(1)
          idle = !core.io.status.running.peek().litToBoolean
          watchdog += 1
        }
        utest.assert(idle)

        val accum = readReg(core, 2)
        val passes = readReg(core, 9)
        println(s"  lane0 (x=0, trip count 2): accum = $accum (expect 2), total passes = $passes (expect 5)")
        utest.assert(accum == 2)
        // 5, not 3: 4 passes where at least one lane (the x=1 pair, trip
        // count 4) is still active, plus the final pass where every lane's
        // counter has already reached 0 -- the one EXANY reports as empty.
        // A broken EXANY (or one that only reflected lane 0's own state)
        // would stop this at 3.
        utest.assert(passes == 5)
        utest.assert(!core.io.execFault.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("a_shader_using_no_mask_ops_runs_fully_unmasked") {
      simulate(new BorgCore(SIMT)) { core =>
        println("\n--- BorgCore: no mask ops -> nothing changes ---")
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)

        // The regression anchor: the mask powers up all-ones and nothing
        // narrows it, so a program that predates these instructions behaves
        // exactly as it did.
        writeReg(core, 0, floatToBits(2.0f))
        writeReg(core, 1, floatToBits(3.0f))
        writeImem(core, 0, Instructions.ADD(0, 1, 2))
        writeImem(core, 1, 0)
        startAndWait(core)

        val r2 = bitsToFloat(readReg(core, 2))
        println(f"  r2 = $r2%.2f (expect 5.0), execFault=${core.io.execFault.peek().litToBoolean}")
        utest.assert(math.abs(r2 - 5.0f) < 0.01f)
        utest.assert(!core.io.execFault.peek().litToBoolean)
        println("  PASSED")
      }
    }

  }
}
