// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// HdmiTestPattern3Pll -- small, fast-synthesizing diagnostic bitstream.
//
// The full ULX3S SoC needs its system clock (CPU/GPU/SDRAM) decoupled from
// the 25 MHz HDMI pixel clock, to let the system clock drop below 25 MHz
// without also slowing the video timing down. The first attempt added a
// genuine 4th PLL output (a dedicated pixel clock). That change synthesized,
// routed and met timing -- but on a real cold boot from SPI flash the PLL
// never locked at all (LEDs dark), while the exact same bitstream loaded via
// JTAG + a warm refresh booted fine. A separate, unrelated minimal bitstream
// (Hutt+UART only, no HDMI, no multi-output PLL) cold-booted cleanly on the
// same board and flash chip, ruling out the flash chip and the board itself.
// That scopes the failure to the new 4th PLL output.
//
// This bitstream tests the fix -- decoupling the video timing from the
// system clock via a divide-by-5 clock ENABLE inside the existing 125 MHz
// HDMI clock domain, instead of a new PLL output -- staying at the same
// 3-output PLL structure (system clock, system clock at 90° for SDRAM, 125
// MHz for HDMI) the full SoC has used since before today, and which this
// project's own history shows cold-boots and drives real HDMI output
// correctly on this board. It reuses HdmiTestPattern's own TmdsEncoder/
// TmdsSerializer (already proven on real hardware) and its tick25 divider
// pattern, extended to a 3-output PLL with a free-running counter in the
// system-clock domain (an LED bit) to confirm that domain is genuinely
// alive too, not just PLL-locked.
//
// Build: cd fpga/ulx3s/debug && make hdmi-test-3pll
// Load (SRAM only):            make load-hdmi-test-3pll
// Expected: LED[7] (locked) and LED[6] (sysClock alive, toggles slowly) on;
// colour bars on the HDMI monitor.

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

  // System-clock domain: a free-running counter, so its top bit is a slow
  // LED blink proving this domain is genuinely clocked, not just that the
  // PLL reports locked.
  val sysAlive = withClockAndReset(sysClock, !locked) {
    val r = RegInit(0.U(24.W))
    r := r + 1.U
    r
  }

  // HDMI-clock domain: VGA timing at a real 25 MHz CADENCE via a divide-by-5
  // clock ENABLE (tick25) -- the fix under test -- instead of a genuine 4th
  // PLL output.
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

  // LED[7]=locked, LED[6]=sysClock alive (slow blink), LED[5]=hdmiClock's
  // own display-enable (should be a fast, steady flicker), LED[4:0]=0.
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
