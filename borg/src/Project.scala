// SPDX-FileCopyrightText: © 2024 Michael Bell
// SPDX-License-Identifier: Apache-2.0
package borg

import chisel3._
import chisel3.util._
import chisel3.experimental.IntParam
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

class tinyQVIO extends Bundle {
  val clk = Input(Clock())
  val rstn = Input(Bool())

  val data_addr = Output(UInt(28.W))
  val data_write_n = Output(UInt(2.W))
  val data_read_n = Output(UInt(2.W))
  val data_read_complete = Output(Bool())
  val data_out = Output(UInt(32.W))

  val data_ready = Input(Bool())
  val data_in = Input(UInt(32.W))

  val interrupt_req = Input(UInt(16.W))
  val time_pulse = Input(Bool())

  val spi_data_in = Input(UInt(4.W))
  val spi_data_out = Output(UInt(4.W))
  val spi_data_oe = Output(UInt(4.W))
  val spi_clk_out = Output(Bool())
  val spi_flash_select = Output(Bool())
  val spi_ram_a_select = Output(Bool())
  val spi_ram_b_select = Output(Bool())

  val debug_instr_complete = Output(Bool())
  val debug_instr_ready = Output(Bool())
  val debug_instr_valid = Output(Bool())
  val debug_fetch_restart = Output(Bool())
  val debug_data_ready = Output(Bool())
  val debug_interrupt_pending = Output(Bool())
  val debug_branch = Output(Bool())
  val debug_early_branch = Output(Bool())
  val debug_ret = Output(Bool())
  val debug_reg_wen = Output(Bool())
  val debug_counter_0 = Output(Bool())
  val debug_data_continue = Output(Bool())
  val debug_stall_txn = Output(Bool())
  val debug_stop_txn = Output(Bool())
  val debug_rd = Output(UInt(4.W))
}

class tinyQV extends BlackBox {
  val io = IO(new tinyQVIO)
}

class tinyQV_peripherals_ext_IO extends Bundle {
  val clk = Input(Clock())
  val rst_n = Input(Bool())
  val ui_in = Input(UInt(8.W))
  val ui_in_raw = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))
  val audio = Output(Bool())
  val audio_select = Output(Bool())
  val addr_in = Input(UInt(11.W))
  val data_in = Input(UInt(32.W))
  val data_write_n = Input(UInt(2.W))
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(32.W))
  val data_ready = Output(Bool())
  val data_read_complete = Input(Bool())
  val user_interrupts = Output(UInt(14.W))
}

class tinyQV_peripherals_ext(val CLOCK_MHZ: Int = 64) extends BlackBox(Map("CLOCK_MHZ" -> IntParam(CLOCK_MHZ))) {
  override val desiredName = "tinyQV_peripherals"
  val io = IO(new tinyQV_peripherals_ext_IO)
}

class uart_tx_IO extends Bundle {
  val clk = Input(Clock())
  val resetn = Input(Bool())
  val uart_txd = Output(Bool())
  val uart_tx_en = Input(Bool())
  val uart_tx_data = Input(UInt(8.W))
  val uart_tx_busy = Output(Bool())
}

class uart_tx(val CLK_HZ: Int = 64000000, val BIT_RATE: Int = 4000000) extends BlackBox(Map("CLK_HZ" -> IntParam(CLK_HZ), "BIT_RATE" -> IntParam(BIT_RATE))) {
  val io = IO(new uart_tx_IO)
}

class tt_um_tt_tinyQV(val CLOCK_MHZ: Int = 64) extends RawModule {
  val ui_in = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val uio_in = IO(Input(UInt(8.W)))
  val uio_out = IO(Output(UInt(8.W)))
  val uio_oe = IO(Output(UInt(8.W)))
  val ena = IO(Input(Bool()))
  val clk = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))

  // Register the reset on the negative edge of clock
  val rst_reg_n = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }

  // Address map constants
  val PERI_NONE = 0x0.U(4.W)
  val PERI_ID = 0x2.U(4.W)
  val PERI_GPIO_OUT_SEL = 0x3.U(4.W)
  val PERI_DEBUG_UART = 0x6.U(4.W)
  val PERI_DEBUG_UART_STATUS = 0x7.U(4.W)
  val PERI_TIME_LIMIT = 0xB.U(4.W)
  val PERI_DEBUG = 0xC.U(4.W)
  val PERI_USER = 0xF.U(4.W)

  val qspi_data_in = Cat(uio_in(5, 4), uio_in(2, 1))

  val i_tinyqv = Module(new tinyQV)
  val i_peripherals = Module(new tinyQV_peripherals_ext(CLOCK_MHZ))
  val i_debug_uart_tx = Module(new uart_tx(CLOCK_MHZ * 1000000, 4000000))

  i_tinyqv.io.clk := clk
  i_tinyqv.io.rstn := rst_reg_n
  i_tinyqv.io.spi_data_in := qspi_data_in

  val qspi_data_out = i_tinyqv.io.spi_data_out
  val qspi_data_oe = i_tinyqv.io.spi_data_oe
  val qspi_clk_out = i_tinyqv.io.spi_clk_out
  val qspi_flash_select = i_tinyqv.io.spi_flash_select
  val qspi_ram_a_select = i_tinyqv.io.spi_ram_a_select
  val qspi_ram_b_select = i_tinyqv.io.spi_ram_b_select

  val audio = i_peripherals.io.audio
  val audio_select = i_peripherals.io.audio_select

  uio_out := Cat(Mux(audio_select, audio, qspi_ram_b_select), qspi_ram_a_select, qspi_data_out(3, 2), qspi_clk_out, qspi_data_out(1, 0), qspi_flash_select)
  uio_oe := Mux(rst_n, Cat(3.U(2.W), qspi_data_oe(3, 2), 1.U(1.W), qspi_data_oe(1, 0), 1.U(1.W)), 0.U(8.W))

  val addr = i_tinyqv.io.data_addr
  val write_n = i_tinyqv.io.data_write_n
  val read_n = i_tinyqv.io.data_read_n
  val read_complete = i_tinyqv.io.data_read_complete
  val data_to_write = i_tinyqv.io.data_out

  val data_ready = Wire(Bool())
  val data_from_read = WireDefault(0.U(32.W))

  i_tinyqv.io.data_ready := data_ready
  i_tinyqv.io.data_in := data_from_read

  val peri_out = i_peripherals.io.uo_out
  val peri_data_out = i_peripherals.io.data_out
  val peri_data_ready = i_peripherals.io.data_ready
  val peri_interrupts = i_peripherals.io.user_interrupts

  // Peripherals get synchronized ui_in.
  val ui_in_sync0 = withClockAndReset(clk, false.B) { RegNext(ui_in) }
  val ui_in_sync = withClockAndReset(clk, false.B) { RegNext(ui_in_sync0) }

  val interrupt_req = Cat(peri_interrupts, ui_in_sync(1, 0))
  i_tinyqv.io.interrupt_req := interrupt_req

  val time_pulse = Wire(Bool())
  i_tinyqv.io.time_pulse := time_pulse

  i_peripherals.io.clk := clk
  i_peripherals.io.rst_n := rst_reg_n
  i_peripherals.io.ui_in := ui_in_sync
  i_peripherals.io.ui_in_raw := ui_in
  i_peripherals.io.addr_in := addr(10, 0)
  i_peripherals.io.data_in := data_to_write
  i_peripherals.io.data_write_n := write_n
  i_peripherals.io.data_read_n := read_n
  i_peripherals.io.data_read_complete := read_complete

  val connect_peripheral = WireDefault(PERI_NONE)

  when(Cat(addr(27, 6), addr(1, 0)) === 0x800000.U) {
    connect_peripheral := addr(5, 2)
  } .elsewhen(addr(27, 11) === 0x10000.U) {
    connect_peripheral := PERI_USER
  }

  val gpio_out_sel = withClockAndReset(clk, !rst_reg_n) { RegInit(Cat(!ui_in(0), 0.U(1.W))) }
  val time_limit = withClockAndReset(clk, !rst_reg_n) { RegInit(((CLOCK_MHZ / 4) - 1).U(5.W)) }

  withClockAndReset(clk, false.B) {
    when(write_n =/= 3.U(2.W)) {
      when(connect_peripheral === PERI_GPIO_OUT_SEL) {
        gpio_out_sel := data_to_write(7, 6)
      }
      when(connect_peripheral === PERI_TIME_LIMIT) {
        time_limit := data_to_write(6, 2)
      }
    }
  }

  val debug_uart_tx_busy = i_debug_uart_tx.io.uart_tx_busy
  
  data_from_read := "hFFFFFFFF".U(32.W)
  switch(connect_peripheral) {
    is(PERI_ID) { data_from_read := 0x41.U(32.W) }
    is(PERI_GPIO_OUT_SEL) { data_from_read := Cat(0.U(24.W), gpio_out_sel, 0.U(6.W)) }
    is(PERI_DEBUG_UART_STATUS) { data_from_read := Cat(0.U(31.W), debug_uart_tx_busy) }
    is(PERI_TIME_LIMIT) { data_from_read := Cat(0.U(25.W), time_limit, 3.U(2.W)) }
    is(PERI_USER) { data_from_read := peri_data_out }
  }

  data_ready := Mux(connect_peripheral === PERI_USER, peri_data_ready, 1.U(1.W))

  val debug_uart_tx_start = (write_n =/= 3.U(2.W)) && (connect_peripheral === PERI_DEBUG_UART)

  i_debug_uart_tx.io.clk := clk
  i_debug_uart_tx.io.resetn := rst_reg_n
  val debug_uart_txd = i_debug_uart_tx.io.uart_txd
  i_debug_uart_tx.io.uart_tx_en := debug_uart_tx_start
  i_debug_uart_tx.io.uart_tx_data := data_to_write(7, 0)

  val time_count = withClockAndReset(clk, !rst_reg_n) { RegInit(0.U(7.W)) }
  withClockAndReset(clk, false.B) {
    when(time_pulse) {
      time_count := 0.U
    } .otherwise {
      time_count := time_count + 1.U
    }
  }
  time_pulse := (time_count === Cat(time_limit, 3.U(2.W)))

  val debug_register_data = withClockAndReset(clk, !rst_reg_n) { RegInit(ui_in(1)) }
  withClockAndReset(clk, false.B) {
    when(write_n =/= 3.U(2.W) && connect_peripheral === PERI_DEBUG) {
      debug_register_data := data_to_write(0)
    }
  }

  val debug_rd_r = withClockAndReset(clk, false.B) { RegNext(i_tinyqv.io.debug_rd) }

  val debug_signals = Cat(
    i_tinyqv.io.debug_instr_complete,
    i_tinyqv.io.debug_instr_ready,
    i_tinyqv.io.debug_instr_valid,
    i_tinyqv.io.debug_fetch_restart,
    read_n =/= 3.U(2.W),
    write_n =/= 3.U(2.W),
    i_tinyqv.io.debug_data_ready,
    i_tinyqv.io.debug_interrupt_pending,
    i_tinyqv.io.debug_branch,
    i_tinyqv.io.debug_early_branch,
    i_tinyqv.io.debug_ret,
    i_tinyqv.io.debug_reg_wen,
    i_tinyqv.io.debug_counter_0,
    i_tinyqv.io.debug_data_continue,
    i_tinyqv.io.debug_stall_txn,
    i_tinyqv.io.debug_stop_txn
  )
  
  val debug_signal = debug_signals(ui_in(6, 3))

  uo_out := Cat(
    Mux(gpio_out_sel(1), peri_out(7), debug_signal),
    Mux(gpio_out_sel(0), peri_out(6), debug_uart_txd),
    Mux(debug_register_data, debug_rd_r(3), peri_out(5)),
    Mux(debug_register_data, debug_rd_r(2), peri_out(4)),
    Mux(debug_register_data, debug_rd_r(1), peri_out(3)),
    Mux(debug_register_data, debug_rd_r(0), peri_out(2)),
    peri_out(1),
    peri_out(0)
  )

  // Avoid warnings on unused inputs
  val unused = ena | uio_in(7, 6).orR | uio_in(3) | uio_in(0) | read_complete | false.B
}

object ProjectMain extends App {
  val targetDir = "src"
  val verilog = ChiselStage.emitSystemVerilog(
    gen = new tt_um_tt_tinyQV(CLOCK_MHZ = 64),
    firtoolOpts = Array("--lowering-options=disallowLocalVariables", "--disable-all-randomization", "--strip-debug-info")
  )
  
  val filteredVerilog = verilog.split("\n").filterNot(line => 
    line.contains("layers-tt_um_tt_tinyQV-Verification") || line.trim.startsWith("`ifndef") || line.trim.startsWith("`define") || line.trim.startsWith("`include") || line.trim.startsWith("`endif") || line.trim.startsWith("`ifdef") || line.trim.startsWith("`else")
  ).mkString("\n")

  val finalVerilog = "/*\n * Copyright (c) 2024 Michael Bell\n * SPDX-License-Identifier: Apache-2.0\n */\n\n`default_nettype none\n\n" + filteredVerilog

  Files.write(Paths.get(targetDir, "project.v"), finalVerilog.getBytes)
}
