// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

// D.3 end-to-end test: FlashBootLoader reads a simulated flash and writes
// the firmware bytes to SDRAM via a SdramBackend model.
//
// This test would have caught the dataReq && !busy protocol bug where
// sWrWaitDone hung forever because dataReq pulses while busy is still true.

import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth}
import chisel3.simulator.EphemeralSimulator._
import utest._

object FlashBootLoaderE2ETests extends TestSuite {
  val tests: Tests = Tests {

    utest.test("D3_full_boot_copy") {
      // The DUT samples MISO on every rising edge, including during cmd+addr
      // TX (4 bytes = 32 SPI clocks). Those bits land in shiftIn but are
      // discarded by the FSM. Pad 4 don't-care bytes so the real size header
      // aligns to offset 4 (i.e., is clocked in during sReadSize).
      val flashContent = Array(
        0x00, 0x00, 0x00, 0x00,   // cmd + 3 addr bytes (MISO ignored by DUT)
        0x04, 0x00, 0x00, 0x00,   // size header LE = 4
        0x01, 0x02, 0x03, 0x04    // payload → SDRAM: [0x0000]=0x0201, [0x0002]=0x0403
      )

      simulate(new FlashBootLoader()) { dut =>
        dut.io.flash_miso.poke(false.B)
        dut.io.backend.busy.poke(false.B)
        dut.io.backend.dataReq.poke(false.B)
        dut.io.backend.dataReady.poke(false.B)
        dut.io.backend.dataOut.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        var prevClk  = 0
        var prevCsn  = 1   // track CS to catch the falling edge of CS itself
        var flashByte = 0
        var flashBit  = 7

        val written = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
        // SdramBackend model phases:
        //  0 = idle (watch for startWrite)
        //  1 = got startWrite: busy=true, pulse dataReq (byte1 request) next cycle
        //  2 = latch byte1, drop dataReq
        //  3 = pulse dataReq (write done, busy still true)
        //  4 = drop dataReq + busy → back to idle
        var wrPhase = 0
        var wAddr   = 0
        var wByte0  = 0
        var wByte1  = 0

        var cycles    = 0
        val maxCycles = 200000

        // KEY: peek signals BEFORE clock.step — startWrite is combinatorial and
        // vanishes on the same edge that transitions state to sWrWaitDone.
        // Continue until boot_done AND backend model is idle (wrPhase==0),
        // so writes that complete on the same cycle as boot_done are recorded.
        while ((!dut.io.boot_done.peek().litToBoolean || wrPhase != 0) && cycles < maxCycles) {

          // 1. Read current SPI clock (gate to 0 when CS deasserted)
          val csn    = dut.io.flash_csn.peek().litValue.toInt
          val curClk = if (csn == 1) 0 else dut.io.spi_clk.peek().litValue.toInt
          val fall   = (prevClk == 1) && (curClk == 0)

          // 1a. Detect CS going low → drive bit7 of flashContent[0] immediately,
          //     so the first spiRise (before first fall) samples the correct MSB.
          val csnFall = (prevCsn == 1) && (csn == 0)
          if (csnFall) {
            val bit = if (flashByte < flashContent.length)
                        (flashContent(flashByte) >> flashBit) & 1
                      else 0
            dut.io.flash_miso.poke((bit != 0).B)
            flashBit -= 1
            if (flashBit < 0) { flashBit = 7; flashByte += 1 }
          }

          // 1b. On falling SPI clock: drive next flash bit (stable for next rise)
          if (fall) {
            val bit = if (flashByte < flashContent.length)
                        (flashContent(flashByte) >> flashBit) & 1
                      else 0
            dut.io.flash_miso.poke((bit != 0).B)
            flashBit -= 1
            if (flashBit < 0) { flashBit = 7; flashByte += 1 }
          }

          // 3. SdramBackend model — peek startWrite BEFORE step
          wrPhase match {
            case 0 =>
              if (dut.io.backend.startWrite.peek().litToBoolean) {
                wAddr  = dut.io.backend.addrIn.peek().litValue.toInt
                wByte0 = dut.io.backend.dataIn.peek().litValue.toInt
                dut.io.backend.busy.poke(true.B)
                wrPhase = 1
              }
            case 1 =>
              // Read buf1 NOW: FlashBootLoader is in sWrWaitDone → dataIn=buf1.
              // After the step (when dataReq fires), state → sReadByte0 → dataIn=buf0.
              wByte1 = dut.io.backend.dataIn.peek().litValue.toInt
              dut.io.backend.dataReq.poke(true.B)   // request byte 1
              wrPhase = 2
            case 2 =>
              dut.io.backend.dataReq.poke(false.B)
              wrPhase = 3
            case 3 =>
              // write done — busy still true this cycle (matches real SdramBackend)
              dut.io.backend.dataReq.poke(true.B)
              written += ((wAddr, (wByte1 << 8) | wByte0))
              wrPhase = 4
            case 4 =>
              dut.io.backend.dataReq.poke(false.B)
              dut.io.backend.busy.poke(false.B)
              wrPhase = 0
            case _ =>
          }

          prevCsn = csn
          prevClk = curClk
          dut.clock.step(1)
          cycles += 1
        }

        println(s"D3: boot_done=${dut.io.boot_done.peek().litToBoolean} after $cycles cycles")
        Predef.assert(dut.io.boot_done.peek().litToBoolean, "boot_done never fired!")

        println(s"D3: SDRAM writes: ${written.map { case (a, d) => f"[0x$a%04x]=0x$d%04x" }.mkString(", ")}")
        Predef.assert(written.length == 2, s"Expected 2 SDRAM writes, got ${written.length}")
        Predef.assert(written(0)._1 == 0,      s"Write 0 addr: got ${written(0)._1}")
        Predef.assert(written(0)._2 == 0x0201, s"Write 0 data: got 0x${written(0)._2.toHexString}")
        Predef.assert(written(1)._1 == 2,      s"Write 1 addr: got ${written(1)._1}")
        Predef.assert(written(1)._2 == 0x0403, s"Write 1 data: got 0x${written(1)._2.toHexString}")

        println("D3: full boot copy ✓")
      }
    }
  }
}
