// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** [[TileOrder]] -- the permutation behind `BorgConfig.stochasticTiles`.
  *
  * The property that matters is BIJECTIVITY: pass 2 must render and flush every
  * tile exactly once, so a repeated or skipped tile would leave a stale or
  * missing block in the framebuffer. Exhaustive over the whole domain rather
  * than sampled, since the domains are tiny.
  */
object BorgTileOrderTests extends TestSuite {

  private class PermIO(k: Int) extends Bundle {
    val x = Input(UInt(k.W))
    val y = Output(UInt(k.W))
  }

  private class PermDut(k: Int) extends Module {
    val io = IO(new PermIO(k))
    io.y := TileOrder.perm(io.x, k)
  }

  val tests = Tests {

    utest.test("model is a bijection on [0, 2^k) for every supported k") {
      for (k <- 2 to 12) {
        val images = (0 until (1 << k)).map(x => TileOrder.permModel(x, k))
        utest.assert(images.toSet.size == (1 << k))
        utest.assert(images.forall(v => v >= 0 && v < (1 << k)))
      }
    }

    utest.test("cycle walk visits every tile of an n-tile grid exactly once") {
      // 1024 = the 128x128 demo (32x32 tiles) at maxBinTiles=1024/4096;
      // the others check that non-full grids still walk to a bijection.
      for ((n, k) <- Seq((1024, 10), (1024, 12), (256, 10), (64, 6), (16, 4), (1000, 10), (700, 12))) {
        val visited = (0 until n).map(o => TileOrder.walkModel(o, n, k))
        utest.assert(visited.toSet.size == n)
        utest.assert(visited.forall(v => v >= 0 && v < n))
      }
    }

    utest.test("hardware perm matches the model over the full 10-bit domain") {
      simulate(new PermDut(10)) { dut =>
        for (x <- 0 until 1024) {
          dut.io.x.poke(x.U)
          dut.clock.step(1)
          val got = dut.io.y.peek().litValue
          utest.assert(got == TileOrder.permModel(x, 10))
        }
      }
    }

    utest.test("order is scattered, not raster") {
      // Not a randomness proof -- a tripwire against an accidentally
      // near-identity mix. In raster order 31 of every 32 consecutive tiles
      // are horizontal neighbours; require almost none here.
      val n = 1024; val w = 32
      val seq = (0 until n).map(o => TileOrder.walkModel(o, n, 10))
      val adjacent = seq.sliding(2).count { case Seq(a, b) =>
        (a / w == b / w) && math.abs(a % w - b % w) <= 1 }
      println(f"  consecutive tiles that are horizontal neighbours: $adjacent%d / ${n - 1}%d (raster: ${n - n / w}%d)")
      println("  first 16 tiles visited: " + seq.take(16).mkString(" "))
      utest.assert(adjacent < 64)
    }

    // Elaboration through firtool catches the width and unconnected-port class
    // of bug in the sequencer edit itself (see BorgFp32ElabTests). Both
    // settings: the default must be unchanged, and the option must build.
    utest.test("Borg elaborates with stochasticTiles off and on") {
      for (on <- Seq(false, true)) {
        val sv = circt.stage.ChiselStage.emitSystemVerilog(
          new Borg(BorgConfig.Test.copy(stochasticTiles = on)))
        utest.assert(sv.nonEmpty)
        utest.assert(sv.contains("BorgTileSequencer"))
        println(s"  Borg(stochasticTiles=$on) elaborated: ${sv.length} chars")
      }
    }
  }
}
