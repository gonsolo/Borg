// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3.{Bool, Module, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral, fromLongToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Step B.1–B.4 gate tests for the Chisel SDRAM controller.
  *
  * These tests verify:
  *   B.1 — Init sequence: PRECHARGE ALL → MODE SET → AUTO REFRESH
  *   B.2 — Write 0xA5C3 to addr 4, read back
  *   B.3 — Row miss: bank open on row 0, access row 1 → PRECHARGE + ACTIVATE
  *   B.4 — Refresh: AUTO REFRESH issued within RFTIME cycles
  */
object SdramControllerTests extends TestSuite {

  // SDRAM command encodings (matching the controller)
  val CMD_NOP      = 0x8 // 4'b1000
  val CMD_PRECHRG  = 0x1 // 4'b0001
  val CMD_AUTORFRSH= 0x4 // 4'b0100
  val CMD_MODESET  = 0x0 // 4'b0000
  val CMD_READ     = 0x6 // 4'b0110
  val CMD_WRITE    = 0x2 // 4'b0010
  val CMD_ACTIVATE = 0x5 // 4'b0101

  /** Extract 4-bit command from pin outputs */
  def readCmd(dut: SdramController): Int = {
    val csn  = dut.io.pins.cs_n.peek().litValue.toInt
    val wen  = dut.io.pins.we_n.peek().litValue.toInt
    val rasn = dut.io.pins.ras_n.peek().litValue.toInt
    val casn = dut.io.pins.cas_n.peek().litValue.toInt
    (csn << 3) | (wen << 2) | (rasn << 1) | casn
  }

  def cmdName(cmd: Int): String = cmd match {
    case 0x8 => "NOP"
    case 0x1 => "PRECHARGE"
    case 0x4 => "AUTO_REFRESH"
    case 0x0 => "MODE_SET"
    case 0x6 => "READ"
    case 0x2 => "WRITE"
    case 0x5 => "ACTIVATE"
    case _   => f"UNKNOWN(0x$cmd%X)"
  }

  /** Common init sequence: waits for init to complete, returns when rdy=1. */
  def waitForInit(dut: SdramController): Unit = {
    // Explicitly assert reset to initialise all registers
    dut.reset.poke(true.B)
    dut.io.sys.rd.poke(false.B)
    dut.io.sys.wr.poke(false.B)
    dut.io.sys.ack.poke(false.B)
    dut.io.sys.ab.poke(0.U(24.W))
    dut.io.sys.di.poke(0.U(16.W))
    dut.io.pins.dq_in.poke(0.U(16.W))
    dut.clock.step(2)
    dut.reset.poke(false.B)

    // Wait for init to complete (DQM goes 00)
    var initDone = false
    for (_ <- 0 until 13000 if !initDone) {
      dut.clock.step(1)
      if (dut.io.pins.dqm.peek().litValue == 0) initDone = true
    }
    Predef.assert(initDone, "Init did not complete within 13000 cycles")

    // Wait for rdy=1
    var ready = false
    for (_ <- 0 until 200 if !ready) {
      dut.clock.step(1)
      if (dut.io.sys.rdy.peek().litValue == 1) ready = true
    }
    Predef.assert(ready, "Controller did not become ready after init")
  }

  val tests: Tests = Tests {

    test("B1_init_sequence") {
      simulate(new SdramController) { dut =>
        dut.reset.poke(true.B)
        dut.io.sys.rd.poke(false.B)
        dut.io.sys.wr.poke(false.B)
        dut.io.sys.ack.poke(false.B)
        dut.io.sys.ab.poke(0.U(24.W))
        dut.io.sys.di.poke(0.U(16.W))
        dut.io.pins.dq_in.poke(0.U(16.W))
        dut.clock.step(2)
        dut.reset.poke(false.B)

        val commandsSeen = scala.collection.mutable.ListBuffer[Int]()

        val totalCycles = 12500 + 200
        for (_ <- 0 until totalCycles) {
          dut.clock.step(1)
          val cmd = readCmd(dut)
          if (cmd != CMD_NOP) commandsSeen += cmd
        }

        println(s"Init commands seen (${commandsSeen.length}): " +
          commandsSeen.map(cmdName).mkString(", "))

        // Must contain PRECHARGE, MODE_SET, AUTO_REFRESH in order
        assert(commandsSeen.contains(CMD_PRECHRG))
        assert(commandsSeen.contains(CMD_MODESET))
        assert(commandsSeen.contains(CMD_AUTORFRSH))

        val pIdx = commandsSeen.indexOf(CMD_PRECHRG)
        val mIdx = commandsSeen.indexOf(CMD_MODESET)
        val aIdx = commandsSeen.indexOf(CMD_AUTORFRSH)
        assert(pIdx < mIdx)  // PRECHARGE before MODE SET
        assert(mIdx < aIdx)  // MODE SET before AUTO REFRESH

        // DQM should be 00 after init
        assert(dut.io.pins.dqm.peek().litValue == 0)
      }
    }

    test("B2_write_read_back") {
      simulate(new SdramController) { dut =>
        waitForInit(dut)

        // ── WRITE 0xA5C3 to addr 4 ──
        dut.io.sys.ab.poke(4.U(24.W))
        dut.io.sys.di.poke(0xA5C3.U(16.W))
        dut.io.sys.wr.poke(true.B)

        // Step past register delay, let FSM leave IDLE
        dut.clock.step(2)

        // Wait for rdy to go HIGH (write accepted in RWRDY)
        var ready = false
        for (_ <- 0 until 50 if !ready) {
          dut.clock.step(1)
          if (dut.io.sys.rdy.peek().litValue == 1) ready = true
        }
        Predef.assert(ready, "No rdy after write")
        dut.io.sys.wr.poke(false.B)
        dut.io.sys.ack.poke(true.B)
        dut.clock.step(2) // 2 cycles: 1 for registered ack, 1 for FSM to reach IDLE
        dut.io.sys.ack.poke(false.B)

        // Wait for idle
        dut.clock.step(5)

        // Hold rd high until rdy; provide data on dq_in (simulating SDRAM)
        dut.io.sys.ab.poke(4.U(24.W))
        dut.io.pins.dq_in.poke(0xA5C3.U(16.W))
        dut.io.sys.rd.poke(true.B)

        // Step past the register delay (rd propagates through RegNext)
        // and past the IDLE→RDWR→WAIT→RWRDY sequence
        dut.clock.step(2)  // let registered rd propagate, FSM leaves IDLE

        // Now wait for rdy to go HIGH (read data valid from RWRDY state)
        ready = false
        for (_ <- 0 until 50 if !ready) {
          dut.clock.step(1)
          if (dut.io.sys.rdy.peek().litValue == 1) ready = true
        }
        Predef.assert(ready, "No rdy after read")
        dut.io.sys.rd.poke(false.B)

        val readData = dut.io.sys.do_.peek().litValue.toInt
        assert(readData == 0xA5C3)

        dut.io.sys.ack.poke(true.B)
        dut.clock.step(1)
        dut.io.sys.ack.poke(false.B)
      }
    }

    test("B3_row_miss") {
      simulate(new SdramController) { dut =>
        waitForInit(dut)

        // ── First write: row=0, bank=0, col=0 → addr = 0x000000 ──
        dut.io.sys.ab.poke(0x000000.U(24.W))
        dut.io.sys.di.poke(0x1234.U(16.W))
        dut.io.sys.wr.poke(true.B)
        dut.clock.step(1)
        dut.io.sys.wr.poke(false.B)

        var ready = false
        for (_ <- 0 until 50 if !ready) {
          dut.clock.step(1)
          if (dut.io.sys.rdy.peek().litValue == 1) ready = true
        }
        dut.io.sys.ack.poke(true.B)
        dut.clock.step(1)
        dut.io.sys.ack.poke(false.B)

        ready = false
        for (_ <- 0 until 50 if !ready) {
          dut.clock.step(1)
          if (dut.io.sys.rdy.peek().litValue == 1) ready = true
        }

        // ── Second write: row=1, bank=0, col=0 → addr = 0x000800 ──
        val commandsSeen = scala.collection.mutable.ListBuffer[Int]()
        dut.io.sys.ab.poke(0x000800.U(24.W))
        dut.io.sys.di.poke(0x5678.U(16.W))
        dut.io.sys.wr.poke(true.B)
        dut.clock.step(1)
        dut.io.sys.wr.poke(false.B)

        ready = false
        for (_ <- 0 until 50 if !ready) {
          dut.clock.step(1)
          val cmd = readCmd(dut)
          if (cmd != CMD_NOP) commandsSeen += cmd
          if (dut.io.sys.rdy.peek().litValue == 1) ready = true
        }

        println(s"Row miss commands: ${commandsSeen.map(cmdName).mkString(", ")}")

        assert(commandsSeen.contains(CMD_PRECHRG))
        assert(commandsSeen.contains(CMD_ACTIVATE))

        val pIdx = commandsSeen.indexOf(CMD_PRECHRG)
        val aIdx = commandsSeen.lastIndexOf(CMD_ACTIVATE)
        assert(pIdx < aIdx)  // PRECHARGE before ACTIVATE

        dut.io.sys.ack.poke(true.B)
        dut.clock.step(1)
        dut.io.sys.ack.poke(false.B)
      }
    }

    test("B4_refresh_timing") {
      simulate(new SdramController) { dut =>
        waitForInit(dut)

        // Sit idle and watch for AUTO_REFRESH
        var refreshSeen = false
        var cyclesWaited = 0
        val maxWait = 1200

        while (!refreshSeen && cyclesWaited < maxWait) {
          dut.clock.step(1)
          cyclesWaited += 1
          if (readCmd(dut) == CMD_AUTORFRSH) refreshSeen = true
        }

        println(s"Auto-refresh seen after $cyclesWaited idle cycles")
        assert(refreshSeen)
        assert(cyclesWaited <= 1100)
      }
    }
  }
}
