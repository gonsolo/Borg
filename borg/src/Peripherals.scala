// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class tinyQV_peripherals(val CLOCK_MHZ: Int = 64) extends RawModule {
  val clk = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))

  val ui_in = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))

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
    val PERI_GPIO = MmioMap.userPeriU(MmioMap.USER_PERI_GPIO)
    val PERI_UART = MmioMap.userPeriU(MmioMap.USER_PERI_UART)
    val PERI_BORG = MmioMap.userPeriU(MmioMap.USER_PERI_BORG)

    val peri_sel = addr_in(10, 6)
    val is_gpio = peri_sel === PERI_GPIO
    val is_uart = peri_sel === PERI_UART
    val is_borg = peri_sel === PERI_BORG

    // --- GPIO & Pin Muxing (Flattened for Synthesis) ---
    val gpio_out = RegInit(0.U(8.W))
    
    val func_sel = RegInit(VecInit(
      2.U(6.W), 2.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W), 1.U(6.W)
    ))

    when(is_gpio) {
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
    when(is_gpio) {
      when(addr_in(5, 0) === 0.U) {
        data_from_peri := Cat(0.U(24.W), gpio_out)
      } .elsewhen(addr_in(5, 0) === 4.U) {
        data_from_peri := Cat(0.U(24.W), ui_in)
      } .otherwise {
        data_from_peri := 0.U
      }
    }

    val i_uart = Module(new tinyqv.peri.uart.PeriUart(CLOCK_MHZ))
    // clk and rst_n are implicit in Chisel modules
    i_uart.io.ui_in := ui_in
    i_uart.io.address := addr_in(5, 0)
    i_uart.io.data_in := data_in
    i_uart.io.data_write_n := data_write_n | Fill(2, !is_uart)
    i_uart.io.data_read_n := data_read_n_peri | Fill(2, !is_uart)
    
    val data_from_uart = i_uart.io.data_out
    val data_ready_uart = i_uart.io.data_ready
    val uo_out_uart = i_uart.io.uo_out

    when(is_uart) {
      data_from_peri := data_from_uart
      data_ready_from_peri := data_ready_uart
    }

    val borg = Module(new Borg(FloatConfig.FP16))
    borg.io.address := addr_in(5, 0)
    borg.io.data_in := data_in(15, 0)
    borg.io.data_write_n := data_write_n | Fill(2, !is_borg)
    borg.io.data_read_n := data_read_n_peri | Fill(2, !is_borg)

    val data_from_borg = Cat(0.U(16.W), borg.io.data_out)
    val data_ready_borg = borg.io.data_ready
    val uo_out_borg = borg.io.uo_out

    when(is_borg) {
      data_from_peri := data_from_borg
      data_ready_from_peri := data_ready_borg
    }

    val uo_out_muxed = Wire(Vec(8, Bool()))
    for (k <- 0 until 8) {
      when(func_sel(k) === PERI_UART) {
        uo_out_muxed(k) := uo_out_uart(k)
      } .elsewhen(func_sel(k) === PERI_BORG) {
        uo_out_muxed(k) := uo_out_borg(k)
      } .otherwise {
        uo_out_muxed(k) := gpio_out(k)
      }
    }
    uo_out := uo_out_muxed.asUInt

    val interrupts = Wire(Vec(14, Bool()))
    for (i <- 0 until 14) { interrupts(i) := false.B }
    interrupts(0) := i_uart.io.user_interrupt(0)
    interrupts(1) := i_uart.io.user_interrupt(1)
    interrupts(2) := borg.io.user_interrupt
    user_interrupts := interrupts.asUInt
  }
}


