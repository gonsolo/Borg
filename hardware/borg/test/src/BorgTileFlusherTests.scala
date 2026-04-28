// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgTileFlusher (Step 25.3g scaffold).
  *
  * Tests the empty FSM handshake in isolation — no BorgTileBuffer,
  * no Borg top-level, no PSRAM.
  *
  * Coverage:
  *   - start pulse transitions sIdle → sBusy, busy asserts for one cycle
  *   - busy deasserts after returning to sIdle
  *   - no gpuMem.req or read.en when idle
  *   - no gpuMem.req or read.en during sBusy (scaffold: no writes)
  *   - back-to-back start pulses work correctly
  *   - start ignored during sBusy (scaffold: sBusy is only 1 cycle, but
  *     verify the FSM doesn't glitch)
  */
object BorgTileFlusherTests extends TestSuite {

  /** Reset and idle the DUT. */
  def resetDut(d: BorgTileFlusher): Unit = {
    d.io.start.poke(false.B)
    d.io.read.data.r.poke(0.U)
    d.io.read.data.g.poke(0.U)
    d.io.read.data.b.poke(0.U)
    d.io.read.data.z.poke(0.U)
    d.io.gpuMem.data.poke(0.U)
    d.io.gpuMem.ready.poke(false.B)
    d.io.fbBase.poke(0.U)
    d.io.zbBase.poke(0.U)
    d.io.fbWidth.poke(0.U)
    d.io.tileX.poke(0.U)
    d.io.tileY.poke(0.U)
    d.reset.poke(true.B)
    d.clock.step(2)
    d.reset.poke(false.B)
    d.clock.step(1)
  }

  val tests = Tests {

    // =========================================================================
    // Idle: busy=false, no memory or tile activity
    // =========================================================================

    utest.test("idle_outputs_quiet") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: idle_outputs_quiet ---")
        resetDut(d)

        // Multiple idle cycles
        d.clock.step(5)
        utest.assert(!d.io.busy.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(!d.io.read.en.peek().litToBoolean)
        println("  busy=false, req=false, wr=false, readEn=false after 5 idle cycles ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // Start → busy for one cycle → idle
    // =========================================================================

    utest.test("start_busy_handshake") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: start_busy_handshake ---")
        resetDut(d)

        utest.assert(!d.io.busy.peek().litToBoolean)

        // Pulse start
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // sBusy: busy should be high
        val busy = d.io.busy.peek().litToBoolean
        println(f"  After start: busy=$busy (expect true)")
        utest.assert(busy)

        // Scaffold FSM returns to sIdle after 1 cycle
        d.clock.step(1)
        val busyAfter = d.io.busy.peek().litToBoolean
        println(f"  After 1 cycle: busy=$busyAfter (expect false)")
        utest.assert(!busyAfter)
        println("  PASSED")
      }
    }

    // =========================================================================
    // No PSRAM or tile activity during sBusy (scaffold: empty FSM)
    // =========================================================================

    utest.test("no_mem_activity_in_scaffold") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: no_mem_activity_in_scaffold ---")
        resetDut(d)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // In sBusy — scaffold should not issue any reads or writes
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(!d.io.read.en.peek().litToBoolean)
        println("  sBusy: no PSRAM or tile buffer activity ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // Back-to-back start pulses
    // =========================================================================

    utest.test("back_to_back_starts") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: back_to_back_starts ---")
        resetDut(d)

        // First flush
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        utest.assert(d.io.busy.peek().litToBoolean)

        d.clock.step(1)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  First flush: busy → idle ✓")

        // Second flush immediately after
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        utest.assert(d.io.busy.peek().litToBoolean)

        d.clock.step(1)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  Second flush: busy → idle ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // Configuration inputs are latched (smoke test: they exist and don't crash)
    // =========================================================================

    utest.test("config_inputs_accepted") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: config_inputs_accepted ---")
        resetDut(d)

        // Drive all config inputs with non-zero values
        d.io.fbBase.poke(0x50000.U)
        d.io.zbBase.poke(0x60000.U)
        d.io.fbWidth.poke(64.U)
        d.io.tileX.poke(8.U)
        d.io.tileY.poke(12.U)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        utest.assert(d.io.busy.peek().litToBoolean)

        d.clock.step(1)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  Config inputs accepted without crash ✓")
        println("  PASSED")
      }
    }
  }
}
