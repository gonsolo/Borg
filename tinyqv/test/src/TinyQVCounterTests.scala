// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object TinyQVCounterTests extends TestSuite {

  def runCounterTest(outputWidth: Int)(testFn: TinyQVCounter => Unit): Unit = {
    simulate(new TinyQVCounter(outputWidth)) { dut =>
      testFn(dut)
    }
  }

  val tests = Tests {
    utest.test("mcycle") {
      runCounterTest(4) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(1)
        dut.reset.poke(false.B)
        dut.add.poke(true.B)
        
        def peekVal32(): Long = {
          var value: Long = 0
          for (i <- 0 until 8) {
            dut.counter.poke(i.U)
            value |= (dut.data.peek().litValue.toLong << (i * 4))
            dut.clock.step(1)
          }
          value
        }

        // Initially 0
        utest.assert(peekVal32() == 0L)
        
        // After 1 rotation, should be 1
        // Note: The peekVal32 helper already steps 8 times.
        for (i <- 1 to 20) {
          utest.assert(peekVal32() == i.toLong)
        }
      }
    }

    utest.test("minstret") {
      runCounterTest(4) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(1)
        dut.reset.poke(false.B)
        dut.add.poke(false.B)

        def stepRotation(add: Boolean): Long = {
          var value: Long = 0
          for (i <- 0 until 8) {
            dut.counter.poke(i.U)
            if (i == 0) dut.add.poke(add.B)
            else dut.add.poke(false.B)
            value |= (dut.data.peek().litValue.toLong << (i * 4))
            dut.clock.step(1)
          }
          value
        }

        var retired: Long = 0
        val rnd = new scala.util.Random(42)

        for (i <- 0 until 100) {
          val currentVal = stepRotation(false) // Just peek
          utest.assert(currentVal == retired)

          val x = rnd.nextInt(2)
          stepRotation(x == 1) // Increment
          retired = (retired + x) & 0xFFFFFFFFL
        }
      }
    }
  }
}
