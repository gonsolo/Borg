// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgBinner (Step 32.1).
  *
  * Tests verify:
  *   - Idle/busy handshake
  *   - Correct DRAM write addresses and data for a simple bbox
  *   - Per-tile count increment across multiple triangles
  *   - Count clearing
  *   - Edge case: single-tile bbox
  */
object BorgBinnerTests extends TestSuite {

  // Small config for tests: 4 max tiles (8×8 fb / 4×4 tiles = 2×2)
  val TestMaxTiles = 4

  def resetDut(d: BorgBinner): Unit = {
    d.reset.poke(true.B)
    d.clock.step(2)
    d.reset.poke(false.B)
    d.io.start.poke(false.B)
    d.io.triIndex.poke(0.U)
    d.io.bbox.min.x.poke(0.U)
    d.io.bbox.min.y.poke(0.U)
    d.io.bbox.max.x.poke(0.U)
    d.io.bbox.max.y.poke(0.U)
    d.io.binBase.poke(0.U)
    d.io.binRowBytes.poke(0.U)
    d.io.tilesPerRow.poke(0.U)
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.data.poke(0.U)
    d.io.clearCounts.poke(false.B)
    d.clock.step(1)
  }

  /** Clear per-tile counts and wait for completion. */
  def clearCounts(d: BorgBinner): Unit = {
    d.io.clearCounts.poke(true.B)
    d.clock.step(1)
    d.io.clearCounts.poke(false.B)
    // Wait for clearing to complete (maxTiles + 1 cycles to be safe)
    for (_ <- 0 until TestMaxTiles + 2) {
      d.clock.step(1)
    }
    // Verify not busy after clear
    utest.assert(!d.io.busy.peek().litToBoolean)
  }

  val tests = Tests {

    // Test: idle state outputs
    utest.test("idle_outputs") {
      simulate(new BorgBinner(TestMaxTiles)) { d =>
        println("\n--- BorgBinner: idle_outputs ---")
        resetDut(d)
        for (_ <- 0 until 3) d.clock.step(1)
        utest.assert(!d.io.busy.peek().litToBoolean)
        utest.assert(!d.io.done.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.wr.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        println("  All idle outputs correct ✓")
        println("  PASSED")
      }
    }

    // Test: single-tile bbox — one DRAM write
    utest.test("single_tile") {
      simulate(new BorgBinner(TestMaxTiles)) { d =>
        println("\n--- BorgBinner: single_tile ---")
        resetDut(d)
        clearCounts(d)

        val binBase = 0x1000
        val binRowBytes = 8  // 4 tiles * 2 bytes per entry (small test)
        val tilesPerRow = 2

        d.io.binBase.poke(binBase.U)
        d.io.binRowBytes.poke(binRowBytes.U)
        d.io.tilesPerRow.poke(tilesPerRow.U)

        // Bbox covers tile (0,0) only: min=(0,0) max=(4,4)
        d.io.bbox.min.x.poke(0.U)
        d.io.bbox.min.y.poke(0.U)
        d.io.bbox.max.x.poke(4.U)
        d.io.bbox.max.y.poke(4.U)
        d.io.triIndex.poke(42.U)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        utest.assert(d.io.busy.peek().litToBoolean)

        // Wait for DRAM write (sWriteDram)
        var writeCount = 0
        var writtenAddr = -1L
        var writtenData = -1L
        for (_ <- 0 until 20) {
          if (d.io.gpuMem.wr.peek().litToBoolean) {
            writtenAddr = d.io.gpuMem.addr.peek().litValue.toLong
            writtenData = d.io.gpuMem.wdata.peek().litValue.toLong
            d.io.gpuMem.ready.poke(true.B)
            writeCount += 1
          } else {
            d.io.gpuMem.ready.poke(false.B)
          }
          d.clock.step(1)
        }
        d.io.gpuMem.ready.poke(false.B)

        // Tile (0,0) → tile_index = 0
        // addr = binBase + 0 * binRowBytes + 0 * 2 = 0x1000
        val expectedAddr = binBase
        println(f"  Write addr=0x$writtenAddr%X (expect 0x$expectedAddr%X)")
        println(f"  Write data=$writtenData (expect 42)")
        println(f"  Write count=$writeCount (expect 1)")
        utest.assert(writeCount == 1)
        utest.assert(writtenAddr == expectedAddr)
        utest.assert(writtenData == 42)
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  PASSED")
      }
    }

    // Test: 2×2 tile bbox — four DRAM writes
    utest.test("two_by_two_bbox") {
      simulate(new BorgBinner(TestMaxTiles)) { d =>
        println("\n--- BorgBinner: two_by_two_bbox ---")
        resetDut(d)
        clearCounts(d)

        val binBase = 0x2000
        val binRowBytes = 8  // 4 tiles * 2 bytes
        val tilesPerRow = 2

        d.io.binBase.poke(binBase.U)
        d.io.binRowBytes.poke(binRowBytes.U)
        d.io.tilesPerRow.poke(tilesPerRow.U)

        // Bbox covers all 4 tiles: min=(0,0) max=(8,8) → tiles (0,0),(4,0),(0,4),(4,4)
        d.io.bbox.min.x.poke(0.U)
        d.io.bbox.min.y.poke(0.U)
        d.io.bbox.max.x.poke(8.U)
        d.io.bbox.max.y.poke(8.U)
        d.io.triIndex.poke(7.U)

        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // Collect all DRAM writes
        var writes = List[(Long, Long)]()
        for (_ <- 0 until 60) {
          if (d.io.gpuMem.wr.peek().litToBoolean) {
            val addr = d.io.gpuMem.addr.peek().litValue.toLong
            val data = d.io.gpuMem.wdata.peek().litValue.toLong
            writes = writes :+ (addr, data)
            d.io.gpuMem.ready.poke(true.B)
          } else {
            d.io.gpuMem.ready.poke(false.B)
          }
          d.clock.step(1)
        }
        d.io.gpuMem.ready.poke(false.B)

        println(f"  Total writes: ${writes.length} (expect 4)")
        utest.assert(writes.length == 4)

        // Expected tile indices (row-major, X first):
        // (0,0)→0, (4,0)→1, (0,4)→2, (4,4)→3
        // addr = binBase + tile_index * binRowBytes + 0 * 2  (count=0 for all, first triangle)
        val expectedAddrs = Seq(
          binBase + 0 * binRowBytes,  // tile (0,0)
          binBase + 1 * binRowBytes,  // tile (4,0)
          binBase + 2 * binRowBytes,  // tile (0,4)
          binBase + 3 * binRowBytes   // tile (4,4)
        )
        for ((((addr, data), expected), i) <- writes.zip(expectedAddrs).zipWithIndex) {
          println(f"  write $i: addr=0x$addr%X (expect 0x$expected%X) data=$data (expect 7)")
          utest.assert(addr == expected)
          utest.assert(data == 7)
        }
        utest.assert(!d.io.busy.peek().litToBoolean)
        println("  PASSED")
      }
    }

    // Test: two triangles — count increments correctly
    utest.test("count_increment") {
      simulate(new BorgBinner(TestMaxTiles)) { d =>
        println("\n--- BorgBinner: count_increment ---")
        resetDut(d)
        clearCounts(d)

        val binBase = 0x3000
        val binRowBytes = 8
        val tilesPerRow = 2

        d.io.binBase.poke(binBase.U)
        d.io.binRowBytes.poke(binRowBytes.U)
        d.io.tilesPerRow.poke(tilesPerRow.U)

        // Helper: bin one triangle to a single tile (0,0)
        def binTriangle(triIdx: Int): Long = {
          d.io.bbox.min.x.poke(0.U)
          d.io.bbox.min.y.poke(0.U)
          d.io.bbox.max.x.poke(4.U)
          d.io.bbox.max.y.poke(4.U)
          d.io.triIndex.poke(triIdx.U)

          d.io.start.poke(true.B)
          d.clock.step(1)
          d.io.start.poke(false.B)

          var addr = 0L
          for (_ <- 0 until 20) {
            if (d.io.gpuMem.wr.peek().litToBoolean) {
              addr = d.io.gpuMem.addr.peek().litValue.toLong
              d.io.gpuMem.ready.poke(true.B)
            } else {
              d.io.gpuMem.ready.poke(false.B)
            }
            d.clock.step(1)
          }
          d.io.gpuMem.ready.poke(false.B)
          addr
        }

        // First triangle: count=0, addr = binBase + 0*binRowBytes + 0*2
        val addr0 = binTriangle(10)
        val exp0 = binBase + 0
        println(f"  tri 0: addr=0x$addr0%X (expect 0x$exp0%X)")
        utest.assert(addr0 == exp0)

        // Second triangle: count=1, addr = binBase + 0*binRowBytes + 1*2
        val addr1 = binTriangle(20)
        val exp1 = binBase + 2
        println(f"  tri 1: addr=0x$addr1%X (expect 0x$exp1%X)")
        utest.assert(addr1 == exp1)

        // Third triangle: count=2, addr = binBase + 0*binRowBytes + 2*2
        val addr2 = binTriangle(30)
        val exp2 = binBase + 4
        println(f"  tri 2: addr=0x$addr2%X (expect 0x$exp2%X)")
        utest.assert(addr2 == exp2)

        println("  PASSED")
      }
    }

    // Test: clearCounts resets all per-tile counters
    utest.test("clear_counts") {
      simulate(new BorgBinner(TestMaxTiles)) { d =>
        println("\n--- BorgBinner: clear_counts ---")
        resetDut(d)
        clearCounts(d)

        val binBase = 0x4000
        val binRowBytes = 8
        val tilesPerRow = 2

        d.io.binBase.poke(binBase.U)
        d.io.binRowBytes.poke(binRowBytes.U)
        d.io.tilesPerRow.poke(tilesPerRow.U)

        // Bin one triangle to tile (0,0) — increments count to 1
        d.io.bbox.min.x.poke(0.U)
        d.io.bbox.min.y.poke(0.U)
        d.io.bbox.max.x.poke(4.U)
        d.io.bbox.max.y.poke(4.U)
        d.io.triIndex.poke(5.U)
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        for (_ <- 0 until 20) {
          if (d.io.gpuMem.wr.peek().litToBoolean) d.io.gpuMem.ready.poke(true.B)
          else d.io.gpuMem.ready.poke(false.B)
          d.clock.step(1)
        }
        d.io.gpuMem.ready.poke(false.B)

        // Now clear counts
        clearCounts(d)

        // Bin another triangle — count should be 0 again, addr = binBase + 0
        d.io.triIndex.poke(99.U)
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)
        var addr = 0L
        for (_ <- 0 until 20) {
          if (d.io.gpuMem.wr.peek().litToBoolean) {
            addr = d.io.gpuMem.addr.peek().litValue.toLong
            d.io.gpuMem.ready.poke(true.B)
          } else {
            d.io.gpuMem.ready.poke(false.B)
          }
          d.clock.step(1)
        }
        d.io.gpuMem.ready.poke(false.B)

        val expected = binBase
        println(f"  After clear: addr=0x$addr%X (expect 0x$expected%X, count=0)")
        utest.assert(addr == expected)
        println("  PASSED")
      }
    }
  }
}
