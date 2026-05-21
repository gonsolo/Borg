// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// SdramBackendSim write-then-read smoke test (16-bit word protocol).
// Writes a 16-bit word to word-address 0, then reads it back.
// This directly exercises what FlashBootLoader (write) + MemoryController (read)
// do during boot, without the SDRAM init delay.

package memory

import chisel3.{Bool, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object SdramReadPathTests extends TestSuite {

  val tests = Tests {

    test("write_then_read_back_16bit_word") {
      simulate(new SdramBackendSim()) { dut =>

        val TIMEOUT = 1000
        var cycle = 0

        def tick(n: Int = 1): Unit = {
          for (_ <- 0 until n) dut.clock.step()
          cycle += n
          Predef.assert(cycle < TIMEOUT, s"TIMEOUT at cycle $cycle")
        }

        dut.reset.poke(true.B)
        tick(3)
        dut.reset.poke(false.B)
        tick()

        dut.io.backend.startRead.poke(false.B)
        dut.io.backend.startWrite.poke(false.B)
        dut.io.backend.addrIn.poke(0.U)
        dut.io.backend.dataIn.poke(0.U)
        dut.io.backend.byteEnIn.poke(0.U)

        Predef.assert(!dut.io.backend.busy.peek().litToBoolean, "SdramBackendSim should start idle")

        // ── Phase 1: Write 0xCDAB to word address 0 ──
        println(s"[$cycle] Starting write of 0xCDAB at word addr=0")
        dut.io.backend.addrIn.poke(0.U)
        dut.io.backend.dataIn.poke(0xCDAB.U)
        dut.io.backend.byteEnIn.poke(3.U)
        dut.io.backend.startWrite.poke(true.B)
        tick()
        dut.io.backend.startWrite.poke(false.B)

        var waited = 0
        while (!dut.io.backend.done.peek().litToBoolean && waited < 100) {
          tick(); waited += 1
        }
        Predef.assert(waited < 100, s"Write done never asserted (waited $waited cycles)")
        println(s"[$cycle] Write done after $waited cycles")
        tick()  // done → sIdle

        // ── Phase 2: Read back from word address 0 ──
        println(s"[$cycle] Starting read at word addr=0")
        dut.io.backend.addrIn.poke(0.U)
        dut.io.backend.startRead.poke(true.B)
        tick()
        dut.io.backend.startRead.poke(false.B)

        waited = 0
        while (!dut.io.backend.done.peek().litToBoolean && waited < 100) {
          tick(); waited += 1
        }
        Predef.assert(waited < 100, s"Read done never asserted (waited $waited cycles)")

        val readWord = dut.io.backend.dataOut.peek().litValue.toInt
        println(s"[$cycle] Read word = 0x${readWord.toHexString} (expected 0xCDAB)")
        Predef.assert(readWord == 0xCDAB, s"readWord mismatch: got 0x${readWord.toHexString}")
        println(s"[$cycle] ✓ Read-back matches written word!")
      }
    }
  }
}
