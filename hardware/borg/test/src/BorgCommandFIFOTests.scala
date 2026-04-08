// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgCommandFIFOTests extends TestSuite {

  val tests = Tests {

    utest.test("enqueue_and_dequeue_properly") {
      simulate(new BorgCommandFIFO(entries = 4)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        
        // Initialize inputs
        dut.io.enq.valid.poke(false.B)
        dut.io.deq.ready.poke(false.B)
        dut.clock.step()
        
        // FIFO should be empty initially
        utest.assert(!dut.io.deq.valid.peek().litToBoolean)
        utest.assert(dut.io.enq.ready.peek().litToBoolean)

        // 1. Enqueue first command
        dut.io.enq.valid.poke(true.B)
        dut.io.enq.bits.uniformPage.poke(0.U)
        dut.io.enq.bits.fragPC.poke(42.U)
        dut.io.enq.bits.bbox.min.x.poke(0.U)
        dut.io.enq.bits.bbox.min.y.poke(0.U)
        dut.io.enq.bits.bbox.max.x.poke(16.U)
        dut.io.enq.bits.bbox.max.y.poke(16.U)
        dut.clock.step()
        
        dut.io.enq.valid.poke(false.B)
        
        // Verify valid data propagates to output
        utest.assert(dut.io.deq.valid.peek().litToBoolean)
        utest.assert(dut.io.deq.bits.uniformPage.peek().litValue == 0)
        utest.assert(dut.io.deq.bits.fragPC.peek().litValue == 42)
        utest.assert(dut.io.deq.bits.bbox.max.x.peek().litValue == 16)
      }
    }

    utest.test("handle_backpressure") {
      simulate(new BorgCommandFIFO(entries = 4)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        
        dut.io.enq.valid.poke(false.B)
        dut.io.deq.ready.poke(false.B)
        
        // Enqueue 4 items
        for (i <- 0 until 4) {
          utest.assert(dut.io.enq.ready.peek().litToBoolean)
          dut.io.enq.valid.poke(true.B)
          dut.io.enq.bits.fragPC.poke(i.U)
          dut.clock.step()
        }
        
        // Now it should be full
        utest.assert(!dut.io.enq.ready.peek().litToBoolean)
        dut.io.enq.valid.poke(false.B)

        // Dequeue one
        dut.io.deq.ready.poke(true.B)
        utest.assert(dut.io.deq.valid.peek().litToBoolean)
        utest.assert(dut.io.deq.bits.fragPC.peek().litValue == 0)
        dut.clock.step()

        dut.io.deq.ready.poke(false.B)
        
        // Should have space for 1 now
        utest.assert(dut.io.enq.ready.peek().litToBoolean)
      }
    }
  }
}
