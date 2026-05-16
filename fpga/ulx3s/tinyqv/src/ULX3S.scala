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

  // ── HDMI (GPDI) ───────────────────────────────────────────────────────────
  val gpdi_dp = IO(Output(UInt(4.W)))

  // ── LEDs and buttons ──────────────────────────────────────────────────────
  val led = IO(Output(UInt(8.W)))
  val btn = IO(Input(UInt(6.W)))

  // ── PLL: 25 MHz → CLOCK_MHZ (0°) + CLOCK_MHZ (90°) for SDRAM clock ──────
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = CLOCK_MHZ.toLong * 1_000_000L,
    out1Hz = CLOCK_MHZ.toLong * 1_000_000L, out1Deg = 90
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
    Module(new SdramBackend(CLOCK_MHZ))
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

  // DEBUG: hardware bypass UART — sends 'H' at 115200 from 125 MHz PLL.
  // Set to true to verify pin wiring without any CPU involvement.
  val DEBUG_UART_BYPASS = false

  if (DEBUG_UART_BYPASS) {
    val bypassUart = withClockAndReset(sysClock, pllRst) {
      val CLKS = (125000000 / 115200)   // 1085
      val baud = RegInit(0.U(11.W))
      val bitIdx = RegInit(0.U(4.W))   // 0=idle, 1=start, 2-9=data, 10=stop
      val gap = RegInit(0.U(24.W))
      val tx = RegInit(true.B)
      val data = "h48".U(8.W)   // 'H'

      when(bitIdx === 0.U) {
        tx := true.B
        gap := gap + 1.U
        when(gap(23)) {   // ~67ms gap
          gap := 0.U
          bitIdx := 1.U
          baud := 0.U
        }
      }.otherwise {
        baud := baud + 1.U
        when(baud === (CLKS - 1).U) {
          baud := 0.U
          when(bitIdx === 1.U) { tx := false.B }            // start bit
          .elsewhen(bitIdx <= 9.U) { tx := data(bitIdx - 2.U) } // data bits
          .otherwise { tx := true.B }                        // stop bit
          when(bitIdx === 10.U) { bitIdx := 0.U }
          .otherwise { bitIdx := bitIdx + 1.U }
        }
      }
      tx
    }
    ftdi_rxd := bypassUart
  } else {
    ftdi_rxd := uo_out_val(6)
  }

  // ── HDMI Scanout ──────────────────────────────────────────────────────────
  val scanout = withClockAndReset(sysClock, pllRst) { Module(new HdmiScanoutTiled) }
  scanout.io.enable := bootDone

  // Mux scanout onto gpuMem after wireSoC() has already connected
  // peripherals.io.gpuMem <> mem.io.gpuMem.
  // Priority: Borg GPU (borgReq) > HDMI scanout.
  // Re-drive mem.io.gpuMem fields to include scanout when GPU is idle.
  locally {
    val borgReq = peripherals.io.gpuMem.req
    mem.io.gpuMem.req   := borgReq || scanout.io.gpuReq
    mem.io.gpuMem.addr  := Mux(borgReq, peripherals.io.gpuMem.addr, scanout.io.gpuAddr)
    mem.io.gpuMem.wr    := peripherals.io.gpuMem.wr
    mem.io.gpuMem.wdata := peripherals.io.gpuMem.wdata
    // GPU side: data/ready only when borgReq
    peripherals.io.gpuMem.data  := mem.io.gpuMem.data
    peripherals.io.gpuMem.ready := mem.io.gpuMem.ready && borgReq
    // Scanout side: data/ready when GPU is idle
    scanout.io.gpuData  := mem.io.gpuMem.data
    scanout.io.gpuReady := mem.io.gpuMem.ready && !borgReq
  }

  // ── VGA timing (25 MHz pixel clock from 125 MHz / 5) ─────────────────────
  val count = withClockAndReset(sysClock, pllRst) { RegInit(0.U(3.W)) }
  val tick25 = (count === 4.U)
  withClockAndReset(sysClock, pllRst) {
    when(tick25) { count := 0.U } .otherwise { count := count + 1.U }
  }
  val hCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }
  val vCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }
  val hTotal = 800.U;  val vTotal = 525.U
  val hActive = 640.U; val vActive = 480.U
  val hFront = 16.U;   val hSync = 96.U
  val vFront = 10.U;   val vSync = 2.U
  withClockAndReset(sysClock, pllRst) {
    when(tick25) {
      when(hCount === hTotal - 1.U) {
        hCount := 0.U
        when(vCount === vTotal - 1.U) { vCount := 0.U }
        .otherwise { vCount := vCount + 1.U }
      } .otherwise { hCount := hCount + 1.U }
    }
  }
  val de    = (hCount < hActive) && (vCount < vActive)
  val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync))
  val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync))
  scanout.io.hCount := hCount; scanout.io.vCount := vCount
  scanout.io.de := de; scanout.io.tick25 := tick25

  // ── TMDS Encoders + Serializers ──────────────────────────────────────────
  val encB = withClockAndReset(sysClock, pllRst) { Module(new TmdsEncoder) }
  encB.io.en := tick25; encB.io.data := scanout.io.blue
  encB.io.c := Cat(vsync, hsync); encB.io.de := de
  val encG = withClockAndReset(sysClock, pllRst) { Module(new TmdsEncoder) }
  encG.io.en := tick25; encG.io.data := scanout.io.green
  encG.io.c := 0.U; encG.io.de := de
  val encR = withClockAndReset(sysClock, pllRst) { Module(new TmdsEncoder) }
  encR.io.en := tick25; encR.io.data := scanout.io.red
  encR.io.c := 0.U; encR.io.de := de
  val serB = withClockAndReset(sysClock, pllRst) { Module(new TmdsSerializer) }
  serB.io.en := tick25; serB.io.tmds := encB.io.tmds
  val serG = withClockAndReset(sysClock, pllRst) { Module(new TmdsSerializer) }
  serG.io.en := tick25; serG.io.tmds := encG.io.tmds
  val serR = withClockAndReset(sysClock, pllRst) { Module(new TmdsSerializer) }
  serR.io.en := tick25; serR.io.tmds := encR.io.tmds
  val serClk = withClockAndReset(sysClock, pllRst) { Module(new TmdsSerializer) }
  serClk.io.en := tick25; serClk.io.tmds := "b0000011111".U
  gpdi_dp := Cat(serClk.io.out, serR.io.out, serG.io.out, serB.io.out)

  // ── LEDs: max debug ────────────────────────────────────────────────────────
  led := Cat(pllLocked, bootDone,
             flashBoot.io.debug_state,
             sdramBackend.io.backend.busy,
             uo_out_val(6))
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
    // GPDI (HDMI)
    PinDef("gpdi_dp[0]", "A16", ioType = "LVCMOS33D"),
    PinDef("gpdi_dp[1]", "A14", ioType = "LVCMOS33D"),
    PinDef("gpdi_dp[2]", "A12", ioType = "LVCMOS33D"),
    PinDef("gpdi_dp[3]", "A17", ioType = "LVCMOS33D"),
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
