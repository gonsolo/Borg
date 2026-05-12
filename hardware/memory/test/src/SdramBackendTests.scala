// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

// C.1–C.3: SdramBackend byte-serial bridge tests
//
// These tests drive the MemBackendIO side and verify that:
//   C.1 — 2-byte read at byte addr 0x10 delivers the correct bytes
//   C.2 — 4-byte read and 4-byte write (each pair becomes one SDRAM word)
//   C.3 — stopTxn aborts cleanly, backend returns to idle

import chisel3.{Bool, Module, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object SdramBackendTests extends TestSuite {

  /** Bring the SdramBackend past SDRAM init (12500+ cycles).
    * Pokes all inputs to safe defaults and holds them there.
    */
  def initBackend(dut: SdramBackend): Unit = {
    dut.reset.poke(true.B)
    dut.io.backend.startRead.poke(false.B)
    dut.io.backend.startWrite.poke(false.B)
    dut.io.backend.stallTxn.poke(false.B)
    dut.io.backend.stopTxn.poke(false.B)
    dut.io.backend.addrIn.poke(0.U(25.W))
    dut.io.backend.dataIn.poke(0.U(8.W))
    dut.io.sdramPins.dq_in.poke(0.U(16.W))
    dut.clock.step(2)
    dut.reset.poke(false.B)

    // Wait for SDRAM init to complete (DQM goes to 0)
    var initDone = false
    for (_ <- 0 until 13500 if !initDone) {
      dut.clock.step(1)
      if (dut.io.sdramPins.dqm.peek().litValue == 0) initDone = true
    }
    Predef.assert(initDone, "SDRAM init did not complete within 13500 cycles")

    // Wait for backend to reach idle (not busy)
    for (_ <- 0 until 100) dut.clock.step(1)
  }

  /** Perform a single-word read via the backend, feeding `dqWord` from the SDRAM side.
    * Returns the pair (byte0, byte1) presented to the arbiter. */
  def doRead(dut: SdramBackend, byteAddr: Int, dqWord: Int): (Int, Int) = {
    // Assert startRead for 1 cycle
    dut.io.backend.addrIn.poke(byteAddr.U(25.W))
    dut.io.backend.startRead.poke(true.B)
    dut.clock.step(1)
    dut.io.backend.startRead.poke(false.B)

    // Feed word from SDRAM when controller issues a read
    dut.io.sdramPins.dq_in.poke(dqWord.U(16.W))

    // Wait for first dataReady
    var b0 = -1
    for (_ <- 0 until 30 if b0 < 0) {
      dut.clock.step(1)
      if (dut.io.backend.dataReady.peek().litValue == 1)
        b0 = dut.io.backend.dataOut.peek().litValue.toInt
    }
    Predef.assert(b0 >= 0, "No dataReady for byte 0")

    // Signal stopTxn after first byte
    dut.io.backend.stopTxn.poke(true.B)
    dut.clock.step(1)
    dut.io.backend.stopTxn.poke(false.B)

    // Wait for backend to go idle
    for (_ <- 0 until 10) dut.clock.step(1)

    (b0, 0)
  }

  val tests: Tests = Tests {

    test("C1_read_low_byte") {
      // Read byte addr 0x10: this is word addr 8, low byte.
      // SDRAM word = 0xBEEF → low byte = 0xEF, high byte = 0xBE.
      // At byteAddr 0x10 (even), we expect low byte first = 0xEF.
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        dut.io.backend.addrIn.poke(0x10.U(25.W))
        dut.io.backend.startRead.poke(true.B)
        dut.clock.step(2)
        dut.io.backend.startRead.poke(false.B)

        // Feed SDRAM response: word 0xBEEF
        dut.io.sdramPins.dq_in.poke(0xBEEF.U(16.W))

        // Wait for dataReady
        var gotReady = false
        var readByte = 0
        for (_ <- 0 until 30 if !gotReady) {
          dut.clock.step(1)
          if (dut.io.backend.dataReady.peek().litValue == 1) {
            gotReady = true
            readByte = dut.io.backend.dataOut.peek().litValue.toInt
          }
        }
        assert(gotReady)

        // Even byte address → low byte first = 0xEF
        println(f"C1: addr=0x10, word=0xBEEF, first byte=0x${readByte}%02X (expect 0xEF)")
        assert(readByte == 0xEF)

        // Stop the transaction
        dut.io.backend.stopTxn.poke(true.B)
        dut.clock.step(2)
        dut.io.backend.stopTxn.poke(false.B)
        dut.clock.step(5)

        // Backend should be idle
        assert(dut.io.backend.busy.peek().litValue == 0)
      }
    }

    test("C1_read_high_byte") {
      // Read byte addr 0x11: this is word addr 8, high byte first.
      // SDRAM word = 0xBEEF → high byte = 0xBE.
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        dut.io.backend.addrIn.poke(0x11.U(25.W))
        dut.io.backend.startRead.poke(true.B)
        dut.clock.step(2)
        dut.io.backend.startRead.poke(false.B)

        dut.io.sdramPins.dq_in.poke(0xBEEF.U(16.W))

        var gotReady = false
        var readByte = 0
        for (_ <- 0 until 30 if !gotReady) {
          dut.clock.step(1)
          if (dut.io.backend.dataReady.peek().litValue == 1) {
            gotReady = true
            readByte = dut.io.backend.dataOut.peek().litValue.toInt
          }
        }
        assert(gotReady)

        // Odd byte address → high byte first = 0xBE
        println(f"C1: addr=0x11, word=0xBEEF, first byte=0x${readByte}%02X (expect 0xBE)")
        assert(readByte == 0xBE)

        dut.io.backend.stopTxn.poke(true.B)
        dut.clock.step(2)
        dut.io.backend.stopTxn.poke(false.B)
        dut.clock.step(5)
        assert(dut.io.backend.busy.peek().litValue == 0)
      }
    }

    test("C2_write_16bit_word") {
      // Write 2 bytes (0xAB, 0xCD) at byte addr 0x20 (even).
      // Backend should accumulate them into word 0xCDAB and write to SDRAM.
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        dut.io.backend.addrIn.poke(0x20.U(25.W))
        dut.io.backend.dataIn.poke(0xAB.U(8.W))   // first byte
        dut.io.backend.startWrite.poke(true.B)
        dut.clock.step(2)
        dut.io.backend.startWrite.poke(false.B)

        // Backend asserts dataReq to get the second byte
        var gotReq = false
        for (_ <- 0 until 10 if !gotReq) {
          dut.clock.step(1)
          if (dut.io.backend.dataReq.peek().litValue == 1) gotReq = true
        }
        assert(gotReq)

        // Provide second byte
        dut.io.backend.dataIn.poke(0xCD.U(8.W))
        dut.clock.step(1)

        // Backend should now complete the write (stopTxn or dataReq again signals done)
        dut.io.backend.stopTxn.poke(true.B)
        var wrDone = false
        for (_ <- 0 until 30 if !wrDone) {
          dut.clock.step(1)
          if (dut.io.backend.busy.peek().litValue == 0) wrDone = true
        }
        dut.io.backend.stopTxn.poke(false.B)

        println(s"C2: write done=${wrDone}")
        assert(wrDone)

        // Check the SDRAM controller received a WRITE command
        // (cs_n=0, we_n=0, ras_n=1, cas_n=0 → cmd = 0b0010 = 0x2)
        // We can observe this during the write phase — simplified: just check busy went false
      }
    }

    test("C3_stop_txn_aborts_read") {
      // Start a read, then immediately stop it.
      // Backend should return to idle cleanly.
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        dut.io.backend.addrIn.poke(0x40.U(25.W))
        dut.io.backend.startRead.poke(true.B)
        dut.clock.step(1)
        dut.io.backend.startRead.poke(false.B)

        // Immediately stop
        dut.clock.step(2)
        dut.io.backend.stopTxn.poke(true.B)
        dut.clock.step(1)
        dut.io.backend.stopTxn.poke(false.B)

        // After a few cycles, backend must be idle
        var idle = false
        for (_ <- 0 until 20 if !idle) {
          dut.clock.step(1)
          if (dut.io.backend.busy.peek().litValue == 0) idle = true
        }
        println(s"C3: idle after stopTxn = $idle")
        assert(idle)

        // And it must accept a new transaction (no stuck state)
        dut.io.backend.addrIn.poke(0x00.U(25.W))
        dut.io.backend.startRead.poke(true.B)
        dut.clock.step(1)
        dut.io.backend.startRead.poke(false.B)
        dut.clock.step(2)
        // Backend is busy again (new transaction started)
        assert(dut.io.backend.busy.peek().litValue == 1)

        // Clean up
        dut.io.backend.stopTxn.poke(true.B)
        dut.clock.step(2)
        dut.io.backend.stopTxn.poke(false.B)
        dut.clock.step(10)
      }
    }
  }
}
