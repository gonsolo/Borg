// Copyright (c) 2025 Michael Bell
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.peri.uart

import chisel3._
import chisel3.util._

class PeriUartIO extends Bundle {
  val ui_in = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))
  
  val address = Input(UInt(6.W))
  val data_in = Input(UInt(32.W))
  
  val data_write_n = Input(UInt(2.W))
  val data_read_n = Input(UInt(2.W))
  
  val data_out = Output(UInt(32.W))
  val data_ready = Output(Bool())
  
  val user_interrupt = Output(UInt(2.W))
}

class PeriUart(val CLOCK_MHZ: Int = 64, val DIVIDER_REG_LEN: Int = 13) extends Module {
  override val desiredName = "tqvp_uart_wrapper"
  
  val io = IO(new PeriUartIO)

  // A read/write register to control the divider
  val baud_divider = RegInit(((CLOCK_MHZ * 1000000) / 115200).U(DIVIDER_REG_LEN.W))
  when(io.address === 8.U) {
    when(io.data_write_n =/= 3.U) {
      baud_divider := Cat(
        Mux(io.data_write_n(1) =/= io.data_write_n(0), io.data_in(DIVIDER_REG_LEN - 1, 8), baud_divider(DIVIDER_REG_LEN - 1, 8)),
        io.data_in(7, 0)
      )
    }
  }

  // A read/write 1-bit register to choose alternative ui_in for rxd
  val rxd_select = RegInit(false.B)
  when(io.address === 12.U) {
    // 6'hc is 12 in decimal
    when(io.data_write_n =/= 3.U) {
      rxd_select := io.data_in(0)
    }
  }

  ////// TX functionality //////

  val i_uart_tx = Module(new UartTx(DIVIDER_REG_LEN))
  i_uart_tx.io.uart_tx_en := (io.address === 0.U) && (io.data_write_n =/= 3.U)
  i_uart_tx.io.uart_tx_data := io.data_in(7, 0)
  i_uart_tx.io.baud_divider := baud_divider

  // Interrupt on ability to send
  val uart_tx_busy = i_uart_tx.io.uart_tx_busy
  val uart_txd = i_uart_tx.io.uart_txd

  ////// RX functionality //////
  
  val uart_rx_buffered = RegInit(false.B)
  
  val uart_rxd = Mux(rxd_select, io.ui_in(3), io.ui_in(7))

  val i_uart_rx = Module(new UartRx(DIVIDER_REG_LEN))
  i_uart_rx.io.uart_rxd := uart_rxd
  i_uart_rx.io.uart_rx_read := !uart_rx_buffered
  i_uart_rx.io.baud_divider := baud_divider

  val uart_rts = i_uart_rx.io.uart_rts
  val uart_rx_valid = i_uart_rx.io.uart_rx_valid
  val uart_rx_data = i_uart_rx.io.uart_rx_data

  // Buffer one byte of received data
  val uart_rx_buf_data = RegInit(0.U(8.W))

  when(!uart_rx_buffered) {
    uart_rx_buffered := uart_rx_valid
    when (uart_rx_valid) {
      uart_rx_buf_data := uart_rx_data
    }
  }.otherwise {
    when(io.address === 0.U && io.data_read_n =/= 3.U) {
      uart_rx_buffered := false.B
    }
  }

  // Interrupt outs
  io.user_interrupt := Cat(!uart_tx_busy, uart_rx_buffered)

  io.data_out := MuxLookup(io.address, 0.U)(Seq(
    0.U -> Cat(0.U(24.W), uart_rx_buf_data),
    4.U -> Cat(0.U(30.W), uart_rx_buffered, uart_tx_busy),
    8.U -> Cat(0.U((32 - DIVIDER_REG_LEN).W), baud_divider),
    12.U -> Cat(0.U(31.W), rxd_select)
  ))

  io.data_ready := true.B

  io.uo_out := Fill(4, Cat(uart_rts, uart_txd))
}
