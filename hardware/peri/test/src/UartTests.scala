// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package peri.uart

// U.1 — UartTx transmits 'A' (0x41) with correct start/data/stop framing.
// U.2 — UartRx receives 'B' (0x42) and asserts rx_valid with correct data.
// U.3 — UartTx→UartRx loopback: send 'Z' (0x5A), receive 'Z'.
// U.4 — PeriUart: MMIO write to TX register drives txd low (start bit).

import chisel3.{Bool, Bundle, Input, Module, Output, UInt, IO}
import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth}
import chisel3.simulator.EphemeralSimulator._
import utest._

class LoopbackIO extends Bundle {
  val tx_en    = Input(Bool())
  val tx_data  = Input(UInt(8.W))
  val tx_busy  = Output(Bool())
  val rx_valid = Output(Bool())
  val rx_data  = Output(UInt(8.W))
  val rx_read  = Input(Bool())
  val baud_div = Input(UInt(13.W))
}

class LoopbackHarness extends Module {
  val io = IO(new LoopbackIO)
  val tx = Module(new UartTx(countRegLen = 13))
  val rx = Module(new UartRx(countRegLen = 13))
  tx.io.uart_tx_en    := io.tx_en
  tx.io.uart_tx_data  := io.tx_data
  tx.io.baud_divider  := io.baud_div
  rx.io.uart_rxd      := tx.io.uart_txd   // loopback
  rx.io.uart_rx_read  := io.rx_read
  rx.io.baud_divider  := io.baud_div
  io.tx_busy  := tx.io.uart_tx_busy
  io.rx_valid := rx.io.uart_rx_valid
  io.rx_data  := rx.io.uart_rx_data
}

object UartTests extends TestSuite {

  val DIV = 9  // baud_divider: bit period = DIV+1 = 10 cycles

  val tests: Tests = Tests {

    test("U1_UartTx_transmit_A") {
      simulate(new UartTx(countRegLen = 13)) { dut =>
        dut.io.uart_tx_en.poke(false.B)
        dut.io.uart_tx_data.poke(0.U)
        dut.io.baud_divider.poke(DIV.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step(1)
        Predef.assert(dut.io.uart_txd.peek().litToBoolean,   "idle: txd must be high")
        Predef.assert(!dut.io.uart_tx_busy.peek().litToBoolean, "idle: not busy")

        // Transmit 'A' = 0x41 = bits LSB-first: 1,0,0,0,0,0,1,0
        dut.io.uart_tx_en.poke(true.B)
        dut.io.uart_tx_data.poke(0x41.U)
        dut.clock.step(1)
        dut.io.uart_tx_en.poke(false.B)

        // After 1 step: state=FSM_START but txd_reg lags 1 cycle (still high).
        Predef.assert(dut.io.uart_tx_busy.peek().litToBoolean, "should be busy at T0")

        dut.clock.step(1)
        // Now at T1: txd_reg has propagated to false (start bit visible).
        Predef.assert(!dut.io.uart_txd.peek().litToBoolean, "start bit: txd must be low")

        val bitPeriod = DIV + 1  // 10 cycles per bit

        // Sample each data bit; first sample lands at T11 (bit 0).
        val expectedBits = Seq(1, 0, 0, 0, 0, 0, 1, 0)
        for ((expectedBit, idx) <- expectedBits.zipWithIndex) {
          dut.clock.step(bitPeriod)
          val got = if (dut.io.uart_txd.peek().litToBoolean) 1 else 0
          Predef.assert(got == expectedBit, s"bit $idx: expected $expectedBit got $got")
        }

        // After bit 7 at T81, one more bitPeriod → T91: stop bit
        dut.clock.step(bitPeriod)
        Predef.assert(dut.io.uart_txd.peek().litToBoolean, "stop bit: txd must be high")

        // After bitPeriod more, FSM returns to IDLE
        dut.clock.step(bitPeriod)
        Predef.assert(!dut.io.uart_tx_busy.peek().litToBoolean, "should be idle after stop bit")
        Predef.assert(dut.io.uart_txd.peek().litToBoolean, "txd must remain high in idle")
      }
    }

    test("U2_UartRx_receive_B") {
      simulate(new UartRx(countRegLen = 13)) { dut =>
        dut.io.uart_rxd.poke(true.B)   // idle high
        dut.io.uart_rx_read.poke(false.B)
        dut.io.baud_divider.poke(DIV.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step(2)

        // 'B' = 0x42 = 0b01000010; LSB-first: 0,1,0,0,0,0,1,0
        val bits = Seq(0, 1, 0, 0, 0, 0, 1, 0)
        val bitPeriod = DIV + 1

        // Start bit
        dut.io.uart_rxd.poke(false.B)
        dut.clock.step(bitPeriod)

        // Data bits
        for (b <- bits) {
          dut.io.uart_rxd.poke((b != 0).B)
          dut.clock.step(bitPeriod)
        }

        // Stop bit
        dut.io.uart_rxd.poke(true.B)
        dut.clock.step(bitPeriod)

        // Wait for FSM_READY
        var ready = false
        for (_ <- 0 until 20 if !ready) {
          dut.clock.step(1)
          if (dut.io.uart_rx_valid.peek().litToBoolean) ready = true
        }
        Predef.assert(ready, "uart_rx_valid never fired")
        val rxData = dut.io.uart_rx_data.peek().litValue
        Predef.assert(rxData == 0x42,
          s"Expected 0x42, got 0x${rxData.toString(16)}")

        // Acknowledge
        dut.io.uart_rx_read.poke(true.B)
        dut.clock.step(1)
        dut.io.uart_rx_read.poke(false.B)
        dut.clock.step(2)
        Predef.assert(!dut.io.uart_rx_valid.peek().litToBoolean, "rx_valid should clear after read")
      }
    }

    test("U3_loopback_Z") {
      simulate(new LoopbackHarness) { dut =>
        dut.io.tx_en.poke(false.B)
        dut.io.rx_read.poke(false.B)
        dut.io.baud_div.poke(DIV.U)
        dut.io.tx_data.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step(2)

        // Send 'Z' = 0x5A
        dut.io.tx_data.poke(0x5A.U)
        dut.io.tx_en.poke(true.B)
        dut.clock.step(1)
        dut.io.tx_en.poke(false.B)

        // Full frame: 10 bits × (DIV+1) cycles, plus slack
        val maxWait = 10 * (DIV + 1) + 20
        var rxValid = false
        for (_ <- 0 until maxWait if !rxValid) {
          dut.clock.step(1)
          if (dut.io.rx_valid.peek().litToBoolean) rxValid = true
        }
        Predef.assert(rxValid, "RX never got valid in loopback")
        val rxData = dut.io.rx_data.peek().litValue
        Predef.assert(rxData == 0x5A,
          s"Loopback mismatch: got 0x${rxData.toString(16)}")
      }
    }

    test("U4_PeriUart_tx_start_bit") {
      simulate(new PeriUart(CLOCK_MHZ = 1)) { dut =>
        dut.io.ui_in.poke(0xFF.U)           // rxd idle high
        dut.io.mmio.req.valid.poke(false.B)
        dut.io.mmio.resp.ready.poke(true.B)
        dut.io.mmio.req.bits.addr.poke(0.U)
        dut.io.mmio.req.bits.write.poke(false.B)
        dut.io.mmio.req.bits.data.poke(0.U)
        dut.io.mmio.req.bits.size.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step(2)

        // Issue a single MMIO write and consume the response.
        def doWrite(addr: Int, data: Int): Unit = {
          dut.io.mmio.req.bits.addr.poke(addr.U)
          dut.io.mmio.req.bits.write.poke(true.B)
          dut.io.mmio.req.bits.data.poke(data.U)
          dut.io.mmio.req.bits.size.poke(2.U)
          dut.io.mmio.req.valid.poke(true.B)
          var waited = 0
          while (!dut.io.mmio.req.ready.peek().litToBoolean && waited < 10) {
            dut.clock.step(1); waited += 1
          }
          dut.clock.step(1)                 // fire request
          dut.io.mmio.req.valid.poke(false.B)
          waited = 0
          while (!dut.io.mmio.resp.valid.peek().litToBoolean && waited < 10) {
            dut.clock.step(1); waited += 1
          }
          dut.clock.step(1)                 // consume response
        }

        // Set a short baud divider (bit period = 4 cycles) via addr 8
        doWrite(8, 3)

        // Verify txd is high (idle) — bit 0 of uo_out
        Predef.assert((dut.io.uo_out.peek().litValue & 1) == 1, "txd should be high before TX")

        // Write 'H' = 0x48 to TX register (addr 0)
        doWrite(0, 0x48)

        // txd should go low (start bit) within a few cycles
        var startBit = false
        for (_ <- 0 until 20 if !startBit) {
          dut.clock.step(1)
          if ((dut.io.uo_out.peek().litValue & 1) == 0) startBit = true
        }
        Predef.assert(startBit, "uart_txd never went low — start bit not seen")
      }
    }
  }
}
