// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgTextureUnit (Step 25.3e).
  *
  * Tests the 2-word PSRAM texel fetch in complete isolation.  GpuMemIO is
  * driven directly — no BorgShaderDispatcher or BorgRasterizer context needed.
  *
  * Coverage:
  *   - B-first read order (offset +4 before offset +0) — Morton-stability
  *   - Correct address computation: baseAddr + (mortonIndex << 3)
  *   - Correct R/G/B unpacking from the two 32-bit words
  *   - done pulses for exactly one cycle then deasserts
  *   - No req asserted while idle (gpuMem port is quiet between fetches)
  *   - Second fetch after done works correctly (no stale state)
  *   - PSRAM stall: gpuMem.ready de-asserted for multiple cycles before response
  *   - start ignored while a fetch is already in progress (no restart glitch)
  */
object BorgTextureUnitTests extends TestSuite {

  // Convenience: compute expected byte address for a Morton index
  def bAddr(base: Int, idx: Int): Int = base + (idx << 3) + 4  // B word: offset +4
  def rgAddr(base: Int, idx: Int): Int = base + (idx << 3)      // RG word: offset +0

  /** Reset the DUT and idle for a couple of cycles. */
  def reset(d: BorgTextureUnit): Unit = {
    d.io.start.poke(false.B)
    d.io.texConfig.en.poke(false.B)
    d.io.texConfig.mortonIndex.poke(0.U)
    d.io.texConfig.baseAddr.poke(0.U)
    d.io.gpuMem.data.poke(0.U)
    d.io.gpuMem.ready.poke(false.B)
    d.reset.poke(true.B)
    d.clock.step(2)
    d.reset.poke(false.B)
    d.clock.step(1)
  }

  /** Drive a single PSRAM read response.
    * Asserts ready for one cycle with the given data word.
    */
  def respondPsram(d: BorgTextureUnit, data: Long): Unit = {
    d.io.gpuMem.data.poke(data.U)
    d.io.gpuMem.ready.poke(true.B)
    d.clock.step(1)
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.data.poke(0.U)
  }

  /** Run a complete fetch: start pulse → B read → RG read → done cycle.
    * Returns (r, g, b) from fragColor on the done cycle.
    * Accepts optional stall cycles before each PSRAM response.
    */
  def runFetch(
    d: BorgTextureUnit,
    base: Int, idx: Int,
    bWord: Long, rgWord: Long,
    stallB: Int = 0, stallRG: Int = 0
  ): (Int, Int, Int) = {
    // Configure texConfig
    d.io.texConfig.en.poke(true.B)
    d.io.texConfig.baseAddr.poke(base.U)
    d.io.texConfig.mortonIndex.poke(idx.U)

    // Pulse start
    d.io.start.poke(true.B)
    d.clock.step(1)
    d.io.start.poke(false.B)

    // Wait for B-word read (sReadB): optional stall
    d.clock.step(stallB)
    val addrB = d.io.gpuMem.addr.peek().litValue.toInt
    utest.assert(d.io.gpuMem.req.peek().litToBoolean)  // req must be asserted in sReadB
    utest.assert(addrB == bAddr(base, idx))             // B word: offset +4

    respondPsram(d, bWord)

    // Wait for RG-word read (sReadRG): optional stall
    d.clock.step(stallRG)
    val addrRG = d.io.gpuMem.addr.peek().litValue.toInt
    utest.assert(d.io.gpuMem.req.peek().litToBoolean)  // req must be asserted in sReadRG
    utest.assert(addrRG == rgAddr(base, idx))            // RG word: offset +0

    respondPsram(d, rgWord)

    // sDone: done should pulse this cycle
    val done = d.io.done.peek().litToBoolean
    val r    = d.io.fragColor.r.peek().litValue.toInt
    val g    = d.io.fragColor.g.peek().litValue.toInt
    val b    = d.io.fragColor.b.peek().litValue.toInt
    utest.assert(done)   // done must be high in sDone
    d.clock.step(1)
    utest.assert(!d.io.done.peek().litToBoolean)  // done must deassert after sDone
    (r, g, b)
  }

  val tests = Tests {

    // =========================================================================
    // Address computation: B word is at offset +4, RG word at offset +0
    // =========================================================================

    utest.test("b_word_address_is_morton_plus4") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: b_word_address_is_morton_plus4 ---")
        reset(d)

        val base = 0x0200
        val idx  = 0x003
        // Expected B addr = 0x0200 + (3 << 3) + 4 = 0x0200 + 24 + 4 = 0x021C
        // Expected RG addr = 0x0200 + (3 << 3)     = 0x0200 + 24     = 0x0218

        d.io.texConfig.en.poke(true.B)
        d.io.texConfig.baseAddr.poke(base.U)
        d.io.texConfig.mortonIndex.poke(idx.U)
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // In sReadB: check address
        val addrB = d.io.gpuMem.addr.peek().litValue.toInt
        val reqB  = d.io.gpuMem.req.peek().litToBoolean
        println(f"  sReadB: addr=0x${addrB.toHexString}, req=$reqB (expect 0x${bAddr(base, idx).toHexString})")
        utest.assert(reqB)
        utest.assert(addrB == bAddr(base, idx))

        respondPsram(d, 0x00005555L)

        // In sReadRG: check address
        val addrRG = d.io.gpuMem.addr.peek().litValue.toInt
        println(f"  sReadRG: addr=0x${addrRG.toHexString} (expect 0x${rgAddr(base, idx).toHexString})")
        utest.assert(addrRG == rgAddr(base, idx))
        println("  PASSED")
      }
    }

    // =========================================================================
    // Correct R/G/B unpacking from the two 32-bit words
    // =========================================================================

    utest.test("rgb_unpacking_correct") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: rgb_unpacking_correct ---")
        reset(d)

        // Word 0 (B): low 16 = B=0xBBBB
        // Word 1 (RG): low 16 = R=0x1234, high 16 = G=0x5678
        val bWord  = 0x0000BBBBL
        val rgWord = 0x56781234L
        val (r, g, b) = runFetch(d, base = 0x0100, idx = 0x002, bWord = bWord, rgWord = rgWord)

        println(f"  fragColor: R=0x${r.toHexString}, G=0x${g.toHexString}, B=0x${b.toHexString}")
        utest.assert(r == 0x1234)
        utest.assert(g == 0x5678)
        utest.assert(b == 0xBBBB)
        println("  PASSED")
      }
    }

    // =========================================================================
    // done pulses exactly one cycle
    // =========================================================================

    utest.test("done_pulses_one_cycle") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: done_pulses_one_cycle ---")
        reset(d)

        runFetch(d, base = 0x0080, idx = 0x001, bWord = 0x00001111L, rgWord = 0x22223333L)
        // runFetch already checks done asserts then deasserts; just assert idle after
        utest.assert(!d.io.done.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        println("  done deasserted, req quiet ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // No req when idle
    // =========================================================================

    utest.test("req_quiet_when_idle") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: req_quiet_when_idle ---")
        reset(d)

        // Without any start pulse, req must stay low
        d.clock.step(5)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        utest.assert(!d.io.done.peek().litToBoolean)
        println("  req=false, done=false after 5 idle cycles ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // PSRAM stall: ready de-asserted for multiple cycles before response
    // =========================================================================

    utest.test("psram_stall_multiple_cycles") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: psram_stall_multiple_cycles ---")
        reset(d)

        // 3-cycle stall before each response
        val (r, g, b) = runFetch(
          d, base = 0x0300, idx = 0x004,
          bWord  = 0x0000AAAAL,
          rgWord = 0xBBBBCCCCL,
          stallB = 3, stallRG = 3
        )
        println(f"  fragColor: R=0x${r.toHexString}, G=0x${g.toHexString}, B=0x${b.toHexString}")
        utest.assert(r == 0xCCCC)
        utest.assert(g == 0xBBBB)
        utest.assert(b == 0xAAAA)
        println("  PASSED")
      }
    }

    // =========================================================================
    // Second fetch works after first completes (no stale state)
    // =========================================================================

    utest.test("second_fetch_after_done") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: second_fetch_after_done ---")
        reset(d)

        // First fetch
        val (r1, g1, b1) = runFetch(d, base = 0x0100, idx = 0x001,
          bWord = 0x00001111L, rgWord = 0x22223333L)
        println(f"  Fetch 1: R=0x${r1.toHexString}, G=0x${g1.toHexString}, B=0x${b1.toHexString}")
        utest.assert(r1 == 0x3333 && g1 == 0x2222 && b1 == 0x1111)

        // Second fetch with different values
        val (r2, g2, b2) = runFetch(d, base = 0x0400, idx = 0x007,
          bWord = 0x0000DDDDL, rgWord = 0xEEEEFFFFL)
        println(f"  Fetch 2: R=0x${r2.toHexString}, G=0x${g2.toHexString}, B=0x${b2.toHexString}")
        utest.assert(r2 == 0xFFFF && g2 == 0xEEEE && b2 == 0xDDDD)
        println("  PASSED")
      }
    }

    // =========================================================================
    // start ignored mid-fetch (no restart / address corruption)
    // =========================================================================

    utest.test("start_ignored_during_fetch") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: start_ignored_during_fetch ---")
        reset(d)

        val base = 0x0200
        val idx  = 0x002
        d.io.texConfig.en.poke(true.B)
        d.io.texConfig.baseAddr.poke(base.U)
        d.io.texConfig.mortonIndex.poke(idx.U)

        // Start the fetch
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // While in sReadB, poke start again — must not restart
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // Address should still be the original B address, not a new one
        val addrB = d.io.gpuMem.addr.peek().litValue.toInt
        println(f"  Mid-fetch addr=0x${addrB.toHexString} (expect 0x${bAddr(base, idx).toHexString})")
        utest.assert(addrB == bAddr(base, idx))

        // Complete the fetch normally
        respondPsram(d, 0x00005555L)
        respondPsram(d, 0x22221111L)
        val done = d.io.done.peek().litToBoolean
        utest.assert(done)
        println("  No restart glitch; fetch completed normally ✓")
        println("  PASSED")
      }
    }

  }
}
