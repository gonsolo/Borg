// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Minimal ULX3S top — Hutt + MemoryController + DEBUG UART only.
// No Borg, no HDMI scanout.  Synthesis takes a small fraction of the
// full borg.bit build and is enough to exercise:
//   * FlashBootLoader → SDRAM boot path
//   * Hutt instruction fetch from SDRAM
//   * uart_hello and similar firmware that writes to PERI_DEBUG_UART

package soc

import chisel3._
import chisel3.util._
import chisel3.{ExtModule, StringParam}
import chisel3.experimental.{Analog, attach}
import memory.{Ecp5PllParams, Ecp5PllWrapper, FlashBootLoader, SdramBackend, Usrmclk}
import _root_.circt.stage.ChiselStage

/** ULX3S top — minimal SoC variant.  Same pinout as `ulx3s_top` so the
  * existing LPF works, but the GPDI / HDMI pins are tied off and there
  * is no Borg GPU.
  */
class ulx3s_minimal_top(val CLOCK_MHZ: Int) extends RawModule with MinimalSoCLogic {
  // ── Board pins (subset of full ULX3S) ─────────────────────────────────────
  val clk_25mhz = IO(Input(Clock()))
  val rst_n     = IO(Input(Bool()))

  val sdram_clk  = IO(Output(Clock()))
  val sdram_cke  = IO(Output(Bool()))
  val sdram_csn  = IO(Output(Bool()))
  val sdram_wen  = IO(Output(Bool()))
  val sdram_rasn = IO(Output(Bool()))
  val sdram_casn = IO(Output(Bool()))
  val sdram_a    = IO(Output(UInt(13.W)))
  val sdram_ba   = IO(Output(UInt(2.W)))
  val sdram_dqm  = IO(Output(UInt(2.W)))
  val sdram_d    = IO(Vec(16, Analog(1.W)))

  val flash_csn  = IO(Output(Bool()))
  val flash_mosi = IO(Output(Bool()))
  val flash_miso = IO(Input(Bool()))

  val ftdi_rxd = IO(Output(Bool()))
  val ftdi_txd = IO(Input(Bool()))

  // Tie off HDMI even though we don't use it — pin is still on the board.
  val gpdi_dp = IO(Output(UInt(4.W)))
  gpdi_dp := 0.U

  val led = IO(Output(UInt(8.W)))
  val btn = IO(Input(UInt(6.W)))

  // ── PLL: 25 → 25 MHz SoC + 25 MHz/90° SDRAM clock ────────────────────────
  // No HDMI PLL output needed — gpdi_dp is dead.
  val SOC_MHZ = 25
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = SOC_MHZ.toLong * 1_000_000L,
    out1Hz = SOC_MHZ.toLong * 1_000_000L, out1Deg = 90,
    out2Hz = SOC_MHZ.toLong * 1_000_000L  // unused but PLL block expects three outputs
  )))
  pll.io.clk_i := clk_25mhz
  val pllLocked = pll.io.locked
  val sysClock   = pll.io.clk_o(0)
  val sdramClock = pll.io.clk_o(1)
  sdram_clk := sdramClock

  val pllRst = !pllLocked

  // ── FlashBootLoader ───────────────────────────────────────────────────────
  val flashBoot = withClockAndReset(sysClock, pllRst) {
    Module(new FlashBootLoader())
  }
  val usrmclk = Module(new Usrmclk)
  usrmclk.USRMCLKI  := flashBoot.io.spi_clk.asClock
  usrmclk.USRMCLKTS := false.B
  flash_csn  := flashBoot.io.flash_csn
  flash_mosi := flashBoot.io.flash_mosi
  flashBoot.io.flash_miso := flash_miso

  // ── MinimalSoCLogic abstract members ─────────────────────────────────────
  def soc_clk = sysClock
  def soc_rst_n = pllLocked && flashBoot.io.boot_done && rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
    RegNext(soc_rst_n)
  }
  def soc_ui_in = Cat(0.U(1.W), btn, ftdi_txd)

  val uo_out_val = wireSoC()

  // ── SdramBackend (real SDRAM, shared between FlashBootLoader and CPU) ────
  val sdramBackend = withClockAndReset(sysClock, pllRst) {
    Module(new SdramBackend(SOC_MHZ))
  }
  val bootDone = flashBoot.io.boot_done

  sdramBackend.io.backend.addrIn     := Mux(bootDone, mem.io.backend.addrIn,     flashBoot.io.backend.addrIn)
  sdramBackend.io.backend.dataIn     := Mux(bootDone, mem.io.backend.dataIn,     flashBoot.io.backend.dataIn)
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead,  false.B)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
  sdramBackend.io.backend.stallTxn   := Mux(bootDone, mem.io.backend.stallTxn,   false.B)
  sdramBackend.io.backend.stopTxn    := Mux(bootDone, mem.io.backend.stopTxn,    flashBoot.io.backend.stopTxn)

  mem.io.backend.dataOut   := Mux(bootDone, sdramBackend.io.backend.dataOut,   0.U)
  mem.io.backend.dataReq   := Mux(bootDone, sdramBackend.io.backend.dataReq,   false.B)
  mem.io.backend.dataReady := Mux(bootDone, sdramBackend.io.backend.dataReady, false.B)
  mem.io.backend.busy      := Mux(bootDone, sdramBackend.io.backend.busy,      false.B)

  flashBoot.io.backend.dataOut   := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.dataReq   := Mux(!bootDone, sdramBackend.io.backend.dataReq, false.B)
  flashBoot.io.backend.dataReady := false.B
  flashBoot.io.backend.busy      := Mux(!bootDone, sdramBackend.io.backend.busy,    false.B)

  // ── SDRAM physical pin wiring ──────────────────────────────────────────────
  val pins = sdramBackend.io.sdramPins
  sdram_cke  := pins.cke
  sdram_csn  := pins.cs_n
  sdram_wen  := pins.we_n
  sdram_rasn := pins.ras_n
  sdram_casn := pins.cas_n
  sdram_a    := pins.addr
  sdram_ba   := pins.ba
  sdram_dqm  := pins.dqm

  val dqIn = Wire(Vec(16, Bool()))
  for (i <- 0 until 16) {
    val bb = Module(new Ecp5BiDirBuf())
    bb.T := !pins.dq_oe
    bb.I := pins.dq_out(i)
    dqIn(i) := bb.O
    attach(sdram_d(i), bb.B)
  }
  pins.dq_in := dqIn.asUInt

  // ── UART out ──────────────────────────────────────────────────────────────
  ftdi_rxd := uo_out_val(6)

  // ── LEDs (same layout as full ULX3S for consistency) ─────────────────────
  led := Cat(pllLocked, bootDone,
             flashBoot.io.debug_state,
             sdramBackend.io.backend.busy,
             uo_out_val(6))
}

/** Emit Verilog + LPF for the minimal ULX3S target. */
object ULX3SMinimalMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val targetDir = "out/ulx3s_minimal/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_minimal_top(clockMhz),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  // Reuse the full pin definitions; unused pins are harmless to constrain.
  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}
