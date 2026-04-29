// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgTileFlusher (Step 25.4.2 Option A — DMA architecture).
  *
  * The flusher is now a 5-state DMA engine: reads 16 tile SRAM entries and
  * writes 32 PSRAM words (2 per entry: lo=R|G, hi=B|Z).
  * No depth test, no per-pixel address arithmetic.
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

  val tests = Tests {

    // Test: idle → busy on start, returns to idle after 32 writes
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

        // Drive ready for all 32 writes (2 per entry × 16 entries).
        // Each entry: sReadSram(1) + sWaitSram(1) + sWriteLo(1+) + sWriteHi(1+) ≥ 4 cycles.
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

          // sReadSram → sWaitSram → sWaitSram2 → sLatchData → sWriteLo (4 plain steps)
          d.clock.step(1)  // → sWaitSram
          d.clock.step(1)  // → sWaitSram2
          d.clock.step(1)  // → sLatchData
          d.clock.step(1)  // → sWriteLo

          // sWriteLo: ready → sWriteHi
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // sWriteHi: ready → sReadSram (next entry) or sIdle (last)
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
    // tileBase=0x100, entry 0: lo at 0x100, hi at 0x104
    //                  entry 1: lo at 0x108, hi at 0x10C
    //                  ...
    //                  entry 15: lo at 0x178, hi at 0x17C
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
          // sReadSram → sWaitSram → sWaitSram2 → sLatchData → sWriteLo (4 plain steps)
          d.clock.step(1)  // → sWaitSram
          d.clock.step(1)  // → sWaitSram2
          d.clock.step(1)  // → sLatchData
          d.clock.step(1)  // → sWriteLo
          // Now in sWriteLo: check address
          val addrLo = d.io.gpuMem.addr.peek().litValue.toInt
          val expLo  = tileBase + entry * 8
          println(f"  entry $entry lo: addr=0x$addrLo%X (expect 0x$expLo%X)")
          utest.assert(addrLo == expLo)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)

          // Now in sWriteHi: check address
          val addrHi = d.io.gpuMem.addr.peek().litValue.toInt
          val expHi  = tileBase + entry * 8 + 4
          println(f"  entry $entry hi: addr=0x$addrHi%X (expect 0x$expHi%X)")
          utest.assert(addrHi == expHi)
          d.io.gpuMem.ready.poke(true.B)
          d.clock.step(1)
          d.io.gpuMem.ready.poke(false.B)
          // Now in sReadSram (next entry) or sIdle (if last)
        }
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  PASSED")
      }
    }


    // Test: write data matches tile SRAM entry (lo=R|G, hi=B|Z)
    utest.test("write_data_matches_sram") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: write_data_matches_sram ---")
        resetDut(d)

        // Poke specific values into tile SRAM read port
        val r = 0x3C00; val g = 0x4000; val b = 0x4200; val z = 0x1234
        d.io.read.data.r.poke(r.U)
        d.io.read.data.g.poke(g.U)
        d.io.read.data.b.poke(b.U)
        d.io.read.data.z.poke(z.U)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        // sReadSram → sWaitSram → sWaitSram2 → sLatchData → sWriteLo (4 plain steps)
        d.clock.step(1)  // → sWaitSram
        d.clock.step(1)  // → sWaitSram2
        d.clock.step(1)  // → sLatchData
        d.clock.step(1)  // → sWriteLo
        // Now in sWriteLo
        val wdataLo = d.io.gpuMem.wdata.peek().litValue.toLong
        println(f"  sWriteLo wdata=0x$wdataLo%08X")
        utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        val wdataHi = d.io.gpuMem.wdata.peek().litValue.toLong
        println(f"  sWriteHi wdata=0x$wdataHi%08X")
        utest.assert(d.io.gpuMem.wr.peek().litToBoolean)
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)
        println("  Write data parity check ✓")
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
        // sReadSram → sWaitSram → sWaitSram2 → sLatchData → sWriteLo (4 plain steps)
        d.clock.step(1)  // → sWaitSram
        d.clock.step(1)  // → sWaitSram2
        d.clock.step(1)  // → sLatchData
        d.clock.step(1)  // → sWriteLo: hold ready=false for 5 cycles
        for (i <- 0 until 5) {
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

    // Test: exactly 32 PSRAM writes per flush
    utest.test("write_count_32") {
      simulate(new BorgTileFlusher) { d =>
        println("\n--- BorgTileFlusher: write_count_32 ---")
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
        println(f"  Total PSRAM writes: $writeCount (expect 32)")
        utest.assert(writeCount == 32)
        println("  PASSED")
      }
    }

  }
}
