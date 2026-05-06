// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Step 11.1: Standalone tests for BorgTileBuffer.
  *
  * Verifies write/read round-trip, multi-entry independence,
  * clear operation, and Z peek (combinational read).
  */
object BorgTileBufferTests extends TestSuite {

  val FP16_MAX_DEPTH = 0x7BFF

  /** Set all inputs to idle. */
  def pokeIdle(tb: BorgTileBuffer): Unit = {
    tb.io.write.idx.poke(0.U)
    tb.io.write.data.r.poke(0.U)
    tb.io.write.data.g.poke(0.U)
    tb.io.write.data.b.poke(0.U)
    tb.io.write.data.z.poke(0.U)
    tb.io.write.en.poke(false.B)
    tb.io.read.idx.poke(0.U)
    tb.io.read.en.poke(false.B)
    tb.io.clear.en.poke(false.B)
    // Clear color: RGB=0, Z=FP16_MAX_DEPTH (0x7BFF).
    // Hardware uses io.clear.color as the written value; caller must supply it.
    tb.io.clear.color.r.poke(0.U)
    tb.io.clear.color.g.poke(0.U)
    tb.io.clear.color.b.poke(0.U)
    tb.io.clear.color.z.poke(FP16_MAX_DEPTH.U)
  }

  /** Explicit reset pulse + wait for BRAM auto-clear (16 cycles). */
  def resetModule(tb: BorgTileBuffer): Unit = {
    pokeIdle(tb)
    tb.reset.poke(true.B)
    tb.clock.step(2)
    tb.reset.poke(false.B)
    tb.clock.step(18)  // 16 cycles for BRAM clear + margin
  }

  /** Write one pixel to the tile buffer. */
  def writePixel(tb: BorgTileBuffer, idx: Int, r: Int, g: Int, b: Int, z: Int): Unit = {
    pokeIdle(tb)
    tb.io.write.idx.poke(idx.U)
    tb.io.write.data.r.poke(r.U)
    tb.io.write.data.g.poke(g.U)
    tb.io.write.data.b.poke(b.U)
    tb.io.write.data.z.poke(z.U)
    tb.io.write.en.poke(true.B)
    tb.clock.step(1)
    tb.io.write.en.poke(false.B)
  }

  /** Read one pixel (RGB has 2-cycle latency: BRAM + hold reg; Z is also latched). */
  def readPixel(tb: BorgTileBuffer, idx: Int): (Int, Int, Int, Int) = {
    pokeIdle(tb)
    tb.io.read.idx.poke(idx.U)
    tb.io.read.en.poke(true.B)
    tb.clock.step(1)  // BRAM read fires
    tb.io.read.en.poke(false.B)
    tb.clock.step(1)  // Hold registers capture BRAM output
    val r = tb.io.read.data.r.peek().litValue.toInt
    val g = tb.io.read.data.g.peek().litValue.toInt
    val b = tb.io.read.data.b.peek().litValue.toInt
    val z = tb.io.read.data.z.peek().litValue.toInt
    (r, g, b, z)
  }

  val tests = Tests {

    utest.test("write_read_roundtrip") {
      simulate(new BorgTileBuffer()) { tb =>
        println("\n--- BorgTileBuffer: write_read_roundtrip ---")
        resetModule(tb)

        // Write pixel at index 0
        writePixel(tb, idx = 0, r = 0x3C00, g = 0x4000, b = 0x4200, z = 0x3000)

        // Read it back
        val (r, g, b, z) = readPixel(tb, idx = 0)
        println(f"  Read back: R=0x$r%04X G=0x$g%04X B=0x$b%04X Z=0x$z%04X")
        utest.assert(r == 0x3C00)
        utest.assert(g == 0x4000)
        utest.assert(b == 0x4200)
        utest.assert(z == 0x3000)
        println("  PASSED")
      }
    }

    utest.test("multi_entry_independence") {
      simulate(new BorgTileBuffer()) { tb =>
        println("\n--- BorgTileBuffer: multi_entry_independence ---")
        resetModule(tb)

        // Write different values to entries 0, 5, 15
        writePixel(tb, idx = 0,  r = 0x1111, g = 0x2222, b = 0x3333, z = 0x1000)
        writePixel(tb, idx = 5,  r = 0x4444, g = 0x5555, b = 0x6666, z = 0x2000)
        writePixel(tb, idx = 15, r = 0x7777, g = 0x0888, b = 0x0999, z = 0x3000)

        // Read each back and verify
        val (r0, g0, b0, z0) = readPixel(tb, 0)
        println(f"  Entry 0: R=0x$r0%04X G=0x$g0%04X B=0x$b0%04X Z=0x$z0%04X")
        utest.assert(r0 == 0x1111 && g0 == 0x2222 && b0 == 0x3333 && z0 == 0x1000)

        val (r5, g5, b5, z5) = readPixel(tb, 5)
        println(f"  Entry 5: R=0x$r5%04X G=0x$g5%04X B=0x$b5%04X Z=0x$z5%04X")
        utest.assert(r5 == 0x4444 && g5 == 0x5555 && b5 == 0x6666 && z5 == 0x2000)

        val (r15, g15, b15, z15) = readPixel(tb, 15)
        println(f"  Entry 15: R=0x$r15%04X G=0x$g15%04X B=0x$b15%04X Z=0x$z15%04X")
        utest.assert(r15 == 0x7777 && g15 == 0x0888 && b15 == 0x0999 && z15 == 0x3000)
        println("  PASSED")
      }
    }

    utest.test("clear_resets_all") {
      simulate(new BorgTileBuffer()) { tb =>
        println("\n--- BorgTileBuffer: clear_resets_all ---")
        resetModule(tb)

        // Write to entries 0 and 7
        writePixel(tb, idx = 0, r = 0xAAAA, g = 0xBBBB, b = 0xCCCC, z = 0x1000)
        writePixel(tb, idx = 7, r = 0xDDDD, g = 0xEEEE, b = 0x0FFF, z = 0x2000)

        // Trigger clear
        pokeIdle(tb)
        tb.io.clear.en.poke(true.B)
        tb.clock.step(1)
        tb.io.clear.en.poke(false.B)

        // Wait for clear to finish (16 cycles for RGB BRAM)
        var waitCycles = 0
        while (tb.io.clear.busy.peek().litToBoolean && waitCycles < 20) {
          tb.clock.step(1)
          waitCycles += 1
        }
        println(f"  Clear took $waitCycles cycles")
        utest.assert(!tb.io.clear.busy.peek().litToBoolean)
        tb.clock.step(1)  // one extra for settling

        // Verify Z entries are FP16_MAX_DEPTH (via readPixel)
        for (i <- Seq(0, 7, 15)) {
          val (_, _, _, z) = readPixel(tb, i)
          println(f"  Z[$i] = 0x$z%04X (expect 0x${FP16_MAX_DEPTH}%04X)")
          utest.assert(z == FP16_MAX_DEPTH)
        }

        // Verify RGB entries are 0 (read from BRAM)
        val (r0, g0, b0, _) = readPixel(tb, 0)
        println(f"  RGB[0] = (0x$r0%04X, 0x$g0%04X, 0x$b0%04X) (expect 0)")
        utest.assert(r0 == 0 && g0 == 0 && b0 == 0)

        val (r7, g7, b7, _) = readPixel(tb, 7)
        println(f"  RGB[7] = (0x$r7%04X, 0x$g7%04X, 0x$b7%04X) (expect 0)")
        utest.assert(r7 == 0 && g7 == 0 && b7 == 0)
        println("  PASSED")
      }
    }

    utest.test("z_peek_bram") {
      simulate(new BorgTileBuffer()) { tb =>
        println("\n--- BorgTileBuffer: z_peek_bram ---")
        resetModule(tb)

        // After reset + auto-clear, all Z should be FP16_MAX_DEPTH
        for (i <- 0 until 16) {
          val (_, _, _, z) = readPixel(tb, i)
          if (i < 4) println(f"  Z[$i] = 0x$z%04X")
          utest.assert(z == FP16_MAX_DEPTH)
        }
        println("  All 16 entries = 0x7BFF after reset ✓")

        // Write Z to entry 3
        writePixel(tb, idx = 3, r = 0, g = 0, b = 0, z = 0x2800)

        // Read back Z via readPixel
        val (_, _, _, z3) = readPixel(tb, 3)
        println(f"  After write: Z[3] = 0x$z3%04X (expect 0x2800)")
        utest.assert(z3 == 0x2800)

        // Other entries unchanged
        val (_, _, _, z4) = readPixel(tb, 4)
        println(f"  Unchanged:   Z[4] = 0x$z4%04X (expect 0x7BFF)")
        utest.assert(z4 == FP16_MAX_DEPTH)
        println("  PASSED")
      }
    }

    utest.test("initial_z_values") {
      simulate(new BorgTileBuffer()) { tb =>
        println("\n--- BorgTileBuffer: initial_z_values ---")
        resetModule(tb)

        // All Z entries should be FP16_MAX_DEPTH (0x7BFF) after reset + auto-clear
        for (i <- 0 until 16) {
          val (_, _, _, z) = readPixel(tb, i)
          utest.assert(z == FP16_MAX_DEPTH)
        }
        println("  All 16 Z entries = 0x7BFF after reset ✓")
        println("  PASSED")
      }
    }
  }
}
