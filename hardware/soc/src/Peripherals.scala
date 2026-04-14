// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.{Borg, FloatConfig}

// User peripheral address decode constants — inlined from the former PeriphDecode.scala.
private[soc] object PeriphDecode {
  // User peripheral selects (addr[10:9])
  val USER_PERI_GPIO = 1
  val USER_PERI_UART = 2
  val USER_PERI_BORG = 3

  // TinyQV bus idle sentinel
  val BUS_IDLE = 3

  // Address field positions within 12-bit addr_in
  val USER_PERI_SEL_HI   = 11
  val USER_PERI_SEL_LO   = 10
  val USER_SUB_ADDR_HI   = 9
  val USER_SUB_ADDR_LO   = 0

  // GPIO non-standard address bit decoding
  val GPIO_FUNC_SEL_BIT    = 5
  val GPIO_FUNC_SEL_IDX_HI = 4
  val GPIO_FUNC_SEL_IDX_LO = 2
  val GPIO_OUT_OFFSET       = 0
  val GPIO_IN_OFFSET        = 4

  def userPeriU(idx: Int): UInt = idx.U
}

class PeripheralsIO(val CLOCK_MHZ: Int) extends Bundle {
  val ui_in = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))
  val addr_in = Input(UInt(12.W))
  val data_in = Input(UInt(32.W))
  val data_write_n = Input(UInt(2.W))
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(32.W))
  val data_ready = Output(Bool())
  val data_read_complete = Input(Bool())
  val user_interrupts = Output(UInt(14.W))

  // GPU read port (Step 19.2: Borg → MemoryController)
  val gpu_addr       = Output(UInt(16.W))
  val gpu_read_req   = Output(Bool())
  val gpu_data       = Input(UInt(32.W))
  val gpu_read_ready = Input(Bool())
}

class tinyQV_peripherals(val CLOCK_MHZ: Int) extends Module {
  val io = IO(new PeripheralsIO(CLOCK_MHZ))
    // --- Address Decoding Variables ---
    val PERI_GPIO = PeriphDecode.userPeriU(PeriphDecode.USER_PERI_GPIO)
    val PERI_UART = PeriphDecode.userPeriU(PeriphDecode.USER_PERI_UART)
    val PERI_BORG = PeriphDecode.userPeriU(PeriphDecode.USER_PERI_BORG)

    val peri_sel = io.addr_in(PeriphDecode.USER_PERI_SEL_HI, PeriphDecode.USER_PERI_SEL_LO)
    val is_gpio = peri_sel === PERI_GPIO
    val is_uart = peri_sel === PERI_UART
    val is_borg = peri_sel === PERI_BORG

    // --- Data Bus Shared Wires ---
    val data_from_peri = WireDefault(0.U(32.W))
    val data_ready_from_peri = WireDefault(false.B)
    val data_ready_r = RegInit(false.B)
    val data_read_n_peri = io.data_read_n | Fill(2, data_ready_r)

    // --- Peripheral Outputs ---
    val gpio_out = Wire(UInt(8.W))
    val func_sel = Wire(Vec(8, UInt(6.W)))
    
    val uo_out_uart = Wire(UInt(8.W))
    val interrupt_uart_0 = Wire(Bool())
    val interrupt_uart_1 = Wire(Bool())

    val uo_out_borg = Wire(UInt(8.W))
    val interrupt_borg = Wire(Bool())

    // --- Instantiate Sub-Modules inside methods to organize logic ---
    val i_uart = Module(new tinyqv.peri.uart.PeriUart(CLOCK_MHZ))
    val borg = Module(new Borg(FloatConfig.FP16))

    // --- Wire Everything ---
    wireDataBus()
    wireGpio()
    wireUart()
    wireBorg()
    wireOutputAndInterrupts()

    private def wireDataBus(): Unit = {
      val data_out_r = RegInit(0.U(32.W))
      val data_out_hold = RegInit(false.B)
      val read_req = io.data_read_n =/= PeriphDecode.BUS_IDLE.U(2.W)

      when(io.data_read_complete) {
        data_out_hold := false.B
      }

      when(!data_out_hold && data_ready_from_peri && read_req) {
        data_out_hold := true.B
        data_out_r := data_from_peri
      }
      data_ready_r := read_req && data_ready_from_peri

      val write_req = io.data_write_n =/= PeriphDecode.BUS_IDLE.U(2.W)
      val write_ready_r = RegNext(write_req && !(data_ready_r || RegNext(write_req)))
      io.data_out := data_out_r
      io.data_ready := data_ready_r || write_ready_r
    }

    private def wireGpio(): Unit = {
      val gpio_out_reg = RegInit(0.U(8.W))
      val func_sel_reg = RegInit(VecInit(
        PERI_UART.pad(6), PERI_UART.pad(6),
        PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6),
        PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6)
      ))

      gpio_out := gpio_out_reg
      func_sel := func_sel_reg

      when(is_gpio) {
        when(io.addr_in(PeriphDecode.USER_SUB_ADDR_HI, PeriphDecode.USER_SUB_ADDR_LO) === PeriphDecode.GPIO_OUT_OFFSET.U && io.data_write_n =/= PeriphDecode.BUS_IDLE.U) {
          gpio_out_reg := io.data_in(7, 0)
        }
        when(io.addr_in(PeriphDecode.GPIO_FUNC_SEL_BIT) === 1.U && io.addr_in(1, 0) === 0.U && io.data_write_n =/= PeriphDecode.BUS_IDLE.U) {
          val sel_idx = io.addr_in(PeriphDecode.GPIO_FUNC_SEL_IDX_HI, PeriphDecode.GPIO_FUNC_SEL_IDX_LO)
          func_sel_reg(sel_idx) := io.data_in(5, 0)
        }
      }

      data_ready_from_peri := true.B
      when(is_gpio) {
        when(io.addr_in(PeriphDecode.USER_SUB_ADDR_HI, PeriphDecode.USER_SUB_ADDR_LO) === PeriphDecode.GPIO_OUT_OFFSET.U) {
          data_from_peri := Cat(0.U(24.W), gpio_out_reg)
        } .elsewhen(io.addr_in(PeriphDecode.USER_SUB_ADDR_HI, PeriphDecode.USER_SUB_ADDR_LO) === PeriphDecode.GPIO_IN_OFFSET.U) {
          data_from_peri := Cat(0.U(24.W), io.ui_in)
        } .otherwise {
          data_from_peri := 0.U
        }
      }
    }

    private def wireUart(): Unit = {
      i_uart.io.ui_in := io.ui_in
      i_uart.io.address := io.addr_in(PeriphDecode.USER_SUB_ADDR_HI, PeriphDecode.USER_SUB_ADDR_LO)
      i_uart.io.data_in := io.data_in
      i_uart.io.data_write_n := io.data_write_n | Fill(2, !is_uart)
      i_uart.io.data_read_n := data_read_n_peri | Fill(2, !is_uart)
      
      uo_out_uart := i_uart.io.uo_out
      interrupt_uart_0 := i_uart.io.user_interrupt(0)
      interrupt_uart_1 := i_uart.io.user_interrupt(1)

      when(is_uart) {
        data_from_peri := i_uart.io.data_out
        data_ready_from_peri := i_uart.io.data_ready
      }
    }

    private def wireBorg(): Unit = {
      borg.io.address := io.addr_in(PeriphDecode.USER_SUB_ADDR_HI, PeriphDecode.USER_SUB_ADDR_LO)
      borg.io.data_in := io.data_in
      borg.io.data_write_n := io.data_write_n | Fill(2, !is_borg)
      borg.io.data_read_n := data_read_n_peri | Fill(2, !is_borg)

      uo_out_borg := borg.io.uo_out
      interrupt_borg := borg.io.user_interrupt

      // GPU read port passthrough (Step 19.2)
      borg.io.gpu_data       := io.gpu_data
      borg.io.gpu_read_ready := io.gpu_read_ready
      io.gpu_addr            := borg.io.gpu_addr
      io.gpu_read_req        := borg.io.gpu_read_req

      when(is_borg) {
        data_from_peri := borg.io.data_out
        data_ready_from_peri := borg.io.data_ready
      }
    }

    private def wireOutputAndInterrupts(): Unit = {
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
      interrupts(0) := interrupt_uart_0
      interrupts(1) := interrupt_uart_1
      interrupts(2) := interrupt_borg
      io.user_interrupts := interrupts.asUInt
    }
}


