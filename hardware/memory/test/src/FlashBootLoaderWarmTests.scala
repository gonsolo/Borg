// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

// Warm-reload test: with io.warmBoot held high, the FlashBootLoader must skip the
// flash/SPI path entirely and instead COPY the firmware image from the SDRAM
// scratch region (word base WARM_SCRATCH_WORD) to SDRAM word 0, reading a 4-byte
// LE size header first.  This is the path the serial firmware reload uses so the
// HDMI video domain never resets.
//
// Software SDRAM model (16-bit-word MemBackendIO, supports BOTH reads and writes):
//   The real backend sets busy=1 the cycle AFTER a start pulse and pulses done
//   later with dataOut valid (reads).  We model that with a small phase machine,
//   and because the warm FSM issues transactions back-to-back (gating each start
//   on !busy), we re-check the start lines right after clearing busy.

import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth}
import chisel3.simulator.EphemeralSimulator._
import utest._

object FlashBootLoaderWarmTests extends TestSuite {
  val tests: Tests = Tests {

    utest.test("warm_reload_scratch_copy") {
      // Use small parameters so the test is fast: scratch at word 0x10, copy a
      // 6-byte image (3 words).  Header = size(LE u32) at scratch[0..1].
      val SCRATCH = 0x10
      simulate(new FlashBootLoader(WARM_SCRATCH_WORD = SCRATCH.toLong)) { dut =>
        // Scratch SDRAM contents (word address → 16-bit value).
        // [SCRATCH+0]=size lo16, [SCRATCH+1]=size hi16, then image words.
        val image = Array(0xAABB, 0xCCDD, 0xEE0F)   // 3 words = 6 bytes
        val mem = scala.collection.mutable.Map[Int, Int](
          (SCRATCH + 0) -> (6 & 0xFFFF),            // size lo = 6
          (SCRATCH + 1) -> ((6 >> 16) & 0xFFFF)     // size hi = 0
        )
        for (i <- image.indices) mem(SCRATCH + 2 + i) = image(i)

        dut.io.flash_miso.poke(false.B)
        dut.io.backend.busy.poke(false.B)
        dut.io.backend.done.poke(false.B)
        dut.io.backend.dataOut.poke(0.U)
        dut.io.warmBoot.poke(true.B)              // ← warm reload
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        val written = scala.collection.mutable.ArrayBuffer[(Int, Int)]()

        // phase: 0 idle, 1 busy-rising, 2 done+data, 3 finish
        var phase  = 0
        var isRead = false
        var addr   = 0
        var data   = 0

        def captureStart(): Boolean = {
          if (dut.io.backend.startRead.peek().litToBoolean) {
            addr = dut.io.backend.addrIn.peek().litValue.toInt
            isRead = true; true
          } else if (dut.io.backend.startWrite.peek().litToBoolean) {
            addr = dut.io.backend.addrIn.peek().litValue.toInt
            data = dut.io.backend.dataIn.peek().litValue.toInt
            isRead = false; true
          } else false
        }

        var cycles = 0
        val maxCycles = 5000
        while (!dut.io.boot_done.peek().litToBoolean && cycles < maxCycles) {
          phase match {
            case 0 => if (captureStart()) phase = 1
            case 1 => dut.io.backend.busy.poke(true.B); phase = 2
            case 2 =>
              dut.io.backend.done.poke(true.B)
              if (isRead) dut.io.backend.dataOut.poke(mem.getOrElse(addr, 0).U)
              else        written += ((addr, data))
              phase = 3
            case 3 =>
              dut.io.backend.done.poke(false.B)
              dut.io.backend.busy.poke(false.B)
              // back-to-back: the DUT may assert the next start now busy=0
              if (captureStart()) phase = 1 else phase = 0
            case _ =>
          }
          dut.clock.step(1)
          cycles += 1
        }

        println(s"WARM: boot_done after $cycles cycles; " +
                s"writes=${written.map { case (a, d) => f"[0x$a%x]=0x$d%04x" }.mkString(", ")}")
        Predef.assert(dut.io.boot_done.peek().litToBoolean, "boot_done never fired on warm reload")
        Predef.assert(written.length == 3, s"expected 3 word writes, got ${written.length}")
        Predef.assert(written(0) == (0, 0xAABB), s"write 0 wrong: ${written(0)}")
        Predef.assert(written(1) == (1, 0xCCDD), s"write 1 wrong: ${written(1)}")
        Predef.assert(written(2) == (2, 0xEE0F), s"write 2 wrong: ${written(2)}")
        println("WARM: scratch→0 copy ✓")
      }
    }
  }
}
