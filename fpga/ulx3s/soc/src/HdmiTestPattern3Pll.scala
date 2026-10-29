// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Colour bars from a 3-output PLL (system, system at 90 degrees, 125 MHz HDMI), video timing by
// a divide-by-5 enable: a 4th PLL output for the pixel clock never locked on a cold flash boot.
// Build: cd fpga/ulx3s/debug && make hdmi-test-3pll; load: make load-hdmi-test-3pll

package soc

import chisel3._
import chisel3.util._
import memory.{Ecp5PllParams, Ecp5PllWrapper}
import _root_.circt.stage.ChiselStage

class HdmiTestPattern3Pll extends RawModule {
  val clk_25mhz = IO(Input(Clock()))
  val rst_n     = IO(Input(Bool()))
  val gpdi_dp   = IO(Output(UInt(4.W))) // 0:B, 1:G, 2:R, 3:Clk
  val led       = IO(Output(UInt(8.W)))
  val sdram_clk = IO(Output(Clock()))  // real load on the 90°-shifted output

  val SOC_MHZ = 10
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = SOC_MHZ.toLong * 1_000_000L,
    out1Hz = SOC_MHZ.toLong * 1_000_000L, out1Deg = 90,
    out2Hz = 125_000_000L
  )))
  pll.io.clk_i   := clk_25mhz
  val sysClock   = pll.io.clk_o(0)
  val sdramClock = pll.io.clk_o(1)
  val hdmiClock  = pll.io.clk_o(2)
  val locked     = pll.io.locked

  sdram_clk := sdramClock

  // A slow LED blink proves the system clock runs, not just that the PLL reports lock.
  val sysAlive = withClockAndReset(sysClock, !locked) {
    val r = RegInit(0.U(24.W))
    r := r + 1.U
    r
  }

  val ledReg = withClockAndReset(hdmiClock, !locked) {
    val bars = Module(new HdmiColourBars)
    val ser = bars.io.tmds.map { w =>
      val s = Module(new TmdsSerializer)
      s.io.en   := bars.io.tick
      s.io.tmds := w
      s.io.out
    }
    gpdi_dp := Cat(ser.reverse)
    bars.io.de
  }

  // LED[7] locked, LED[6] system clock blink, LED[5] HDMI display enable.
  led := Cat(locked, sysAlive(23), ledReg, 0.U(5.W))
}

object HdmiTest3PllMain extends App {
  val targetDir = sys.env.getOrElse("TARGET_DIR", "out/ulx3s/hdmi_test_3pll")
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new HdmiTestPattern3Pll(),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
