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
    tb.io.write.coverage.poke(((1 << tb.samples) - 1).U)  // all samples
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
    // Sample 0: writePixel drives full coverage, so every sample holds the same
    // value and sample 0 is representative.
    val r = tb.io.read.data(0).r.peek().litValue.toInt
    val g = tb.io.read.data(0).g.peek().litValue.toInt
    val b = tb.io.read.data(0).b.peek().litValue.toInt
    val z = tb.io.read.data(0).z.peek().litValue.toInt
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

    // ── 4× MSAA ───────────────────────────────────────────────────────────
    // The whole point of the per-sample tile buffer: one shaded colour is
    // broadcast to the samples selected by `coverage`, and the samples NOT
    // covered must keep their previous contents.  A partial mask is the only
    // thing that distinguishes real MSAA storage from 4 redundant copies, so
    // that is what these test.

    utest.test("msaa_partial_coverage_write") {
      simulate(new BorgTileBuffer(16, 4)) { tb =>
        println("\n--- BorgTileBuffer: msaa_partial_coverage_write ---")
        resetModule(tb)

        // After reset every sample holds RGB=0, Z=0x7BFF.
        // Write to samples 0 and 2 only (mask 0b0101).
        pokeIdle(tb)
        tb.io.write.idx.poke(5.U)
        tb.io.write.data.r.poke(0x3C00.U)   // 1.0
        tb.io.write.data.g.poke(0x3800.U)   // 0.5
        tb.io.write.data.b.poke(0x0000.U)
        tb.io.write.data.z.poke(0x3000.U)   // 0.125, nearer than 0x7BFF
        tb.io.write.coverage.poke("b0101".U)
        tb.io.write.en.poke(true.B)
        tb.clock.step(1)
        tb.io.write.en.poke(false.B)

        // Read the pixel back and inspect every sample individually.
        pokeIdle(tb)
        tb.io.read.idx.poke(5.U)
        tb.io.read.en.poke(true.B)
        tb.clock.step(1)
        tb.io.read.en.poke(false.B)
        tb.clock.step(1)

        for (s <- 0 until 4) {
          val r = tb.io.read.data(s).r.peek().litValue.toInt
          val z = tb.io.read.data(s).z.peek().litValue.toInt
          val covered = (s == 0 || s == 2)
          println(f"  sample $s: R=0x$r%04x Z=0x$z%04x (covered=$covered)")
          if (covered) {
            utest.assert(r == 0x3C00)
            utest.assert(z == 0x3000)
          } else {
            // Untouched samples must still hold the post-clear values.
            utest.assert(r == 0x0000)
            utest.assert(z == FP16_MAX_DEPTH)
          }
        }
        println("  Covered samples updated, uncovered samples preserved ✓")
        println("  PASSED")
      }
    }

    utest.test("msaa_clear_writes_all_samples") {
      simulate(new BorgTileBuffer(16, 4)) { tb =>
        println("\n--- BorgTileBuffer: msaa_clear_writes_all_samples ---")
        resetModule(tb)

        // A clear has no per-sample coverage: every sample of every pixel takes
        // the clear value (this is what makes a cleared MSAA image resolve to
        // exactly the clear colour).
        for (i <- 0 until 16; s <- 0 until 4) {
          pokeIdle(tb)
          tb.io.read.idx.poke(i.U)
          tb.io.read.en.poke(true.B)
          tb.clock.step(1)
          tb.io.read.en.poke(false.B)
          tb.clock.step(1)
          val z = tb.io.read.data(s).z.peek().litValue.toInt
          utest.assert(z == FP16_MAX_DEPTH)
        }
        println("  All 16 pixels × 4 samples = 0x7BFF after reset clear ✓")
        println("  PASSED")
      }
    }

    // --- colorBits=8 (BorgConfig.tileColorBits): the narrow-storage path ---
    // ColorQuantizeTests already verifies the pure quantize8/dequantize8
    // functions exhaustively; these exercise them wired into the real
    // module -- the actual SyncReadMem width change, the encode-on-write /
    // decode-on-read plumbing, and (critically) that per-sample MSAA write
    // masking still works correctly against the now-narrower storage word.

    utest.test("write_read_roundtrip_narrow_color") {
      simulate(new BorgTileBuffer(16, 1, 8)) { tb =>
        println("\n--- BorgTileBuffer: write_read_roundtrip_narrow_color ---")
        resetModule(tb)

        // 1.0, 0.5, 0.0 all quantize to an EXACT UNORM8 (255, 128, 0), but the
        // dequantized FP16 bit pattern is not required to match the original
        // bits: dequantize8's u*257/65536 approximation of u/255 means even
        // these "nice" values are off by up to 1 FP16 ULP after the round
        // trip (matches ColorQuantizeTests' own dequantize8_exhaustive_256_values,
        // which allows exactly this and covers u=255/128/0 as part of its
        // exhaustive sweep). Z is untouched by colorBits and must stay
        // bit-exact regardless.
        writePixel(tb, idx = 0, r = 0x3C00, g = 0x3800, b = 0x0000, z = 0x3000)
        val (r, g, b, z) = readPixel(tb, idx = 0)
        println(f"  Read back: R=0x$r%04X G=0x$g%04X B=0x$b%04X Z=0x$z%04X")
        val gotR = BorgTests.bitsToFloat(BigInt(r), FloatConfig.FP16)
        val gotG = BorgTests.bitsToFloat(BigInt(g), FloatConfig.FP16)
        utest.assert(math.abs(gotR - 1.0f) < (1.5f / 255))
        utest.assert(math.abs(gotG - 0.5f) < (1.5f / 255))
        utest.assert(b == 0x0000)
        utest.assert(z == 0x3000) // Z must be bit-exact: never quantized
        println("  PASSED")
      }
    }

    utest.test("narrow_color_is_lossy_but_bounded") {
      // A value with NO exact UNORM8 representation must still come back
      // close (this is the whole trade this config makes), matching
      // ColorQuantizeTests' own +/-1-in-256 characterization.
      simulate(new BorgTileBuffer(16, 1, 8)) { tb =>
        println("\n--- BorgTileBuffer: narrow_color_is_lossy_but_bounded ---")
        resetModule(tb)

        val oneThird = BorgTests.floatToBits(1.0f / 3, FloatConfig.FP16).toInt
        writePixel(tb, idx = 3, r = oneThird, g = 0, b = 0, z = 0x3000)
        val (r, _, _, z) = readPixel(tb, idx = 3)
        val got  = BorgTests.bitsToFloat(BigInt(r), FloatConfig.FP16)
        val want = 1.0f / 3
        println(f"  wrote 1/3 (fp16 0x$oneThird%04x), read back 0x$r%04x = $got%.5f")
        utest.assert(math.abs(got - want) < (1.5f / 255)) // within ~1.5 UNORM8 steps
        utest.assert(z == 0x3000) // still bit-exact
        println("  PASSED")
      }
    }

    utest.test("msaa_partial_coverage_write_narrow_color") {
      // Same scenario as msaa_partial_coverage_write, at colorBits=8: proves
      // per-sample write masking is unaffected by the narrower stored word
      // width (each sample plane is still its own SyncReadMem, just narrower).
      simulate(new BorgTileBuffer(16, 4, 8)) { tb =>
        println("\n--- BorgTileBuffer: msaa_partial_coverage_write_narrow_color ---")
        resetModule(tb)

        pokeIdle(tb)
        tb.io.write.idx.poke(5.U)
        tb.io.write.data.r.poke(0x3C00.U) // 1.0
        tb.io.write.data.g.poke(0x3800.U) // 0.5
        tb.io.write.data.b.poke(0x0000.U)
        tb.io.write.data.z.poke(0x3000.U)
        tb.io.write.coverage.poke("b0101".U)
        tb.io.write.en.poke(true.B)
        tb.clock.step(1)
        tb.io.write.en.poke(false.B)

        pokeIdle(tb)
        tb.io.read.idx.poke(5.U)
        tb.io.read.en.poke(true.B)
        tb.clock.step(1)
        tb.io.read.en.poke(false.B)
        tb.clock.step(1)

        for (s <- 0 until 4) {
          val r = tb.io.read.data(s).r.peek().litValue.toInt
          val z = tb.io.read.data(s).z.peek().litValue.toInt
          val covered = (s == 0 || s == 2)
          println(f"  sample $s: R=0x$r%04x Z=0x$z%04x (covered=$covered)")
          if (covered) {
            // See write_read_roundtrip_narrow_color: 1.0 -> UNORM8 255 exactly,
            // but the dequantized bits can be up to 1 FP16 ULP off 0x3C00.
            val got = BorgTests.bitsToFloat(BigInt(r), FloatConfig.FP16)
            utest.assert(math.abs(got - 1.0f) < (1.5f / 255))
            utest.assert(z == 0x3000)
          } else {
            utest.assert(r == 0x0000)
            utest.assert(z == FP16_MAX_DEPTH)
          }
        }
        println("  Covered samples updated, uncovered samples preserved (narrow) ✓")
        println("  PASSED")
      }
    }

    utest.test("clear_color_quantized_narrow_color") {
      // The clear path (encodeStored(io.clear.color)) is a separate code path
      // from the write path (encodeStored(io.write.data)) -- exercise it with
      // a non-exact value specifically, not just the RGB=0 every other test
      // (including the default resetModule clear) already covers.
      simulate(new BorgTileBuffer(16, 1, 8)) { tb =>
        println("\n--- BorgTileBuffer: clear_color_quantized_narrow_color ---")
        resetModule(tb)
        val oneThird = BorgTests.floatToBits(1.0f / 3, FloatConfig.FP16).toInt
        tb.io.clear.color.r.poke(oneThird.U)
        tb.io.clear.color.g.poke(0.U)
        tb.io.clear.color.b.poke(0.U)
        tb.io.clear.color.z.poke(FP16_MAX_DEPTH.U)
        tb.io.clear.en.poke(true.B)
        tb.clock.step(1)
        tb.io.clear.en.poke(false.B)
        tb.clock.step(18)

        val (r, _, _, z) = readPixel(tb, idx = 0)
        val got  = BorgTests.bitsToFloat(BigInt(r), FloatConfig.FP16)
        println(f"  clear color 1/3, read back R=0x$r%04x = $got%.5f")
        utest.assert(math.abs(got - 1.0f / 3) < (1.5f / 255))
        utest.assert(z == FP16_MAX_DEPTH)
        println("  PASSED")
      }
    }
  }
}
