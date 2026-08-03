// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import chisel3._
import chisel3.util._
import soc.SoCLogic
import borg.BorgConfig
import memory.QspiBackend

/** Tiny Tapeout ASIC top-level module.
  *
  * Implements the standard TT pad interface (ui_in / uo_out / uio_*)
  * and maps QSPI signals to/from the uio bidirectional pad mux.
  *
  * All shared SoC logic (CPU, MemoryController, Peripherals) is provided
  * by the [[soc.SoCLogic]] trait.
  */
class tt_um_gonsolo_borg(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  // Fit the IHP 8×4 tile: reduce BorgBinner's count SRAM from 1024 to 16 tiles.
  override def BORG_CFG: BorgConfig = BorgConfig.Asic
  // TTIHP26b targets RV32I (Hutt's default -- no override needed). RV64 +
  // Linux was investigated and measured: Linux+Borg needs ~2.3 mm^2 of core
  // (8x8 tiles), 1.9x the 8x4 TT-IHP maximum -- not reachable without either
  // a bigger die (not offered by TT-IHP at any tile size) or gutting Borg to
  // make room, which isn't the right trade for a GPU tapeout. That RV64/area
  // campaign is tracked separately as a future-shuttle goal; September ships
  // RV32 + Borg, no Linux.
  //
  // Hutt's CSR read/write is split into two pipeline stages purely to
  // close ECP5's 25MHz timing (see Hutt.scala's constructor doc). TT's
  // sign-off clock is 250ns/4MHz (src/config.json CLOCK_PERIOD) -- 6x the
  // period the split exists for -- so skip it and save the extra stage's
  // registers + duplicated select logic.
  override def pipelinedCsrRead: Boolean = false
  // S-mode/CSR delegation machinery (sstatus/sie/stvec/sscratch/sepc/scause/
  // stval/sip, medeleg/mideleg) exists only to support Linux -- see Hutt's
  // constructor doc. software/borg's bare-metal firmware never leaves
  // M-mode: no ecall/mret/sret anywhere, mtvec is never set, the only CSR
  // touched at all is the read-only `cycle` counter (borg_driver.c:369).
  // Recovers ~117k um^2 of the regression the Linux merge introduced on
  // this target (main went from 843,556 um^2, TTIHP26a's proven-good
  // synthesis area, to 960,127 -- which is why make gds-ihp started failing
  // detailed placement even at RV32, before any of the RV64 work).
  override def hasSupervisorMode: Boolean = false

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
  // No scanout on TT: immediately reflect fb_select writes so the firmware's
  // PERI_FB_SELECT sync loop exits on the first read.
  override def scanoutCurBuf: Bool = fbSelectReg
  // Wire up the SoC
  val uo_out_val = wireSoC()

  // QSPI backend — bridges MemoryController to TT uio pad mux
  val qspiBackend = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new QspiBackend())
  }
  mem.io.backend               <> qspiBackend.io.backend
  qspiBackend.io.qspiPins.dataIn := Cat(uio_in(5, 4), uio_in(2, 1))

  // TT-specific QSPI I/O mapping
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


