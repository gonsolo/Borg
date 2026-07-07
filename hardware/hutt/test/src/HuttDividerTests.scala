// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.simulator.EphemeralSimulator._
import utest._

object HuttDividerTests extends TestSuite {
  val WIDTH = 64
  val MASK  = (BigInt(1) << WIDTH) - 1

  // Runs one division to completion and returns (quotient, remainder).
  def divide(dut: HuttDivider, dividend: BigInt, divisor: BigInt): (BigInt, BigInt) = {
    dut.io.dividend.poke(dividend.U(WIDTH.W))
    dut.io.divisor.poke(divisor.U(WIDTH.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)

    var steps = 0
    while (!dut.io.done.peek().litToBoolean) {
      assert(dut.io.busy.peek().litToBoolean)
      dut.clock.step(1)
      steps += 1
      if (steps > WIDTH + 4)
        throw new RuntimeException(s"divider never asserted done after $steps steps")
    }
    val q = dut.io.quotient.peek().litValue
    val r = dut.io.remainder.peek().litValue
    dut.clock.step(1)  // let done's one-cycle pulse pass, back to idle
    (q, r)
  }

  val tests = Tests {
    test("simple division") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q, r) = divide(dut, 100, 7)
        assert(q == 14)
        assert(r == 2)
      }
    }

    test("exact division, no remainder") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q, r) = divide(dut, 42, 6)
        assert(q == 7)
        assert(r == 0)
      }
    }

    test("dividend smaller than divisor") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q, r) = divide(dut, 3, 100)
        assert(q == 0)
        assert(r == 3)
      }
    }

    test("divide by one") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q, r) = divide(dut, 123456789L, 1)
        assert(q == 123456789L)
        assert(r == 0)
      }
    }

    test("dividend equals divisor") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q, r) = divide(dut, 999999, 999999)
        assert(q == 1)
        assert(r == 0)
      }
    }

    test("zero dividend") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q, r) = divide(dut, 0, 42)
        assert(q == 0)
        assert(r == 0)
      }
    }

    test("large 64-bit magnitudes near the width boundary") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val dividend = MASK          // all-ones, largest 64-bit unsigned value
        val divisor  = BigInt(3)
        val (q, r) = divide(dut, dividend, divisor)
        assert(q == dividend / divisor)
        assert(r == dividend % divisor)
      }
    }

    test("divisor is the max 64-bit value") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val dividend = BigInt(5)
        val divisor  = MASK
        val (q, r) = divide(dut, dividend, divisor)
        assert(q == 0)
        assert(r == 5)
      }
    }

    test("both operands at the 64-bit max") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q, r) = divide(dut, MASK, MASK)
        assert(q == 1)
        assert(r == 0)
      }
    }

    test("pseudo-random sweep matches BigInt division") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val rnd = new scala.util.Random(42)
        for (_ <- 0 until 30) {
          val dividend = BigInt(WIDTH, rnd) & MASK
          var divisor  = BigInt(WIDTH, rnd) & MASK
          if (divisor == 0) divisor = 1
          val (q, r) = divide(dut, dividend, divisor)
          assert(q == dividend / divisor)
          assert(r == dividend % divisor)
        }
      }
    }

    test("back-to-back divisions produce independent correct results") {
      simulate(new HuttDivider(WIDTH)) { dut =>
        val (q1, r1) = divide(dut, 100, 7)
        assert(q1 == 14 && r1 == 2)
        val (q2, r2) = divide(dut, 55, 5)
        assert(q2 == 11 && r2 == 0)
        val (q3, r3) = divide(dut, 7, 100)
        assert(q3 == 0 && r3 == 7)
      }
    }
  }
}
