// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Beat-phase training tests.
  *
  * The property under test is not "does it lock" but "does it lock '''mid-eye'''".
  * At N=2 the master's pins change one core cycle after its beat, so a correctly
  * trained slave beats on the *opposite* core cycle from the master -- roughly 40 ns
  * into the eye rather than right at the transition.  A slave that beat in phase
  * with the master would be sampling at the edge, which is the failure this whole
  * mechanism exists to prevent, and it would still pass a naive "did data arrive"
  * check.
  */
object BorgLinkTrainTests extends TestSuite {

  def init(dut: LinkTrainHarness, extraSlaveResetCycles: Int): Unit = {
    dut.io.linkFast.poke(false.B)
    dut.io.slaveReset.poke(true.B)
    dut.reset.poke(true.B)
    dut.clock.step(6)
    dut.reset.poke(false.B)
    // Hold the slave in reset longer, so its divider starts at a different phase.
    dut.clock.step(extraSlaveResetCycles)
    dut.io.slaveReset.poke(false.B)
  }

  /** Step until link_up, returning the number of cycles taken (-1 if it never rose). */
  def waitForLock(dut: LinkTrainHarness, limit: Int = 400): Int = {
    var c = 0
    while (c < limit && !dut.io.linkUp.peek().litToBoolean) {
      dut.clock.step(1)
      c += 1
    }
    if (dut.io.linkUp.peek().litToBoolean) c else -1
  }

  /** Collect (masterBeat, slaveBeat) over n cycles once locked. */
  def sampleBeats(dut: LinkTrainHarness, n: Int): Seq[(Boolean, Boolean)] =
    (0 until n).map { _ =>
      val m = dut.io.masterBeat.peek().litToBoolean
      val s = dut.io.slaveBeat.peek().litToBoolean
      dut.clock.step(1)
      (m, s)
    }

  val tests = Tests {

    utest.test("locks_from_either_starting_phase") {
      // Both parities of initial offset must converge; on silicon we do not get to
      // choose which one reset happens to produce.
      for (offset <- Seq(0, 1, 2, 3)) {
        val p = LinkParams(divLog2 = 1, trainBeats = 8)
        simulate(new LinkTrainHarness(p)) { dut =>
          init(dut, offset)
          val cycles = waitForLock(dut)
          utest.assert(cycles > 0)
        }
      }
    }

    utest.test("locks_mid_eye_not_on_the_edge") {
      // At N=2 a correctly trained slave beats on the opposite core cycle from the
      // master. Equal phase would mean sampling right at the pin transition.
      for (offset <- Seq(0, 1)) {
        val p = LinkParams(divLog2 = 1, trainBeats = 8)
        simulate(new LinkTrainHarness(p)) { dut =>
          init(dut, offset)
          utest.assert(waitForLock(dut) > 0)
          dut.clock.step(4) // let it settle past the lock edge
          val beats = sampleBeats(dut, 24)
          // Each side beats exactly every other cycle...
          utest.assert(beats.count(_._1) == 12)
          utest.assert(beats.count(_._2) == 12)
          // ...and never on the same cycle as the other.
          utest.assert(beats.forall { case (m, s) => m != s })
        }
      }
    }

    utest.test("stays_locked") {
      val p = LinkParams(divLog2 = 1, trainBeats = 8)
      simulate(new LinkTrainHarness(p)) { dut =>
        init(dut, 1)
        utest.assert(waitForLock(dut) > 0)
        dut.clock.step(4)
        val beats = sampleBeats(dut, 200)
        utest.assert(beats.forall { case (m, s) => m != s })
        utest.assert(dut.io.linkUp.peek().litToBoolean)
      }
    }

    utest.test("div4_locks_to_a_stable_phase") {
      // Note the slave's beat is NOT required to differ from the master's here.
      // The eye that matters is on the slave's captured `inD`, two flops
      // downstream of the pins, so the correct mid-eye placement at N=4 happens to
      // coincide with the master's beat -- whereas at N=2 it lands on the opposite
      // cycle.  Asserting a specific offset would just be hand-derived trivia; the
      // property worth guarding is that the offset is *stable*.
      for (offset <- Seq(0, 1, 2, 3)) {
        val p = LinkParams(divLog2 = 2, trainBeats = 8)
        simulate(new LinkTrainHarness(p)) { dut =>
          init(dut, offset)
          utest.assert(waitForLock(dut) > 0)
          dut.clock.step(8)
          val beats = sampleBeats(dut, 40)
          utest.assert(beats.count(_._1) == 10)
          utest.assert(beats.count(_._2) == 10)

          val mCycles = beats.zipWithIndex.collect { case ((true, _), i) => i }
          val sCycles = beats.zipWithIndex.collect { case ((_, true), i) => i }
          // Both tick every N cycles...
          utest.assert(mCycles.sliding(2).forall(w => w(1) - w(0) == 4))
          utest.assert(sCycles.sliding(2).forall(w => w(1) - w(0) == 4))
          // ...at one fixed offset, the same one no matter where the slave started.
          val offsets = mCycles.zip(sCycles).map { case (m, s) => ((s - m) % 4 + 4) % 4 }
          utest.assert(offsets.distinct.length == 1)
        }
      }
    }

    utest.test("link_fast_strap_makes_training_a_no_op") {
      // N=1: every cycle is a beat, there is no phase to recover, and link_up must
      // still come up so the strap is actually usable post-silicon.
      val p = LinkParams(divLog2 = 1, trainBeats = 8)
      simulate(new LinkTrainHarness(p)) { dut =>
        dut.io.linkFast.poke(true.B)
        dut.io.slaveReset.poke(true.B)
        dut.reset.poke(true.B)
        dut.clock.step(6)
        dut.reset.poke(false.B)
        dut.io.slaveReset.poke(false.B)
        utest.assert(waitForLock(dut) > 0)
        val beats = sampleBeats(dut, 16)
        utest.assert(beats.forall { case (m, s) => m && s })
      }
    }
  }
}
