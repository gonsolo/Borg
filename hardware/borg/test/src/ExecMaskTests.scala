// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._

/** The counters give the masks a stack of enclosing masks would, at any depth. */
object ExecMaskTests extends TestSuite {
  val tests = Tests {
    utest.test("counters_match_a_mask_stack_40_deep") {
      simulate(new ExecMask(4, 2)) { m =>
        val rnd = new scala.util.Random(7)
        val all = 15
        // Per slot: the mask and the stack of enclosing masks.
        val masks = Array(all, all)
        val stacks = Array(List.empty[Int], List.empty[Int])
        def idle(): Unit = {
          m.io.push.poke(false.B); m.io.els.poke(false.B); m.io.pop.poke(false.B)
          m.io.start.valid.poke(false.B)
        }
        idle()
        m.reset.poke(true.B); m.clock.step(); m.reset.poke(false.B)
        for (_ <- 0 until 3000) {
          val s = rnd.nextInt(2)
          m.io.slot.poke(s.U)
          val d = stacks(s).size
          rnd.nextInt(10) match {
            case k if k < 4 && d < 40 =>
              val c = rnd.nextInt(16)
              (0 until 4).foreach(i => m.io.cond(i).poke((c >> i & 1).U))
              m.io.push.poke(true.B)
              stacks(s) = masks(s) :: stacks(s); masks(s) &= c
            case k if k < 6 && d > 0 =>
              m.io.els.poke(true.B)
              masks(s) = stacks(s).head & ~masks(s) & all
            case _ if d > 0 =>
              m.io.pop.poke(true.B)
              masks(s) = stacks(s).head; stacks(s) = stacks(s).tail
            case _ =>
          }
          m.clock.step(); idle()
          for (t <- 0 until 2) utest.assert(m.io.mask(t).peek().litValue == masks(t))
        }
        utest.assert(!m.io.fault.peek().litToBoolean)
        // A pop with nothing pushed is a fault.
        while (stacks(0).nonEmpty) { m.io.slot.poke(0.U); m.io.pop.poke(true.B); m.clock.step(); idle(); stacks(0) = stacks(0).tail }
        m.io.slot.poke(0.U); m.io.pop.poke(true.B); m.clock.step(); idle()
        utest.assert(m.io.fault.peek().litToBoolean)
      }
    }
    utest.test("a_barrier_resume_starts_inside_its_levels") {
      simulate(new ExecMask(4, 2)) { m =>
        m.io.push.poke(false.B); m.io.els.poke(false.B); m.io.pop.poke(false.B); m.io.slot.poke(0.U); m.io.startSlot.poke(0.U)
        m.reset.poke(true.B); m.clock.step(); m.reset.poke(false.B)
        m.io.start.valid.poke(true.B); m.io.start.bits.poke(7.U); m.io.startDepth.poke(2.U); m.clock.step()
        m.io.start.valid.poke(false.B)
        utest.assert(m.io.mask(0).peek().litValue == 7)
        utest.assert(m.io.depth(0).peek().litValue == 2)
        (0 until 4).foreach(i => m.io.cond(i).poke((i & 1).U))
        m.io.push.poke(true.B); m.clock.step(); m.io.push.poke(false.B)
        utest.assert(m.io.mask(0).peek().litValue == 2)
        m.io.els.poke(true.B); m.clock.step(); m.io.els.poke(false.B)
        utest.assert(m.io.mask(0).peek().litValue == 5)
        m.io.pop.poke(true.B); m.clock.step(3); m.io.pop.poke(false.B)
        utest.assert(m.io.mask(0).peek().litValue == 7)
        utest.assert(!m.io.fault.peek().litToBoolean)
      }
    }
  }
}
