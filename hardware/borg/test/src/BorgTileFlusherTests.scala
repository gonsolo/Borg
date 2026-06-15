// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import scala.collection.mutable.ArrayBuffer

/** Unit tests for BorgTileFlusher — one 64-word burst per tile.
  *
  * The flusher reads all 16 tile-buffer entries ({R,G,B,Z}) and streams the whole
  * tile to SDRAM as a single 64-word burst write (R,G,B,Z per pixel, in raster
  * order).  These tests mock the tile-buffer read port and play the role of the
  * MemoryController: present `waccept` per word, then `ready` at the end, while
  * collecting the streamed words to verify order and content (Z now included).
  */
object BorgTileFlusherTests extends TestSuite {

  // Mock tile entry e: distinct value per (entry, channel) so a reorder/drop shows.
  def entR(e: Int): Long = 0x1000L + e
  def entG(e: Int): Long = 0x2000L + e
  def entB(e: Int): Long = 0x3000L + e
  def entZ(e: Int): Long = 0x4000L + e
  // Expected SDRAM word w: pixel e = w/4, channel = w%4 (R,G,B,Z order).
  def wordVal(w: Int): Long = (w % 4) match {
    case 0 => entR(w / 4)
    case 1 => entG(w / 4)
    case 2 => entB(w / 4)
    case _ => entZ(w / 4)
  }

  val tests = Tests {

    utest.test("flusher streams 64 words (R,G,B,Z x 16) in one burst, correct order") {
      simulate(new BorgTileFlusher) { dut =>
        var cycle = 0

        // BorgTileBuffer has a 2-cycle read latency:
        //   cycle T  : flusher drives io.read.en=1, io.read.idx=N
        //   cycle T+1: SyncReadMem output valid; readDataHeld latches it
        //   cycle T+2: io.read.data = readDataHeld holds the result
        //
        // Model this with a 2-stage shift register so the test drives
        // io.read.data with the right entry at the right cycle.
        var pipe0: Option[Int] = None  // request issued this cycle
        var pipe1: Option[Int] = None  // request from 1 cycle ago (readDataHeld)

        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          // Drive io.read.data from whatever is in the hold-register stage.
          pipe1.foreach { i =>
            dut.io.read.data.r.poke(entR(i).U)
            dut.io.read.data.g.poke(entG(i).U)
            dut.io.read.data.b.poke(entB(i).U)
            dut.io.read.data.z.poke(entZ(i).U)
          }
          // Sample the flusher's current read request (combinational output).
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
          // Advance pipeline: stage1 ← stage0 ← new request.
          pipe1 = pipe0
          pipe0 = if (en) Some(idx) else None
          dut.clock.step()
          cycle += 1
          Predef.assert(cycle < 5000, "TIMEOUT")
        }

        dut.reset.poke(true.B);  step(4)
        dut.reset.poke(false.B)
        dut.io.start.poke(false.B)
        dut.io.tileBase.poke(0.U)
        dut.io.gpuMem.ready.poke(false.B)
        dut.io.gpuMem.waccept.poke(false.B)
        dut.io.gpuMem.data.poke(0.U)
        step(2)

        // Trigger the flush.
        dut.io.tileBase.poke(0x2000.U)
        dut.io.start.poke(true.B)
        step()
        dut.io.start.poke(false.B)

        // Wait for the read-in phase to finish and the burst to start.
        var guard = 0
        while (!(dut.io.gpuMem.wr.peek().litToBoolean &&
                 dut.io.gpuMem.wlen.peek().litValue.toInt == 64) && guard < 500) {
          step(); guard += 1
        }
        Predef.assert(guard < 500, "burst never started")
        Predef.assert(dut.io.gpuMem.addr.peek().litValue.toInt == 0x2000,
          "burst base address mismatch")
        val burstStart = cycle

        // Play the controller: collect the current word, pulse waccept to advance.
        val collected = ArrayBuffer[Long]()
        for (w <- 0 until 64) {
          Predef.assert(dut.io.gpuMem.wr.peek().litToBoolean, s"wr dropped at word $w")
          collected += (dut.io.gpuMem.wdata.peek().litValue.toLong & 0xFFFFL)
          if (w < 63) {
            dut.io.gpuMem.waccept.poke(true.B)
            step()
            dut.io.gpuMem.waccept.poke(false.B)
          }
        }
        val burstCycles = cycle - burstStart

        // End the burst.
        dut.io.gpuMem.ready.poke(true.B)
        step()
        dut.io.gpuMem.ready.poke(false.B)
        step()
        Predef.assert(!dut.io.gpuMem.wr.peek().litToBoolean, "wr still high after ready")
        Predef.assert(!dut.io.busy.peek().litToBoolean, "flusher still busy after ready")

        // Verify all 64 words, in order, including the Z slots.
        var errors = 0
        for (w <- 0 until 64) {
          if (collected(w) != wordVal(w)) {
            println(f"  word $w%2d: got 0x${collected(w).toHexString} exp 0x${wordVal(w).toHexString}")
            errors += 1
          }
        }
        Predef.assert(errors == 0, s"$errors word mismatches in the tile burst")
        println(s"[flusher] 64-word tile burst streamed in $burstCycles cycles, all words correct")
      }
    }
  }
}
