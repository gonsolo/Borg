// SPDX-FileCopyrightText: © 2024 Michael Bell
// Changes Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import borg.BorgConfig

/** pico-ice FPGA top-level module.
  *
  * Functionally equivalent to the TT ASIC top-level (tt_um_gonsolo_borg),
  * but with direct FPGA I/O and SB_IO primitives for tri-state QSPI.
  */
class tinyQV_top(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  override def BORG_CFG: BorgConfig = BorgConfig.PicoIce
  val clk      = IO(Input(Clock()))
  val rst_n    = IO(Input(Bool()))

  val flash_cs = IO(Analog(1.W))
  val sd       = IO(Vec(4, Analog(1.W)))
  val sck      = IO(Analog(1.W))
  val ram_a_cs = IO(Analog(1.W))
  val ram_b_cs = IO(Analog(1.W))

  val ui_in    = IO(Input(UInt(8.W)))
  val uo_out   = IO(Output(UInt(8.W)))

  // Implement SoCLogic abstract members
  def soc_clk = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // SB_IO instances for QSPI data pins (sd[3:0]) — bidirectional
  val qspi_data_io = Seq.tabulate(4) { i =>
    val sbio = Module(new SB_IO(pinType = 0x29, pullup = 0))  // 6'b101001
    sbio.PACKAGE_PIN  <> sd(i)
    sbio.OUTPUT_CLK   := clk
    sbio.INPUT_CLK    := clk
    sbio
  }

  // Read qspi_data_in from SB_IO D_IN_0 outputs
  def soc_qspi_data_in = Cat(
    qspi_data_io(3).D_IN_0,
    qspi_data_io(2).D_IN_0,
    qspi_data_io(1).D_IN_0,
    qspi_data_io(0).D_IN_0
  )

  // SB_IO instances for QSPI control pins — output only
  val controlPins = Seq(flash_cs, sck, ram_a_cs, ram_b_cs)
  val qspi_ctrl_io = controlPins.map { pin =>
    val sbio = Module(new SB_IO(pinType = 0x29, pullup = 0))  // 6'b101001
    sbio.PACKAGE_PIN  <> pin
    sbio.OUTPUT_CLK   := clk
    sbio.INPUT_CLK    := clk
    sbio.OUTPUT_ENABLE := rst_n
    sbio
  }

  // Wire up the SoC
  val uo_out_val = wireSoC()

  // Connect QSPI data SB_IO outputs from SoC
  for (i <- 0 until 4) {
    qspi_data_io(i).OUTPUT_ENABLE := qspi_data_oe(i)
    qspi_data_io(i).D_OUT_0      := qspi_data_out(i)
  }

  // Connect QSPI control SB_IO outputs from SoC
  // Order: flash_cs=0, sck=1, ram_a_cs=2, ram_b_cs=3
  qspi_ctrl_io(0).D_OUT_0 := qspi_flash_select
  qspi_ctrl_io(1).D_OUT_0 := qspi_clk_out
  qspi_ctrl_io(2).D_OUT_0 := qspi_ram_a_select
  qspi_ctrl_io(3).D_OUT_0 := qspi_ram_b_select

  uo_out := uo_out_val
}

/** pico-ice board pin mapping and PCF generation.
  *
  * Pin numbers are physical properties of the pico-ice PCB.
  * Signal names are derived from tinyQV_top's Chisel IO to stay in sync.
  */
object PicoIcePins {
  // (signal_name, pin_number) — order matches the PCB schematic sections
  val pins: Seq[(String, Int)] = Seq(
    // Clock and reset
    ("clk",      35), ("rst_n",    39),
    // QSPI — Chisel Vec generates sd_0..sd_3
    ("flash_cs", 25),
    ("sd_0",     19), ("sd_1",     27),
    ("sck",      21),
    ("sd_2",     23), ("sd_3",     18),
    ("ram_a_cs", 26), ("ram_b_cs", 20),
    // General purpose I/O — UInt(8.W) generates bus notation [N]
    ("ui_in[0]", 43), ("ui_in[1]", 38), ("ui_in[2]", 34), ("ui_in[3]", 31),
    ("ui_in[4]", 42), ("ui_in[5]", 36), ("ui_in[6]", 32), ("ui_in[7]", 28),
    ("uo_out[0]", 4), ("uo_out[1]", 2), ("uo_out[2]",47), ("uo_out[3]",45),
    ("uo_out[4]", 3), ("uo_out[5]",48), ("uo_out[6]",46), ("uo_out[7]",44),
  )

  def emitPCF(path: String): Unit = {
    val writer = new java.io.PrintWriter(path)
    for ((name, pin) <- pins)
      writer.println(f"set_io --warn-no-port $name%-12s $pin")
    writer.println()
    // Clock frequency constraint is in fpga/Makefile (--freq flag)
    writer.close()
    println(s"Generated PCF: $path")
  }
}
