// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** msaaMultiPass: one live plane plus a colour accumulator instead of one
  * plane per sample.
  *
  * The tile is rendered once per sample and the passes are averaged, so 4x
  * MSAA costs 1,376 stored bits instead of 3,584. See BorgConfig.msaaMultiPass
  * for why that is still MSAA and still Vulkan-conformant.
  *
  * Sample 0 is rendered LAST by convention: its colour and Z stay in the
  * working plane, which is what the sample-zero depth resolve reads, so the
  * accumulator carries no Z.
  */
object BorgTileBufferMultiPassTests extends TestSuite {

  val FP16_MAX_DEPTH = 0x7BFF
  val WHITE = 0x3C00   // FP16 1.0, as written
  // ...and as it reads back. Tile colour is stored UNORM8 (colorBits = 8), so
  // 1.0 quantizes to 255 and dequantize8(255) is Cat(255,255) * 2^-16 =
  // 0.99998, whose nearest FP16 is 0x3BFF. Every colour compared below is in
  // this stored domain, which is also the domain the accumulator averages in.
  val WHITE_Q = 0x3BFF
  val BLACK = 0x0000

  def mk = new BorgTileBuffer(dataBits = 16, samples = 4, colorBits = 8,
                              hasStencil = true, hasAlpha = true, multiPass = true)

  def pokeIdle(tb: BorgTileBuffer): Unit = {
    tb.io.write.idx.poke(0.U); tb.io.write.en.poke(false.B)
    tb.io.write.data.r.poke(0.U); tb.io.write.data.g.poke(0.U)
    tb.io.write.data.b.poke(0.U); tb.io.write.data.z.poke(0.U)
    tb.io.write.coverage.poke(0xF.U)
    tb.io.read.idx.poke(0.U); tb.io.read.en.poke(false.B)
    tb.io.clear.en.poke(false.B)
    tb.io.clear.color.r.poke(0.U); tb.io.clear.color.g.poke(0.U)
    tb.io.clear.color.b.poke(0.U); tb.io.clear.color.z.poke(FP16_MAX_DEPTH.U)
    tb.io.stencilWrite.foreach(_.poke(0.U))
    tb.io.stencilWriteMask.foreach(_.poke(0.U))
    tb.io.stencilClear.foreach(_.poke(0.U))
    tb.io.alphaWrite.foreach(_.poke(0.U))
    tb.io.alphaWriteMask.foreach(_.poke(false.B))
    tb.io.alphaClear.foreach(_.poke(0xFF.U))
    tb.io.pass.foreach { p =>
      p.sampleIdx.poke(0.U); p.accumEn.poke(false.B)
      p.accumFirst.poke(false.B); p.resolve.poke(false.B)
    }
  }

  def resetModule(tb: BorgTileBuffer): Unit = {
    tb.reset.poke(true.B); tb.clock.step(1); tb.reset.poke(false.B)
    tb.clock.step(20)   // let the reset auto-clear finish
  }

  /** Write one colour to every pixel of the tile for the given sample. */
  def paint(tb: BorgTileBuffer, sample: Int, rgb: Int): Unit = {
    pokeIdle(tb)
    tb.io.pass.get.sampleIdx.poke(sample.U)
    for (i <- 0 until 16) {
      tb.io.write.idx.poke(i.U)
      tb.io.write.data.r.poke(rgb.U); tb.io.write.data.g.poke(rgb.U)
      tb.io.write.data.b.poke(rgb.U); tb.io.write.data.z.poke(0x3000.U)
      tb.io.write.coverage.poke((1 << sample).U)
      tb.io.write.en.poke(true.B)
      tb.clock.step(1)
    }
    tb.io.write.en.poke(false.B)
  }

  /** Fold the working plane into the accumulator and wait for the sweep. */
  def accumulate(tb: BorgTileBuffer, first: Boolean): Unit = {
    pokeIdle(tb)
    tb.io.pass.get.accumFirst.poke(first.B)
    tb.io.pass.get.accumEn.poke(true.B)
    tb.clock.step(1)
    tb.io.pass.get.accumEn.poke(false.B)
    tb.io.pass.get.accumFirst.poke(false.B)
    tb.clock.step(20)
  }

  def readResolved(tb: BorgTileBuffer, idx: Int): (Int, Int) = {
    pokeIdle(tb)
    tb.io.pass.get.resolve.poke(true.B)
    tb.io.read.idx.poke(idx.U); tb.io.read.en.poke(true.B)
    tb.clock.step(1)
    tb.io.read.en.poke(false.B)
    tb.clock.step(1)
    val r = tb.io.read.data(0).r.peek().litValue.toInt
    val z = tb.io.read.data(0).z.peek().litValue.toInt
    tb.io.pass.get.resolve.poke(false.B)
    (r, z)
  }

  val tests = Tests {

    utest.test("only_the_passes_own_sample_bit_writes") {
      simulate(mk) { tb =>
        println("\n--- multiPass: coverage is selected by sampleIdx ---")
        resetModule(tb)

        // Pass for sample 2. A coverage mask WITHOUT bit 2 must not write,
        // even though other bits are set -- there is only one plane and it
        // belongs to this pass.
        pokeIdle(tb)
        tb.io.pass.get.sampleIdx.poke(2.U)
        tb.io.write.idx.poke(5.U)
        tb.io.write.data.r.poke(WHITE.U); tb.io.write.data.z.poke(0x3000.U)
        tb.io.write.coverage.poke("b1011".U)   // bit 2 clear
        tb.io.write.en.poke(true.B)
        tb.clock.step(1); tb.io.write.en.poke(false.B)

        pokeIdle(tb)
        tb.io.read.idx.poke(5.U); tb.io.read.en.poke(true.B)
        tb.clock.step(1); tb.io.read.en.poke(false.B); tb.clock.step(1)
        val rMiss = tb.io.read.data(0).r.peek().litValue.toInt
        println(f"  coverage 0b1011 at sampleIdx=2 -> R=0x$rMiss%04x (expect 0)")
        utest.assert(rMiss == 0)

        // Now WITH bit 2 set.
        pokeIdle(tb)
        tb.io.pass.get.sampleIdx.poke(2.U)
        tb.io.write.idx.poke(5.U)
        tb.io.write.data.r.poke(WHITE.U); tb.io.write.data.z.poke(0x3000.U)
        tb.io.write.coverage.poke("b0100".U)
        tb.io.write.en.poke(true.B)
        tb.clock.step(1); tb.io.write.en.poke(false.B)

        pokeIdle(tb)
        tb.io.read.idx.poke(5.U); tb.io.read.en.poke(true.B)
        tb.clock.step(1); tb.io.read.en.poke(false.B); tb.clock.step(1)
        val rHit = tb.io.read.data(0).r.peek().litValue.toInt
        println(f"  coverage 0b0100 at sampleIdx=2 -> R=0x$rHit%04x (expect quantized white)")
        utest.assert(rHit == WHITE_Q)

        // Every read index reports the same live plane, so the dispatcher's
        // data(dstIdx) indexing keeps working unchanged.
        for (s <- 0 until 4)
          utest.assert(tb.io.read.data(s).r.peek().litValue.toInt == rHit)
        println("  all four read lanes mirror the live plane ✓")
        println("  PASSED")
      }
    }

    utest.test("four_equal_passes_resolve_to_that_colour") {
      simulate(mk) { tb =>
        println("\n--- multiPass: 4 equal passes average to themselves ---")
        resetModule(tb)

        // Passes for samples 1,2,3 accumulate; sample 0 runs LAST and stays
        // in the working plane.
        paint(tb, 1, WHITE); accumulate(tb, first = true)
        paint(tb, 2, WHITE); accumulate(tb, first = false)
        paint(tb, 3, WHITE); accumulate(tb, first = false)
        paint(tb, 0, WHITE)

        val (r, z) = readResolved(tb, 7)
        println(f"  resolve -> R=0x$r%04x Z=0x$z%04x (expect quantized white, near Z)")
        utest.assert(r == WHITE_Q)
        utest.assert(z == 0x3000)   // sample 0's Z, straight from the working plane
        println("  PASSED")
      }
    }

    utest.test("all_black_passes_resolve_to_black") {
      simulate(mk) { tb =>
        println("\n--- multiPass: 4 black passes stay black ---")
        resetModule(tb)
        paint(tb, 1, BLACK); accumulate(tb, first = true)
        paint(tb, 2, BLACK); accumulate(tb, first = false)
        paint(tb, 3, BLACK); accumulate(tb, first = false)
        paint(tb, 0, BLACK)
        val (r, _) = readResolved(tb, 3)
        println(f"  resolve -> R=0x$r%04x (expect 0)")
        utest.assert(r == BLACK)
        println("  PASSED")
      }
    }

    utest.test("mixed_passes_land_between_the_extremes") {
      simulate(mk) { tb =>
        println("\n--- multiPass: 3 white + 1 black averages between ---")
        resetModule(tb)
        paint(tb, 1, WHITE); accumulate(tb, first = true)
        paint(tb, 2, WHITE); accumulate(tb, first = false)
        paint(tb, 3, WHITE); accumulate(tb, first = false)
        paint(tb, 0, BLACK)          // sample 0 last, as always

        val (r, _) = readResolved(tb, 11)
        println(f"  resolve -> R=0x$r%04x (expect strictly between 0 and 0x3C00)")
        // 3/4 white in UNORM8 is 191/255; the exact FP16 depends on
        // ColorQuantize's dequantize table, so assert the ordering that any
        // correct average must satisfy rather than a hard-coded pattern.
        utest.assert(r > BLACK)
        utest.assert(r < WHITE_Q)
        println("  PASSED")
      }
    }
    utest.test("alpha_is_averaged_across_passes_like_colour") {
      // A 32-bit colour flush writes alpha out, so at multiPass alpha must
      // resolve the same way colour does -- summed in the accumulator, not
      // left as whatever sample 0 (the last pass) happened to write.
      simulate(mk) { tb =>
        println("\n--- multiPass: alpha resolves to the average ---")
        resetModule(tb)
        def paintAlpha(sample: Int, a: Int): Unit = {
          pokeIdle(tb)
          tb.io.pass.get.sampleIdx.poke(sample.U)
          tb.io.alphaWriteMask.get.poke(true.B)
          tb.io.alphaWrite.get.poke(a.U)
          for (i <- 0 until 16) {
            tb.io.write.idx.poke(i.U)
            tb.io.write.data.z.poke(0x3000.U)
            tb.io.write.coverage.poke((1 << sample).U)
            tb.io.write.en.poke(true.B)
            tb.clock.step(1)
          }
          tb.io.write.en.poke(false.B)
        }
        // Alphas 40, 80, 120, then sample 0 last with 200: average 110. Sample
        // 0 alone would read 200, and a sum of the first three without the
        // working plane would give 60 -- both distinguishable.
        paintAlpha(1, 40);  accumulate(tb, first = true)
        paintAlpha(2, 80);  accumulate(tb, first = false)
        paintAlpha(3, 120); accumulate(tb, first = false)
        paintAlpha(0, 200)
        readResolved(tb, 9)
        val aRes = tb.io.alphaRead.get(0).peek().litValue.toInt
        // A non-resolving read still returns the live plane.
        pokeIdle(tb)
        tb.io.read.idx.poke(9.U); tb.io.read.en.poke(true.B)
        tb.clock.step(1); tb.io.read.en.poke(false.B); tb.clock.step(1)
        val aLive = tb.io.alphaRead.get(0).peek().litValue.toInt
        println(s"  resolved alpha = $aRes (expect 110), live plane = $aLive (expect 200)")
        utest.assert(aRes == 110)
        utest.assert(aLive == 200)
        println("  PASSED")
      }
    }
  }
}
