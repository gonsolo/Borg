// Copyright (c) 2025 Michael Bell
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package peri.uart

import chisel3._
import chisel3.util._
import hutt.HuttBus

class PeriUartIO extends Bundle {
  val ui_in  = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))

  /** CPU MMIO port: 6-bit byte address (64-byte window inside the user
    * peripheral region).  Reads return register values in 32-bit words;
    * writes update the addressed register.  Reads complete combinationally,
    * so `resp.valid` rises the cycle after `req.fire`.
    */
  val mmio = Flipped(new HuttBus(6))

  val user_interrupt = Output(UInt(2.W))
}

class PeriUart(val CLOCK_MHZ: Int, val DIVIDER_REG_LEN: Int = 13) extends Module {
  override val desiredName = "peri_uart"

  val io = IO(new PeriUartIO)

  // -- Request handshake -----------------------------------------------------
  // Single-outstanding: accept a new request only when no response is pending.
  val respPending = RegInit(false.B)
  val respData    = Reg(UInt(32.W))

  io.mmio.req.ready  := !respPending
  io.mmio.resp.valid := respPending
  io.mmio.resp.bits  := respData

  when(io.mmio.resp.fire) { respPending := false.B }

  val req      = io.mmio.req
  val reqFire  = req.fire
  val isWrite  = reqFire && req.bits.write
  val isRead   = reqFire && !req.bits.write
  val addr     = req.bits.addr
  val wdata    = req.bits.data

  // -- Baud divider register (addr 0x08) -------------------------------------
  val baud_divider = RegInit(((CLOCK_MHZ * 1000000) / 115200).U(DIVIDER_REG_LEN.W))
  when(isWrite && addr === 8.U) {
    baud_divider := wdata(DIVIDER_REG_LEN - 1, 0)
  }

  // -- RX-select register (addr 0x0C) ----------------------------------------
  val rxd_select = RegInit(false.B)
  when(isWrite && addr === 12.U) {
    rxd_select := wdata(0)
  }

  // -- TX path ---------------------------------------------------------------
  val i_uart_tx = Module(new UartTx(DIVIDER_REG_LEN))
  // TX is triggered by any write to addr 0
  i_uart_tx.io.uart_tx_en   := isWrite && addr === 0.U
  i_uart_tx.io.uart_tx_data := wdata(7, 0)
  i_uart_tx.io.baud_divider := baud_divider

  val uart_tx_busy = i_uart_tx.io.uart_tx_busy
  val uart_txd     = i_uart_tx.io.uart_txd

  // -- RX path ---------------------------------------------------------------
  val uart_rx_buffered = RegInit(false.B)
  val uart_rxd         = Mux(rxd_select, io.ui_in(3), io.ui_in(7))

  val i_uart_rx = Module(new UartRx(DIVIDER_REG_LEN))
  i_uart_rx.io.uart_rxd      := uart_rxd
  i_uart_rx.io.uart_rx_read  := !uart_rx_buffered
  i_uart_rx.io.baud_divider  := baud_divider

  val uart_rts       = i_uart_rx.io.uart_rts
  val uart_rx_valid  = i_uart_rx.io.uart_rx_valid
  val uart_rx_data   = i_uart_rx.io.uart_rx_data

  val uart_rx_buf_data = RegInit(0.U(8.W))

  when(!uart_rx_buffered) {
    uart_rx_buffered := uart_rx_valid
    when(uart_rx_valid) { uart_rx_buf_data := uart_rx_data }
  }.otherwise {
    when(isRead && addr === 0.U) { uart_rx_buffered := false.B }
  }

  io.user_interrupt := Cat(!uart_tx_busy, uart_rx_buffered)

  // -- Read data muxing & response latch ------------------------------------
  // Compute combinationally so the value latched at req.fire is the correct read.
  val readData = MuxLookup(addr, 0.U)(Seq(
    0.U  -> Cat(0.U(24.W), uart_rx_buf_data),
    4.U  -> Cat(0.U(30.W), uart_rx_buffered, uart_tx_busy),
    8.U  -> Cat(0.U((32 - DIVIDER_REG_LEN).W), baud_divider),
    12.U -> Cat(0.U(31.W), rxd_select)
  ))

  when(reqFire) {
    respPending := true.B
    when(!req.bits.write) { respData := readData }
      .otherwise           { respData := 0.U }
  }

  io.uo_out := Fill(4, Cat(uart_rts, uart_txd))
}
