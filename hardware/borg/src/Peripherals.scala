// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class PeripheralsIO(val CLOCK_MHZ: Int) extends Bundle {
  val ui_in = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))
  val addr_in = Input(UInt(11.W))
  val data_in = Input(UInt(32.W))
  val data_write_n = Input(UInt(2.W))
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(32.W))
  val data_ready = Output(Bool())
  val data_read_complete = Input(Bool())
  val user_interrupts = Output(UInt(14.W))
}

class tinyQV_peripherals(val CLOCK_MHZ: Int) extends Module {
  val io = IO(new PeripheralsIO(CLOCK_MHZ))
    // --- Data Bus Logic ---
    val data_out_r = RegInit(0.U(32.W))
    val data_out_hold = RegInit(false.B)
    val data_ready_r = RegInit(false.B)

    val read_req = io.data_read_n =/= MmioMap.BUS_IDLE.U(2.W)
    val data_from_peri = WireDefault(0.U(32.W))
    val data_ready_from_peri = WireDefault(false.B)

    val data_read_n_peri = io.data_read_n | Fill(2, data_ready_r)

    when(io.data_read_complete) {
      data_out_hold := false.B
    }

    when(!data_out_hold && data_ready_from_peri && read_req) {
      data_out_hold := true.B
      data_out_r := data_from_peri
    }
    data_ready_r := read_req && data_ready_from_peri

    val write_req = io.data_write_n =/= MmioMap.BUS_IDLE.U(2.W)
    val write_ready_r = RegNext(write_req && !(data_ready_r || RegNext(write_req)))
    io.data_out := data_out_r
    io.data_ready := data_ready_r || write_ready_r

    // --- Address Decoding ---
    val PERI_GPIO = MmioMap.userPeriU(MmioMap.USER_PERI_GPIO)
    val PERI_UART = MmioMap.userPeriU(MmioMap.USER_PERI_UART)
    val PERI_BORG = MmioMap.userPeriU(MmioMap.USER_PERI_BORG)

    val peri_sel = io.addr_in(MmioMap.USER_PERI_SEL_HI, MmioMap.USER_PERI_SEL_LO)
    val is_gpio = peri_sel === PERI_GPIO
    val is_uart = peri_sel === PERI_UART
    val is_borg = peri_sel === PERI_BORG

    // --- GPIO & Pin Muxing (Flattened for Synthesis) ---
    val gpio_out = RegInit(0.U(8.W))
    
    // Default pin muxing: pins 0-1 → UART, pins 2-7 → GPIO
    val func_sel = RegInit(VecInit(
      PERI_UART.pad(6), PERI_UART.pad(6),
      PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6),
      PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6)
    ))

    when(is_gpio) {
      when(io.addr_in(MmioMap.USER_SUB_ADDR_HI, MmioMap.USER_SUB_ADDR_LO) === MmioMap.GPIO_OUT_OFFSET.U && io.data_write_n =/= MmioMap.BUS_IDLE.U) {
        gpio_out := io.data_in(7, 0)
      }
      when(io.addr_in(MmioMap.GPIO_FUNC_SEL_BIT) === 1.U && io.addr_in(1, 0) === 0.U && io.data_write_n =/= MmioMap.BUS_IDLE.U) {
        val sel_idx = io.addr_in(MmioMap.GPIO_FUNC_SEL_IDX_HI, MmioMap.GPIO_FUNC_SEL_IDX_LO)
        func_sel(sel_idx) := io.data_in(5, 0)
      }
    }

    // Bus Mux
    data_ready_from_peri := true.B
    when(is_gpio) {
      when(io.addr_in(MmioMap.USER_SUB_ADDR_HI, MmioMap.USER_SUB_ADDR_LO) === MmioMap.GPIO_OUT_OFFSET.U) {
        data_from_peri := Cat(0.U(24.W), gpio_out)
      } .elsewhen(io.addr_in(MmioMap.USER_SUB_ADDR_HI, MmioMap.USER_SUB_ADDR_LO) === MmioMap.GPIO_IN_OFFSET.U) {
        data_from_peri := Cat(0.U(24.W), io.ui_in)
      } .otherwise {
        data_from_peri := 0.U
      }
    }

    val i_uart = Module(new tinyqv.peri.uart.PeriUart(CLOCK_MHZ))
    // clk and rst_n are implicit in Chisel modules
    i_uart.io.ui_in := io.ui_in
    i_uart.io.address := io.addr_in(MmioMap.USER_SUB_ADDR_HI, MmioMap.USER_SUB_ADDR_LO)
    i_uart.io.data_in := io.data_in
    i_uart.io.data_write_n := io.data_write_n | Fill(2, !is_uart)
    i_uart.io.data_read_n := data_read_n_peri | Fill(2, !is_uart)
    
    val data_from_uart = i_uart.io.data_out
    val data_ready_uart = i_uart.io.data_ready
    val uo_out_uart = i_uart.io.uo_out

    when(is_uart) {
      data_from_peri := data_from_uart
      data_ready_from_peri := data_ready_uart
    }

    val borg = Module(new Borg(FloatConfig.FP16))
    borg.io.address := io.addr_in(MmioMap.USER_SUB_ADDR_HI, MmioMap.USER_SUB_ADDR_LO)
    borg.io.data_in := io.data_in
    borg.io.data_write_n := io.data_write_n | Fill(2, !is_borg)
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
    io.uo_out := uo_out_muxed.asUInt

    val interrupts = Wire(Vec(14, Bool()))
    for (i <- 0 until 14) { interrupts(i) := false.B }
    interrupts(0) := i_uart.io.user_interrupt(0)
    interrupts(1) := i_uart.io.user_interrupt(1)
    interrupts(2) := borg.io.user_interrupt
    io.user_interrupts := interrupts.asUInt
}


