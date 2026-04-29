// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgTileFlusher.
  *
  * Step 25.3g scaffold tests: handshake, idle outputs, back-to-back.
  * Step 25.4.1 tests: pixel 0 read sequence, write addresses, ready stall,
  *                    busy deassertion, skip-unshaded pixel.
  */
object BorgTileFlusherTests extends TestSuite {

  // FP16_MAX_DEPTH — unshaded pixel sentinel
  val FP16_MAX_DEPTH = 0x7BFF

  /** Reset helper: drives all inputs to neutral, applies reset. */
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
    d.io.fbWidthLog2.poke(0.U)
    d.io.tileX.poke(0.U)
    d.io.tileY.poke(0.U)
    d.reset.poke(true.B)
    d.clock.step(2)
    d.reset.poke(false.B)
    d.clock.step(1)
  }

  val tests = Tests {

    // =========================================================================
    // Step 25.3g scaffold tests (retained)
    // =========================================================================

    utest.test("idle_outputs_quiet") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: idle_outputs_quiet ---")
        resetDut(d)

        d.clock.step(5)
        utest.assert(!d.io.busy.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(!d.io.read.en.peek().litToBoolean)
        println("  busy=false, req=false, wr=false, readEn=false ✓")
        println("  PASSED")
      }
    }

    utest.test("start_busy_handshake") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: start_busy_handshake ---")
        resetDut(d)

        utest.assert(!d.io.busy.peek().litToBoolean)

        // Pulse start; provide unshaded Z so flusher skips writes and returns quickly
        d.io.read.data.z.poke(FP16_MAX_DEPTH.U)
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // sReadTile: busy should be high
        val busy = d.io.busy.peek().litToBoolean
        println(f"  After start: busy=$busy (expect true)")
        utest.assert(busy)

        // sWaitBram, sLatchData (z >= MAX → skip writes), sIdle
        d.clock.step(3)
        val busyAfter = d.io.busy.peek().litToBoolean
        println(f"  After skip path: busy=$busyAfter (expect false)")
        utest.assert(!busyAfter)
        println("  PASSED")
      }
    }

    utest.test("back_to_back_starts") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: back_to_back_starts ---")
        resetDut(d)
        d.io.read.data.z.poke(FP16_MAX_DEPTH.U)  // skip writes

        // First flush
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        utest.assert(d.io.busy.peek().litToBoolean)
        d.clock.step(3)  // sWaitBram + sLatchData + skip → sIdle
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  First flush: busy → idle ✓")

        // Second flush immediately after
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        utest.assert(d.io.busy.peek().litToBoolean)
        d.clock.step(3)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  Second flush: busy → idle ✓")
        println("  PASSED")
      }
    }

    utest.test("config_inputs_accepted") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: config_inputs_accepted ---")
        resetDut(d)
        d.io.read.data.z.poke(FP16_MAX_DEPTH.U)

        d.io.fbBase.poke(0x80080.U)
        d.io.zbBase.poke(0x86080.U)
        d.io.fbWidthLog2.poke(5.U)  // log2(32)
        d.io.tileX.poke(8.U)
        d.io.tileY.poke(12.U)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        utest.assert(d.io.busy.peek().litToBoolean)

        d.clock.step(3)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  Config inputs accepted without crash ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // Step 25.4.1 tests
    // =========================================================================

    // Test: flusher asserts read.en=1 with read.idx=0, then deasserts
    utest.test("single_pixel_read_sequence") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: single_pixel_read_sequence ---")
        resetDut(d)
        d.io.read.data.z.poke(FP16_MAX_DEPTH.U)  // skip writes

        // Idle: read.en must be false
        utest.assert(!d.io.read.en.peek().litToBoolean)

        // Start
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // sReadTile: read.en=1, read.idx=0
        val enHigh = d.io.read.en.peek().litToBoolean
        println(f"  sReadTile: read.en=$enHigh (expect true), read.idx=${d.io.read.idx.peek().litValue} (expect 0)")
        utest.assert(enHigh)
        utest.assert(d.io.read.idx.peek().litValue == 0)  // idx=0

        // sWaitBram: read.en must be back to false
        d.clock.step(1)
        val enLow = d.io.read.en.peek().litToBoolean
        println(f"  sWaitBram: read.en=$enLow (expect false)")
        utest.assert(!enLow)

        println("  PASSED")
      }
    }

    // Test: correct byte addresses issued for the 4 writes (R, G, B, Z)
    // Config: fbBase=0x80080, zbBase=0x86080, fbWidthLog2=5 (width=32), tileX=4, tileY=0
    //   pixel_off = (0 << 5) + 4 = 4
    //   fb_addr   = 0x80080 + 4*12 = 0x80080 + 48 = 0x800B0
    //   zb_addr   = 0x86080 + 4*4  = 0x86080 + 16 = 0x86090
    //
    // FSM path for shaded pixel (z < FP16_MAX_DEPTH, depth test passes):
    //   sReadTile → sWaitBram → sLatchData → sReadOldZ → sWriteR → sWriteG → sWriteB → sWriteZ → sIdle
    //   sReadOldZ reads old_z from PSRAM; if new_z >= old_z the write is skipped.
    utest.test("single_pixel_write_sequence") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: single_pixel_write_sequence ---")
        resetDut(d)

        val fbBase     = 0x80080
        val zbBase     = 0x86080
        val fbWidthLog2 = 5  // log2(32)
        val tileX      = 4
        val tileY      = 0

        d.io.fbBase.poke(fbBase.U)
        d.io.zbBase.poke(zbBase.U)
        d.io.fbWidthLog2.poke(fbWidthLog2.U)
        d.io.tileX.poke(tileX.U)
        d.io.tileY.poke(tileY.U)

        // Pixel data
        d.io.read.data.r.poke(0x3C00.U)
        d.io.read.data.g.poke(0x4000.U)
        d.io.read.data.b.poke(0x4200.U)
        d.io.read.data.z.poke(0x1234.U)  // valid Z (< FP16_MAX_DEPTH)

        // Expected addresses
        val pixel_off = (tileY << fbWidthLog2) + tileX  // = 4
        val fb_addr   = fbBase + pixel_off * 12         // = 0x800B0
        val zb_addr   = zbBase + pixel_off * 4          // = 0x86090
        println(f"  pixel_off=$pixel_off, fb_addr=0x${fb_addr}%X, zb_addr=0x${zb_addr}%X")

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // sReadTile (1 cycle)
        d.clock.step(1)
        // sWaitBram (1 cycle) — read.data is being latched
        d.clock.step(1)
        // sLatchData — data now valid, addresses computed, → sReadOldZ
        d.clock.step(1)

        // sReadOldZ: flusher reads existing Z from PSRAM for depth test.
        // Verify req is asserted at zb_addr, then provide old_z > new_z so depth passes.
        utest.assert(d.io.gpuMem.req.peek().litToBoolean)
        utest.assert(d.io.gpuMem.addr.peek().litValue.toInt == zb_addr)
        println(f"  sReadOldZ: req=true, addr=0x${zb_addr}%X ✓")
        // old_z=0x7000 > new_z=0x1234 → depth test passes, proceed to writes
        d.io.gpuMem.data.poke(0x00007000L.U)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)
        d.io.gpuMem.data.poke(0.U)
        // now in sWriteR

        // Helper: drive ready 1 cycle after wr asserted
        def doWrite(name: String, expectedAddr: Int, expectedData: Int): Unit = {
          d.clock.step(1)
          val wr   = d.io.gpuMem.wr.peek().litToBoolean
          val addr = d.io.gpuMem.addr.peek().litValue.toInt
          val data = d.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF
          println(f"  $name: wr=$wr addr=0x${addr}%X data=0x${data}%X (exp addr=0x${expectedAddr}%X data=0x${expectedData}%X)")
          utest.assert(wr)
          utest.assert(addr == expectedAddr)
          utest.assert(data == expectedData)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)
        }

        doWrite("R", fb_addr,     0x3C00)
        doWrite("G", fb_addr + 4, 0x4000)
        doWrite("B", fb_addr + 8, 0x4200)
        doWrite("Z", zb_addr,     0x1234)

        // Should now be idle
        d.clock.step(1)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  PASSED")
      }
    }

    // Test: flusher stays in write state when ready is withheld
    utest.test("write_waits_for_ready") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: write_waits_for_ready ---")
        resetDut(d)

        val zbBase = 0x86080
        d.io.fbBase.poke(0x80080.U)
        d.io.zbBase.poke(zbBase.U)
        d.io.fbWidthLog2.poke(5.U)
        d.io.tileX.poke(0.U)
        d.io.tileY.poke(0.U)
        d.io.read.data.r.poke(0x1111.U)
        d.io.read.data.g.poke(0x2222.U)
        d.io.read.data.b.poke(0x3333.U)
        d.io.read.data.z.poke(0x4444.U)  // valid Z

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // sReadTile + sWaitBram + sLatchData → sReadOldZ
        d.clock.step(3)

        // sReadOldZ: handle the depth-test PSRAM read.
        // Provide old_z=FP16_MAX_DEPTH so depth test passes (new_z=0x4444 < 0x7BFF).
        utest.assert(d.io.gpuMem.req.peek().litToBoolean)
        d.io.gpuMem.data.poke(0x00007BFFL.U)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)
        d.io.gpuMem.data.poke(0.U)
        // now in sWriteR

        // Hold ready=0 for 4 cycles; verify wr stays high and busy stays high
        d.io.gpuMem.ready.poke(false.B)
        for (i <- 0 until 4) {
          d.clock.step(1)
          utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
          utest.assert(d.io.busy.peek().litToBoolean)
        }
        println("  Held in sWriteR for 4 cycles ✓")
        println("  PASSED")
      }
    }

    // Test: busy deasserts exactly when last (Z) write's ready pulses
    utest.test("busy_deasserts_after_last_write") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: busy_deasserts_after_last_write ---")
        resetDut(d)

        d.io.fbBase.poke(0x80080.U)
        d.io.zbBase.poke(0x86080.U)
        d.io.fbWidthLog2.poke(5.U)
        d.io.read.data.r.poke(0x1111.U)
        d.io.read.data.g.poke(0x2222.U)
        d.io.read.data.b.poke(0x3333.U)
        d.io.read.data.z.poke(0x4444.U)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // sReadTile + sWaitBram + sLatchData → sReadOldZ
        d.clock.step(3)

        // sReadOldZ: handle the depth-test PSRAM read.
        // old_z=0x7BFF > new_z=0x4444 → depth test passes.
        utest.assert(d.io.gpuMem.req.peek().litToBoolean)
        d.io.gpuMem.data.poke(0x00007BFFL.U)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)
        d.io.gpuMem.data.poke(0.U)
        // now in sWriteR

        // Drive through R, G, B with ready
        for (_ <- 0 until 3) {
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)
          d.clock.step(1)
        }

        // Now in sWriteZ — busy must still be high
        utest.assert(d.io.busy.peek().litToBoolean)
        println("  In sWriteZ: busy=true ✓")

        // Pulse ready → should go to sIdle next cycle
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        // Now in sIdle
        val busyAfter = d.io.busy.peek().litToBoolean
        println(f"  After Z write ready: busy=$busyAfter (expect false)")
        utest.assert(!busyAfter)
        println("  PASSED")
      }
    }

    // Test: unshaded pixel (Z >= FP16_MAX_DEPTH) is skipped — no PSRAM writes
    utest.test("skip_unshaded_pixel") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: skip_unshaded_pixel ---")
        resetDut(d)

        d.io.fbBase.poke(0x80080.U)
        d.io.zbBase.poke(0x86080.U)
        d.io.fbWidthLog2.poke(5.U)
        d.io.read.data.z.poke(FP16_MAX_DEPTH.U)  // unshaded

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // Run for enough cycles to reach sIdle via skip path (sReadTile+sWaitBram+sLatchData+sIdle)
        var wrEverHigh = false
        var reqEverHigh = false
        for (_ <- 0 until 8) {
          d.clock.step(1)
          if (d.io.gpuMem.wr.peek().litToBoolean)  wrEverHigh  = true
          if (d.io.gpuMem.req.peek().litToBoolean) reqEverHigh = true
        }

        utest.assert(!wrEverHigh)
        utest.assert(!reqEverHigh)  // no PSRAM read either (skipped before sReadOldZ)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  No PSRAM writes/reads for unshaded pixel ✓")
        println("  PASSED")
      }
    }

    // Test: depth test fail — new_z >= old_z → no PSRAM writes
    utest.test("depth_test_fail_skips_write") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: depth_test_fail_skips_write ---")
        resetDut(d)

        d.io.fbBase.poke(0x80080.U)
        d.io.zbBase.poke(0x86080.U)
        d.io.fbWidthLog2.poke(5.U)
        d.io.read.data.r.poke(0x1111.U)
        d.io.read.data.g.poke(0x2222.U)
        d.io.read.data.b.poke(0x3333.U)
        d.io.read.data.z.poke(0x4444.U)  // new_z

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // sReadTile + sWaitBram + sLatchData → sReadOldZ
        d.clock.step(3)

        // sReadOldZ: provide old_z <= new_z → depth test fails, no writes
        utest.assert(d.io.gpuMem.req.peek().litToBoolean)
        d.io.gpuMem.data.poke(0x00001000L.U)  // old_z=0x1000 < new_z=0x4444 → fail
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)
        d.io.gpuMem.data.poke(0.U)
        // should jump to sIdle — no writes

        var wrEverHigh = false
        for (_ <- 0 until 8) {
          d.clock.step(1)
          if (d.io.gpuMem.wr.peek().litToBoolean) wrEverHigh = true
        }
        utest.assert(!wrEverHigh)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  Depth test fail: no writes, returned to idle ✓")
        println("  PASSED")
      }
    }
  }
}
