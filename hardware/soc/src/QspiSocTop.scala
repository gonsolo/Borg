// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.BorgConfig
import memory.QspiBackend

/** The CPU SoC with QSPI flash/PSRAM behind a [[memory.QspiBackend]]: the harness for the
  * cocotb SoC tests and `make lint`, with the Tiny Tapeout pin interface they drive.
  */
class QspiSocTop(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  // The tapeout's config, so lint and the SoC tests see the ASIC-sized Borg.
  override def BORG_CFG: BorgConfig = BorgConfig.Wafer
  // The bare-metal firmware stays in M-mode and takes no interrupts, so the Linux-only
  // parts go. The CSR read split only closes ECP5 timing at 25 MHz.
  override def pipelinedCsrRead: Boolean = false
  override def hasSupervisorMode: Boolean = false
  override def hasClint: Boolean = false
  override def hasDebugPorts: Boolean = false

  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val uio_in  = IO(Input(UInt(8.W)))
  val uio_out = IO(Output(UInt(8.W)))
  val uio_oe  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in
  // No scanout: reflect fb_select at once so the firmware's sync loop exits.
  override def scanoutCurBuf: Bool = fbSelectReg
  val uo_out_val = wireSoC()

  val qspiBackend = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new QspiBackend())
  }
  mem.io.backend               <> qspiBackend.io.backend
  qspiBackend.io.qspiPins.dataIn := Cat(uio_in(5, 4), uio_in(2, 1))

  uio_out := Cat(
    qspiBackend.io.qspiPins.ramBSelect,
    qspiBackend.io.qspiPins.ramASelect,
    qspiBackend.io.qspiPins.dataOut(3, 2),
    qspiBackend.io.qspiPins.clkOut,
    qspiBackend.io.qspiPins.dataOut(1, 0),
    qspiBackend.io.qspiPins.flashSelect
  )
  uio_oe := Mux(
    rst_n,
    Cat(3.U(2.W), qspiBackend.io.qspiPins.dataOe(3, 2), 1.U(1.W), qspiBackend.io.qspiPins.dataOe(1, 0), 1.U(1.W)),
    0.U(8.W)
  )

  // Keeps the unused inputs from warning.
  val unused = ena ^ uio_in(7) ^ uio_in(6) ^ uio_in(3) ^ uio_in(0)

  uo_out := Cat(uo_out_val(7, 1), uo_out_val(0) ^ unused ^ unused)
}


