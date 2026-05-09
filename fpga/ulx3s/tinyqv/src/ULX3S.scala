// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.BorgConfig
import memory.QspiBackend
import _root_.circt.stage.ChiselStage

/** ULX3S (Lattice ECP5-85K) FPGA top-level — STUB (Step 27).
  *
  * Hardware not yet available. This module compiles and emits Verilog
  * so that the ECP5 synthesis flow can be validated offline.  IO ports
  * are placeholders; pin constraints (.lpf) and PHY wrappers
  * (EHXPLLL clock, BB / TRELLIS_IO bidirectional QSPI) will be added
  * when the board arrives (see roadmap "⏳ ULX3S arrives" section).
  *
  * Differences from tinyQV_top (pico-ice):
  *   - BorgConfig.ULX3S: coordWidth=9, hasDMA=true, hasFlusher=true
  *   - No SB_IO primitives (those are iCE40-specific)
  *   - Bidirectional QSPI will use BB / TRELLIS_IO (ECP5 primitive)
  */
class ulx3s_top(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  override def BORG_CFG: BorgConfig = BorgConfig.ULX3S

  val clk   = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))

  // ULX3S has standard LVDS/GPIO pins — bidirectional QSPI stubs.
  // TODO: replace with TRELLIS_IO / BB primitives when porting to ECP5.
  val flash_cs = IO(Output(Bool()))
  val sck      = IO(Output(Bool()))
  val ram_a_cs = IO(Output(Bool()))
  val ram_b_cs = IO(Output(Bool()))
  val sd_out   = IO(Output(UInt(4.W)))
  val sd_in    = IO(Input(UInt(4.W)))
  val sd_oe    = IO(Output(UInt(4.W)))

  val ui_in  = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))

  // Implement SoCLogic abstract members
  def soc_clk         = clk
  def soc_rst_n       = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in       = ui_in

  // Wire up the SoC
  val uo_out_val = wireSoC()

  // QSPI backend — on real ECP5 qspiPins would go through TRELLIS_IO
  val qspiBackend = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new QspiBackend())
  }
  mem.io.backend                 <> qspiBackend.io.backend
  qspiBackend.io.qspiPins.dataIn := sd_in

  flash_cs := qspiBackend.io.qspiPins.flashSelect
  sck      := qspiBackend.io.qspiPins.clkOut
  ram_a_cs := qspiBackend.io.qspiPins.ramASelect
  ram_b_cs := qspiBackend.io.qspiPins.ramBSelect
  sd_out   := qspiBackend.io.qspiPins.dataOut
  sd_oe    := qspiBackend.io.qspiPins.dataOe

  uo_out := uo_out_val
}

/** ULX3S pin mapping stub — LPF constraints will be filled in when
  * the board arrives.  Emit a skeletal LPF so CI can validate that
  * the Verilog compiles; nextpnr-ecp5 invocation is left as a TODO.
  */
object ULX3SPins {
  // (signal_name, pin_name) — ECP5 uses named FPGA pins, not integers.
  // These are example assignments from the ULX3S schematic; verify
  // against the actual board before programming.
  val pins: Seq[(String, String)] = Seq(
    ("clk",      "G2"),   // 25 MHz oscillator
    ("rst_n",    "R1"),   // BTN_PWRn
    ("flash_cs", "R2"),
    ("sck",      "U3"),
    ("ram_a_cs", "P1"),
    ("ram_b_cs", "N2"),
    ("sd_out[0]", "W2"), ("sd_out[1]", "V2"),
    ("sd_out[2]", "Y2"), ("sd_out[3]", "W1"),
    ("sd_in[0]",  "W2"), ("sd_in[1]",  "V2"),
    ("sd_in[2]",  "Y2"), ("sd_in[3]",  "W1"),
  )

  def emitLPF(path: String): Unit = {
    val writer = new java.io.PrintWriter(path)
    writer.println("# ULX3S (ECP5-85K) pin constraints — STUB, verify before use")
    for ((name, pin) <- pins)
      writer.println(f"""LOCATE COMP "$name" SITE "$pin";""")
    writer.println()
    writer.close()
    println(s"Generated LPF: $path")
  }
}

/** Emit Verilog + LPF stub for the ULX3S target. */
object ULX3SMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val targetDir = "out/ulx3s/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen      = new ulx3s_top(clockMhz),
    args     = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}
