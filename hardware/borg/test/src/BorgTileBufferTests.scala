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
    tb.io.writeIdx.poke(0.U)
    tb.io.writeData.r.poke(0.U)
    tb.io.writeData.g.poke(0.U)
    tb.io.writeData.b.poke(0.U)
    tb.io.writeData.z.poke(0.U)
    tb.io.writeEn.poke(false.B)
    tb.io.readIdx.poke(0.U)
    tb.io.readEn.poke(false.B)
    tb.io.peekZIdx.poke(0.U)
    tb.io.clearEn.poke(false.B)
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
    tb.io.writeIdx.poke(idx.U)
    tb.io.writeData.r.poke(r.U)
    tb.io.writeData.g.poke(g.U)
    tb.io.writeData.b.poke(b.U)
    tb.io.writeData.z.poke(z.U)
    tb.io.writeEn.poke(true.B)
    tb.clock.step(1)
    tb.io.writeEn.poke(false.B)
  }

  /** Read one pixel (RGB has 2-cycle latency: BRAM + hold reg; Z is also latched). */
  def readPixel(tb: BorgTileBuffer, idx: Int): (Int, Int, Int, Int) = {
    pokeIdle(tb)
    tb.io.readIdx.poke(idx.U)
    tb.io.readEn.poke(true.B)
    tb.clock.step(1)  // BRAM read fires
    tb.io.readEn.poke(false.B)
    tb.clock.step(1)  // Hold registers capture BRAM output
    val r = tb.io.readData.r.peek().litValue.toInt
    val g = tb.io.readData.g.peek().litValue.toInt
    val b = tb.io.readData.b.peek().litValue.toInt
    val z = tb.io.readData.z.peek().litValue.toInt
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
        tb.io.clearEn.poke(true.B)
        tb.clock.step(1)
        tb.io.clearEn.poke(false.B)

        // Wait for clear to finish (16 cycles for RGB BRAM)
        var waitCycles = 0
        while (tb.io.clearBusy.peek().litToBoolean && waitCycles < 20) {
          tb.clock.step(1)
          waitCycles += 1
        }
        println(f"  Clear took $waitCycles cycles")
        utest.assert(!tb.io.clearBusy.peek().litToBoolean)
        tb.clock.step(1)  // one extra for settling

        // Verify Z entries are FP16_MAX_DEPTH (via peekZ — 1-cycle BRAM latency)
        for (i <- Seq(0, 7, 15)) {
          pokeIdle(tb)
          tb.io.peekZIdx.poke(i.U)
          tb.clock.step(2)  // BRAM read + peekZ hold register
          val z = tb.io.peekZ.peek().litValue.toInt
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
        // peekZ now has 2-cycle latency (BRAM read + hold register)
        for (i <- 0 until 16) {
          tb.io.peekZIdx.poke(i.U)
          tb.clock.step(2)  // BRAM read + hold register
          val z = tb.io.peekZ.peek().litValue.toInt
          if (i < 4) println(f"  Z[$i] = 0x$z%04X")
          utest.assert(z == FP16_MAX_DEPTH)
        }
        println("  All 16 entries = 0x7BFF after reset ✓")

        // Write Z to entry 3
        writePixel(tb, idx = 3, r = 0, g = 0, b = 0, z = 0x2800)

        // Peek should return updated value after 2-cycle BRAM latency
        tb.io.peekZIdx.poke(3.U)
        tb.clock.step(2)
        val z3 = tb.io.peekZ.peek().litValue.toInt
        println(f"  After write: Z[3] = 0x$z3%04X (expect 0x2800)")
        utest.assert(z3 == 0x2800)

        // Other entries unchanged
        tb.io.peekZIdx.poke(4.U)
        tb.clock.step(2)
        val z4 = tb.io.peekZ.peek().litValue.toInt
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
          tb.io.peekZIdx.poke(i.U)
          tb.clock.step(2)  // BRAM read + hold register
          val z = tb.io.peekZ.peek().litValue.toInt
          utest.assert(z == FP16_MAX_DEPTH)
        }
        println("  All 16 Z entries = 0x7BFF after reset ✓")
        println("  PASSED")
      }
    }
  }
}
