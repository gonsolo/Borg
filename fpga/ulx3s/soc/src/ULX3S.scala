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
  *       Hutt starts only after boot_done && pll_locked.
  * UART: ftdi_rxd = FPGA→host TX (debug output at 115200 baud).
  */
class ulx3s_top(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  override def BORG_CFG: BorgConfig = BorgConfig.Simt   // 2×2 quad SIMT fragment shading
  override def xlen: Int = 64
  override def scanoutCurBuf: Bool = scanout.io.curBuf

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

  // ── PLL: 25 MHz osc → SoC + SoC/90° SDRAM + 125 MHz HDMI ──────────
  // SoC clock = the build's CLOCK_MHZ.  SINGLE SOURCE OF TRUTH is ULX3S_MHZ in
  // fpga/ulx3s/Makefile — it drives this PLL clock, the debug-UART baud divider
  // (SoCLogic), AND the firmware's CLOCK_MHZ together. The video pixel clock is
  // NOT this clock -- see the VGA timing section below -- it is generated at a
  // real 25 MHz cadence inside the 125 MHz HDMI domain instead, via a
  // divide-by-5 clock enable, so CLOCK_MHZ is free to be whatever the CPU/GPU
  // logic can meet timing at.
  //
  // CLOCK_MHZ is 10, not the higher value the routed logic can actually reach
  // (~17 MHz measured), for a reason with nothing to do with logic timing: an
  // ECP5 EHXPLLL solve that lands the VCO near 750 MHz (the exact solve this
  // PLL's 3 outputs get for several nearby targets, including 15 MHz) reliably
  // FAILED TO LOCK on a genuine cold boot from SPI flash on this board, though
  // the identical bitstream booted fine loaded via JTAG -- loading bypasses
  // whatever real-hardware margin a true cold start needs. A small, fast
  // diagnostic bitstream (HdmiTestPattern3Pll.scala) swept target frequencies
  // and found this exactly and repeatably: every target solving to VCO 750 MHz
  // (15, 17, 18 MHz) failed cold boot on real hardware; every target solving
  // to 500 or 625 MHz (10, 20, 25 MHz) succeeded, confirmed multiple times.
  // 10 MHz is the fastest INTEGER MHz target that lands EXACTLY (no rounding,
  // so no firmware UART-baud mismatch) on one of the confirmed-safe VCOs
  // (500 MHz here). Faster options exist (e.g. a ~15.6 MHz solve also lands on
  // the other safe VCO, 625 MHz) but only approximately, and would need
  // CLOCK_MHZ's integer-MHz assumption fixed throughout the Makefiles and
  // firmware first.
  val SOC_MHZ = CLOCK_MHZ
  val HDMI_MHZ = 125
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = SOC_MHZ.toLong * 1_000_000L,
    out1Hz = SOC_MHZ.toLong * 1_000_000L, out1Deg = 90,
    out2Hz = HDMI_MHZ.toLong * 1_000_000L
  )))
  pll.io.clk_i   := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)   // CPU, SDRAM, Borg, scanout fill FSM
  val sdramClock = pll.io.clk_o(1)   // SoC + 90° — SDRAM clock pin
  val hdmiClock  = pll.io.clk_o(2)   // 125 MHz — TMDS serializer AND (below,
                                      // divided by 5) the video timing/display

  // Route 90°-shifted clock directly to the SDRAM clock pin
  sdram_clk := sdramClock

  // FlashBootLoader and SdramBackend start as soon as the PLL locks.
  // They must NOT be gated on boot_done — boot_done depends on SdramBackend
  // completing a write, which requires SdramBackend to be out of reset.
  val pllRst = !pllLocked

  // ── Warm-reset controller (serial firmware reload) ─────────────────────────
  // Firmware streams a new image into SDRAM scratch, then writes WARM_RESET_MAGIC
  // to PERI_WARM_RESET → wireSoC() raises `warmReset`.  We latch a persistent
  // `warmBootReg` (cleared only by a cold/PLL reset) and pulse `warmRst` for a
  // few cycles.  `warmRst` resets ONLY the bootloader + (via boot_done) the
  // CPU/icache/MemoryController — NOT the PLL, SDRAM backend, scanout, or VGA
  // timing.  So the bootloader re-runs in warm-copy mode (scratch→0) and the CPU
  // reboots with a freshly-flushed icache, while the HDMI sync generator keeps
  // running and the monitor never loses signal.
  // Declared as a Wire because flashBoot's reset needs it, but its value derives
  // from warmReset which wireSoC() produces further below.
  val warmRst     = Wire(Bool())
  val warmBootReg = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

  // ── FlashBootLoader: copies firmware flash→SDRAM before Hutt starts ────────
  // Reset on pllRst (cold) OR warmRst (warm reload). warmBoot selects scratch→0.
  val flashBoot = withClockAndReset(sysClock, pllRst || warmRst) {
    Module(new FlashBootLoader())
  }
  flashBoot.io.warmBoot := warmBootReg

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

  // ui_in[7]=ftdi_txd (UART RX from host); ui_in[6:1]=btn[5:0]; ui_in[0]=0
  // ftdi_txd at bit 7 feeds PeriUart's default uart_rxd (ui_in(7)), enabling
  // UART receive.  Debug UART TX still routes to ftdi_rxd via gpio_out_sel(0)=0.
  def soc_ui_in = Cat(ftdi_txd, btn, 0.U(1.W))

  // ── HDMI Scanout — declared here so wireGpuMem() can reference it ─────────
  // The framebuffer bases are NOT hardcoded here: firmware programs them via the
  // PERI_SCANOUT_FB0/FB1 registers (scanoutFbBase0/1), from the SAME borg_layout.h
  // constants that drive the GPU flush base — so the scanout and the GPU cannot
  // drift apart (a 0x80 drift here previously caused the blinking green corner
  // pixel).  Wired below once the SoC registers exist.
  val scanout = withClockAndReset(sysClock, pllRst) {
    Module(new HdmiScanoutFp16(fbWidth = 128, fbHeight = 128, separatePixelClock = true))
  }

  // ── GPU memory arbiter: Borg GPU writes/reads have priority over scanout ──
  // scanoutOwns is registered so that once the scanout's request is accepted
  // by the MemoryController, the ready pulse is always routed back to the
  // scanout — even if gpuActive goes high while the transaction is in-flight.
  override def wireGpuMem(): Unit = {
    val gpuActive  = peripherals.io.gpuMem.req || peripherals.io.gpuMem.wr
    val scanoutOwns = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

    when(scanoutOwns) {
      when(mem.io.gpuMem.ready) { scanoutOwns := false.B }
    }.otherwise {
      when(!gpuActive && scanout.io.gpuReq) { scanoutOwns := true.B }
    }

    val serveGpu = !scanoutOwns

    mem.io.gpuMem.req   := Mux(serveGpu, peripherals.io.gpuMem.req,   scanout.io.gpuReq)
    mem.io.gpuMem.addr  := Mux(serveGpu, peripherals.io.gpuMem.addr,  scanout.io.gpuAddr)
    mem.io.gpuMem.wr    := Mux(serveGpu, peripherals.io.gpuMem.wr,    false.B)
    mem.io.gpuMem.wdata := peripherals.io.gpuMem.wdata
    // Burst length: only the GPU writes (and bursts); the scanout only reads.
    mem.io.gpuMem.wlen  := Mux(serveGpu, peripherals.io.gpuMem.wlen, 1.U)

    peripherals.io.gpuMem.data    := mem.io.gpuMem.data
    peripherals.io.gpuMem.ready   := mem.io.gpuMem.ready && !scanoutOwns
    // Forward the burst per-word pull to the GPU only while it owns the bus.
    peripherals.io.gpuMem.waccept := mem.io.gpuMem.waccept && serveGpu

    scanout.io.gpuData  := mem.io.gpuMem.data
    scanout.io.gpuReady := mem.io.gpuMem.ready && scanoutOwns
  }

  // ── Wire the SoC ──────────────────────────────────────────────────────────
  val uo_out_val = wireSoC()

  // ── Warm-reset controller logic (uses `warmReset` produced by wireSoC) ─────
  // On a warm-reset request: latch warmBootReg (so the re-run bootloader picks
  // the scratch→0 copy) and hold warmRst for a handful of cycles to fully reset
  // the bootloader + CPU.  warmBootReg persists until the next cold/PLL reset.
  val warmRstCtr = withClockAndReset(sysClock, pllRst) { RegInit(0.U(5.W)) }
  when(warmReset) {
    warmBootReg := true.B
    warmRstCtr  := 16.U
  } .elsewhen(warmRstCtr =/= 0.U) {
    warmRstCtr := warmRstCtr - 1.U
  }
  warmRst := warmRstCtr =/= 0.U

  // ── SdramBackend: bridges MemoryController ↔ SdramController ─────────────
  val sdramBackend = withClockAndReset(sysClock, pllRst) {
    Module(new SdramBackend(SOC_MHZ))
  }

  // Mux backend: FlashBootLoader during boot, MemoryController after boot_done
  val bootDone = flashBoot.io.boot_done

  // → SdramBackend inputs (mux: bootloader before boot_done, MemoryController after)
  sdramBackend.io.backend.addrIn     := Mux(bootDone, mem.io.backend.addrIn,     flashBoot.io.backend.addrIn)
  sdramBackend.io.backend.dataIn     := Mux(bootDone, mem.io.backend.dataIn,     flashBoot.io.backend.dataIn)
  sdramBackend.io.backend.byteEnIn   := Mux(bootDone, mem.io.backend.byteEnIn,   flashBoot.io.backend.byteEnIn)
  // During boot the bootloader owns the backend.  The cold path never reads, but
  // the warm-reload path DOES (scratch→0 copy), so route flashBoot's startRead.
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead,  flashBoot.io.backend.startRead)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
  sdramBackend.io.backend.lenIn      := Mux(bootDone, mem.io.backend.lenIn,      flashBoot.io.backend.lenIn)

  // → MemoryController (only active after boot_done)
  mem.io.backend.dataOut := Mux(bootDone, sdramBackend.io.backend.dataOut, 0.U)
  mem.io.backend.accept  := Mux(bootDone, sdramBackend.io.backend.accept,  false.B)
  mem.io.backend.done    := Mux(bootDone, sdramBackend.io.backend.done,    false.B)
  mem.io.backend.busy    := Mux(bootDone, sdramBackend.io.backend.busy,    false.B)

  // → FlashBootLoader (single-word; never bursts, so accept is unused there)
  flashBoot.io.backend.dataOut := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.accept  := Mux(!bootDone, sdramBackend.io.backend.accept, false.B)
  flashBoot.io.backend.done    := Mux(!bootDone, sdramBackend.io.backend.done, false.B)
  flashBoot.io.backend.busy    := Mux(!bootDone, sdramBackend.io.backend.busy, false.B)

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



  // ── HDMI Scanout: enable + front-buffer select ────────────────────────────
  scanout.io.enable   := true.B
  scanout.io.frontBuf := fbSelectReg
  // Firmware-programmed framebuffer bases (single source of truth, no drift).
  scanout.io.fbBase   := scanoutFbBase0
  scanout.io.fbBase1  := scanoutFbBase1

  // ── VGA timing, at a real 25 MHz CADENCE inside the 125 MHz HDMI domain ──
  // A divide-by-5 clock ENABLE (hdmiTick25), not a genuine dedicated pixel-
  // clock PLL output -- see the PLL comment above for why. Everything below
  // that used to run on a 25 MHz pixel clock (VGA timing, the scanout's
  // display side, the TMDS encoders) now runs on hdmiClock and only ADVANCES
  // state on hdmiTick25 -- behaviorally identical to a real 25 MHz clock,
  // physically just a clock enable, and the frame buffer's display-side read
  // port is genuinely on hdmiClock (a real dual-clock BRAM crossing against
  // the fill FSM's sysClock write side -- ECP5 block RAM supports independent
  // port clocks). hdmiClock itself is a plain, un-divided, historically
  // reliable PLL output, so this fix adds no new PLL output and no new
  // cold-boot risk.
  val hdmiRst = !pllLocked
  val hdmiCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(3.W)) }
  val hdmiTick25 = (hdmiCount === 4.U)
  withClockAndReset(hdmiClock, hdmiRst) {
    when(hdmiTick25) { hdmiCount := 0.U } .otherwise { hdmiCount := hdmiCount + 1.U }
  }

  val hCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(10.W)) }
  val vCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(10.W)) }
  val hTotal = 800.U;  val vTotal = 525.U
  val hActive = 640.U; val vActive = 480.U
  val hFront = 16.U;   val hSync = 96.U
  val vFront = 10.U;   val vSync = 2.U
  withClockAndReset(hdmiClock, hdmiRst) {
    when(hdmiTick25) {
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
  scanout.io.de := de; scanout.io.tick25 := hdmiTick25
  scanout.io.pixClk.get := hdmiClock; scanout.io.pixRst.get := hdmiRst

  // scanout.io.red/green/blue and hsync/vsync/de are already natively in the
  // hdmiClock domain (they only change on hdmiTick25) -- no CDC stage needed;
  // feed the TMDS encoders directly.
  // ── TMDS Encoders + Serializers (125 MHz domain) ─────────────────────────
  val encB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encB.io.en := hdmiTick25; encB.io.data := scanout.io.blue
  encB.io.c := Cat(vsync, hsync); encB.io.de := de
  val encG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encG.io.en := hdmiTick25; encG.io.data := scanout.io.green
  encG.io.c := 0.U; encG.io.de := de
  val encR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encR.io.en := hdmiTick25; encR.io.data := scanout.io.red
  encR.io.c := 0.U; encR.io.de := de
  // TmdsEncoder has 1 pipeline stage (q_m_reg); delay serializer load by 1 cycle.
  val hdmiTick25D1 = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hdmiTick25, false.B) }
  val serB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serB.io.en := hdmiTick25D1; serB.io.tmds := encB.io.tmds
  val serG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serG.io.en := hdmiTick25D1; serG.io.tmds := encG.io.tmds
  val serR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serR.io.en := hdmiTick25D1; serR.io.tmds := encR.io.tmds
  val serClk = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serClk.io.en := hdmiTick25D1; serClk.io.tmds := "b0000011111".U
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
