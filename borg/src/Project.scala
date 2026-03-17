// SPDX-FileCopyrightText: © 2024 Michael Bell
// SPDX-License-Identifier: CERN-OHL-S-2.0
package borg

import chisel3._
import MmioMap._
import chisel3.util._
import chisel3.experimental.IntParam
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

class tinyQVIO extends Bundle {

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

class tinyQV extends ExtModule {
  val clk = IO(Input(Clock()))
  val rstn = IO(Input(Bool()))
  val io = FlatIO(new tinyQVIO)
}


/** Common SoC logic shared between TT ASIC and pico-ice FPGA top-level modules.
  *
  * Subclasses must provide: soc_clk, soc_rst_n, soc_rst_reg_n, soc_ui_in, soc_qspi_data_in.
  * The trait provides: QSPI outputs, uo_out value, and all internal SoC wiring.
  */
trait SoCLogic { self: RawModule =>
  def CLOCK_MHZ: Int

  // --- Abstract members provided by each top-level ---
  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt
  def soc_qspi_data_in: UInt


  // --- Core and peripheral instantiation ---
  val i_tinyqv = Module(new tinyQV)
  lazy val i_peripherals = Module(new tinyQV_peripherals(CLOCK_MHZ))
  lazy val i_debug_uart_tx = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new tinyqv.peri.uart.UartTx(13))
  }

  // --- QSPI outputs (exposed for top-level wiring) ---
  lazy val qspi_data_out: UInt = i_tinyqv.io.spi_data_out
  lazy val qspi_data_oe: UInt = i_tinyqv.io.spi_data_oe
  lazy val qspi_clk_out: Bool = i_tinyqv.io.spi_clk_out
  lazy val qspi_flash_select: Bool = i_tinyqv.io.spi_flash_select
  lazy val qspi_ram_a_select: Bool = i_tinyqv.io.spi_ram_a_select
  lazy val qspi_ram_b_select: Bool = i_tinyqv.io.spi_ram_b_select

  /** Wire up the entire SoC. Call this from the top-level module body. */
  def wireSoC(): UInt = {
    i_tinyqv.clk := soc_clk
    i_tinyqv.rstn := soc_rst_reg_n
    i_tinyqv.io.spi_data_in := soc_qspi_data_in

    val addr = i_tinyqv.io.data_addr
    val write_n = i_tinyqv.io.data_write_n
    val read_n = i_tinyqv.io.data_read_n
    val read_complete = i_tinyqv.io.data_read_complete
    val data_to_write = i_tinyqv.io.data_out

    val data_ready = Wire(Bool())
    val data_from_read = WireDefault(0.U(32.W))

    i_tinyqv.io.data_ready := data_ready
    i_tinyqv.io.data_in := data_from_read

    val peri_out = i_peripherals.uo_out
    val peri_data_out = i_peripherals.data_out
    val peri_data_ready = i_peripherals.data_ready
    val peri_interrupts = i_peripherals.user_interrupts

    // Peripherals get synchronized ui_in.
    val ui_in_sync0 = withClockAndReset(soc_clk, false.B) { RegNext(soc_ui_in) }
    val ui_in_sync = withClockAndReset(soc_clk, false.B) { RegNext(ui_in_sync0) }

    val interrupt_req = Cat(peri_interrupts, ui_in_sync(1, 0))
    i_tinyqv.io.interrupt_req := interrupt_req

    val time_pulse = Wire(Bool())
    i_tinyqv.io.time_pulse := time_pulse

    i_peripherals.clk := soc_clk
    i_peripherals.rst_n := soc_rst_reg_n
    i_peripherals.ui_in := ui_in_sync
    i_peripherals.addr_in := addr(10, 0)
    i_peripherals.data_in := data_to_write
    i_peripherals.data_write_n := write_n
    i_peripherals.data_read_n := read_n
    i_peripherals.data_read_complete := read_complete

    val connect_peripheral = WireDefault(socPeriU(PERI_NONE))

    when(MmioMap.socRegion.matches(addr)) {
      connect_peripheral := MmioMap.socRegion.index(addr)
    } .elsewhen(MmioMap.userRegion.matches(addr)) {
      connect_peripheral := socPeriU(PERI_USER)
    }

    val gpio_out_sel = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(Cat(!soc_ui_in(0), 0.U(1.W))) }
    // Minimum time_limit is 1: the nibble-serial mtime counter needs 8 cycles per
    // increment, so the pulse period must be >= 8 cycles = (limit+1)*4 >= 8.
    val time_limit = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(Math.max((CLOCK_MHZ / 4) - 1, 1).U(5.W)) }

    // MMIO-configurable debug UART baud divider, defaults to 115200 baud.
    val default_baud_divider = ((CLOCK_MHZ * 1000000) / 115200).U(13.W)
    val debug_baud_divider = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(default_baud_divider) }

    withClockAndReset(soc_clk, false.B) {
      when(write_n =/= 3.U(2.W)) {
        when(connect_peripheral === socPeriU(PERI_GPIO_OUT_SEL)) {
          gpio_out_sel := data_to_write(7, 6)
        }
        when(connect_peripheral === socPeriU(PERI_TIME_LIMIT)) {
          time_limit := data_to_write(6, 2)
        }
        when(connect_peripheral === socPeriU(PERI_DEBUG_UART_BAUD)) {
          debug_baud_divider := data_to_write(12, 0)
        }
      }
    }

    val debug_uart_tx_busy = i_debug_uart_tx.io.uart_tx_busy

    data_from_read := "hFFFFFFFF".U(32.W)
    switch(connect_peripheral) {
      is(socPeriU(PERI_ID)) { data_from_read := 0x41.U(32.W) }
      is(socPeriU(PERI_GPIO_OUT_SEL)) { data_from_read := Cat(0.U(24.W), gpio_out_sel, 0.U(6.W)) }
      is(socPeriU(PERI_DEBUG_UART_STATUS)) { data_from_read := Cat(0.U(31.W), debug_uart_tx_busy) }
      is(socPeriU(PERI_DEBUG_UART_BAUD)) { data_from_read := Cat(0.U(19.W), debug_baud_divider) }
      is(socPeriU(PERI_TIME_LIMIT)) { data_from_read := Cat(0.U(25.W), time_limit, 3.U(2.W)) }
      is(socPeriU(PERI_USER)) { data_from_read := peri_data_out }
    }

    data_ready := Mux(connect_peripheral === socPeriU(PERI_USER), peri_data_ready, 1.U(1.W))

    val debug_uart_tx_start = (write_n =/= 3.U(2.W)) && (connect_peripheral === socPeriU(PERI_DEBUG_UART))

    val debug_uart_txd = i_debug_uart_tx.io.uart_txd
    i_debug_uart_tx.io.uart_tx_en := debug_uart_tx_start
    i_debug_uart_tx.io.uart_tx_data := data_to_write(7, 0)
    i_debug_uart_tx.io.baud_divider := debug_baud_divider

    val time_count = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(0.U(7.W)) }
    withClockAndReset(soc_clk, false.B) {
      when(time_pulse) {
        time_count := 0.U
      } .otherwise {
        time_count := time_count + 1.U
      }
    }
    time_pulse := (time_count === Cat(time_limit, 3.U(2.W)))

    val debug_register_data = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(soc_ui_in(1)) }
    withClockAndReset(soc_clk, false.B) {
      when(write_n =/= 3.U(2.W) && connect_peripheral === socPeriU(PERI_DEBUG)) {
        debug_register_data := data_to_write(0)
      }
    }

    val debug_rd_r = withClockAndReset(soc_clk, false.B) { RegNext(i_tinyqv.io.debug_rd) }

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

    val debug_signal = debug_signals(soc_ui_in(6, 3))

    // Build uo_out value
    val uo_out_val = Cat(
      Mux(gpio_out_sel(1), peri_out(7), debug_signal),
      Mux(gpio_out_sel(0), peri_out(6), debug_uart_txd),
      Mux(debug_register_data, debug_rd_r(3), peri_out(5)),
      Mux(debug_register_data, debug_rd_r(2), peri_out(4)),
      Mux(debug_register_data, debug_rd_r(1), peri_out(3)),
      Mux(debug_register_data, debug_rd_r(0), peri_out(2)),
      peri_out(1),
      peri_out(0)
    )

    // Return read_complete for the unused signal XOR in TT top-level
    // (pico-ice doesn't need it)
    uo_out_val
  }
}


class tt_um_gonsolo_borg(val CLOCK_MHZ: Int = 4) extends RawModule with SoCLogic {
  val ui_in = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val uio_in = IO(Input(UInt(8.W)))
  val uio_out = IO(Output(UInt(8.W)))
  val uio_oe = IO(Output(UInt(8.W)))
  val ena = IO(Input(Bool()))
  val clk = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))

  // Implement SoCLogic abstract members
  def soc_clk = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in
  def soc_qspi_data_in = Cat(uio_in(5, 4), uio_in(2, 1))

  // Wire up the SoC
  val uo_out_val = wireSoC()

  // TT-specific QSPI I/O mapping
  uio_out := Cat(qspi_ram_b_select, qspi_ram_a_select, qspi_data_out(3, 2), qspi_clk_out, qspi_data_out(1, 0), qspi_flash_select)
  uio_oe := Mux(rst_n, Cat(3.U(2.W), qspi_data_oe(3, 2), 1.U(1.W), qspi_data_oe(1, 0), 1.U(1.W)), 0.U(8.W))

  // Avoid warnings on unused inputs
  val read_complete = i_tinyqv.io.data_read_complete
  val unused = ena ^ uio_in(7) ^ uio_in(6) ^ uio_in(3) ^ uio_in(0) ^ read_complete

  uo_out := Cat(uo_out_val(7, 1), uo_out_val(0) ^ unused ^ unused)
}
