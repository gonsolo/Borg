// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgTileFlusher (Step 25.4.2 Option A — DMA architecture).
  *
  * The flusher is an 8-state DMA engine: reads 16 tile SRAM entries and
  * writes 64 PSRAM words (4 per entry: R, G, B, Z channels individually at
  * stride +2 bytes each).
  */
object BorgTileFlusherTests extends TestSuite {

  val FP16_MAX_DEPTH = 0x7BFF

  def resetDut(d: BorgTileFlusher): Unit = {
    d.reset.poke(true.B)
    d.clock.step(2)
    d.reset.poke(false.B)
    d.io.start.poke(false.B)
    d.io.read.data.r.poke(0.U)
    d.io.read.data.g.poke(0.U)
    d.io.read.data.b.poke(0.U)
    d.io.read.data.z.poke(0.U)
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.data.poke(0.U)
    d.io.tileBase.poke(0.U)
    d.clock.step(1)
  }

  // Drive one full entry through: 3 setup steps then 4 ready pulses (R/G/B/Z).
  def driveEntry(d: BorgTileFlusher): Unit = {
    d.clock.step(1)  // sReadSram → sWaitSram
    d.clock.step(1)  // sWaitSram → sWaitSram2
    d.clock.step(1)  // sWaitSram2 → sWriteR
    for (_ <- 0 until 4) {
      d.io.gpuMem.ready.poke(true.B)
      d.clock.step(1)
      d.io.gpuMem.ready.poke(false.B)
    }
  }

  val tests = Tests {

    // Test: idle → busy on start, returns to idle after 64 writes
    utest.test("start_busy_handshake") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: start_busy_handshake ---")
        resetDut(d)

        utest.assert(!d.io.busy.peek().litToBoolean)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        val busy = d.io.busy.peek().litToBoolean
        println(f"  After start: busy=$busy (expect true)")
        utest.assert(busy)

        // Drive ready for all 64 writes (4 per entry × 16 entries).
        var done = false
        for (_ <- 0 until 200) {
          if (d.io.gpuMem.wr.peek().litToBoolean) {
            d.io.gpuMem.ready.poke(true.B)
          } else {
            d.io.gpuMem.ready.poke(false.B)
          }
          d.clock.step(1)
          if (!d.io.busy.peek().litToBoolean && !done) {
            println("  Flusher returned to idle")
            d.io.gpuMem.ready.poke(false.B)
            done = true
          }
        }
        d.io.gpuMem.ready.poke(false.B)
        val busyAfter = d.io.busy.peek().litToBoolean
        println(f"  After run: busy=$busyAfter (expect false)")
        utest.assert(!busyAfter)
        println("  PASSED")
      }
    }

    // Test: idle when not started
    utest.test("idle_outputs") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: idle_outputs ---")
        resetDut(d)
        for (_ <- 0 until 5) { d.clock.step(1) }
        utest.assert(!d.io.busy.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        utest.assert(!d.io.read.en.peek().litToBoolean)
        println("  All idle outputs correct ✓")
        println("  PASSED")
      }
    }

    // Test: read.en asserts on sReadSram, read.idx sequences 0..15
    utest.test("sram_read_sequence") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: sram_read_sequence ---")
        resetDut(d)

        // Pulse start: sIdle → sReadSram
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // State is now sReadSram (entry 0): check outputs BEFORE next step

        for (expected_idx <- 0 until 16) {
          // In sReadSram: read.en=1, read.idx=expected_idx
          val en  = d.io.read.en.peek().litToBoolean
          val idx = d.io.read.idx.peek().litValue.toInt
          println(f"  entry $expected_idx: read.en=$en idx=$idx")
          utest.assert(en)
          utest.assert(idx == expected_idx)

          // sReadSram → sWaitSram → sWaitSram2 → sWriteR (3 plain steps)
          d.clock.step(1)  // → sWaitSram
          d.clock.step(1)  // → sWaitSram2
          d.clock.step(1)  // → sWriteR

          // sWriteR: ready → sWriteG
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // sWriteG: ready → sWriteB
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // sWriteB: ready → sWriteZ
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // sWriteZ: ready → sReadSram (next entry) or sIdle (last)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)
        }
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  All 16 entries read in order ✓")
        println("  PASSED")
      }
    }


    // Test: PSRAM write addresses are correct
    // tileBase=0x100, entry 0: R=0x100, G=0x102, B=0x104, Z=0x106
    //                  entry 1: R=0x108, G=0x10A, B=0x10C, Z=0x10E
    //                  ...
    //                  entry 15: R=0x178, G=0x17A, B=0x17C, Z=0x17E
    utest.test("write_addresses") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: write_addresses ---")
        resetDut(d)

        val tileBase = 0x100
        d.io.tileBase.poke(tileBase.U)

        // sIdle → sReadSram
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // Now in sReadSram (entry 0)

        for (entry <- 0 until 16) {
          // sReadSram → sWaitSram → sWaitSram2 → sWriteR (3 plain steps)
          d.clock.step(1)  // → sWaitSram
          d.clock.step(1)  // → sWaitSram2
          d.clock.step(1)  // → sWriteR

          // sWriteR: R at tileBase + entry*8
          val addrR = d.io.gpuMem.addr.peek().litValue.toInt
          val expR  = tileBase + entry * 8
          println(f"  entry $entry R: addr=0x$addrR%X (expect 0x$expR%X)")
          utest.assert(addrR == expR)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // sWriteG: G at tileBase + entry*8 + 2
          val addrG = d.io.gpuMem.addr.peek().litValue.toInt
          val expG  = tileBase + entry * 8 + 2
          println(f"  entry $entry G: addr=0x$addrG%X (expect 0x$expG%X)")
          utest.assert(addrG == expG)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // sWriteB: B at tileBase + entry*8 + 4
          val addrB = d.io.gpuMem.addr.peek().litValue.toInt
          val expB  = tileBase + entry * 8 + 4
          println(f"  entry $entry B: addr=0x$addrB%X (expect 0x$expB%X)")
          utest.assert(addrB == expB)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // sWriteZ: Z at tileBase + entry*8 + 6
          val addrZ = d.io.gpuMem.addr.peek().litValue.toInt
          val expZ  = tileBase + entry * 8 + 6
          println(f"  entry $entry Z: addr=0x$addrZ%X (expect 0x$expZ%X)")
          utest.assert(addrZ == expZ)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)
          // Now in sReadSram (next entry) or sIdle (if last)
        }
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  PASSED")
      }
    }


    // Test: write data matches tile SRAM entry (R, G, B, Z as individual 16-bit words)
    utest.test("write_data_matches_sram") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: write_data_matches_sram ---")
        resetDut(d)

        val r = 0x3C00; val g = 0x4000; val b = 0x4200; val z = 0x1234
        d.io.read.data.r.poke(r.U)
        d.io.read.data.g.poke(g.U)
        d.io.read.data.b.poke(b.U)
        d.io.read.data.z.poke(z.U)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // sReadSram → sWaitSram → sWaitSram2 → sWriteR (3 plain steps)
        d.clock.step(1)  // → sWaitSram
        d.clock.step(1)  // → sWaitSram2
        d.clock.step(1)  // → sWriteR

        val wdataR = d.io.gpuMem.wdata.peek().litValue.toLong
        println(f"  sWriteR wdata=0x$wdataR%X (expect 0x${r.toLong}%X)")
        utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(wdataR == r.toLong)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        val wdataG = d.io.gpuMem.wdata.peek().litValue.toLong
        println(f"  sWriteG wdata=0x$wdataG%X (expect 0x${g.toLong}%X)")
        utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(wdataG == g.toLong)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        val wdataB = d.io.gpuMem.wdata.peek().litValue.toLong
        println(f"  sWriteB wdata=0x$wdataB%X (expect 0x${b.toLong}%X)")
        utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(wdataB == b.toLong)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        val wdataZ = d.io.gpuMem.wdata.peek().litValue.toLong
        println(f"  sWriteZ wdata=0x$wdataZ%X (expect 0x${z.toLong}%X)")
        utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(wdataZ == z.toLong)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        println("  Write data channel check ✓")
        println("  PASSED")
      }
    }

    // Test: ready stall — wr stays high until ready
    utest.test("ready_stall") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: ready_stall ---")
        resetDut(d)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // sReadSram → sWaitSram → sWaitSram2 → sWriteR (3 plain steps)
        d.clock.step(1)  // → sWaitSram
        d.clock.step(1)  // → sWaitSram2
        d.clock.step(1)  // → sWriteR: hold ready=false for 5 cycles
        for (_ <- 0 until 5) {
          utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
          utest.assert(d.io.busy.peek().litToBoolean)
          d.clock.step(1)
        }
        println("  wr stays high during stall ✓")
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)
        println("  PASSED")
      }
    }

    // Test: back-to-back flushes
    utest.test("back_to_back_flushes") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: back_to_back_flushes ---")
        resetDut(d)

        def runFlush(): Unit = {
          d.io.start.poke(true.B)
          d.clock.step(1)
          d.io.start.poke(false.B)
          for (_ <- 0 until 300) {
            if (d.io.gpuMem.wr.peek().litToBoolean)
              d.io.gpuMem.ready.poke(true.B)
            else
              d.io.gpuMem.ready.poke(false.B)
            d.clock.step(1)
            if (!d.io.busy.peek().litToBoolean) {
              d.io.gpuMem.ready.poke(false.B)
              return
            }
          }
          d.io.gpuMem.ready.poke(false.B)
        }

        runFlush()
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  First flush done ✓")
        runFlush()
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  Second flush done ✓")
        println("  PASSED")
      }
    }

    // Test: exactly 64 PSRAM writes per flush (4 channels × 16 entries)
    utest.test("write_count_64") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: write_count_64 ---")
        resetDut(d)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        var writeCount = 0
        var done = false
        for (_ <- 0 until 300) {
          val wr = d.io.gpuMem.wr.peek().litToBoolean
          if (wr) {
            d.io.gpuMem.ready.poke(true.B)
            writeCount += 1
          } else {
            d.io.gpuMem.ready.poke(false.B)
          }
          d.clock.step(1)
          if (!d.io.busy.peek().litToBoolean && writeCount > 0 && !done) {
            done = true
            d.io.gpuMem.ready.poke(false.B)
          }
        }
        d.io.gpuMem.ready.poke(false.B)
        println(f"  Total PSRAM writes: $writeCount (expect 64)")
        utest.assert(writeCount == 64)
        println("  PASSED")
      }
    }

  }
}
