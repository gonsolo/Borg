// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// SdramBackend read-path smoke test.
// Writes two bytes to SDRAM address 0 via the backend write protocol,
// then reads them back via the read protocol.
// This directly simulates what FlashBootLoader (write) + MemoryController (read)
// do during boot, without involving the CPU.

package memory

import chisel3.{Bool, Module, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral, fromLongToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object SdramReadPathTests extends TestSuite {

  val tests = Tests {

    test("write then read back two bytes at address 0") {
      simulate(new SdramBackendSim()) { dut =>

        val TIMEOUT = 10000
        var cycle = 0

        def tick(n: Int = 1): Unit = {
          for (_ <- 0 until n) dut.clock.step()
          cycle += n
          Predef.assert(cycle < TIMEOUT, s"TIMEOUT at cycle $cycle")
        }

        // Reset
        dut.reset.poke(true.B)
        tick(3)
        dut.reset.poke(false.B)
        tick()
        cycle = 4

        // Deassert all command signals initially
        dut.io.backend.startRead.poke(false.B)
        dut.io.backend.startWrite.poke(false.B)
        dut.io.backend.stallTxn.poke(false.B)
        dut.io.backend.stopTxn.poke(false.B)
        dut.io.backend.addrIn.poke(0.U)
        dut.io.backend.dataIn.poke(0.U)

        // No SDRAM init needed — SdramBackendSim is immediately idle
        Predef.assert(!dut.io.backend.busy.peek().litToBoolean, "SdramBackendSim should start idle")
        println(s"[$cycle] Backend idle (no SDRAM init required)")

        // ── Phase 1: Write 0xAB, 0xCD to SDRAM byte address 0 ──
        println(s"[$cycle] Starting write of 0xAB,0xCD at addr=0")
        dut.io.backend.addrIn.poke(0.U)
        dut.io.backend.dataIn.poke(0xAB.U)
        dut.io.backend.startWrite.poke(true.B)
        tick()
        dut.io.backend.startWrite.poke(false.B)

        // Wait for dataReq (SdramBackend consumed byte 0, wants byte 1)
        var waited = 0
        while (!dut.io.backend.dataReq.peek().litToBoolean && waited < 1000) {
          tick()
          waited += 1
        }
        Predef.assert(waited < 1000, s"Write dataReq never asserted (waited $waited cycles)")
        println(s"[$cycle] dataReq asserted after $waited cycles — sending byte 1 (0xCD)")

        // Provide byte 1
        dut.io.backend.dataIn.poke(0xCD.U)
        tick()

        // Wait for second dataReq (write complete)
        waited = 0
        while (!dut.io.backend.dataReq.peek().litToBoolean && waited < 1000) {
          tick()
          waited += 1
        }
        println(s"[$cycle] Write complete (second dataReq after $waited cycles)")

        // Terminate the write transaction.  The backend stays in its write
        // loop until the caller raises stopTxn (real hardware: MemoryController
        // arbiter / FlashBootLoader); asserting it at the second-dataReq cycle
        // gets captured by the backend's wrStopPending latch.
        dut.io.backend.stopTxn.poke(true.B)
        tick()
        dut.io.backend.stopTxn.poke(false.B)

        // Wait for busy to go low
        waited = 0
        while (dut.io.backend.busy.peek().litToBoolean && waited < 1000) {
          tick()
          waited += 1
        }
        println(s"[$cycle] Backend idle after write ($waited cycles)")

        // ── Phase 2: Read back from SDRAM byte address 0 ──
        println(s"[$cycle] Starting read at addr=0")
        dut.io.backend.addrIn.poke(0.U)
        dut.io.backend.startRead.poke(true.B)
        dut.io.backend.stopTxn.poke(false.B)
        tick()
        dut.io.backend.startRead.poke(false.B)

        // Wait for first dataReady
        waited = 0
        while (!dut.io.backend.dataReady.peek().litToBoolean && waited < 1000) {
          tick()
          waited += 1
        }
        Predef.assert(waited < 1000, s"Read dataReady never asserted for byte 0 (waited $waited cycles)")
        val byte0 = dut.io.backend.dataOut.peek().litValue.toInt
        println(s"[$cycle] Read byte 0 = 0x${byte0.toHexString} (expected 0xAB)")
        tick()

        // Wait for second dataReady
        waited = 0
        while (!dut.io.backend.dataReady.peek().litToBoolean && waited < 1000) {
          tick()
          waited += 1
        }
        Predef.assert(waited < 1000, s"Read dataReady never asserted for byte 1 (waited $waited cycles)")
        val byte1 = dut.io.backend.dataOut.peek().litValue.toInt
        println(s"[$cycle] Read byte 1 = 0x${byte1.toHexString} (expected 0xCD)")

        // Stop the transaction
        dut.io.backend.stopTxn.poke(true.B)
        tick()
        dut.io.backend.stopTxn.poke(false.B)

        // Verify
        println(s"byte0=0x${byte0.toHexString} byte1=0x${byte1.toHexString}")
        Predef.assert(byte0 == 0xAB, s"byte0 mismatch: got 0x${byte0.toHexString}")
        Predef.assert(byte1 == 0xCD, s"byte1 mismatch: got 0x${byte1.toHexString}")
        println(s"[$cycle] ✓ Read-back matches written data!")
      }
    }
  }
}
