// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

// D.3 end-to-end test: FlashBootLoader reads a simulated flash and writes
// the firmware words to SDRAM via the 16-bit-word MemBackendIO protocol.
//
// Backend model (software SDRAM):
//   phase 0 — idle: watch for startWrite pulse.
//   phase 1 — startWrite seen: set busy, record addr+data (word address + 16-bit word).
//   phase 2 — pulse done for 1 cycle, then clear busy → back to phase 0.

import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth}
import chisel3.simulator.EphemeralSimulator._
import utest._

object FlashBootLoaderE2ETests extends TestSuite {
  val tests: Tests = Tests {

    utest.test("D3_full_boot_copy") {
      // The DUT samples MISO on every rising edge, including while sending
      // bytes whose MISO replies are ignored.  Pad 6 don't-care bytes so the
      // real size header aligns to offset 6 (clocked in during sReadSize):
      //   2 bytes — flash software reset (0x66 + 0x99)
      //   1 byte  — READ command (0x03)
      //   3 bytes — 24-bit flash address
      val flashContent = Array(
        0x00, 0x00,               // 0x66 / 0x99 flash reset (MISO ignored)
        0x00, 0x00, 0x00, 0x00,   // READ cmd + 3 addr bytes (MISO ignored)
        0x04, 0x00, 0x00, 0x00,   // size header LE = 4
        0x01, 0x02, 0x03, 0x04    // payload → SDRAM: [0x0000]=0x0201, [0x0001]=0x0403
      )

      simulate(new FlashBootLoader()) { dut =>
        dut.io.flash_miso.poke(false.B)
        dut.io.backend.busy.poke(false.B)
        dut.io.backend.done.poke(false.B)
        dut.io.backend.dataOut.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        var prevClk   = 0
        var prevCsn   = 1
        var flashByte = 0
        var flashBit  = 7

        val written = scala.collection.mutable.ArrayBuffer[(Int, Int)]()

        // Software SDRAM model (16-bit word protocol):
        //   phase 0 → idle: watch for startWrite
        //   phase 1 → busy=true, capture addr+data; pulse done next cycle
        //   phase 2 → done=false, busy=false, record write
        var wrPhase = 0
        var wAddr   = 0
        var wData   = 0

        var cycles    = 0
        val maxCycles = 200000

        while ((!dut.io.boot_done.peek().litToBoolean || wrPhase != 0) && cycles < maxCycles) {

          // 1. Read current SPI clock (gate to 0 when CS deasserted)
          val csn    = dut.io.flash_csn.peek().litValue.toInt
          val curClk = if (csn == 1) 0 else dut.io.spi_clk.peek().litValue.toInt
          val fall   = (prevClk == 1) && (curClk == 0)

          // 1a. CS falling edge → pre-load first bit.
          // Force flashBit=7 so each new CS-low window starts at MSB, even if
          // the previous window's byteDone fall over-advanced flashBit.
          val csnFall = (prevCsn == 1) && (csn == 0)
          if (csnFall) {
            flashBit = 7
            val bit = if (flashByte < flashContent.length)
                        (flashContent(flashByte) >> flashBit) & 1
                      else 0
            dut.io.flash_miso.poke((bit != 0).B)
            flashBit -= 1
            if (flashBit < 0) { flashBit = 7; flashByte += 1 }
          }

          // 1b. SPI clock falling edge → drive next flash bit
          if (fall) {
            val bit = if (flashByte < flashContent.length)
                        (flashContent(flashByte) >> flashBit) & 1
                      else 0
            dut.io.flash_miso.poke((bit != 0).B)
            flashBit -= 1
            if (flashBit < 0) { flashBit = 7; flashByte += 1 }
          }

          // 2. Software SDRAM model — peek startWrite BEFORE step
          wrPhase match {
            case 0 =>
              if (dut.io.backend.startWrite.peek().litToBoolean) {
                wAddr = dut.io.backend.addrIn.peek().litValue.toInt
                wData = dut.io.backend.dataIn.peek().litValue.toInt
                dut.io.backend.busy.poke(true.B)
                wrPhase = 1
              }
            case 1 =>
              dut.io.backend.done.poke(true.B)
              written += ((wAddr, wData))
              wrPhase = 2
            case 2 =>
              dut.io.backend.done.poke(false.B)
              dut.io.backend.busy.poke(false.B)
              wrPhase = 0
            case _ =>
          }

          prevCsn  = csn
          prevClk  = curClk
          dut.clock.step(1)
          cycles += 1
        }

        println(s"D3: boot_done=${dut.io.boot_done.peek().litToBoolean} after $cycles cycles")
        Predef.assert(dut.io.boot_done.peek().litToBoolean, "boot_done never fired!")

        println(s"D3: SDRAM writes: ${written.map { case (a, d) => f"[0x$a%04x]=0x$d%04x" }.mkString(", ")}")
        Predef.assert(written.length == 2, s"Expected 2 SDRAM writes, got ${written.length}")
        Predef.assert(written(0)._1 == 0,      s"Write 0 word-addr: got ${written(0)._1}")
        Predef.assert(written(0)._2 == 0x0201, s"Write 0 data: got 0x${written(0)._2.toHexString}")
        Predef.assert(written(1)._1 == 1,      s"Write 1 word-addr: got ${written(1)._1}")
        Predef.assert(written(1)._2 == 0x0403, s"Write 1 data: got 0x${written(1)._2.toHexString}")

        println("D3: full boot copy ✓")
      }
    }
  }
}
