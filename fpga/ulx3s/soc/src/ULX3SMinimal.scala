// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Minimal ULX3S top — Hutt + MemoryController + DEBUG UART + HDMI scanout.
// No Borg GPU.  Synthesis takes a fraction of the full borg.bit build and
// is enough to exercise:
//   * FlashBootLoader → SDRAM boot path
//   * Hutt instruction fetch from SDRAM
//   * uart_hello and similar firmware
//   * HdmiScanoutFp16 reading FP16 tiled framebuffer from SDRAM

package soc

import chisel3._
import chisel3.util._
import chisel3.{ExtModule, StringParam}
import chisel3.experimental.{Analog, attach}
import memory.{Ecp5PllParams, Ecp5PllWrapper, FlashBootLoader, SdramBackend, Usrmclk}
import _root_.circt.stage.ChiselStage

/** ULX3S top — minimal SoC variant.  Same pinout as `ulx3s_top`.
  * Hutt + UART + HDMI scanout; no Borg GPU.
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

  val gpdi_dp = IO(Output(UInt(4.W)))

  val led = IO(Output(UInt(8.W)))
  val btn = IO(Input(UInt(6.W)))

  // ── PLL: 25 → 25 MHz SoC + 25 MHz/90° SDRAM + 125 MHz HDMI ──────────────
  val SOC_MHZ  = 25
  val HDMI_MHZ = 125
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = SOC_MHZ.toLong  * 1_000_000L,
    out1Hz = SOC_MHZ.toLong  * 1_000_000L, out1Deg = 90,
    out2Hz = HDMI_MHZ.toLong * 1_000_000L
  )))
  pll.io.clk_i := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)   // 25 MHz — CPU, SDRAM, scanout
  val sdramClock = pll.io.clk_o(1)   // 25 MHz + 90° — SDRAM clock pin
  val hdmiClock  = pll.io.clk_o(2)   // 125 MHz — TMDS serializer only
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

  // ── HDMI scanout: instantiate before wireSoC so wireGpuMem can connect ──
  val scanout = withClockAndReset(sysClock, pllRst) {
    Module(new HdmiScanoutFp16(fbBase = 0x100000, fbWidth = 32, fbHeight = 32))
  }

  override def wireGpuMem(): Unit = {
    mem.io.gpuMem.req   := scanout.io.gpuReq
    mem.io.gpuMem.addr  := scanout.io.gpuAddr
    mem.io.gpuMem.wr    := false.B
    mem.io.gpuMem.wdata := 0.U
    scanout.io.gpuData  := mem.io.gpuMem.data
    scanout.io.gpuReady := mem.io.gpuMem.ready
  }

  val uo_out_val = wireSoC()   // calls wireGpuMem() above

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

  // ── VGA timing (25 MHz pixel clock = sysClock) ────────────────────────────
  val tick25 = true.B
  val hCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }
  val vCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }
  val hTotal = 800.U;  val vTotal = 525.U
  val hActive = 640.U; val vActive = 480.U
  val hFront = 16.U;   val hSync = 96.U
  val vFront = 10.U;   val vSync = 2.U
  withClockAndReset(sysClock, pllRst) {
    when(hCount === hTotal - 1.U) {
      hCount := 0.U
      when(vCount === vTotal - 1.U) { vCount := 0.U }
      .otherwise { vCount := vCount + 1.U }
    } .otherwise { hCount := hCount + 1.U }
  }
  val de    = (hCount < hActive) && (vCount < vActive)
  val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync))
  val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync))

  scanout.io.hCount := hCount
  scanout.io.vCount := vCount
  scanout.io.de     := de
  scanout.io.tick25 := tick25
  // Gate scanout on btn(0) — pressed = enable HDMI fetch.  Hutt's instr
  // fetch is the lowest-priority requester in MemoryController, and
  // scanout's gpuReq stays asserted continuously across pixel reads.
  // Leaving it always-on starves the CPU and silences the UART.
  // BTN[0] is the active-high "FIRE1" button on the ULX3S.
  scanout.io.enable := btn(0)

  // ── CDC: latch RGB8 + sync from 25 MHz → 125 MHz ─────────────────────────
  val hdmiRst   = !pllLocked
  val hdmiRed   = withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.red) }
  val hdmiGreen = withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.green) }
  val hdmiBlue  = withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.blue) }
  val hdmiHsync = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hsync) }
  val hdmiVsync = withClockAndReset(hdmiClock, hdmiRst) { RegNext(vsync) }
  val hdmiDe    = withClockAndReset(hdmiClock, hdmiRst) { RegNext(de) }

  val hdmiCount  = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(3.W)) }
  val hdmiTick25 = (hdmiCount === 4.U)
  withClockAndReset(hdmiClock, hdmiRst) {
    when(hdmiTick25) { hdmiCount := 0.U } .otherwise { hdmiCount := hdmiCount + 1.U }
  }

  // ── TMDS Encoders + Serializers (125 MHz domain) ─────────────────────────
  val encB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encB.io.en := hdmiTick25; encB.io.data := hdmiBlue
  encB.io.c  := Cat(hdmiVsync, hdmiHsync); encB.io.de := hdmiDe
  val encG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encG.io.en := hdmiTick25; encG.io.data := hdmiGreen
  encG.io.c  := 0.U; encG.io.de := hdmiDe
  val encR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encR.io.en := hdmiTick25; encR.io.data := hdmiRed
  encR.io.c  := 0.U; encR.io.de := hdmiDe
  val serB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serB.io.en := hdmiTick25; serB.io.tmds := encB.io.tmds
  val serG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serG.io.en := hdmiTick25; serG.io.tmds := encG.io.tmds
  val serR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serR.io.en := hdmiTick25; serR.io.tmds := encR.io.tmds
  val serClk = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serClk.io.en := hdmiTick25; serClk.io.tmds := "b0000011111".U
  gpdi_dp := Cat(serClk.io.out, serR.io.out, serG.io.out, serB.io.out)

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
