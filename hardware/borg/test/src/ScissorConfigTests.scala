// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Module wrapper so the scissor predicate can be driven directly. The
  * shipping path calls [[ScissorConfig.passes]] inline in BorgRasterizer. */
class ScissorProbeIO extends Bundle {
  val cfg  = Input(new ScissorConfig)
  val x    = Input(UInt(13.W))
  val y    = Input(UInt(13.W))
  val pass = Output(Bool())
}

class ScissorProbe extends Module {
  val io = IO(new ScissorProbeIO)
  io.pass := ScissorConfig.passes(io.cfg, io.x, io.y)
}

/** Tests for the scissor rectangle predicate.
  *
  * Small, but the two things it gets wrong are both silent and both change
  * every pixel of a frame: the half-open convention (x0 inclusive, x1
  * exclusive, so a `VkRect2D`'s `offset + extent` maps straight across), and
  * disabled meaning "everything passes" rather than "the empty rectangle".
  */
object ScissorConfigTests extends TestSuite {

  def pokeRect(d: ScissorProbe, enable: Boolean, x0: Int, x1: Int, y0: Int, y1: Int): Unit = {
    d.io.cfg.enable.poke(enable.B)
    d.io.cfg.x0.poke(x0.U); d.io.cfg.x1.poke(x1.U)
    d.io.cfg.y0.poke(y0.U); d.io.cfg.y1.poke(y1.U)
  }

  def at(d: ScissorProbe, x: Int, y: Int): Boolean = {
    d.io.x.poke(x.U); d.io.y.poke(y.U)
    d.clock.step(1)
    d.io.pass.peek().litToBoolean
  }

  val tests = Tests {

    utest.test("disabled_passes_everything_including_outside_the_rectangle") {
      simulate(new ScissorProbe) { d =>
        println("\n--- ScissorConfig: disabled ---")
        // The rectangle is deliberately set to something that would reject
        // these points if the enable were ignored -- and deliberately empty,
        // since "disabled" and "empty rectangle" must not be the same state.
        pokeRect(d, enable = false, x0 = 10, x1 = 10, y0 = 10, y1 = 10)
        for ((x, y) <- Seq((0, 0), (5, 5), (100, 200), (4095, 4095)))
          utest.assert(at(d, x, y))
        println("  every probed pixel passes ✓")
        println("  PASSED")
      }
    }

    utest.test("bounds_are_half_open_x0_inclusive_x1_exclusive") {
      simulate(new ScissorProbe) { d =>
        println("\n--- ScissorConfig: half-open bounds ---")
        // VkRect2D offset (10,20) extent (5,5) -> x in [10,15), y in [20,25).
        pokeRect(d, enable = true, x0 = 10, x1 = 15, y0 = 20, y1 = 25)

        utest.assert(at(d, 10, 20))   // top-left corner is inside
        utest.assert(at(d, 14, 24))   // bottom-right *last* pixel is inside
        utest.assert(!at(d, 15, 24))  // x1 itself is outside
        utest.assert(!at(d, 14, 25))  // y1 itself is outside
        utest.assert(!at(d, 9, 20))   // one left of x0
        utest.assert(!at(d, 10, 19))  // one above y0
        println("  [10,15) x [20,25): corners and all four edges correct ✓")

        // Exhaustive over a small neighbourhood, so an off-by-one on any
        // single edge cannot hide behind the corner checks above.
        for (x <- 8 until 18; y <- 18 until 28) {
          val exp = x >= 10 && x < 15 && y >= 20 && y < 25
          if (at(d, x, y) != exp) sys.error(s"($x,$y): expected $exp")
        }
        println("  100-pixel neighbourhood matches the reference ✓")
        println("  PASSED")
      }
    }

    utest.test("an_empty_rectangle_rejects_everything") {
      simulate(new ScissorProbe) { d =>
        println("\n--- ScissorConfig: empty rectangle ---")
        // x0 == x1 is a legal VkRect2D (extent.width == 0) and must discard
        // every fragment -- the opposite of the disabled state above.
        pokeRect(d, enable = true, x0 = 10, x1 = 10, y0 = 0, y1 = 100)
        for ((x, y) <- Seq((9, 50), (10, 50), (11, 50)))
          utest.assert(!at(d, x, y))
        println("  zero-width rectangle discards every fragment ✓")
        println("  PASSED")
      }
    }

    utest.test("a_full_screen_rectangle_passes_everything") {
      simulate(new ScissorProbe) { d =>
        println("\n--- ScissorConfig: full-screen rectangle ---")
        // The common case: enabled but covering the whole framebuffer. Must
        // be indistinguishable from disabled.
        pokeRect(d, enable = true, x0 = 0, x1 = 4096, y0 = 0, y1 = 4096)
        for ((x, y) <- Seq((0, 0), (4095, 4095), (2048, 1)))
          utest.assert(at(d, x, y))
        println("  0..4095 in both axes all pass ✓")
        println("  PASSED")
      }
    }
  }
}
