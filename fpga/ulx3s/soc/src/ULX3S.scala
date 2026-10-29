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

/** ECP5 bidirectional buffer (Lattice cell BB): T = 0 drives I onto B, T = 1 is high-Z with B on O. */
class Ecp5BiDirBuf extends ExtModule {
  override def desiredName = "BB"  // must match the Lattice/nextpnr cell name
  val B = IO(Analog(1.W))
  val T = IO(Input(Bool()))
  val I = IO(Input(Bool()))
  val O = IO(Output(Bool()))
}

/** ULX3S (Lattice ECP5-85K) top: SDRAM, HDMI, UART on ftdi_rxd.
  * FlashBootLoader copies firmware from flash 0x400000 to SDRAM 0x0; Hutt starts after boot_done.
  */
class ulx3s_top(val CLOCK_MHZ: Int, val borgModeOverride: BorgMode = BorgDirect) extends RawModule with SoCLogic {
  // BORG_ULX_CFG: cube (default), stencil (the blend and stencil scenes) or
  // full, which does not fit the ECP5-85K.
  override def BORG_CFG: BorgConfig = sys.env.getOrElse("BORG_ULX_CFG", "cube") match {
    case "full"    => BorgConfig.Default
    case "cube"    => BorgConfig.Cube
    case "stencil" => BorgConfig.Cube.copy(hasBlend = true, hasStencil = true)
    case other     => throw new IllegalArgumentException(s"BORG_ULX_CFG=$other")
  }
  // RV32I without S-mode: RV64 does not fit the ECP5-85K next to FP32 Borg, and the
  // bare-metal firmware never leaves M-mode. Linux work stays on the MinimalSoC harness.
  override def hasSupervisorMode: Boolean = false
  override def scanoutCurBuf: Bool = scanout.io.curBuf
  override def borgMode: BorgMode = borgModeOverride
  // SdramBackend decodes byte-address bit 24 of the CPU's DRAM window; without it
  // on the GPU port the GPU sees different physical SDRAM than the CPU.
  override def gpuVramRegionBit: Boolean = true
  // Rung B needs two pads per wire, which only fits J1+J2 at the narrow width.
  override def linkParams: borg.link.LinkParams =
    if (borgModeOverride == BorgPadLoop) borg.link.LinkParams(w = 8)
    else borg.link.LinkParams()

  val clk_25mhz = IO(Input(Clock()))
  val rst_n      = IO(Input(Bool()))   // BTN_PWRn, active-low

  // SDRAM IS42S16160G-7TL, 16-bit
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

  // Flash W25Q128JV; flash_clk goes through USRMCLK, so it has no port.
  val flash_csn  = IO(Output(Bool()))
  val flash_mosi = IO(Output(Bool()))
  val flash_miso = IO(Input(Bool()))

  val ftdi_rxd = IO(Output(Bool()))   // FPGA → host TX
  val ftdi_txd = IO(Input(Bool()))    // host → FPGA RX

  val gpdi_dp = IO(Output(UInt(4.W)))

  val led = IO(Output(UInt(8.W)))
  val btn = IO(Input(UInt(6.W)))

  // Bridge link pins (BorgExternal only), directions as BorgLinkMasterIO.
  // dbg_sel and link_narrow only reserve lane-map positions; no RTL reads them.
  val dn_d        = if (borgMode == BorgExternal) Some(IO(Output(UInt(16.W)))) else None
  val dn_v        = if (borgMode == BorgExternal) Some(IO(Output(Bool())))     else None
  val dn_p        = if (borgMode == BorgExternal) Some(IO(Output(Bool())))     else None
  val dn_cred     = if (borgMode == BorgExternal) Some(IO(Input(Bool())))      else None
  val up_d        = if (borgMode == BorgExternal) Some(IO(Input(UInt(16.W)))) else None
  val up_v        = if (borgMode == BorgExternal) Some(IO(Input(Bool())))      else None
  val up_p        = if (borgMode == BorgExternal) Some(IO(Input(Bool())))      else None
  val up_cred     = if (borgMode == BorgExternal) Some(IO(Output(Bool())))     else None
  // The far side's link_up. Looping back the master's own linkUp would never train,
  // since the master's linkUp is its farLinkUp; single-board loopback is BorgPadLoop.
  val far_link_up  = if (borgMode == BorgExternal) Some(IO(Input(Bool())))  else None
  // Onboard DIP switches SW1-4.
  val dbg_sel     = if (borgMode == BorgExternal) Some(IO(Input(UInt(2.W)))) else None
  val link_narrow = if (borgMode == BorgExternal) Some(IO(Input(Bool())))    else None
  val link_fast   =
    if (borgMode == BorgExternal || borgMode == BorgPadLoop) Some(IO(Input(Bool()))) else None

  // Rung B pad loop: master and slave are both on this FPGA, so each wire needs a
  // driving and a receiving pad, bridged by the ribbon. 23 pairs at w=8.
  private def padLoop = borgMode == BorgPadLoop
  // master -> pads -> cable -> pads -> slave
  val pl_dn_d_out   = if (padLoop) Some(IO(Output(UInt(8.W)))) else None
  val pl_dn_v_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_dn_p_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_dn_d_in    = if (padLoop) Some(IO(Input(UInt(8.W))))  else None
  val pl_dn_v_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_dn_p_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_upcred_out = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_upcred_in  = if (padLoop) Some(IO(Input(Bool())))     else None
  // slave -> pads -> cable -> pads -> master
  val pl_up_d_out   = if (padLoop) Some(IO(Output(UInt(8.W)))) else None
  val pl_up_v_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_up_p_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_up_d_in    = if (padLoop) Some(IO(Input(UInt(8.W))))  else None
  val pl_up_v_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_up_p_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_dncred_out = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_dncred_in  = if (padLoop) Some(IO(Input(Bool())))     else None
  // The slave's linkUp into the master's farLinkUp; the master's own would deadlock.
  val pl_linkup_out = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_linkup_in  = if (padLoop) Some(IO(Input(Bool())))     else None

  // PLL: 25 MHz osc → SoC, SoC/90° for the SDRAM pin, 125 MHz HDMI.
  // CLOCK_MHZ comes from ULX3S_MHZ in fpga/ulx3s/Makefile, which also sets the UART divider
  // and the firmware. Keep it at 10: solves with a ~750 MHz VCO fail to lock on a cold
  // boot from flash (JTAG loads work); 500 and 625 MHz VCOs boot.
  val SOC_MHZ = CLOCK_MHZ
  // HDMI_DECOUPLE=1: video runs on hdmiClock with a divide-by-5 enable and a dual-clock
  // frame buffer. 0: pixel clock == sysClock (25 MHz), plain CDC into the TMDS domain.
  val hdmiDecouple = sys.env.getOrElse("HDMI_DECOUPLE", "0") == "1"
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
  val sdramClock = pll.io.clk_o(1)
  val hdmiClock  = pll.io.clk_o(2)   // TMDS serializer and, divided by 5, video timing

  sdram_clk := sdramClock

  // Not gated on boot_done: boot_done needs SdramBackend to complete a write.
  val pllRst = !pllLocked

  // Warm reset (serial firmware reload): resets only the bootloader and, via boot_done,
  // the CPU side, so HDMI keeps running. warmBootReg makes the bootloader copy scratch→0.
  val warmRst     = Wire(Bool())
  val warmBootReg = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

  val flashBoot = withClockAndReset(sysClock, pllRst || warmRst) {
    Module(new FlashBootLoader())
  }
  flashBoot.io.warmBoot := warmBootReg

  val usrmclk = Module(new Usrmclk)
  usrmclk.USRMCLKI  := flashBoot.io.spi_clk.asClock
  usrmclk.USRMCLKTS := false.B   // always enabled (active-low tristate)

  flash_csn  := flashBoot.io.flash_csn
  flash_mosi := flashBoot.io.flash_mosi
  flashBoot.io.flash_miso := flash_miso

  def soc_clk = sysClock
  def soc_rst_n = pllLocked && flashBoot.io.boot_done && rst_n

  lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
    RegNext(soc_rst_n)
  }

  // ui_in[7] = ftdi_txd feeds PeriUart's uart_rxd; ui_in[6:1] = btn.
  def soc_ui_in = Cat(ftdi_txd, btn, 0.U(1.W))

  // Declared before wireGpuMem() uses it. Firmware programs the framebuffer bases.
  val scanout = withClockAndReset(sysClock, pllRst) {
    Module(new HdmiScanoutFp16(fbWidth = 128, fbHeight = 128, separatePixelClock = hdmiDecouple))
  }

  // The GPU has priority over scanout. scanoutOwns holds an accepted scanout request
  // until its ready, even if the GPU becomes active meanwhile.
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
    mem.io.gpuMem.wlen  := Mux(serveGpu, peripherals.io.gpuMem.wlen, 1.U)

    peripherals.io.gpuMem.data    := mem.io.gpuMem.data
    peripherals.io.gpuMem.ready   := mem.io.gpuMem.ready && !scanoutOwns
    peripherals.io.gpuMem.waccept := mem.io.gpuMem.waccept && serveGpu

    scanout.io.gpuData  := mem.io.gpuMem.data
    scanout.io.gpuReady := mem.io.gpuMem.ready && scanoutOwns
  }

  val uo_out_val = wireSoC()

  // Rung A: close peripherals.io.link on an on-chip slave and Borg.
  if (borgMode == BorgLoopback) wireBorgLoopback()

  // Rung C: close peripherals.io.link on board pins.
  if (borgMode == BorgExternal) {
    val linkIo = peripherals.io.link.getOrElse(
      throw new IllegalStateException("BorgExternal requires peripherals.io.link to be present")
    )
    dn_d.get    := linkIo.dnPins.d
    dn_v.get    := linkIo.dnPins.v
    dn_p.get    := linkIo.dnPins.p
    linkIo.dnCred := dn_cred.get

    linkIo.upPins.d := up_d.get
    linkIo.upPins.v := up_v.get
    linkIo.upPins.p := up_p.get
    up_cred.get := linkIo.upCred

    linkIo.linkFast   := link_fast.get
    linkIo.linkNarrow := link_narrow.get
    linkIo.farLinkUp  := far_link_up.get
    // This build is fixed-width (only narrowCapable builds mux), so link_narrow is ignored.
  }

  // Rung B: the whole bridge, with the master<->slave path out on pads.
  if (borgMode == BorgPadLoop) {
    val pads = Wire(new BorgPadLoopIO(linkParams))

    // The ribbon shorts each *_out to its matching *_in.
    pl_dn_d_out.get := pads.dnOut.d
    pl_dn_v_out.get := pads.dnOut.v
    pl_dn_p_out.get := pads.dnOut.p
    pads.dnIn.d     := pl_dn_d_in.get
    pads.dnIn.v     := pl_dn_v_in.get
    pads.dnIn.p     := pl_dn_p_in.get

    pl_upcred_out.get := pads.upCredOut
    pads.upCredIn     := pl_upcred_in.get

    pl_up_d_out.get := pads.upOut.d
    pl_up_v_out.get := pads.upOut.v
    pl_up_p_out.get := pads.upOut.p
    pads.upIn.d     := pl_up_d_in.get
    pads.upIn.v     := pl_up_v_in.get
    pads.upIn.p     := pl_up_p_in.get

    pl_dncred_out.get := pads.dnCredOut
    pads.dnCredIn     := pl_dncred_in.get

    pl_linkup_out.get := pads.linkUpOut
    pads.linkUpIn     := pl_linkup_in.get

    wireBorgPadLoop(pads)

    peripherals.io.link.get.linkFast := link_fast.get
    // Already 8 lanes wide, so no 16->8 mux to engage.
    peripherals.io.link.get.linkNarrow := false.B
  }

  // Hold warmRst for 16 cycles; warmBootReg persists until the next PLL reset.
  val warmRstCtr = withClockAndReset(sysClock, pllRst) { RegInit(0.U(5.W)) }
  when(warmReset) {
    warmBootReg := true.B
    warmRstCtr  := 16.U
  } .elsewhen(warmRstCtr =/= 0.U) {
    warmRstCtr := warmRstCtr - 1.U
  }
  warmRst := warmRstCtr =/= 0.U

  val sdramBackend = withClockAndReset(sysClock, pllRst) {
    Module(new SdramBackend(SOC_MHZ))
  }

  // The bootloader owns the backend until boot_done, then the MemoryController.
  val bootDone = flashBoot.io.boot_done

  sdramBackend.io.backend.addrIn     := Mux(bootDone, mem.io.backend.addrIn,     flashBoot.io.backend.addrIn)
  sdramBackend.io.backend.dataIn     := Mux(bootDone, mem.io.backend.dataIn,     flashBoot.io.backend.dataIn)
  sdramBackend.io.backend.byteEnIn   := Mux(bootDone, mem.io.backend.byteEnIn,   flashBoot.io.backend.byteEnIn)
  // The warm-reload copy reads.
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead,  flashBoot.io.backend.startRead)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
  sdramBackend.io.backend.lenIn      := Mux(bootDone, mem.io.backend.lenIn,      flashBoot.io.backend.lenIn)

  mem.io.backend.dataOut := Mux(bootDone, sdramBackend.io.backend.dataOut, 0.U)
  mem.io.backend.accept  := Mux(bootDone, sdramBackend.io.backend.accept,  false.B)
  mem.io.backend.done    := Mux(bootDone, sdramBackend.io.backend.done,    false.B)
  mem.io.backend.busy    := Mux(bootDone, sdramBackend.io.backend.busy,    false.B)

  flashBoot.io.backend.dataOut := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.accept  := Mux(!bootDone, sdramBackend.io.backend.accept, false.B)
  flashBoot.io.backend.done    := Mux(!bootDone, sdramBackend.io.backend.done, false.B)
  flashBoot.io.backend.busy    := Mux(!bootDone, sdramBackend.io.backend.busy, false.B)

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

  // Debug: send 'H' at 115200 from a 125 MHz clock, with no CPU involved.
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



  scanout.io.enable   := true.B
  scanout.io.frontBuf := fbSelectReg
  scanout.io.fbBase   := scanoutFbBase0
  scanout.io.fbBase1  := scanoutFbBase1

  val hdmiRst = !pllLocked
  val pRed = Wire(UInt(8.W)); val pGreen = Wire(UInt(8.W)); val pBlue = Wire(UInt(8.W))
  val pHsync = Wire(Bool()); val pVsync = Wire(Bool()); val pDe = Wire(Bool())
  val hdmiTick25 = Wire(Bool())
  if (hdmiDecouple) {
    // VGA timing on hdmiClock, advancing on a divide-by-5 enable instead of a fourth
    // PLL output. The frame buffer crosses clocks in a dual-clock BRAM.
    val hdmiCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(3.W)) }
    hdmiTick25 := (hdmiCount === 4.U)
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

    // Already in the hdmiClock domain, so no CDC.
    pRed := scanout.io.red; pGreen := scanout.io.green; pBlue := scanout.io.blue
    pHsync := hsync; pVsync := vsync; pDe := de
  } else {
    // Pixel clock == sysClock (25 MHz): every cycle is a pixel.
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
    scanout.io.hCount := hCount; scanout.io.vCount := vCount
    scanout.io.de := de; scanout.io.tick25 := true.B
    // CDC: latch RGB8 + sync from 25 MHz -> 125 MHz (data stable for 5 fast clocks).
    pRed   := withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.red) }
    pGreen := withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.green) }
    pBlue  := withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.blue) }
    pHsync := withClockAndReset(hdmiClock, hdmiRst) { RegNext(hsync) }
    pVsync := withClockAndReset(hdmiClock, hdmiRst) { RegNext(vsync) }
    pDe    := withClockAndReset(hdmiClock, hdmiRst) { RegNext(de) }
    val hdmiCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(3.W)) }
    hdmiTick25 := (hdmiCount === 4.U)
    withClockAndReset(hdmiClock, hdmiRst) {
      when(hdmiTick25) { hdmiCount := 0.U } .otherwise { hdmiCount := hdmiCount + 1.U }
    }
  }

  val encB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encB.io.en := hdmiTick25; encB.io.data := pBlue
  encB.io.c := Cat(pVsync, pHsync); encB.io.de := pDe
  val encG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encG.io.en := hdmiTick25; encG.io.data := pGreen
  encG.io.c := 0.U; encG.io.de := pDe
  val encR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encR.io.en := hdmiTick25; encR.io.data := pRed
  encR.io.c := 0.U; encR.io.de := pDe
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

object ULX3SPins {
  case class PinDef(name: String, site: String, pull: String = "NONE",
                    ioType: String = "LVCMOS33", drive: Int = 4)

  val pins: Seq[PinDef] = Seq(
    PinDef("clk_25mhz", "G2",  pull = "NONE", drive = 4),
    PinDef("rst_n",      "D6",  pull = "UP",   drive = 4),

    // Flash (flash_clk via USRMCLK)
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

  // Bridge link pins (BorgExternal). Sites from ulx3s_v20.lpf's gp[]/gn[] table;
  // gp/gn[11-13] are shared with WiFi on v3.0.x and skipped. Inputs pull DOWN so a
  // missing far side fails the link's odd-parity check. GP is the outer row
  // (toward the board edge), GN the inner one.
  val linkExternalPins: Seq[PinDef] = Seq(
    // J1 pins 0-10: dn_d[0..10] / up_d[0..10].
    PinDef("dn_d[0]",  "B11", pull = "NONE"), PinDef("up_d[0]",  "C11", pull = "DOWN"),
    PinDef("dn_d[1]",  "A10", pull = "NONE"), PinDef("up_d[1]",  "A11", pull = "DOWN"),
    PinDef("dn_d[2]",  "A9",  pull = "NONE"), PinDef("up_d[2]",  "B10", pull = "DOWN"),
    PinDef("dn_d[3]",  "B9",  pull = "NONE"), PinDef("up_d[3]",  "C10", pull = "DOWN"),
    PinDef("dn_d[4]",  "A7",  pull = "NONE"), PinDef("up_d[4]",  "A8",  pull = "DOWN"),
    PinDef("dn_d[5]",  "C8",  pull = "NONE"), PinDef("up_d[5]",  "B8",  pull = "DOWN"),
    PinDef("dn_d[6]",  "C6",  pull = "NONE"), PinDef("up_d[6]",  "C7",  pull = "DOWN"),
    PinDef("dn_d[7]",  "A6",  pull = "NONE"), PinDef("up_d[7]",  "B6",  pull = "DOWN"),
    PinDef("dn_d[8]",  "A4",  pull = "NONE"), PinDef("up_d[8]",  "A5",  pull = "DOWN"),
    PinDef("dn_d[9]",  "A2",  pull = "NONE"), PinDef("up_d[9]",  "B1",  pull = "DOWN"),
    PinDef("dn_d[10]", "C4",  pull = "NONE"), PinDef("up_d[10]", "B4",  pull = "DOWN"),

    // J2 pins 14-22 (9 pairs): dn_d[11..15]/up_d[11..15], dn_v/up_v,
    // dn_p/up_p, dn_cred/up_cred, far_link_up. gn[22] and pins 23-27 spare.
    PinDef("dn_d[11]", "U18", pull = "NONE"), PinDef("up_d[11]", "U17", pull = "DOWN"),
    PinDef("dn_d[12]", "N17", pull = "NONE"), PinDef("up_d[12]", "P16", pull = "DOWN"),
    PinDef("dn_d[13]", "N16", pull = "NONE"), PinDef("up_d[13]", "M17", pull = "DOWN"),
    PinDef("dn_d[14]", "L16", pull = "NONE"), PinDef("up_d[14]", "L17", pull = "DOWN"),
    PinDef("dn_d[15]", "H18", pull = "NONE"), PinDef("up_d[15]", "H17", pull = "DOWN"),
    PinDef("dn_v",     "F17", pull = "NONE"), PinDef("up_v",     "G18", pull = "DOWN"),
    PinDef("dn_p",     "D18", pull = "NONE"), PinDef("up_p",     "E17", pull = "DOWN"),
    PinDef("dn_cred",  "C18", pull = "DOWN"), PinDef("up_cred",  "D17", pull = "NONE"),
    PinDef("far_link_up",  "B15", pull = "DOWN"),
  )

  // Rung B pad loop: GP drives, GN receives, so the ribbon bridges each pin's two rows.
  // Inputs pull DOWN so a missing jumper reads 0, which v, p and linkUp treat as idle.
  val padLoopPins: Seq[PinDef] = Seq(
    // J1 pins 0-10: master -> cable -> slave.
    PinDef("pl_dn_d_out[0]", "B11", pull = "NONE"), PinDef("pl_dn_d_in[0]", "C11", pull = "DOWN"),
    PinDef("pl_dn_d_out[1]", "A10", pull = "NONE"), PinDef("pl_dn_d_in[1]", "A11", pull = "DOWN"),
    PinDef("pl_dn_d_out[2]", "A9",  pull = "NONE"), PinDef("pl_dn_d_in[2]", "B10", pull = "DOWN"),
    PinDef("pl_dn_d_out[3]", "B9",  pull = "NONE"), PinDef("pl_dn_d_in[3]", "C10", pull = "DOWN"),
    PinDef("pl_dn_d_out[4]", "A7",  pull = "NONE"), PinDef("pl_dn_d_in[4]", "A8",  pull = "DOWN"),
    PinDef("pl_dn_d_out[5]", "C8",  pull = "NONE"), PinDef("pl_dn_d_in[5]", "B8",  pull = "DOWN"),
    PinDef("pl_dn_d_out[6]", "C6",  pull = "NONE"), PinDef("pl_dn_d_in[6]", "C7",  pull = "DOWN"),
    PinDef("pl_dn_d_out[7]", "A6",  pull = "NONE"), PinDef("pl_dn_d_in[7]", "B6",  pull = "DOWN"),
    PinDef("pl_dn_v_out",    "A4",  pull = "NONE"), PinDef("pl_dn_v_in",    "A5",  pull = "DOWN"),
    PinDef("pl_dn_p_out",    "A2",  pull = "NONE"), PinDef("pl_dn_p_in",    "B1",  pull = "DOWN"),
    PinDef("pl_upcred_out",  "C4",  pull = "NONE"), PinDef("pl_upcred_in",  "B4",  pull = "DOWN"),

    // J2 pins 14-25: slave -> cable -> master.
    PinDef("pl_up_d_out[0]", "U18", pull = "NONE"), PinDef("pl_up_d_in[0]", "U17", pull = "DOWN"),
    PinDef("pl_up_d_out[1]", "N17", pull = "NONE"), PinDef("pl_up_d_in[1]", "P16", pull = "DOWN"),
    PinDef("pl_up_d_out[2]", "N16", pull = "NONE"), PinDef("pl_up_d_in[2]", "M17", pull = "DOWN"),
    PinDef("pl_up_d_out[3]", "L16", pull = "NONE"), PinDef("pl_up_d_in[3]", "L17", pull = "DOWN"),
    PinDef("pl_up_d_out[4]", "H18", pull = "NONE"), PinDef("pl_up_d_in[4]", "H17", pull = "DOWN"),
    PinDef("pl_up_d_out[5]", "F17", pull = "NONE"), PinDef("pl_up_d_in[5]", "G18", pull = "DOWN"),
    PinDef("pl_up_d_out[6]", "D18", pull = "NONE"), PinDef("pl_up_d_in[6]", "E17", pull = "DOWN"),
    PinDef("pl_up_d_out[7]", "C18", pull = "NONE"), PinDef("pl_up_d_in[7]", "D17", pull = "DOWN"),
    PinDef("pl_up_v_out",    "B15", pull = "NONE"), PinDef("pl_up_v_in",    "C15", pull = "DOWN"),
    PinDef("pl_up_p_out",    "B17", pull = "NONE"), PinDef("pl_up_p_in",    "C17", pull = "DOWN"),
    PinDef("pl_dncred_out",  "C16", pull = "NONE"), PinDef("pl_dncred_in",  "D16", pull = "DOWN"),
    PinDef("pl_linkup_out",  "D14", pull = "NONE"), PinDef("pl_linkup_in",  "E14", pull = "DOWN"),
  )

  // SW4 alone: rung B reads only link_fast.
  val linkFastPin: Seq[PinDef] = Seq(PinDef("link_fast", "E7", pull = "DOWN"))

  // DIP switches SW1-4, from ulx3s_v20.lpf's sw[] block.
  val dipSwitchPins: Seq[PinDef] = Seq(
    PinDef("dbg_sel[0]",   "E8", pull = "DOWN"),
    PinDef("dbg_sel[1]",   "D8", pull = "DOWN"),
    PinDef("link_narrow",  "D7", pull = "DOWN"),
    PinDef("link_fast",    "E7", pull = "DOWN"),
  )

  def emitLPF(path: String, extraPins: Seq[PinDef] = Seq()): Unit = {
    val writer = new java.io.PrintWriter(path)
    writer.println("# ULX3S (ECP5-85K) pin constraints")
    writer.println("# Sites from ulx3s_v20.lpf — https://github.com/emard/ulx3s")
    writer.println()
    // USRMCLK needs the SPI ports released; SPI_SERIAL keeps the flash out of QPI mode,
    // where it ignores 1-bit SPI commands.
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
    for (p <- extraPins) {
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

/** Rung A of the Borg-only bridge ladder: Borg reached through the link RTL on internal wires. */
object ULX3SLoopbackMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog_loopback"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz, borgModeOverride = BorgLoopback),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** Rung C of the bridge ladder: the link master on GP/GN pins, to a second board running
  * BorgOnlyTop across 74LVC8T245 level shifters.
  */
object ULX3SExternalMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog_external"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz, borgModeOverride = BorgExternal),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf",
    extraPins = ULX3SPins.linkExternalPins ++ ULX3SPins.dipSwitchPins)
}

/** Rung B of the bridge ladder: rung A's master, slave and Borg, with the link routed out
  * through pads and shorted back by a ribbon across J1/J2. ULX3SExternalMain cannot do this:
  * it elaborates the master alone.
  */
object ULX3SPadLoopMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog_padloop"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz, borgModeOverride = BorgPadLoop),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf",
    extraPins = ULX3SPins.padLoopPins ++ ULX3SPins.linkFastPin)
}
