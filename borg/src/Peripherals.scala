// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class TqvpUartWrapperIO extends Bundle {
  val clk = Input(Clock())
  val rst_n = Input(Bool())
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
import chisel3.experimental.IntParam

class tqvp_uart_wrapper(val CLOCK_MHZ: Int = 64) extends BlackBox(Map("CLOCK_MHZ" -> IntParam(CLOCK_MHZ))) {
  val io = IO(new TqvpUartWrapperIO)
}

class tinyQV_peripherals(val CLOCK_MHZ: Int = 64) extends RawModule {
  val clk = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))

  val ui_in = IO(Input(UInt(8.W)))
  val ui_in_raw = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))

  val audio = IO(Output(Bool()))
  val audio_select = IO(Output(Bool()))

  val addr_in = IO(Input(UInt(11.W)))
  val data_in = IO(Input(UInt(32.W)))

  val data_write_n = IO(Input(UInt(2.W)))
  val data_read_n = IO(Input(UInt(2.W)))

  val data_out = IO(Output(UInt(32.W)))
  val data_ready = IO(Output(Bool()))

  val data_read_complete = IO(Input(Bool()))

  val user_interrupts = IO(Output(UInt(14.W)))

  withClockAndReset(clk, !rst_n) {
    // --- Data Bus Logic ---
    val data_out_r = RegInit(0.U(32.W))
    val data_out_hold = RegInit(false.B)
    val data_ready_r = RegInit(false.B)

    val read_req = data_read_n =/= 3.U(2.W)
    val data_from_peri = WireDefault(0.U(32.W))
    val data_ready_from_peri = WireDefault(false.B)

    val data_read_n_peri = data_read_n | Fill(2, data_ready_r)

    when(data_read_complete) {
      data_out_hold := false.B
    }

    when(!data_out_hold && data_ready_from_peri && read_req) {
      data_out_hold := true.B
      data_out_r := data_from_peri
    }
    data_ready_r := read_req && data_ready_from_peri

    data_out := data_out_r
    data_ready := data_ready_r || (data_write_n =/= 3.U(2.W))

    // --- Address Decoding ---
    val PERI_GPIO = 1
    val PERI_UART = 2
    val PERI_BORG = 23

    val peri_user = WireDefault(0.U(24.W))
    when(addr_in(10) === 1.U || addr_in(9) === 1.U) {
      when(addr_in(10, 9) === 3.U || addr_in(10, 6) === 23.U) {
        peri_user := (1L << PERI_BORG).U
      }
    } .otherwise {
      val shiftAmt = addr_in(9, 6)
      peri_user := 1.U << shiftAmt
    }

    // --- GPIO & Pin Muxing (Flattened for Synthesis) ---
    val gpio_out = RegInit(0.U(8.W))
    
    val func_sel = RegInit(VecInit(
      2.U(6.W), 2.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W)
    ))

    when(peri_user(PERI_GPIO)) {
      when(addr_in(5, 0) === 0.U && data_write_n =/= 3.U) {
        gpio_out := data_in(7, 0)
      }
      when(addr_in(5) === 1.U && addr_in(1, 0) === 0.U && data_write_n =/= 3.U) {
        val sel_idx = addr_in(4, 2)
        func_sel(sel_idx) := data_in(5, 0)
      }
    }

    // Bus Mux
    data_ready_from_peri := true.B
    when(peri_user(PERI_GPIO)) {
      when(addr_in(5, 0) === 0.U) {
        data_from_peri := Cat(0.U(24.W), gpio_out)
      } .elsewhen(addr_in(5, 0) === 4.U) {
        data_from_peri := Cat(0.U(24.W), ui_in)
      } .otherwise {
        data_from_peri := 0.U
      }
    }

    val i_uart = Module(new tqvp_uart_wrapper(CLOCK_MHZ))
    i_uart.io.clk := clk
    i_uart.io.rst_n := rst_n
    i_uart.io.ui_in := ui_in
    i_uart.io.address := addr_in(5, 0)
    i_uart.io.data_in := data_in
    i_uart.io.data_write_n := data_write_n | Fill(2, ~peri_user(PERI_UART))
    i_uart.io.data_read_n := data_read_n_peri | Fill(2, ~peri_user(PERI_UART))
    
    val data_from_uart = i_uart.io.data_out
    val data_ready_uart = i_uart.io.data_ready
    val uo_out_uart = i_uart.io.uo_out

    when(peri_user(PERI_UART)) {
      data_from_peri := data_from_uart
      data_ready_from_peri := data_ready_uart
    }

    val i_user_peri39 = Module(new Borg())
    i_user_peri39.io.ui_in := ui_in
    i_user_peri39.io.address := addr_in(5, 0)
    i_user_peri39.io.data_in := data_in
    i_user_peri39.io.data_write_n := data_write_n | Fill(2, ~peri_user(PERI_BORG))
    i_user_peri39.io.data_read_n := data_read_n_peri | Fill(2, ~peri_user(PERI_BORG))

    val data_from_borg = i_user_peri39.io.data_out
    val data_ready_borg = i_user_peri39.io.data_ready
    val uo_out_borg = i_user_peri39.io.uo_out

    when(peri_user(PERI_BORG)) {
      data_from_peri := data_from_borg
      data_ready_from_peri := data_ready_borg
    }

    val uo_out_muxed = Wire(Vec(8, Bool()))
    for (k <- 0 until 8) {
      when(func_sel(k) === 23.U) {
        uo_out_muxed(k) := uo_out_borg(k)
      } .elsewhen(func_sel(k) === 2.U) {
        uo_out_muxed(k) := uo_out_uart(k)
      } .otherwise {
        uo_out_muxed(k) := gpio_out(k)
      }
    }
    uo_out := uo_out_muxed.asUInt

    val interrupts = Wire(Vec(14, Bool()))
    for (i <- 0 until 14) { interrupts(i) := false.B }
    interrupts(0) := i_uart.io.user_interrupt(0)
    interrupts(1) := i_uart.io.user_interrupt(1)
    user_interrupts := interrupts.asUInt

    audio := false.B
    audio_select := false.B
  }
}


