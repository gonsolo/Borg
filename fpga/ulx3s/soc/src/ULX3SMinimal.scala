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
  *
  * @param xlen 32 = RV32I (default, e.g. uart_hello bring-up), 64 = RV64I
  *             with CLINT — used to test OpenSBI/Linux boot without Borg's
  *             SDRAM contention and dense synthesis in the loop, isolating
  *             whether a full-SoC timing-closure issue is the cause of a
  *             hardware boot that's silent despite working in simulation.
  */
class ulx3s_minimal_top(val CLOCK_MHZ: Int, override val xlen: Int = 32, scanoutOn: Boolean = true, fbSize: Int = 32,
                         override val borgCfg: Option[borg.BorgConfig] = None) extends RawModule with MinimalSoCLogic {
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
  val SOC_MHZ  = CLOCK_MHZ
  val HDMI_MHZ = 125
  // The SDRAM clock must be asked for what the primary really gets (e.g. 18.75, not 18 MHz).
  val pllBase = Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = SOC_MHZ.toLong  * 1_000_000L,
    out2Hz = HDMI_MHZ.toLong * 1_000_000L
  )
  val pllParams = pllBase.copy(out1Hz = Ecp5PllParams.solve(pllBase).fOut, out1Deg = 90)
  override def CLOCK_HZ: Long = Ecp5PllParams.solve(pllParams).fOut
  val pll = Module(new Ecp5PllWrapper(pllParams))
  pll.io.clk_i := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)   // 25 MHz — CPU, SDRAM, scanout fill FSM
  val sdramClock = pll.io.clk_o(1)   // 25 MHz + 90° — SDRAM clock pin
  val hdmiClock  = pll.io.clk_o(2)   // 125 MHz — TMDS serializer AND (below,
                                      // divided by 5) the video timing/display
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
  flashBoot.io.warmBoot   := false.B   // minimal SoC: cold flash boot only

  // ── MinimalSoCLogic abstract members ─────────────────────────────────────
  def soc_clk = sysClock
  def soc_rst_n = pllLocked && flashBoot.io.boot_done && rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
    RegNext(soc_rst_n)
  }
  def soc_ui_in = Cat(0.U(1.W), btn, ftdi_txd)

  // ── HDMI scanout: instantiate before wireSoC so wireGpuMem can connect ──
  val scanout = withClockAndReset(sysClock, pllRst) {
    Module(new HdmiScanoutFp16(fbWidth = fbSize, fbHeight = fbSize, separatePixelClock = true,
                               rgb565Store = borgCfg.isDefined, doubleBuffer = borgCfg.isEmpty))
  }
  scanout.io.frontBuf := false.B   // minimal SoC has no Borg; always read fbBase
  // Minimal SoC has no firmware programming the base; pin it to the test region.
  // With a Borg the scanout shows its frame: DRAM_OUT_BASE_SPI of software/borg/borg_layout.h.
  val fbAddr = if (borgCfg.isDefined) 0x85680 else 0x100000
  scanout.io.fbBase   := fbAddr.U
  scanout.io.fbBase1  := fbAddr.U

  // The scanout runs once the Borg has written to GPU memory (first flush): it starves the CPU's
  // instruction fetch, so it must not run during boot.
  val borgWrote = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

  override def wireGpuMem(): Unit = if (borgCfg.isDefined && !scanoutOn) {
    mem.io.gpuMem <> borg.io.gpuMem   // Borg owns the port; scanout stays off
    scanout.io.gpuData  := 0.U
    scanout.io.gpuReady := false.B
  } else if (borgCfg.isDefined) {
    // Borg first; the scanout takes the port when Borg is idle (as in ULX3S.scala).
    val gpuActive   = borg.io.gpuMem.req || borg.io.gpuMem.wr
    val scanoutOwns = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }
    withClockAndReset(sysClock, pllRst) {
      when(borg.io.gpuMem.wr) { borgWrote := true.B }
      when(scanoutOwns) {
        when(mem.io.gpuMem.ready) { scanoutOwns := false.B }
      }.elsewhen(!gpuActive && scanout.io.gpuReq) { scanoutOwns := true.B }
    }
    val serveGpu = !scanoutOwns
    mem.io.gpuMem.req    := Mux(serveGpu, borg.io.gpuMem.req, scanout.io.gpuReq)
    mem.io.gpuMem.addr   := Mux(serveGpu, borg.io.gpuMem.addr, scanout.io.gpuAddr)
    mem.io.gpuMem.wr     := Mux(serveGpu, borg.io.gpuMem.wr, false.B)
    mem.io.gpuMem.wdata  := borg.io.gpuMem.wdata
    mem.io.gpuMem.wlen   := Mux(serveGpu, borg.io.gpuMem.wlen, 1.U)
    borg.io.gpuMem.data    := mem.io.gpuMem.data
    borg.io.gpuMem.ready   := mem.io.gpuMem.ready && !scanoutOwns
    borg.io.gpuMem.waccept := mem.io.gpuMem.waccept && serveGpu
    scanout.io.gpuData  := mem.io.gpuMem.data
    scanout.io.gpuReady := mem.io.gpuMem.ready && scanoutOwns
  } else {
    mem.io.gpuMem.req   := scanout.io.gpuReq
    mem.io.gpuMem.addr  := scanout.io.gpuAddr
    mem.io.gpuMem.wr    := false.B
    mem.io.gpuMem.wdata := 0.U
    mem.io.gpuMem.wlen  := 1.U
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
  sdramBackend.io.backend.byteEnIn   := Mux(bootDone, mem.io.backend.byteEnIn,   flashBoot.io.backend.byteEnIn)
  sdramBackend.io.backend.lenIn      := Mux(bootDone, mem.io.backend.lenIn,      1.U)
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead,  false.B)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)

  mem.io.backend.dataOut := Mux(bootDone, sdramBackend.io.backend.dataOut, 0.U)
  mem.io.backend.done    := Mux(bootDone, sdramBackend.io.backend.done,    false.B)
  mem.io.backend.busy    := Mux(bootDone, sdramBackend.io.backend.busy,    false.B)
  mem.io.backend.accept  := Mux(bootDone, sdramBackend.io.backend.accept,  false.B)

  flashBoot.io.backend.dataOut := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.done    := Mux(!bootDone, sdramBackend.io.backend.done,   false.B)
  flashBoot.io.backend.busy    := Mux(!bootDone, sdramBackend.io.backend.busy,   false.B)
  flashBoot.io.backend.accept  := Mux(!bootDone, sdramBackend.io.backend.accept, false.B)

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

  // ── VGA timing, at a real 25 MHz CADENCE inside the 125 MHz HDMI domain ──
  // A divide-by-5 clock ENABLE (hdmiTick25), not a genuine dedicated pixel-
  // clock PLL output -- mirrors ULX3S.scala's fix for the same reason: keep
  // the historically reliable 3-output PLL structure and generate video
  // timing inside the existing 125 MHz HDMI domain instead of adding a 4th
  // output. The frame buffer's display-side read port is genuinely on
  // hdmiClock (a real dual-clock BRAM crossing against the fill FSM's
  // sysClock write side, via HdmiScanoutFp16's write-side toggle-sync CDC).
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

  scanout.io.hCount := hCount
  scanout.io.vCount := vCount
  scanout.io.de     := de
  scanout.io.tick25 := hdmiTick25
  scanout.io.pixClk.get := hdmiClock
  scanout.io.pixRst.get := hdmiRst
  // Auto-enable after a boot delay instead of gating on btn(0) (no button
  // needed) or being always-on from reset. Hutt's instr fetch is the
  // LOWEST-priority requester in MemoryController, and scanout's gpuReq
  // stays asserted essentially continuously once enabled (sReq/sWait cycle
  // back to back) -- confirmed on real hardware that this genuinely starves
  // instruction fetch badly enough to hang firmware bigger than a handful
  // of instructions (a proven-working fill loop plus one extra, entirely
  // unused `andi` never completed with scanout enabled from boot; the
  // identical firmware ran fine immediately with scanout held off). A fixed
  // delay before the first enable gives firmware a clear run at SDRAM
  // during boot -- ~1M sysClock cycles (~42 ms @ 25 MHz) is generous for
  // any of this harness's diagnostic firmware.
  val scanoutBootDelay = withClockAndReset(sysClock, pllRst) { RegInit(0.U(21.W)) }
  val scanoutReady     = scanoutBootDelay(20)
  withClockAndReset(sysClock, pllRst) {
    when(!scanoutReady) { scanoutBootDelay := scanoutBootDelay + 1.U }
  }
  // Off for Linux: scanout starves the boot copy and instruction fetch.
  scanout.io.enable := scanoutReady && scanoutOn.B && (borgCfg.isEmpty.B || borgWrote)

  // scanout.io.red/green/blue and hsync/vsync/de are already natively in the
  // hdmiClock domain (they only change on hdmiTick25) -- no CDC stage needed;
  // feed the TMDS encoders directly.
  // ── TMDS Encoders + Serializers (125 MHz domain) ─────────────────────────
  val encB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encB.io.en := hdmiTick25; encB.io.data := scanout.io.blue
  encB.io.c  := Cat(vsync, hsync); encB.io.de := de
  val encG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encG.io.en := hdmiTick25; encG.io.data := scanout.io.green
  encG.io.c  := 0.U; encG.io.de := de
  val encR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encR.io.en := hdmiTick25; encR.io.data := scanout.io.red
  encR.io.c  := 0.U; encR.io.de := de
  // TmdsEncoder has 2 pipeline stages (q_m_reg, then the registered
  // PopCount/diff added to pipeline the disparity computation -- see its
  // own comment); delay serializer load by 2 cycles to match. (This was
  // previously missing entirely -- serializers loaded on the same cycle
  // the encoder's own `en` fired, one symbol behind the encoder's actual
  // output. Invisible on a static test color, which is all this harness
  // has rendered so far, but a real bug ULX3S.scala didn't have.)
  val hdmiTick25D1 = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hdmiTick25, false.B) }
  val hdmiTick25D2 = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hdmiTick25D1, false.B) }
  val serB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serB.io.en := hdmiTick25D2; serB.io.tmds := encB.io.tmds
  val serG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serG.io.en := hdmiTick25D2; serG.io.tmds := encG.io.tmds
  val serR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serR.io.en := hdmiTick25D2; serR.io.tmds := encR.io.tmds
  val serClk = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serClk.io.en := hdmiTick25D2; serClk.io.tmds := "b0000011111".U
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

/** Emit Verilog + LPF for the minimal ULX3S target, RV64 + CLINT — Hutt
  * without Borg, for isolating whether a Linux/OpenSBI boot that's silent
  * on real hardware (despite working in simulation) is caused by the full
  * SoC's timing-closure margin rather than a logic bug.
  */
object ULX3SMinimalLinuxMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val targetDir = "out/ulx3s_minimal_linux/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_minimal_top(clockMhz, xlen = 64, scanoutOn = false),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  // Reuse the full pin definitions; unused pins are harmless to constrain.
  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** The same with HDMI scanout of the Borg's 128x128 frame (shown after the first flush). */
object ULX3SMinimalLinuxBorgHdmiMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "18").toInt
  val targetDir = "out/ulx3s_minimal_linux_borg_hdmi/verilog"
  new java.io.File(targetDir).mkdirs()
  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_minimal_top(clockMhz, xlen = 64, scanoutOn = true, fbSize = 128,
                                        borgCfg = Some(borg.BorgConfig.Tiny)),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** RV64 Linux top with Borg attached; BORG_MINIMAL_CFG = tiny (default) | cube. */
object ULX3SMinimalLinuxBorgMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val targetDir = "out/ulx3s_minimal_linux_borg/verilog"
  new java.io.File(targetDir).mkdirs()

  val cfg = sys.env.getOrElse("BORG_MINIMAL_CFG", "tiny") match {
    case "tiny" => borg.BorgConfig.Tiny
    case "cube" => borg.BorgConfig.Simt.copy(samples = 1, hasBlend = false, hasStencil = false,
                                             hasCompute = false, hasDepthFlush = false)
    case other  => throw new IllegalArgumentException(s"BORG_MINIMAL_CFG=$other")
  }
  val fma = sys.env.get("BORG_FMA_STAGES").fold(cfg)(n => cfg.copy(fmaStages = n.toInt))
  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_minimal_top(clockMhz, xlen = 64, scanoutOn = false, borgCfg = Some(fma)),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}
