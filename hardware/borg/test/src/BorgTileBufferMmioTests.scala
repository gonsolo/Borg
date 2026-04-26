// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Step 25.3a: Integration-level tile buffer tests.
  *
  * Unlike BorgTileBufferTests (which tests the standalone module), these tests
  * exercise the FULL MMIO path through `Borg.scala`:
  *   CPU sw → BorgBusIO → wireTileBuffer() → BorgTileBuffer → wireMmioRead() → data_out
  *
  * This locks in the integration wiring as a regression gate before architecture
  * decoupling (Steps 25.3b–h).
  */
object BorgTileBufferMmioTests extends TestSuite {

  // --- MMIO offsets (from BorgGpuRegs) ---
  val TILE_CTRL = BorgGpuRegs.tile_ctrl_offset.litValue.toInt
  val TILE_RG   = BorgGpuRegs.tile_rg_offset.litValue.toInt
  val TILE_BZ   = BorgGpuRegs.tile_bz_offset.litValue.toInt

  // --- Low-level bus helpers (mirroring BorgTests) ---

  /** MMIO write: assert data_write_n=2 for one cycle. */
  def mmioWrite(borg: Borg, addr: Int, data: BigInt): Unit = {
    borg.io.address.poke(addr.U)
    borg.io.data_in.poke(data.U)
    borg.io.data_write_n.poke(2.U)
    borg.clock.step(1)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
  }

  /** MMIO read: assert data_read_n=2, wait for pipeline, peek data_out.
    * Returns the raw 32-bit value (not float-converted).
    */
  def mmioReadRaw(borg: Borg, addr: Int): BigInt = {
    borg.io.address.poke(addr.U)
    borg.io.data_read_n.poke(2.U)
    borg.clock.step(1)
    borg.clock.step(1) // wait for MuxCase + latch
    val bits = borg.io.data_out.peek().litValue
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    bits
  }

  /** Reset the Borg module and idle all bus signals. */
  def resetAndIdle(borg: Borg): Unit = {
    borg.reset.poke(true.B)
    borg.clock.step(2)
    borg.reset.poke(false.B)
    borg.io.data_write_n.poke(3.U)
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(20)  // 16 cycles for BRAM auto-clear + margin
  }

  /** Write a pixel to the tile buffer via the 2-step MMIO protocol.
    *
    * The firmware does:
    *   1. sw TILE_BZ, (B << 16) | Z
    *   2. sw TILE_RG, (R << 16) | G   ← this triggers the actual BRAM write
    *
    * The write index is taken from TILE_CTRL's `read_idx` field (set by
    * a prior TILE_CTRL write). This mirrors the firmware behavior where
    * the CPU sets the index before writing pixel data.
    */
  def writeTilePixel(borg: Borg, idx: Int, r: Int, g: Int, b: Int, z: Int): Unit = {
    // Set the write index via TILE_CTRL (bits 3:0 = read_idx)
    mmioWrite(borg, TILE_CTRL, idx)
    // Step 1: shadow B and Z  (use toLong to avoid Int overflow for values >= 0x8000)
    mmioWrite(borg, TILE_BZ, ((b.toLong & 0xFFFFL) << 16) | (z.toLong & 0xFFFFL))
    // Step 2: write R and G (triggers BRAM write)
    mmioWrite(borg, TILE_RG, ((r.toLong & 0xFFFFL) << 16) | (g.toLong & 0xFFFFL))
  }

  /** Read a pixel from the tile buffer via the MMIO path.
    *
    * The firmware does:
    *   1. sw TILE_CTRL, idx    ← triggers BRAM read
    *   2. (wait 1 cycle for BRAM latency)
    *   3. lw TILE_RG           ← returns Cat(R, G)
    *   4. lw TILE_BZ           ← returns Cat(B, Z)
    *
    * Returns (R, G, B, Z) as 16-bit unsigned integers.
    */
  def readTilePixel(borg: Borg, idx: Int): (Int, Int, Int, Int) = {
    // Trigger BRAM read by writing TILE_CTRL with the index
    mmioWrite(borg, TILE_CTRL, idx)
    // Read RG (BRAM output is now available after the write cycle + 1)
    val rg = mmioReadRaw(borg, TILE_RG)
    val r = ((rg >> 16) & 0xFFFF).toInt
    val g = (rg & 0xFFFF).toInt
    // Read BZ
    val bz = mmioReadRaw(borg, TILE_BZ)
    val b = ((bz >> 16) & 0xFFFF).toInt
    val z = (bz & 0xFFFF).toInt
    (r, g, b, z)
  }

  val tests = Tests {

    // -----------------------------------------------------------------
    // Test 1: MMIO write → read round-trip
    // Verifies the full CPU sw/lw path through Borg.scala wiring.
    // -----------------------------------------------------------------
    utest.test("mmio_tile_write_read_roundtrip") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n--- Step 25.3a: MMIO tile buffer write/read round-trip ---")
        resetAndIdle(borg)

        // Write a known pixel at index 3
        val testR = 0x3C00  // 1.0 in FP16
        val testG = 0x4000  // 2.0 in FP16
        val testB = 0x4200  // 3.0 in FP16
        val testZ = 0x3000  // 0.5 in FP16
        writeTilePixel(borg, idx = 3, r = testR, g = testG, b = testB, z = testZ)

        // Read it back via the MMIO path
        val (r, g, b, z) = readTilePixel(borg, idx = 3)
        println(f"  Written:  R=0x$testR%04X G=0x$testG%04X B=0x$testB%04X Z=0x$testZ%04X")
        println(f"  Readback: R=0x$r%04X G=0x$g%04X B=0x$b%04X Z=0x$z%04X")
        Predef.assert(r == testR, s"R mismatch: got 0x${r.toHexString}, expected 0x${testR.toHexString}")
        Predef.assert(g == testG, s"G mismatch: got 0x${g.toHexString}, expected 0x${testG.toHexString}")
        Predef.assert(b == testB, s"B mismatch: got 0x${b.toHexString}, expected 0x${testB.toHexString}")
        Predef.assert(z == testZ, s"Z mismatch: got 0x${z.toHexString}, expected 0x${testZ.toHexString}")
        println("  PASSED")
      }
    }

    // -----------------------------------------------------------------
    // Test 2: Multiple indices are independent
    // Write different values to indices 0, 7, 15 and verify each reads
    // back correctly — ensures BRAM addressing works through MMIO.
    // -----------------------------------------------------------------
    utest.test("mmio_tile_multi_index_independence") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n--- Step 25.3a: MMIO tile multi-index independence ---")
        resetAndIdle(borg)

        // Write three different pixels
        writeTilePixel(borg, idx = 0,  r = 0x1111, g = 0x2222, b = 0x3333, z = 0x1000)
        writeTilePixel(borg, idx = 7,  r = 0x4444, g = 0x5555, b = 0x6666, z = 0x2000)
        writeTilePixel(borg, idx = 15, r = 0x7000, g = 0x0800, b = 0x0900, z = 0x3000)

        // Read each back
        val (r0, g0, b0, z0) = readTilePixel(borg, idx = 0)
        println(f"  idx=0:  R=0x$r0%04X G=0x$g0%04X B=0x$b0%04X Z=0x$z0%04X")
        utest.assert(r0 == 0x1111 && g0 == 0x2222 && b0 == 0x3333 && z0 == 0x1000)

        val (r7, g7, b7, z7) = readTilePixel(borg, idx = 7)
        println(f"  idx=7:  R=0x$r7%04X G=0x$g7%04X B=0x$b7%04X Z=0x$z7%04X")
        utest.assert(r7 == 0x4444 && g7 == 0x5555 && b7 == 0x6666 && z7 == 0x2000)

        val (r15, g15, b15, z15) = readTilePixel(borg, idx = 15)
        println(f"  idx=15: R=0x$r15%04X G=0x$g15%04X B=0x$b15%04X Z=0x$z15%04X")
        utest.assert(r15 == 0x7000 && g15 == 0x0800 && b15 == 0x0900 && z15 == 0x3000)

        println("  PASSED")
      }
    }

    // -----------------------------------------------------------------
    // Test 3: Clear resets all entries to max-depth Z
    // After writing pixels, assert TILE_CTRL.clear and verify all
    // entries read back as (0, 0, 0, 0x7BFF).
    // -----------------------------------------------------------------
    utest.test("mmio_tile_clear") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n--- Step 25.3a: MMIO tile clear ---")
        resetAndIdle(borg)

        // Write a pixel
        writeTilePixel(borg, idx = 5, r = 0xAAAA, g = 0xBBBB, b = 0xCCCC, z = 0xDDDD)

        // Verify it's there
        val (r_pre, _, _, _) = readTilePixel(borg, idx = 5)
        Predef.assert(r_pre == 0xAAAA, s"Pre-clear: expected 0xAAAA, got 0x${r_pre.toHexString}")

        // Assert clear via TILE_CTRL bit 4
        mmioWrite(borg, TILE_CTRL, (1 << 4))  // clear bit
        borg.clock.step(20)  // 16 cycles for BRAM clear sweep + margin

        // All 16 entries should now be (0, 0, 0, FP16_MAX_DEPTH=0x7BFF)
        for (idx <- 0 until 16) {
          val (r, g, b, z) = readTilePixel(borg, idx)
          if (idx == 0 || idx == 5 || idx == 15) {
            println(f"  idx=$idx%2d: R=0x$r%04X G=0x$g%04X B=0x$b%04X Z=0x$z%04X")
          }
          Predef.assert(r == 0, s"idx=$idx: R should be 0, got 0x${r.toHexString}")
          Predef.assert(g == 0, s"idx=$idx: G should be 0, got 0x${g.toHexString}")
          Predef.assert(b == 0, s"idx=$idx: B should be 0, got 0x${b.toHexString}")
          Predef.assert(z == 0x7BFF, s"idx=$idx: Z should be 0x7BFF, got 0x${z.toHexString}")
        }
        println("  PASSED")
      }
    }

    // -----------------------------------------------------------------
    // Test 4: Back-to-back reads of the same index
    // Ensures the hold registers don't corrupt on repeated reads.
    // -----------------------------------------------------------------
    utest.test("mmio_tile_repeated_read") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n--- Step 25.3a: MMIO tile repeated read ---")
        resetAndIdle(borg)

        writeTilePixel(borg, idx = 10, r = 0xDEAD, g = 0xBEEF, b = 0xCAFE, z = 0xF00D)

        // Read the same index 3 times
        for (i <- 0 until 3) {
          val (r, g, b, z) = readTilePixel(borg, idx = 10)
          println(f"  Read $i: R=0x$r%04X G=0x$g%04X B=0x$b%04X Z=0x$z%04X")
          Predef.assert(r == 0xDEAD && g == 0xBEEF && b == 0xCAFE && z == 0xF00D,
            s"Read $i: mismatch")
        }
        println("  PASSED")
      }
    }

    // -----------------------------------------------------------------
    // Test 5: Read different indices without re-writing
    // Write all 16 entries, then read them in reverse order to verify
    // the BRAM read path handles index switching correctly.
    // -----------------------------------------------------------------
    utest.test("mmio_tile_full_readback") {
      val config = FloatConfig.FP16
      simulate(new Borg(config)) { borg =>
        println("\n--- Step 25.3a: MMIO tile full 16-entry readback ---")
        resetAndIdle(borg)

        // Write all 16 entries with distinct values
        for (idx <- 0 until 16) {
          val r = (0x1000 + idx * 0x100) & 0xFFFF
          val g = (0x2000 + idx * 0x100) & 0xFFFF
          val b = (0x3000 + idx * 0x100) & 0xFFFF
          val z = (0x4000 + idx * 0x100) & 0xFFFF
          writeTilePixel(borg, idx, r, g, b, z)
        }

        // Read them all back in reverse order
        for (idx <- (0 until 16).reverse) {
          val expectedR = (0x1000 + idx * 0x100) & 0xFFFF
          val expectedG = (0x2000 + idx * 0x100) & 0xFFFF
          val expectedB = (0x3000 + idx * 0x100) & 0xFFFF
          val expectedZ = (0x4000 + idx * 0x100) & 0xFFFF
          val (r, g, b, z) = readTilePixel(borg, idx)
          if (idx == 0 || idx == 8 || idx == 15) {
            println(f"  idx=$idx%2d: R=0x$r%04X G=0x$g%04X B=0x$b%04X Z=0x$z%04X")
          }
          Predef.assert(r == expectedR, s"idx=$idx R: got 0x${r.toHexString}, want 0x${expectedR.toHexString}")
          Predef.assert(g == expectedG, s"idx=$idx G: got 0x${g.toHexString}, want 0x${expectedG.toHexString}")
          Predef.assert(b == expectedB, s"idx=$idx B: got 0x${b.toHexString}, want 0x${expectedB.toHexString}")
          Predef.assert(z == expectedZ, s"idx=$idx Z: got 0x${z.toHexString}, want 0x${expectedZ.toHexString}")
        }
        println("  PASSED")
      }
    }
  }
}
