// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Minimal ULX3S top: Hutt + MemoryController + debug UART + HDMI scanout, no Borg.
// Builds fast for boot, UART and scanout bring-up.

package soc

import chisel3._
import chisel3.util._
import chisel3.{ExtModule, StringParam}
import chisel3.experimental.{Analog, attach}
import memory.{Ecp5PllParams, Ecp5PllWrapper, FlashBootLoader, SdramBackend, Usrmclk}
import _root_.circt.stage.ChiselStage

/** ULX3S top, minimal SoC variant with the same pinout as `ulx3s_top`.
  *
  * @param xlen 32 = RV32I, 64 = RV64I with CLINT (OpenSBI/Linux boot without Borg)
  */
class ulx3s_minimal_top(val CLOCK_MHZ: Int, override val xlen: Int = 32, scanoutOn: Boolean = true, fbSize: Int = 32,
                         override val borgCfg: Option[borg.BorgConfig] = None) extends RawModule with MinimalSoCLogic {
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

  // PLL: SoC clock, SDRAM clock at 90 degrees, 125 MHz HDMI.
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
  val hdmiClock  = pll.io.clk_o(2)   // 125 MHz: TMDS serializer, and video timing divided by 5
  sdram_clk := sdramClock

  val pllRst = !pllLocked

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

  def soc_clk = sysClock
  def soc_rst_n = pllLocked && flashBoot.io.boot_done && rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
    RegNext(soc_rst_n)
  }
  def soc_ui_in = Cat(0.U(1.W), btn, ftdi_txd)

  // Instantiated before wireSoC so wireGpuMem can connect it.
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

  ftdi_rxd := uo_out_val(6)

  // VGA timing at a 25 MHz cadence: a divide-by-5 enable in the 125 MHz HDMI domain,
  // which keeps the 3-output PLL (as in ULX3S.scala).
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
  // Enable after ~1M cycles: instruction fetch has the lowest priority and a continuously
  // requesting scanout starves it enough to hang firmware during boot.
  val scanoutBootDelay = withClockAndReset(sysClock, pllRst) { RegInit(0.U(21.W)) }
  val scanoutReady     = scanoutBootDelay(20)
  withClockAndReset(sysClock, pllRst) {
    when(!scanoutReady) { scanoutBootDelay := scanoutBootDelay + 1.U }
  }
  // Off for Linux: scanout starves the boot copy and instruction fetch.
  scanout.io.enable := scanoutReady && scanoutOn.B && (borgCfg.isEmpty.B || borgWrote)

  // The scanout outputs are already in the hdmiClock domain; feed the TMDS encoders directly.
  val encB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encB.io.en := hdmiTick25; encB.io.data := scanout.io.blue
  encB.io.c  := Cat(vsync, hsync); encB.io.de := de
  val encG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encG.io.en := hdmiTick25; encG.io.data := scanout.io.green
  encG.io.c  := 0.U; encG.io.de := de
  val encR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encR.io.en := hdmiTick25; encR.io.data := scanout.io.red
  encR.io.c  := 0.U; encR.io.de := de
  // TmdsEncoder has 2 pipeline stages; delay the serializer load to match.
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

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** Emit Verilog + LPF for the minimal ULX3S target, RV64 + CLINT, no Borg. */
object ULX3SMinimalLinuxMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val targetDir = "out/ulx3s_minimal_linux/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_minimal_top(clockMhz, xlen = 64, scanoutOn = false),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** The same with HDMI scanout of the Borg's 128x128 frame (shown after the first flush). */
object ULX3SMinimalLinuxBorgHdmiMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "18").toInt
  val targetDir = "out/ulx3s_minimal_linux_borg_hdmi/verilog"
  new java.io.File(targetDir).mkdirs()
  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_minimal_top(clockMhz, xlen = 64, scanoutOn = true, fbSize = 128,
                                        borgCfg = Some(borg.BorgConfig.Cube)),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** RV64 Linux top with Borg attached. */
object ULX3SMinimalLinuxBorgMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val targetDir = "out/ulx3s_minimal_linux_borg/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_minimal_top(clockMhz, xlen = 64, scanoutOn = false, borgCfg = Some(borg.BorgConfig.Cube)),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}
