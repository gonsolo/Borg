// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.BorgConfig
import memory.QspiBackend

/** The CPU SoC on a QSPI memory bus: Hutt + MemoryController + Peripherals
  * (Borg included), with QSPI flash/PSRAM behind a [[memory.QspiBackend]].
  *
  * This was the Tiny Tapeout ASIC top (`tt_um_gonsolo_borg`); Tiny Tapeout is
  * retired and the ASIC is now the Borg-only wafer.space bridge
  * (asic/wafer/src/BorgOnlyTop.scala). It remains the harness for the cocotb
  * SoC tests (test/soc: CPU -> QSPI -> MemoryController -> Borg end to end)
  * and for `make lint`. The pin interface (ui_in / uo_out / uio_*) is the old
  * TT one, which those testbenches drive.
  *
  * All shared SoC logic (CPU, MemoryController, Peripherals) is provided
  * by the [[soc.SoCLogic]] trait.
  */
class QspiSocTop(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  // Same sized-down config as the wafer.space tapeout, so `make lint` and the
  // SoC tests exercise the ASIC-sized Borg.
  override def BORG_CFG: BorgConfig = BorgConfig.Wafer
  // RV32I (Hutt's default), M-mode only: the bare-metal firmware never leaves
  // M-mode, takes no interrupts and touches only the read-only `cycle` CSR, so
  // the Linux-only machinery below is left out.
  // Hutt splits CSR read/write into two stages only to close ECP5 timing at
  // 25 MHz (see Hutt.scala); not needed at this SoC's 4 MHz.
  override def pipelinedCsrRead: Boolean = false
  // S-mode/CSR delegation (sstatus/sie/stvec/..., medeleg/mideleg) exists
  // only to support Linux -- see Hutt's constructor doc.
  override def hasSupervisorMode: Boolean = false
  // CLINT (mtime/mtimecmp, timer interrupts): same story as S-mode --
  // Linux/OpenSBI-only, and this firmware never takes an interrupt.
  override def hasClint: Boolean = false
  // Hutt's debug trace registers and HuttRegFile's forensic read taps are
  // observed only by the Verilator/ULX3S Linux harnesses.
  override def hasDebugPorts: Boolean = false

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
  def soc_ui_in = ui_in
  // No scanout here: immediately reflect fb_select writes so the firmware's
  // PERI_FB_SELECT sync loop exits on the first read.
  override def scanoutCurBuf: Bool = fbSelectReg
  // Wire up the SoC
  val uo_out_val = wireSoC()

  // QSPI backend — bridges MemoryController to the uio pad mux
  val qspiBackend = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new QspiBackend())
  }
  mem.io.backend               <> qspiBackend.io.backend
  qspiBackend.io.qspiPins.dataIn := Cat(uio_in(5, 4), uio_in(2, 1))

  // QSPI I/O mapping onto uio
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

  // Suppress warnings on unused inputs.  (The old CPU had a `data_read_complete`
  // output we XOR'd into uo_out as a signal-keeper; Hutt has no equivalent,
  // so we use a static zero stand-in.)
  val unused = ena ^ uio_in(7) ^ uio_in(6) ^ uio_in(3) ^ uio_in(0)

  uo_out := Cat(uo_out_val(7, 1), uo_out_val(0) ^ unused ^ unused)
}


