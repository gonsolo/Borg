// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3.{assert => _, test => _, _}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

class QspiBackendHarnessIO extends Bundle {
  // MemBackendIO (arbiter side)
  val addrIn     = Input(UInt(24.W))
  val dataIn     = Input(UInt(16.W))
  val byteEnIn   = Input(UInt(2.W))
  val startRead  = Input(Bool())
  val startWrite = Input(Bool())
  val dataOut    = Output(UInt(16.W))
  val done       = Output(Bool())
  val busy       = Output(Bool())
  // QSPI pin side
  val spiDataIn  = Input(UInt(4.W))
  val spiDataOut = Output(UInt(4.W))
  val spiDataOe  = Output(UInt(4.W))
  val spiClkOut  = Output(Bool())
  val flashSel   = Output(Bool())
  val ramASel    = Output(Bool())
  val ramBSel    = Output(Bool())
}

/** Harness exposing QspiBackend's MemBackendIO and all QSPI pins. */
class QspiBackendHarness extends Module {
  val io = IO(new QspiBackendHarnessIO)

  val dut = Module(new QspiBackend)

  dut.io.backend.addrIn     := io.addrIn
  dut.io.backend.dataIn     := io.dataIn
  dut.io.backend.byteEnIn   := io.byteEnIn
  dut.io.backend.lenIn      := 1.U   // single-word backend test
  dut.io.backend.startRead  := io.startRead
  dut.io.backend.startWrite := io.startWrite
  dut.io.qspiPins.dataIn    := io.spiDataIn

  io.dataOut    := dut.io.backend.dataOut
  io.done       := dut.io.backend.done
  io.busy       := dut.io.backend.busy
  io.spiDataOut := dut.io.qspiPins.dataOut
  io.spiDataOe  := dut.io.qspiPins.dataOe
  io.spiClkOut  := dut.io.qspiPins.clkOut
  io.flashSel   := dut.io.qspiPins.flashSelect
  io.ramASel    := dut.io.qspiPins.ramASelect
  io.ramBSel    := dut.io.qspiPins.ramBSelect
}

object QspiBackendTests extends TestSuite {

  // Address helpers:
  //   QspiBackend: byteAddr = Cat(addrIn_24bit, 0) → 25-bit byte address
  //   QspiCtrl: flash_select when byteAddr(24) = 0; ram_a_select when byteAddr(24:23) = 2
  //   DRAM-A: byteAddr(24:23) = 10 → addrIn(23:22) = 10 → addrIn >= 0x800000
  //   Flash:   byteAddr(24) = 0    → addrIn(23)   = 0  → addrIn <  0x800000
  private val dramWordAddr: Int = 0x800042  // selects DRAM-A
  private val flashWordAddr: Int = 0x000042  // selects flash

  val tests = Tests {

    test("DRAM write completes and asserts RAM chip select") {
      simulate(new QspiBackendHarness) { dut =>
        dut.io.spiDataIn.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.addrIn.poke(dramWordAddr.U)
        dut.io.dataIn.poke(0xabcd.U)
        dut.io.byteEnIn.poke(3.U)
        dut.io.startWrite.poke(true.B)
        dut.clock.step(1)
        dut.io.startWrite.poke(false.B)

        var done       = false
        var ramSelected = false
        for (_ <- 0 until 300 if !done) {
          if (!dut.io.ramASel.peek().litToBoolean) ramSelected = true
          if (dut.io.done.peek().litToBoolean)     done = true
          dut.clock.step(1)
        }
        Predef.assert(done, "DRAM write never completed (done never fired in 300 cycles)")
        Predef.assert(ramSelected, "DRAM-A chip select never asserted during write")
      }
    }

    test("DRAM write sends 0x02 command followed by correct address nibbles") {
      // Expected nibble sequence on rising SPI clock edges (OE=1):
      //   [0x0, 0x2]               CMD   = 0x02
      //   [0x0, 0x0, 0x0, 0x0, 0x8, 0x4]  ADDR  = 0x000084 (Cat(dramWordAddr,0)[23:0])
      //   [0xC, 0xD]               DATA  byte0 = 0xCD (low byte of 0xABCD)
      //   [0xA, 0xB]               DATA  byte1 = 0xAB (high byte of 0xABCD)
      simulate(new QspiBackendHarness) { dut =>
        dut.io.spiDataIn.poke(0.U)  // delay_cycles_cfg = 0 during reset
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.addrIn.poke(dramWordAddr.U)
        dut.io.dataIn.poke(0xabcd.U)
        dut.io.byteEnIn.poke(3.U)
        dut.io.startWrite.poke(true.B)
        dut.clock.step(1)
        dut.io.startWrite.poke(false.B)

        val nibbles = scala.collection.mutable.ArrayBuffer[Int]()
        var lastClk = false
        var done    = false
        for (_ <- 0 until 300 if !done) {
          val curClk  = dut.io.spiClkOut.peek().litToBoolean
          val oe      = dut.io.spiDataOe.peek().litValue != 0
          val csActive = !dut.io.ramASel.peek().litToBoolean
          // Capture on rising SPI clock edge while CS active and OE asserted
          if (curClk && !lastClk && csActive && oe)
            nibbles += dut.io.spiDataOut.peek().litValue.toInt
          lastClk = curClk
          if (dut.io.done.peek().litToBoolean) done = true
          dut.clock.step(1)
        }

        Predef.assert(done, s"DRAM write never completed; captured ${nibbles.length} nibbles: ${nibbles.mkString(",")}")
        Predef.assert(nibbles.length >= 10,
          s"Expected ≥10 nibbles (2 CMD + 6 ADDR + 2 DATA), got ${nibbles.length}: ${nibbles.mkString(",")}")

        // CMD = 0x02
        Predef.assert(nibbles(0) == 0x0, s"CMD high nibble: expected 0x0, got 0x${nibbles(0).toHexString}")
        Predef.assert(nibbles(1) == 0x2, s"CMD low  nibble: expected 0x2, got 0x${nibbles(1).toHexString}")

        // ADDR = 0x000084 (byte address = Cat(0x800042, 0)[23:0] = 0x000084)
        val expectedAddr = Seq(0x0, 0x0, 0x0, 0x0, 0x8, 0x4)
        for (i <- expectedAddr.indices) {
          Predef.assert(nibbles(2 + i) == expectedAddr(i),
            s"ADDR nibble $i: expected 0x${expectedAddr(i).toHexString}, got 0x${nibbles(2 + i).toHexString}")
        }

        // DATA low byte first: 0xCD (0xC, 0xD), then high byte: 0xAB (0xA, 0xB)
        Predef.assert(nibbles(8)  == 0xC, s"DATA byte0 high: expected 0xC, got 0x${nibbles(8).toHexString}")
        Predef.assert(nibbles(9)  == 0xD, s"DATA byte0 low:  expected 0xD, got 0x${nibbles(9).toHexString}")
        Predef.assert(nibbles(10) == 0xA, s"DATA byte1 high: expected 0xA, got 0x${nibbles(10).toHexString}")
        Predef.assert(nibbles(11) == 0xB, s"DATA byte1 low:  expected 0xB, got 0x${nibbles(11).toHexString}")
      }
    }

    test("DRAM read completes and returns correct data") {
      // Hold spi_data_in = 0xA throughout; each nibble reads 0xA → each byte = 0xAA → dataOut = 0xAAAA
      simulate(new QspiBackendHarness) { dut =>
        dut.io.spiDataIn.poke(0.U)  // delay_cycles_cfg = 0 during reset
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.spiDataIn.poke(0xA.U)  // slave holds 0xA on all data lines
        dut.io.addrIn.poke(dramWordAddr.U)
        dut.io.dataIn.poke(0.U)
        dut.io.byteEnIn.poke(0.U)
        dut.io.startRead.poke(true.B)
        dut.clock.step(1)
        dut.io.startRead.poke(false.B)

        var done:    Boolean  = false
        var readData: BigInt  = -1
        for (_ <- 0 until 300 if !done) {
          if (dut.io.done.peek().litToBoolean) {
            done     = true
            readData = dut.io.dataOut.peek().litValue
          }
          dut.clock.step(1)
        }

        Predef.assert(done, "DRAM read never completed in 300 cycles")
        Predef.assert(readData == 0xAAAA,
          s"DRAM read: expected 0xAAAA, got 0x${readData.toString(16)}")
      }
    }

    test("Flash read selects flash chip and completes") {
      simulate(new QspiBackendHarness) { dut =>
        dut.io.spiDataIn.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.spiDataIn.poke(0xA.U)
        dut.io.addrIn.poke(flashWordAddr.U)
        dut.io.dataIn.poke(0.U)
        dut.io.byteEnIn.poke(0.U)
        dut.io.startRead.poke(true.B)
        dut.clock.step(1)
        dut.io.startRead.poke(false.B)

        var done          = false
        var flashSelected = false
        for (_ <- 0 until 300 if !done) {
          if (!dut.io.flashSel.peek().litToBoolean) flashSelected = true
          if (dut.io.done.peek().litToBoolean)      done = true
          dut.clock.step(1)
        }

        Predef.assert(done, "Flash read never completed in 300 cycles")
        Predef.assert(flashSelected, "Flash chip select never asserted during flash read")
      }
    }

    test("Flash read sends 0x0B command") {
      simulate(new QspiBackendHarness) { dut =>
        dut.io.spiDataIn.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.spiDataIn.poke(0xA.U)
        dut.io.addrIn.poke(flashWordAddr.U)
        dut.io.dataIn.poke(0.U)
        dut.io.byteEnIn.poke(0.U)
        dut.io.startRead.poke(true.B)
        dut.clock.step(1)
        dut.io.startRead.poke(false.B)

        val nibbles = scala.collection.mutable.ArrayBuffer[Int]()
        var lastClk = false
        var done    = false
        for (_ <- 0 until 300 if !done) {
          val curClk   = dut.io.spiClkOut.peek().litToBoolean
          val oe       = dut.io.spiDataOe.peek().litValue != 0
          val csActive = !dut.io.flashSel.peek().litToBoolean
          if (curClk && !lastClk && csActive && oe)
            nibbles += dut.io.spiDataOut.peek().litValue.toInt
          lastClk = curClk
          if (dut.io.done.peek().litToBoolean) done = true
          dut.clock.step(1)
        }

        Predef.assert(done, s"Flash read never completed; nibbles: ${nibbles.mkString(",")}")
        Predef.assert(nibbles.length >= 2,
          s"Expected ≥2 CMD nibbles, got ${nibbles.length}: ${nibbles.mkString(",")}")

        // CMD = 0x0B (fast read)
        Predef.assert(nibbles(0) == 0x0, s"CMD high nibble: expected 0x0, got 0x${nibbles(0).toHexString}")
        Predef.assert(nibbles(1) == 0xB, s"CMD low  nibble: expected 0xB, got 0x${nibbles(1).toHexString}")
      }
    }
  }
}
