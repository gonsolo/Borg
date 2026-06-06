// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import chisel3._
import soc.SoCLogic

/** Arcilator simulation top-level module.
  *
  * Same SoC as [[BorgSimTop]] but exposes the [[memory.MemBackendIO]] as
  * plain IOs instead of wiring to an internal [[memory.SdramBackendSim]].
  * The C++ harness drives the backend bus directly (flat_read16/flat_write16),
  * keeping the arcilated circuit small (no 32 MB SDRAM state array).
  */
class BorgArcSimTop(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  val ui_in  = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val ena    = IO(Input(Bool()))
  val clk    = IO(Input(Clock()))
  val rst_n  = IO(Input(Bool()))

  // MemBackendIO exposed as ports — driven by the C++ harness.
  // MemoryController (arbiter) → C++ (backend):
  val be_addrIn     = IO(Output(UInt(24.W)))
  val be_startRead  = IO(Output(Bool()))
  val be_startWrite = IO(Output(Bool()))
  val be_dataIn     = IO(Output(UInt(16.W)))
  val be_byteEnIn   = IO(Output(UInt(2.W)))
  val be_lenIn      = IO(Output(UInt(7.W)))   // burst word count (1..64)
  // C++ (backend) → MemoryController (arbiter):
  val be_dataOut = IO(Input(UInt(16.W)))
  val be_done    = IO(Input(Bool()))
  val be_busy    = IO(Input(Bool()))
  val be_accept  = IO(Input(Bool()))          // burst: consumed dataIn, present next word

  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // No scanout: immediately reflect fb_select writes so the firmware's
  // PERI_FB_SELECT sync loop exits on the first read.
  override def scanoutCurBuf: Bool = fbSelectReg

  val uo_out_val = wireSoC()

  be_addrIn     := mem.io.backend.addrIn
  be_startRead  := mem.io.backend.startRead
  be_startWrite := mem.io.backend.startWrite
  be_dataIn     := mem.io.backend.dataIn
  be_byteEnIn   := mem.io.backend.byteEnIn
  be_lenIn      := mem.io.backend.lenIn
  mem.io.backend.dataOut := be_dataOut
  mem.io.backend.done    := be_done
  mem.io.backend.busy    := be_busy
  mem.io.backend.accept  := be_accept

  uo_out := uo_out_val
}
