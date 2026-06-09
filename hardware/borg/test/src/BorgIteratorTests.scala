// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Phase 4: BorgIterator quad-emission tests.
  *
  * fragLanes==4: one advance emits a 2×2 quad; four advances tile the 4×4 tile.
  * fragLanes==1: the original single-pixel row-major walk (regression).
  */
object BorgIteratorTests extends TestSuite {

  private def reset(it: BorgIterator): Unit = {
    it.io.advance.poke(false.B)
    it.io.phaseIdle.poke(false.B)
    it.io.cmdPop.valid.poke(false.B)
    it.io.cmdPop.bits.tileOrigin.x.poke(0.U)
    it.io.cmdPop.bits.tileOrigin.y.poke(0.U)
    it.reset.poke(true.B); it.clock.step(2); it.reset.poke(false.B); it.clock.step(1)
  }

  private def popTile(it: BorgIterator, ox: Int, oy: Int): Unit = {
    it.io.phaseIdle.poke(true.B)
    it.io.cmdPop.valid.poke(true.B)
    it.io.cmdPop.bits.tileOrigin.x.poke(ox.U)
    it.io.cmdPop.bits.tileOrigin.y.poke(oy.U)
    it.clock.step(1)
    it.io.cmdPop.valid.poke(false.B)
  }

  /** Pulse advance once; return (tileComplete this advance, the per-lane shaderIter
    * coords latched, the per-lane shaderTileIndex). */
  private def advance(it: BorgIterator, lanes: Int): (Boolean, Seq[(Int, Int)], Seq[Int]) = {
    it.io.advance.poke(true.B)
    val tc = it.io.tileComplete.peek().litToBoolean       // combinational, before edge
    it.clock.step(1)                                      // latch shader_iter_reg
    val coords = (0 until lanes).map { i =>
      (it.io.shaderIter(i).x.peek().litValue.toInt, it.io.shaderIter(i).y.peek().litValue.toInt)
    }
    val idx = (0 until lanes).map(i => it.io.shaderTileIndex(i).peek().litValue.toInt)
    it.io.advance.poke(false.B)
    (tc, coords, idx)
  }

  val tests = Tests {

    utest.test("quad_emission_4lane") {
      simulate(new BorgIterator(BorgConfig.Default.copy(fragLanes = 4))) { it =>
        reset(it)
        popTile(it, 0, 0)

        val expectedOrigins = Seq((0, 0), (2, 0), (0, 2), (2, 2))
        val allIdx = scala.collection.mutable.ArrayBuffer[Int]()
        var lastTc = false
        for (q <- 0 until 4) {
          val (tc, coords, idx) = advance(it, 4)
          val (ox, oy) = expectedOrigins(q)
          val expCoords = Seq((ox, oy), (ox + 1, oy), (ox, oy + 1), (ox + 1, oy + 1))
          println(f"  quad $q origin=($ox,$oy): coords=$coords idx=$idx tc=$tc")
          utest.assert(coords == expCoords)
          allIdx ++= idx
          lastTc = tc
        }
        // tileComplete fires on the 4th (last) quad only
        utest.assert(lastTc)
        // the 16 tile slots are all distinct and cover 0..15
        utest.assert(allIdx.toSet == (0 until 16).toSet)
        println("  PASSED")
      }
    }

    utest.test("single_pixel_1lane") {
      simulate(new BorgIterator(BorgConfig.Default.copy(fragLanes = 1))) { it =>
        reset(it)
        popTile(it, 0, 0)

        val seen = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
        var lastTc = false
        for (_ <- 0 until 16) {
          val (tc, coords, _) = advance(it, 1)
          seen += coords.head
          lastTc = tc
        }
        // row-major 4×4 walk, 16 distinct pixels, complete on the 16th
        val expected = for (y <- 0 until 4; x <- 0 until 4) yield (x, y)
        println(s"  walk=$seen")
        utest.assert(seen.toList == expected.toList)
        utest.assert(lastTc)
        println("  PASSED")
      }
    }
  }
}
