// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import chisel3._
import chisel3.util._
import soc.SoCLogic
import borg.BorgConfig

/** Tiny Tapeout ASIC top-level module.
  *
  * Implements the standard TT pad interface (ui_in / uo_out / uio_*)
  * and maps QSPI signals to/from the uio bidirectional pad mux.
  *
  * All shared SoC logic (CPU, MemoryController, Peripherals) is provided
  * by the [[soc.SoCLogic]] trait.
  */
class tt_um_gonsolo_borg(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val uio_in  = IO(Input(UInt(8.W)))
  val uio_out = IO(Output(UInt(8.W)))
  val uio_oe  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  // Implement SoCLogic abstract members
  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in        = ui_in
  def soc_qspi_data_in = Cat(uio_in(5, 4), uio_in(2, 1))

  // Wire up the SoC
  val uo_out_val = wireSoC()

  // TT-specific QSPI I/O mapping — signals sourced from MemoryController via SoCLogic
  uio_out := Cat(
    qspi_ram_b_select,
    qspi_ram_a_select,
    qspi_data_out(3, 2),
    qspi_clk_out,
    qspi_data_out(1, 0),
    qspi_flash_select
  )
  uio_oe := Mux(
    rst_n,
    Cat(3.U(2.W), qspi_data_oe(3, 2), 1.U(1.W), qspi_data_oe(1, 0), 1.U(1.W)),
    0.U(8.W)
  )

  // Suppress warnings on unused inputs
  val read_complete = cpu.io.data_read_complete
  val unused = ena ^ uio_in(7) ^ uio_in(6) ^ uio_in(3) ^ uio_in(0) ^ read_complete

  uo_out := Cat(uo_out_val(7, 1), uo_out_val(0) ^ unused ^ unused)
}


