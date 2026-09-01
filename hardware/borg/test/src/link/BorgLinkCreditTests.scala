// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Credit flow-control tests.
  *
  * Credits exist because `Decoupled`'s combinational `ready`/`valid` backpressure
  * cannot survive an off-chip hop.  The two properties that matter are that the
  * sender never launches a packet it has no credit for (checked by an assertion
  * inside [[CreditCounter]] itself), and that the count never drifts -- a lost or
  * double-counted credit would deadlock or overflow the link with no way to
  * recover short of a reset.
  */
object BorgLinkCreditTests extends TestSuite {

  def init(dut: CreditCounter): Unit = {
    dut.io.consume.poke(false.B)
    dut.io.returnPin.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(4)
    dut.reset.poke(false.B)
    dut.clock.step(1)
  }

  def count(dut: CreditCounter): Int = dut.io.count.peek().litValue.toInt

  val tests = Tests {

    utest.test("starts_full") {
      simulate(new CreditCounter(2)) { dut =>
        init(dut)
        utest.assert(count(dut) == 2)
        utest.assert(dut.io.available.peek().litToBoolean)
      }
    }

    utest.test("consume_drains_and_blocks") {
      simulate(new CreditCounter(2)) { dut =>
        init(dut)
        for (_ <- 0 until 2) {
          utest.assert(dut.io.available.peek().litToBoolean)
          dut.io.consume.poke(true.B)
          dut.clock.step(1)
        }
        dut.io.consume.poke(false.B)
        dut.clock.step(1)
        utest.assert(count(dut) == 0)
        utest.assert(!dut.io.available.peek().litToBoolean)
      }
    }

    utest.test("toggle_edge_returns_one_credit") {
      // Either edge counts: the line is a toggle, not a pulse, precisely so it
      // cannot be missed or double-counted across the beat/core rate difference.
      simulate(new CreditCounter(2)) { dut =>
        init(dut)
        dut.io.consume.poke(true.B)
        dut.clock.step(2)
        dut.io.consume.poke(false.B)
        dut.clock.step(1)
        utest.assert(count(dut) == 0)

        // Rising edge -> +1
        dut.io.returnPin.poke(true.B)
        dut.clock.step(6)
        utest.assert(count(dut) == 1)

        // Falling edge -> +1 as well
        dut.io.returnPin.poke(false.B)
        dut.clock.step(6)
        utest.assert(count(dut) == 2)
      }
    }

    utest.test("simultaneous_consume_and_return_nets_out") {
      // A return and a consume landing on the same cycle must net to zero change,
      // not let one clobber the other.
      simulate(new CreditCounter(4)) { dut =>
        init(dut)
        var pin = false
        // Drain one so we can observe a net-zero cycle away from the ceiling.
        dut.io.consume.poke(true.B)
        dut.clock.step(1)
        dut.io.consume.poke(false.B)
        dut.clock.step(4)
        utest.assert(count(dut) == 3)

        // Toggle the return line and consume on the cycle the edge is observed.
        pin = !pin
        dut.io.returnPin.poke(pin.B)
        // Sync2 is two flops, plus one for the edge detect.
        dut.clock.step(2)
        dut.io.consume.poke(true.B)
        dut.clock.step(1)
        dut.io.consume.poke(false.B)
        dut.clock.step(4)
        utest.assert(count(dut) == 3)
      }
    }

    utest.test("long_random_stream_never_drifts") {
      // The invariant that matters over time: credits returned minus credits spent
      // is exactly the count, for a long adversarial interleaving.
      simulate(new CreditCounter(2)) { dut =>
        init(dut)
        val rng = new scala.util.Random(0xb019)
        var pin = false
        var expected = 2
        var pendingReturns = 0

        for (_ <- 0 until 20000) {
          // Consume only when a credit is actually held -- the RTL asserts on
          // spending one it does not have, which is itself part of the contract.
          val doConsume = expected > 0 && rng.nextInt(3) == 0
          dut.io.consume.poke(doConsume.B)

          // Occasionally toggle the return line, but only for outstanding packets.
          val outstanding = 2 - expected - pendingReturns
          val doReturn = outstanding > 0 && rng.nextInt(4) == 0
          if (doReturn) {
            pin = !pin
            dut.io.returnPin.poke(pin.B)
            pendingReturns += 1
          }

          dut.clock.step(1)
          if (doConsume) expected -= 1

          // Returns land a few cycles later through Sync2; settle and re-read.
          if (pendingReturns > 0) {
            dut.io.consume.poke(false.B)
            dut.clock.step(4)
            expected += pendingReturns
            pendingReturns = 0
            utest.assert(count(dut) == expected)
          }
        }
        utest.assert(count(dut) <= 2)
      }
    }
  }
}
