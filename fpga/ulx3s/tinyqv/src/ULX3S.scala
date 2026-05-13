// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import chisel3.{ExtModule, StringParam}
import chisel3.experimental.{Analog, attach}
import borg.BorgConfig
import memory.{Ecp5PllParams, Ecp5PllWrapper, FlashBootLoader, SdramBackend, Usrmclk}
import _root_.circt.stage.ChiselStage

/** ECP5 bidirectional buffer primitive (Lattice cell name: BB).
  *
  * Used for the SDRAM DQ bus — each bit can be driven (write phase) or
  * sampled (read phase) depending on T:
  *   T = 0 → drive I onto pad B
  *   T = 1 → high-Z; pad voltage readable on O
  */
class Ecp5BiDirBuf extends ExtModule {
  override def desiredName = "BB"  // must match the Lattice/nextpnr cell name
  val B = IO(Analog(1.W))
  val T = IO(Input(Bool()))
  val I = IO(Input(Bool()))
  val O = IO(Output(Bool()))
}

/** ULX3S (Lattice ECP5-85K) top-level — SDRAM + flash boot.
  *
  * Clock: 25 MHz oscillator → Ecp5Pll → 125 MHz system clock.
  * Memory: onboard SDRAM (IS42S16160G) via SdramBackend.
  * Boot: FlashBootLoader copies firmware from flash 0x400000 → SDRAM 0x0.
  *       TinyQV starts only after boot_done && pll_locked.
  * UART: ftdi_rxd = FPGA→host TX (debug output at 115200 baud).
  */
class ulx3s_top(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  override def BORG_CFG: BorgConfig = BorgConfig.ULX3S

  // ── Board clock and reset ──────────────────────────────────────────────────
  val clk_25mhz = IO(Input(Clock()))
  val rst_n      = IO(Input(Bool()))   // BTN_PWRn, active-low

  // ── SDRAM pins (IS42S16160G-7TL, 16-bit) ──────────────────────────────────
  val sdram_clk  = IO(Output(Clock()))
  val sdram_cke  = IO(Output(Bool()))
  val sdram_csn  = IO(Output(Bool()))
  val sdram_wen  = IO(Output(Bool()))
  val sdram_rasn = IO(Output(Bool()))
  val sdram_casn = IO(Output(Bool()))
  val sdram_a    = IO(Output(UInt(13.W)))
  val sdram_ba   = IO(Output(UInt(2.W)))
  val sdram_dqm  = IO(Output(UInt(2.W)))
  val sdram_d    = IO(Vec(16, Analog(1.W)))   // bidirectional DQ bus

  // ── Onboard flash pins (Winbond W25Q128JV) ────────────────────────────────
  // flash_clk is routed via the USRMCLK primitive — no IO port needed.
  val flash_csn  = IO(Output(Bool()))
  val flash_mosi = IO(Output(Bool()))
  val flash_miso = IO(Input(Bool()))

  // ── UART ──────────────────────────────────────────────────────────────────
  val ftdi_rxd = IO(Output(Bool()))   // FPGA → host TX
  val ftdi_txd = IO(Input(Bool()))    // host → FPGA RX

  // ── LEDs and buttons ──────────────────────────────────────────────────────
  val led = IO(Output(UInt(8.W)))
  val btn = IO(Input(UInt(6.W)))

  // ── PLL: 25 MHz → 125 MHz (0°) + 125 MHz (90°) for SDRAM clock ───────────
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = 125_000_000L,
    out1Hz = 125_000_000L, out1Deg = 90
  )))
  pll.io.clk_i   := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)
  val sdramClock = pll.io.clk_o(1)

  // Route 90°-shifted clock directly to the SDRAM clock pin
  sdram_clk := sdramClock

  // FlashBootLoader and SdramBackend start as soon as the PLL locks.
  // They must NOT be gated on boot_done — boot_done depends on SdramBackend
  // completing a write, which requires SdramBackend to be out of reset.
  val pllRst = !pllLocked

  // ── FlashBootLoader: copies firmware flash→SDRAM before TinyQV starts ─────
  val flashBoot = withClockAndReset(sysClock, pllRst) {
    Module(new FlashBootLoader())
  }

  // Wire USRMCLK: route flashBoot SPI clock to the flash MCLK pin
  val usrmclk = Module(new Usrmclk)
  usrmclk.USRMCLKI  := flashBoot.io.spi_clk.asClock
  usrmclk.USRMCLKTS := false.B   // always enabled (active-low tristate)

  flash_csn  := flashBoot.io.flash_csn
  flash_mosi := flashBoot.io.flash_mosi
  flashBoot.io.flash_miso := flash_miso

  // ── SoCLogic abstract members ──────────────────────────────────────────────
  def soc_clk = sysClock
  def soc_rst_n = pllLocked && flashBoot.io.boot_done && rst_n

  lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
    RegNext(soc_rst_n)
  }

  // ui_in[0]=UART RX; ui_in[6:1]=btn[5:0]; ui_in[7]=0
  def soc_ui_in = Cat(0.U(1.W), btn, ftdi_txd)

  // ── Wire the SoC ──────────────────────────────────────────────────────────
  val uo_out_val = wireSoC()

  // ── SdramBackend: bridges MemoryController ↔ SdramController ─────────────
  val sdramBackend = withClockAndReset(sysClock, pllRst) {
    Module(new SdramBackend())
  }

  // Mux backend: FlashBootLoader during boot, MemoryController after boot_done
  val bootDone = flashBoot.io.boot_done

  // → SdramBackend inputs
  sdramBackend.io.backend.addrIn     := Mux(bootDone, mem.io.backend.addrIn,    flashBoot.io.backend.addrIn)
  sdramBackend.io.backend.dataIn     := Mux(bootDone, mem.io.backend.dataIn,    flashBoot.io.backend.dataIn)
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead, false.B)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
  sdramBackend.io.backend.stallTxn   := Mux(bootDone, mem.io.backend.stallTxn, false.B)
  sdramBackend.io.backend.stopTxn    := Mux(bootDone, mem.io.backend.stopTxn,  false.B)

  // → MemoryController (only active after boot_done)
  mem.io.backend.dataOut   := Mux(bootDone, sdramBackend.io.backend.dataOut,   0.U)
  mem.io.backend.dataReq   := Mux(bootDone, sdramBackend.io.backend.dataReq,   false.B)
  mem.io.backend.dataReady := Mux(bootDone, sdramBackend.io.backend.dataReady, false.B)
  mem.io.backend.busy      := Mux(bootDone, sdramBackend.io.backend.busy,      false.B)

  // → FlashBootLoader
  flashBoot.io.backend.dataOut   := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.dataReq   := Mux(!bootDone, sdramBackend.io.backend.dataReq,   false.B)
  flashBoot.io.backend.dataReady := false.B
  flashBoot.io.backend.busy      := Mux(!bootDone, sdramBackend.io.backend.busy,       false.B)

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

  // Bidirectional DQ: one BB per bit
  val dqIn = Wire(Vec(16, Bool()))
  for (i <- 0 until 16) {
    val bb = Module(new Ecp5BiDirBuf())
    bb.T := !pins.dq_oe
    bb.I := pins.dq_out(i)
    dqIn(i) := bb.O
    attach(sdram_d(i), bb.B)
  }
  pins.dq_in := dqIn.asUInt

  // ── Peripherals ───────────────────────────────────────────────────────────
  ftdi_rxd := uo_out_val(6)

  // LEDs: [7]=pll_locked, [6]=boot_done
  // While booting: [5:2]=FlashBootLoader FSM state, [1:0]=uo_out_val[1:0]
  // After boot:    [5:0]=uo_out_val[5:0]
  led := Cat(pllLocked, bootDone,
             Mux(bootDone, uo_out_val(5, 2), flashBoot.io.debug_state),
             uo_out_val(1, 0))
}

// ── Pin constraints ────────────────────────────────────────────────────────

object ULX3SPins {
  case class PinDef(name: String, site: String, pull: String = "NONE",
                    ioType: String = "LVCMOS33", drive: Int = 4)

  val pins: Seq[PinDef] = Seq(
    PinDef("clk_25mhz", "G2",  pull = "NONE", drive = 4),
    PinDef("rst_n",      "D6",  pull = "UP",   drive = 4),

    // Flash (clock via USRMCLK — no pin needed for flash_clk)
    PinDef("flash_csn",  "R2",  pull = "UP"),
    PinDef("flash_mosi", "W2",  pull = "UP"),
    PinDef("flash_miso", "V2",  pull = "UP"),

    // SDRAM
    PinDef("sdram_clk",    "F19", drive = 8),
    PinDef("sdram_cke",    "F20"),
    PinDef("sdram_csn",    "P20"),
    PinDef("sdram_wen",    "T20"),
    PinDef("sdram_rasn",   "R20"),
    PinDef("sdram_casn",   "T19"),
    PinDef("sdram_a[0]",   "M20"), PinDef("sdram_a[1]",  "M19"),
    PinDef("sdram_a[2]",   "L20"), PinDef("sdram_a[3]",  "L19"),
    PinDef("sdram_a[4]",   "K20"), PinDef("sdram_a[5]",  "K19"),
    PinDef("sdram_a[6]",   "K18"), PinDef("sdram_a[7]",  "J20"),
    PinDef("sdram_a[8]",   "J19"), PinDef("sdram_a[9]",  "H20"),
    PinDef("sdram_a[10]",  "N19"), PinDef("sdram_a[11]", "G20"),
    PinDef("sdram_a[12]",  "G19"),
    PinDef("sdram_ba[0]",  "P19"), PinDef("sdram_ba[1]", "N20"),
    PinDef("sdram_dqm[0]", "U19"), PinDef("sdram_dqm[1]","E20"),
    PinDef("sdram_d_0",   "J16"), PinDef("sdram_d_1",  "L18"),
    PinDef("sdram_d_2",   "M18"), PinDef("sdram_d_3",  "N18"),
    PinDef("sdram_d_4",   "P18"), PinDef("sdram_d_5",  "T18"),
    PinDef("sdram_d_6",   "T17"), PinDef("sdram_d_7",  "U20"),
    PinDef("sdram_d_8",   "E19"), PinDef("sdram_d_9",  "D20"),
    PinDef("sdram_d_10",  "D19"), PinDef("sdram_d_11", "C20"),
    PinDef("sdram_d_12",  "E18"), PinDef("sdram_d_13", "F18"),
    PinDef("sdram_d_14",  "J18"), PinDef("sdram_d_15", "J17"),

    // UART
    PinDef("ftdi_rxd",  "L4",  pull = "UP"),
    PinDef("ftdi_txd",  "M1",  pull = "UP"),

    // LEDs
    PinDef("led[0]", "B2"), PinDef("led[1]", "C2"),
    PinDef("led[2]", "C1"), PinDef("led[3]", "D2"),
    PinDef("led[4]", "D1"), PinDef("led[5]", "E2"),
    PinDef("led[6]", "E1"), PinDef("led[7]", "H3"),

    // Buttons
    PinDef("btn[0]", "R1",  pull = "DOWN"), PinDef("btn[1]", "T1",  pull = "DOWN"),
    PinDef("btn[2]", "R18", pull = "DOWN"), PinDef("btn[3]", "V1",  pull = "DOWN"),
    PinDef("btn[4]", "U1",  pull = "DOWN"), PinDef("btn[5]", "H16", pull = "DOWN"),
  )

  def emitLPF(path: String): Unit = {
    val writer = new java.io.PrintWriter(path)
    writer.println("# ULX3S (ECP5-85K) pin constraints")
    writer.println("# Sites from ulx3s_v20.lpf — https://github.com/emard/ulx3s")
    writer.println()
    // Required for USRMCLK: hand flash SPI clock control to user logic after boot.
    // CONFIG_MODE=SPI_SERIAL: prevents ECP5 from leaving flash in QPI mode after boot.
    // Without this, the flash ignores all standard 1-bit SPI commands from user logic.
    writer.println("SYSCONFIG CONFIG_IOVOLTAGE=3.3 COMPRESS_CONFIG=ON MCCLK_FREQ=2.4 MASTER_SPI_PORT=DISABLE SLAVE_SPI_PORT=DISABLE SLAVE_PARALLEL_PORT=DISABLE CONFIG_MODE=SPI_SERIAL;");
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
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}
