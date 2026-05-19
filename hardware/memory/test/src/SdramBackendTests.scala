// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

// R1–R2, W1: SdramBackend 16-bit-word protocol tests.
//
// These tests drive the MemBackendIO side and verify that:
//   R1 — startRead returns a done pulse with dataOut = word from dq_in
//   W1 — startWrite returns a done pulse and backend goes idle
//   R2 — two sequential reads both complete without getting stuck

import chisel3.{Bool, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object SdramBackendTests extends TestSuite {

  def initBackend(dut: SdramBackend): Unit = {
    dut.reset.poke(true.B)
    dut.io.backend.startRead.poke(false.B)
    dut.io.backend.startWrite.poke(false.B)
    dut.io.backend.addrIn.poke(0.U)
    dut.io.backend.dataIn.poke(0.U)
    dut.io.backend.byteEnIn.poke(0.U)
    dut.io.sdramPins.dq_in.poke(0.U)
    dut.clock.step(2)
    dut.reset.poke(false.B)

    var initDone = false
    for (_ <- 0 until 13500 if !initDone) {
      dut.clock.step(1)
      if (dut.io.sdramPins.dqm.peek().litValue == 0) initDone = true
    }
    Predef.assert(initDone, "SDRAM init did not complete within 13500 cycles")
    for (_ <- 0 until 100) dut.clock.step(1)
  }

  val tests: Tests = Tests {

    test("R1_read_word") {
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        dut.io.backend.addrIn.poke(8.U)
        dut.io.sdramPins.dq_in.poke(0xBEEF.U)
        dut.io.backend.startRead.poke(true.B)
        dut.clock.step(1)
        dut.io.backend.startRead.poke(false.B)

        var gotDone  = false
        var readWord = 0
        for (_ <- 0 until 60 if !gotDone) {
          dut.clock.step(1)
          if (dut.io.backend.done.peek().litValue == 1) {
            gotDone  = true
            readWord = dut.io.backend.dataOut.peek().litValue.toInt
          }
        }
        Predef.assert(gotDone, "No done pulse for read")
        println(f"R1: readWord=0x${readWord}%04X (expect 0xBEEF)")
        assert(readWord == 0xBEEF)

        dut.clock.step(2)
        assert(dut.io.backend.busy.peek().litValue == 0)
      }
    }

    test("W1_write_word") {
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        dut.io.backend.addrIn.poke(0x10.U)
        dut.io.backend.dataIn.poke(0xCDAB.U)
        dut.io.backend.byteEnIn.poke(3.U)
        dut.io.backend.startWrite.poke(true.B)
        dut.clock.step(1)
        dut.io.backend.startWrite.poke(false.B)

        var gotDone = false
        for (_ <- 0 until 60 if !gotDone) {
          dut.clock.step(1)
          if (dut.io.backend.done.peek().litValue == 1) gotDone = true
        }
        println(s"W1: write done=$gotDone")
        Predef.assert(gotDone, "No done pulse for write")

        dut.clock.step(2)
        assert(dut.io.backend.busy.peek().litValue == 0)
      }
    }

    test("R2_sequential_reads") {
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        for (i <- 0 until 2) {
          dut.io.backend.addrIn.poke(i.U)
          dut.io.sdramPins.dq_in.poke((0x1000 + i).U)
          dut.io.backend.startRead.poke(true.B)
          dut.clock.step(1)
          dut.io.backend.startRead.poke(false.B)

          var gotDone = false
          for (_ <- 0 until 60 if !gotDone) {
            dut.clock.step(1)
            if (dut.io.backend.done.peek().litValue == 1) gotDone = true
          }
          Predef.assert(gotDone, s"No done pulse for read $i")
          dut.clock.step(1)  // sDone → sIdle
        }
      }
    }
  }
}
