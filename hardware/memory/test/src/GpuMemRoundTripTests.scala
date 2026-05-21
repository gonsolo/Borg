// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// GPU write → SDRAM → GPU read round-trip test.
//
// Writes 0xF800 (red pixel, 16-bit) to individual pixel addresses via the
// GPU write port, then reads them back as 32-bit words via the GPU read port.
// Verifies data integrity through the full MemoryController + SdramBackendSim path.

package memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object GpuMemRoundTripTests extends TestSuite {

  val tests = Tests {

    utest.test("GPU 16-bit write then 32-bit read round-trip") {
      simulate(new GpuMemTestHarness) { dut =>

        val TIMEOUT = 200000
        var cycle = 0

        def tick(n: Int = 1): Unit = {
          for (_ <- 0 until n) dut.clock.step()
          cycle += n
          Predef.assert(cycle < TIMEOUT, s"TIMEOUT at cycle $cycle")
        }

        // Reset
        dut.reset.poke(true.B)
        tick(5)
        dut.reset.poke(false.B)
        tick(5)
        cycle = 10

        // Deassert all
        dut.io.gpuReq.poke(false.B)
        dut.io.gpuWr.poke(false.B)
        dut.io.gpuAddr.poke(0.U)
        dut.io.gpuWdata.poke(0.U)

        // Wait for backend idle
        var waited = 0
        while (dut.io.memBusy.peek().litToBoolean && waited < 1000) {
          tick(); waited += 1
        }
        println(s"[$cycle] Backend idle")

        // ── Phase 1: Write 0xF800 (16-bit) to each pixel address ──
        // SDRAM backend writes one 16-bit word per transaction (data_txn_len=1)
        val pixelVal  = 0xF800L       // red in RGB565
        val baseAddr  = 0x1000
        val numPixels = 64            // 64 pixels = 128 bytes

        for (i <- 0 until numPixels) {
          val addr = baseAddr + i * 2  // 2 bytes per pixel

          dut.io.gpuWr.poke(true.B)
          dut.io.gpuAddr.poke(addr.U)
          dut.io.gpuWdata.poke(pixelVal.U)  // only lower 16 bits used
          tick()

          waited = 0
          while (!dut.io.gpuReady.peek().litToBoolean && waited < 500) {
            tick(); waited += 1
          }
          Predef.assert(waited < 500, s"GPU write $i at addr 0x${addr.toHexString} never completed")

          dut.io.gpuWr.poke(false.B)
          tick()

          waited = 0
          while (dut.io.memBusy.peek().litToBoolean && waited < 500) {
            tick(); waited += 1
          }

          if (i < 3 || i == numPixels - 1) {
            println(s"[$cycle] GPU write $i: addr=0x${addr.toHexString} data=0x${pixelVal.toHexString}")
          }
        }
        println(s"[$cycle] ── All $numPixels GPU writes complete ──")

        // ── Phase 2: Read back as 32-bit words (2 pixels each) ──
        val numWords = numPixels / 2
        val expected = (pixelVal << 16) | pixelVal  // 0xF800F800
        var errors = 0

        for (i <- 0 until numWords) {
          val addr = baseAddr + i * 4

          dut.io.gpuReq.poke(true.B)
          dut.io.gpuAddr.poke(addr.U)
          tick()

          waited = 0
          while (!dut.io.gpuReady.peek().litToBoolean && waited < 500) {
            tick(); waited += 1
          }
          Predef.assert(waited < 500, s"GPU read $i at addr 0x${addr.toHexString} never completed")

          val readData = dut.io.gpuData.peek().litValue.toLong & 0xFFFFFFFFL

          dut.io.gpuReq.poke(false.B)
          tick()

          waited = 0
          while (dut.io.memBusy.peek().litToBoolean && waited < 500) {
            tick(); waited += 1
          }

          if (readData != expected) {
            println(s"[$cycle] ✗ GPU read $i: addr=0x${addr.toHexString} got=0x${readData.toHexString} expected=0x${expected.toHexString}")
            errors += 1
          } else if (i < 3 || i == numWords - 1) {
            println(s"[$cycle] ✓ GPU read $i: addr=0x${addr.toHexString} data=0x${readData.toHexString}")
          }
        }

        println(s"\n[$cycle] ══ Result: $errors errors out of $numWords reads ══")
        Predef.assert(errors == 0, s"$errors GPU read mismatches!")
        println(s"[$cycle] ✓ All GPU write/read round-trips passed!")
      }
    }
  }
}
