// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import scala.collection.mutable.ArrayBuffer

/** Unit tests for BorgTileFlusher — one 16-word RGB565 burst per tile.
  *
  * The flusher reads all 16 tile-buffer entries ({R,G,B,Z}), converts each to a
  * single RGB565 halfword (depth dropped — TBR keeps Z on-chip only), and streams
  * the whole tile to SDRAM as one 16-word burst write (raster order).  These
  * tests mock the tile-buffer read port and play the role of the
  * MemoryController: present `waccept` per word, then `ready` at the end, while
  * collecting the streamed words to verify order and content.
  */
object BorgTileFlusherTests extends TestSuite {

  // ── Reference FP16 → RGB565, mirroring BorgTileFlusher.fp16ToUnorm ──
  def fp16ToUnorm(h: Int, bits: Int): Int = {
    val sign = (h >> 15) & 1
    val exp  = (h >> 10) & 0x1f
    val mant = h & 0x3ff
    val full = (1 << 10) | mant
    if (sign == 1 || exp < 7) 0
    else if (exp >= 15) (1 << bits) - 1
    else (full >> (17 - exp)) >> (8 - bits)
  }
  def rgb565(r: Int, g: Int, b: Int): Int =
    (fp16ToUnorm(r, 5) << 11) | (fp16ToUnorm(g, 6) << 5) | fp16ToUnorm(b, 5)

  // FP16 bit pattern for a finite float (round-to-nearest-even, no NaN/Inf).
  def f16(f: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(f)
    val sign = (bits >>> 16) & 0x8000
    val expF = ((bits >>> 23) & 0xff) - 127 + 15
    val mantF = bits & 0x7fffff
    if (f == 0.0f) sign
    else if (expF <= 0) sign  // flush subnormals to ±0 (good enough for the test)
    else if (expF >= 0x1f) sign | 0x7bff
    else sign | (expF << 10) | (mantF >> 13)
  }

  // Distinct, predictable colours per entry so a reorder/drop shows up.
  def entR(e: Int): Int = f16(e / 16.0f)          // 0 .. ~0.94
  def entG(e: Int): Int = f16((15 - e) / 16.0f)   // reverse ramp
  def entB(e: Int): Int = f16(0.5f)               // constant
  def entZ(e: Int): Int = 0x4000 + e              // dropped by the flusher
  def expWord(e: Int): Int = rgb565(entR(e), entG(e), entB(e))

  val tests = Tests {

    utest.test("flusher streams 16 RGB565 words in one burst, correct order") {
      simulate(new BorgTileFlusher) { dut =>
        var cycle = 0

        // BorgTileBuffer has a 2-cycle read latency:
        //   cycle T  : flusher drives io.read.en=1, io.read.idx=N
        //   cycle T+1: SyncReadMem output valid; readDataHeld latches it
        //   cycle T+2: io.read.data = readDataHeld holds the result
        var pipe0: Option[Int] = None  // request issued this cycle
        var pipe1: Option[Int] = None  // request from 1 cycle ago (readDataHeld)

        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          pipe1.foreach { i =>
            dut.io.read.data.r.poke(entR(i).U)
            dut.io.read.data.g.poke(entG(i).U)
            dut.io.read.data.b.poke(entB(i).U)
            dut.io.read.data.z.poke(entZ(i).U)
          }
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
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
                 dut.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 500) {
          step(); guard += 1
        }
        Predef.assert(guard < 500, "burst never started")
        Predef.assert(dut.io.gpuMem.addr.peek().litValue.toInt == 0x2000,
          "burst base address mismatch")
        val burstStart = cycle

        // Play the controller: collect the current word, pulse waccept to advance.
        val collected = ArrayBuffer[Int]()
        for (w <- 0 until 16) {
          Predef.assert(dut.io.gpuMem.wr.peek().litToBoolean, s"wr dropped at word $w")
          collected += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
          if (w < 15) {
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

        // Verify all 16 RGB565 words, in order.
        var errors = 0
        for (w <- 0 until 16) {
          if (collected(w) != expWord(w)) {
            println(f"  word $w%2d: got 0x${collected(w).toHexString} exp 0x${expWord(w).toHexString}")
            errors += 1
          }
        }
        Predef.assert(errors == 0, s"$errors word mismatches in the tile burst")
        println(s"[flusher] 16-word RGB565 tile burst streamed in $burstCycles cycles, all words correct")
      }
    }
  }
}
