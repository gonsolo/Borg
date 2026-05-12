// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import chisel3.{ExtModule, StringParam}
import chisel3.experimental.{Analog, attach}
import borg.BorgConfig
import memory.QspiBackend
import _root_.circt.stage.ChiselStage

/** ECP5 TRELLIS_IO primitive — bidirectional GPIO pad.
  *
  * Used for QSPI sd[3:0] data lines which switch direction per transaction phase.
  *   T = 0 → drive I onto pad B
  *   T = 1 → high-Z, pad value readable on O
  */
class TRELLIS_IO extends ExtModule(Map("DIR" -> StringParam("BIDIR"))) {
  val B = IO(Analog(1.W))    // bidirectional pad
  val T = IO(Input(Bool()))  // tristate: 0=drive, 1=high-Z
  val I = IO(Input(Bool()))  // data to pad
  val O = IO(Output(Bool())) // data from pad
}

/** ULX3S (Lattice ECP5-85K) FPGA top-level.
  *
  * All QSPI memory is provided by the TinyTapeout QSPI PMOD plugged into
  * J2 GP/GN 21-24 (right-side header, pins 21-26 where 25-26 = GND/3.3V):
  *   GP24 → CS0 (Flash): firmware at address 0, TinyQV boots here
  *   GP23 → SD0 (MOSI/data[0])
  *   GP22 → SD1 (MISO/data[1])
  *   GP21 → SCK (QSPI clock — regular GPIO, not USRMCLK)
  *   GN24 → SD2 (data[2])  — PMOD pin 5
  *   GN23 → SD3 (data[3])  — PMOD pin 6
  *   GN22 → CS1 (PSRAM A): framebuffer, stack, heap  — PMOD pin 7
  *   GN21 → CS2 (PSRAM B): additional data  — PMOD pin 8
  *
  * The onboard Winbond flash is used only for FPGA configuration (bitstream).
  * TinyQV never touches it. Firmware is written to the PMOD flash at offset 0
  * via a passthrough bitstream + openFPGALoader (see Makefile flash-firmware).
  *
  * UART: ftdi_rxd (FPGA→host TX) = uo_out_val[6] = debug_uart_txd.
  *       ftdi_txd (host→FPGA RX) = soc_ui_in[0] = UART RX.
  */
class ulx3s_top(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  override def BORG_CFG: BorgConfig = BorgConfig.ULX3S

  // Clock and reset — 25 MHz oscillator; BTN_PWRn (active-low)
  val clk_25mhz = IO(Input(Clock()))
  val rst_n     = IO(Input(Bool()))

  // QSPI PMOD on J2 GP/GN 21-24 — chip selects and clock are simple outputs
  // Row 1 (GP): CS0, SD0, SD1, SCK  — PMOD pins 1-4
  // Row 2 (GN): SD2, SD3, CS1, CS2  — PMOD pins 5-8
  val pmod_cs0  = IO(Output(Bool()))  // GP24 — CS0 Flash   (PMOD pin 1)
  val pmod_cs1  = IO(Output(Bool()))  // GN22 — CS1 PSRAM A (PMOD pin 7)
  val pmod_cs2  = IO(Output(Bool()))  // GN21 — CS2 PSRAM B (PMOD pin 8)
  val pmod_sck  = IO(Output(Bool()))  // GP21 — SCK         (PMOD pin 4)
  // Data lines are bidirectional — driven by TRELLIS_IO primitives
  val pmod_sd0  = IO(Analog(1.W))     // GP23 — SD0         (PMOD pin 2)
  val pmod_sd1  = IO(Analog(1.W))     // GP22 — SD1         (PMOD pin 3)
  val pmod_sd2  = IO(Analog(1.W))     // GN24 — SD2         (PMOD pin 5)
  val pmod_sd3  = IO(Analog(1.W))     // GN23 — SD3         (PMOD pin 6)

  // FT231X USB-serial — /dev/ttyUSB0
  val ftdi_rxd = IO(Output(Bool()))   // FPGA → host (UART TX)
  val ftdi_txd = IO(Input(Bool()))    // host → FPGA (UART RX)

  // LEDs (active high) and user buttons
  val led = IO(Output(UInt(8.W)))
  val btn = IO(Input(UInt(6.W)))

  // ---- SoCLogic abstract members ----

  def soc_clk   = clk_25mhz
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk_25mhz.asBool).asClock, false.B) {
    RegNext(rst_n)
  }

  // ui_in[0] = UART RX; ui_in[6:1] = btn[5:0]; ui_in[7] = 0
  def soc_ui_in = Cat(0.U(1.W), btn, ftdi_txd)

  // ---- Wire up the SoC ----
  val uo_out_val = wireSoC()

  // ---- QSPI backend — bridges MemoryController to TRELLIS_IO pads ----
  val qspiBackend = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new QspiBackend())
  }
  mem.io.backend <> qspiBackend.io.backend

  // Chip selects and clock: simple outputs
  pmod_cs0 := qspiBackend.io.qspiPins.flashSelect
  pmod_cs1 := qspiBackend.io.qspiPins.ramASelect
  pmod_cs2 := qspiBackend.io.qspiPins.ramBSelect
  pmod_sck := qspiBackend.io.qspiPins.clkOut

  // Bidirectional data: TRELLIS_IO for each bit
  val pmod_sd_in   = Wire(Vec(4, Bool()))
  val pmod_sd_pads = Seq(pmod_sd0, pmod_sd1, pmod_sd2, pmod_sd3)
  for (i <- 0 until 4) {
    val tio = Module(new TRELLIS_IO())
    tio.T := !qspiBackend.io.qspiPins.dataOe(i)  // T=1 → high-Z; T=0 → drive
    tio.I := qspiBackend.io.qspiPins.dataOut(i)
    pmod_sd_in(i) := tio.O
    attach(pmod_sd_pads(i), tio.B)
  }
  qspiBackend.io.qspiPins.dataIn := Cat(pmod_sd_in(3), pmod_sd_in(2), pmod_sd_in(1), pmod_sd_in(0))

  // ---- Peripherals ----

  // UART TX
  ftdi_rxd := uo_out_val(6)

  // LEDs: [7]=0, [6]=rst_n health, [5:0]=uo_out_val
  led := Cat(0.U(1.W), rst_n, uo_out_val)
}

/** ULX3S pin constraints — ECP5 sites from ulx3s_v20.lpf. */
object ULX3SPins {
  case class PinDef(name: String, site: String, pull: String = "NONE",
                    ioType: String = "LVCMOS33", drive: Int = 8)

  val pins: Seq[PinDef] = Seq(
    PinDef("clk_25mhz", "G2",  pull = "NONE", drive = 4),
    PinDef("rst_n",     "D6",  pull = "UP",   drive = 4),

    // QSPI PMOD — J2 GP/GN 21-24
    PinDef("pmod_cs0",  "C16", pull = "UP"),
    PinDef("pmod_sck",  "C18", pull = "NONE"),
    PinDef("pmod_sd0",  "B17", pull = "NONE"),
    PinDef("pmod_sd1",  "B15", pull = "NONE"),
    PinDef("pmod_sd2",  "D16", pull = "NONE"),
    PinDef("pmod_sd3",  "C17", pull = "NONE"),
    PinDef("pmod_cs1",  "C15", pull = "UP"),
    PinDef("pmod_cs2",  "D17", pull = "UP"),

    PinDef("ftdi_rxd",  "L4",  pull = "UP",   drive = 4),
    PinDef("ftdi_txd",  "M1",  pull = "UP",   drive = 4),

    PinDef("led[0]",    "B2",  pull = "NONE", drive = 4),
    PinDef("led[1]",    "C2",  pull = "NONE", drive = 4),
    PinDef("led[2]",    "C1",  pull = "NONE", drive = 4),
    PinDef("led[3]",    "D2",  pull = "NONE", drive = 4),
    PinDef("led[4]",    "D1",  pull = "NONE", drive = 4),
    PinDef("led[5]",    "E2",  pull = "NONE", drive = 4),
    PinDef("led[6]",    "E1",  pull = "NONE", drive = 4),
    PinDef("led[7]",    "H3",  pull = "NONE", drive = 4),

    PinDef("btn[0]",    "R1",  pull = "DOWN", drive = 4),
    PinDef("btn[1]",    "T1",  pull = "DOWN", drive = 4),
    PinDef("btn[2]",    "R18", pull = "DOWN", drive = 4),
    PinDef("btn[3]",    "V1",  pull = "DOWN", drive = 4),
    PinDef("btn[4]",    "U1",  pull = "DOWN", drive = 4),
    PinDef("btn[5]",    "H16", pull = "DOWN", drive = 4),
  )

  def emitLPF(path: String): Unit = {
    val writer = new java.io.PrintWriter(path)
    writer.println("# ULX3S (ECP5-85K) pin constraints")
    writer.println("# Sites from ulx3s_v20.lpf — https://github.com/emard/ulx3s")
    writer.println()
    // Required for USRMCLK: hand flash SPI clock control to user logic after boot.
    writer.println("SYSCONFIG MASTER_SPI_PORT=DISABLE;")
    writer.println()
    writer.println("BLOCK RESETPATHS;")
    writer.println("BLOCK ASYNCPATHS;")
    writer.println()
    writer.println(s"""LOCATE COMP "clk_25mhz" SITE "G2";""")
    writer.println(s"""IOBUF  PORT "clk_25mhz" PULLMODE=NONE IO_TYPE=LVCMOS33;""")
    writer.println(s"""FREQUENCY PORT "clk_25mhz" 25 MHZ;""")
    writer.println()
    for (p <- pins if p.name != "clk_25mhz") {
      writer.println(f"""LOCATE COMP "${p.name}" SITE "${p.site}";""")
      writer.println(f"""IOBUF  PORT "${p.name}" PULLMODE=${p.pull} IO_TYPE=${p.ioType} DRIVE=${p.drive};""")
    }
    writer.println()
    writer.close()
    println(s"Generated LPF: $path")
  }
}

/** Emit Verilog + LPF for the ULX3S target. */
object ULX3SMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val targetDir = "out/ulx3s/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}
