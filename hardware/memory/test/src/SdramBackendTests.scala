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

    // Burst write (lenIn > 1): one startWrite streams N words to consecutive
    // word addresses through the single-word controller.  Observe the SDRAM
    // WRITE commands on the pins to confirm N writes at base, base+1, … with the
    // streamed data, exactly one `done`, and `lenIn-1` `accept` pulses.
    test("WB_burst_write") {
      simulate(new SdramBackend) { dut =>
        initBackend(dut)

        val base = 0x20
        val n    = 4
        def word(i: Int): Int = 0xAA00 + i

        def isWriteCmd: Boolean =
          dut.io.sdramPins.cs_n.peek().litValue  == 0 &&
          dut.io.sdramPins.ras_n.peek().litValue == 1 &&
          dut.io.sdramPins.cas_n.peek().litValue == 0 &&
          dut.io.sdramPins.we_n.peek().litValue  == 0

        // Issue the burst: present word 0, len = n.
        dut.io.backend.addrIn.poke(base.U)
        dut.io.backend.lenIn.poke(n.U)
        dut.io.backend.dataIn.poke(word(0).U)
        dut.io.backend.startWrite.poke(true.B)
        dut.clock.step(1)
        dut.io.backend.startWrite.poke(false.B)

        val cols = scala.collection.mutable.ArrayBuffer[Int]()
        val data = scala.collection.mutable.ArrayBuffer[Int]()
        var accepts = 0
        var wordIdx = 0
        var gotDone = false
        var guard   = 0
        // Registered producer: advance the word AFTER the step on which accept fired.
        while (!gotDone && guard < 400) {
          if (isWriteCmd) {
            cols += dut.io.sdramPins.addr.peek().litValue.toInt
            data += dut.io.sdramPins.dq_out.peek().litValue.toInt
          }
          val acc = dut.io.backend.accept.peek().litValue == 1
          if (dut.io.backend.done.peek().litValue == 1) gotDone = true
          dut.clock.step(1)
          if (acc) {
            accepts += 1
            wordIdx += 1
            if (wordIdx < n) dut.io.backend.dataIn.poke(word(wordIdx).U)
          }
          guard += 1
        }

        println(s"WB: cols=${cols.map(c => f"0x$c%X").mkString(",")} " +
                s"data=${data.map(d => f"0x$d%X").mkString(",")} accepts=$accepts done=$gotDone")
        Predef.assert(gotDone, "burst never produced done")
        Predef.assert(accepts == n - 1, s"expected ${n - 1} accept pulses, got $accepts")
        Predef.assert(cols.length == n, s"expected $n WRITE commands, got ${cols.length}")
        for (i <- 0 until n) {
          Predef.assert(cols(i) == base + i, s"write $i col: got 0x${cols(i).toHexString} exp 0x${(base+i).toHexString}")
          Predef.assert(data(i) == word(i),  s"write $i data: got 0x${data(i).toHexString} exp 0x${word(i).toHexString}")
        }
        dut.clock.step(2)
        assert(dut.io.backend.busy.peek().litValue == 0)
        println("WB: 4-word burst wrote consecutive columns with correct data ✓")
      }
    }
  }
}
