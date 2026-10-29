// SPDX-FileCopyrightText: © 2024 Michael Bell
// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
package soc

import chisel3._
import chisel3.util._
import hutt.{Hutt, HuttBus, HuttBusReq, HuttDataWidthAdapter, HuttInstrBus}
import memory.{MemoryController, MemoryControllerIO, QspiPinsIO}
import borg.BorgConfig
import borg.link.LinkParams

// SoC-internal bus decoder constants, kept for firmware compatibility.
private[soc] object SoCDecode {
  private val SOC_REGION_ID  = 0x800000  // Cat(addr[27:6], addr[1:0])
  private val USER_REGION_ID = 0x8000    // addr[27:12]

  case class AddrRegion(matchFn: UInt => Bool, indexHi: Int, indexLo: Int) {
    def matches(addr: UInt): Bool = matchFn(addr)
    def index(addr: UInt): UInt = addr(indexHi, indexLo)
  }

  val socRegion = AddrRegion(
    matchFn = addr => Cat(addr(27, 6), addr(1, 0)) === SOC_REGION_ID.U,
    indexHi = 5, indexLo = 2
  )
  val userRegion = AddrRegion(
    matchFn = addr => addr(27, 12) === USER_REGION_ID.U,
    indexHi = 11, indexLo = 0
  )

  // SoC peripheral indices (addr[5:2])
  val PERI_NONE              = 0x0
  val PERI_ID                = 0x2
  val PERI_GPIO_OUT_SEL      = 0x3
  val PERI_DEBUG_UART        = 0x6
  val PERI_DEBUG_UART_STATUS = 0x7
  val PERI_DEBUG_UART_BAUD   = 0x8
  val PERI_SCANOUT_FB0       = 0x4
  val PERI_FB_SELECT         = 0x9
  val PERI_SCANOUT_FB1       = 0xA
  val PERI_TIME_LIMIT        = 0xB
  val PERI_DEBUG             = 0xC
  val PERI_WARM_RESET        = 0x5   // write WARM_RESET_MAGIC: warm reset for serial firmware reload
  val PERI_LINK_STATUS       = 0xD   // read-only: bit0 link_up, bit1 link_err
  val PERI_USER              = 0xF

  def socPeriU(idx: Int): UInt = idx.U(4.W)

  // Non-trivial so a stray store can't reset the SoC.
  val WARM_RESET_MAGIC: Long = 0x0B16B007L
}

/** SoC backbone shared by all targets: Hutt, MemoryController and Peripherals,
  * with the CPU data bus demuxed to memory (addr[27:25] == 0), the SoC inline
  * registers and the user peripheral region. */
trait SoCLogic { self: RawModule =>
  def CLOCK_MHZ: Int
  def BORG_CFG: BorgConfig = BorgConfig.Default
  def xlen: Int = 32   // override with def, not val
  def pipelinedCsrRead: Boolean = true
  def hasSupervisorMode: Boolean = true
  // Only Linux/OpenSBI takes interrupts; the bare-metal firmware never does.
  def hasClint: Boolean = true
  // Hutt's debug trace registers, observed only by ULX3S and sim harnesses.
  def hasDebugPorts: Boolean = true
  def borgMode: BorgMode = BorgDirect
  def linkParams: LinkParams = LinkParams()
  // True for QspiBackend (bit 24 selects flash or PSRAM), false for SDRAM.
  def gpuVramRegionBit: Boolean = true

  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt

  lazy val cpu = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Hutt(xlen = xlen, pipelinedCsrRead = pipelinedCsrRead,
      hasSupervisorMode = hasSupervisorMode, hasDebugPorts = hasDebugPorts))
  }
  lazy val mem = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new MemoryController(gpuVramRegionBit))
  }
  lazy val peripherals = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Peripherals(CLOCK_MHZ, BORG_CFG, borgMode, linkParams))
  }
  lazy val uartTx = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new peri.uart.UartTx(13))
  }

  // Set by wireSoC().
  protected var _fbSelectReg: Bool = null.asInstanceOf[Bool]
  def fbSelectReg: Bool = _fbSelectReg

  // The buffer on screen, read back via PERI_FB_SELECT so firmware can wait
  // for the scanout to release the back buffer.
  def scanoutCurBuf: Bool = false.B

  // Firmware writes these from the same constants as the GPU flush base.
  protected var _scanoutFbBase0: UInt = null
  protected var _scanoutFbBase1: UInt = null
  def scanoutFbBase0: UInt = _scanoutFbBase0
  def scanoutFbBase1: UInt = _scanoutFbBase1

  // One-cycle pulse asking the board top to warm-reboot the CPU without
  // disturbing the video clock domain.
  protected var _warmReset: Bool = null
  def warmReset: Bool = if (_warmReset != null) _warmReset else false.B

  def wireGpuMem(): Unit = {
    mem.io.gpuMem <> peripherals.io.gpuMem
  }

  /** Rung A: close the link back on a same-bitstream BorgLinkSlave + Borg, so
    * all Borg traffic crosses the real link RTL. Required under BorgLoopback. */
  def wireBorgLoopback(): Unit = {
    require(borgMode == BorgLoopback, "wireBorgLoopback() only makes sense under BorgLoopback")
    val linkIo = peripherals.io.link.getOrElse(
      throw new IllegalStateException("BorgLoopback requires peripherals.io.link to be present")
    )

    val farBorg = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new borg.Borg(BORG_CFG)) }
    val slave   = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      Module(new borg.link.BorgLinkSlave(linkParams))
    }
    slave.io.mmio   <> farBorg.io.mmio
    slave.io.gpuMem <> farBorg.io.gpuMem

    slave.io.dnPins := linkIo.dnPins
    linkIo.upPins   := slave.io.upPins
    slave.io.upCred := linkIo.upCred
    linkIo.dnCred   := slave.io.dnCred
    slave.io.linkFast := linkIo.linkFast
    slave.io.narrow   := linkIo.linkNarrow

    // Board straps elsewhere; default to the reset-default safe mode. A caller
    // with a real strap overrides after this call.
    linkIo.linkFast   := false.B
    linkIo.linkNarrow := false.B

    linkIo.farLinkUp := slave.io.linkUp
  }

  /** Rung B: rung A, but every master-slave wire leaves the FPGA and comes back
    * over a ribbon cable. linkUp must be the slave's (constant true): looping
    * the master's own linkUp closes farLinkUp = linkUp, stuck at 0. */
  def wireBorgPadLoop(pads: BorgPadLoopIO): Unit = {
    require(borgMode == BorgPadLoop, "wireBorgPadLoop() only makes sense under BorgPadLoop")
    val linkIo = peripherals.io.link.getOrElse(
      throw new IllegalStateException("BorgPadLoop requires peripherals.io.link to be present")
    )

    val farBorg = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new borg.Borg(BORG_CFG)) }
    val slave   = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      Module(new borg.link.BorgLinkSlave(linkParams))
    }
    slave.io.mmio   <> farBorg.io.mmio
    slave.io.gpuMem <> farBorg.io.gpuMem

    // master -> pads -> cable -> pads -> slave
    pads.dnOut      := linkIo.dnPins
    slave.io.dnPins := pads.dnIn
    pads.upCredOut  := linkIo.upCred
    slave.io.upCred := pads.upCredIn

    // slave -> pads -> cable -> pads -> master
    pads.upOut       := slave.io.upPins
    linkIo.upPins    := pads.upIn
    pads.dnCredOut   := slave.io.dnCred
    linkIo.dnCred    := pads.dnCredIn
    pads.linkUpOut   := slave.io.linkUp
    linkIo.farLinkUp := pads.linkUpIn

    slave.io.linkFast := linkIo.linkFast
    slave.io.narrow   := linkIo.linkNarrow
  }

  /** Call from the top-level module body. */
  def wireSoC(): UInt = {

    val iCache = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new hutt.InstrCache(23)) }
    iCache.io.cpu <> cpu.io.instr
    iCache.io.mem <> mem.io.instr

    val ui_in_sync0 = withClockAndReset(soc_clk, false.B) { RegNext(soc_ui_in) }
    val ui_in_sync  = withClockAndReset(soc_clk, false.B) { RegNext(ui_in_sync0) }

    val cpuData: HuttBus = if (xlen > 32) {
      val adapter = withClockAndReset(soc_clk, !soc_rst_reg_n) {
        Module(new HuttDataWidthAdapter(28))
      }
      adapter.io.cpu <> cpu.io.data
      adapter.io.mem
    } else {
      cpu.io.data
    }
    val cpuAddr = cpuData.req.bits.addr  // 28-bit byte address

    val isMem   = cpuAddr(27, 25) === 0.U
    // 0x02000000-0x02FFFFFF: CLINT.
    val isClint = if (hasClint) (cpuAddr(27, 24) === 2.U) else false.B
    val isSoc   = SoCDecode.socRegion.matches(cpuAddr)
    // The SoC window lies inside the user window, whose PERI_NONE never
    // answers: overlap would hang the bus.
    val isUser  = SoCDecode.userRegion.matches(cpuAddr) && !isSoc

    val clintOpt: Option[Clint] = if (hasClint) Some(
      withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new Clint) }
    ) else None
    clintOpt.foreach { clint =>
      clint.io.mmio.req.valid      := cpuData.req.valid && isClint
      clint.io.mmio.req.bits.addr  := cpuAddr(23, 0)
      clint.io.mmio.req.bits.write := cpuData.req.bits.write
      clint.io.mmio.req.bits.size  := cpuData.req.bits.size
      clint.io.mmio.req.bits.data  := cpuData.req.bits.data(31, 0)
    }

    mem.io.cpuData.req.valid           := cpuData.req.valid && isMem
    mem.io.cpuData.req.bits.addr       := cpuAddr(24, 0)
    mem.io.cpuData.req.bits.write      := cpuData.req.bits.write
    mem.io.cpuData.req.bits.size       := cpuData.req.bits.size
    mem.io.cpuData.req.bits.data       := cpuData.req.bits.data

    peripherals.io.mmio.req.valid      := cpuData.req.valid && isUser
    peripherals.io.mmio.req.bits.addr  := cpuAddr(11, 0)
    peripherals.io.mmio.req.bits.write := cpuData.req.bits.write
    peripherals.io.mmio.req.bits.size  := cpuData.req.bits.size
    peripherals.io.mmio.req.bits.data  := cpuData.req.bits.data
    peripherals.io.ui_in               := ui_in_sync

    import SoCDecode._
    val socPeri = SoCDecode.socRegion.index(cpuAddr)  // addr[5:2]

    val gpio_out_sel = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(Cat(!soc_ui_in(0), 0.U(1.W)))
    }
    val fb_select = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    _fbSelectReg = fb_select
    val scanout_fb_base0 = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(0.U(25.W)) }
    val scanout_fb_base1 = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(0.U(25.W)) }
    _scanoutFbBase0 = scanout_fb_base0
    _scanoutFbBase1 = scanout_fb_base1
    val time_limit = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(math.max((CLOCK_MHZ / 4) - 1, 1).U(5.W))
    }
    val default_baud_divider = ((CLOCK_MHZ * 1000000) / 115200).U(13.W)
    val debug_baud_divider = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(default_baud_divider)
    }
    val debug_register_data = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(soc_ui_in(1))
    }

    val socFire = cpuData.req.fire && isSoc

    val warm_reset_pulse = WireDefault(false.B)
    _warmReset = warm_reset_pulse

    when(socFire && cpuData.req.bits.write) {
      switch(socPeri) {
        is(socPeriU(PERI_GPIO_OUT_SEL))    { gpio_out_sel       := cpuData.req.bits.data(7, 6) }
        is(socPeriU(PERI_TIME_LIMIT))      { time_limit         := cpuData.req.bits.data(6, 2) }
        is(socPeriU(PERI_DEBUG_UART_BAUD)) { debug_baud_divider := cpuData.req.bits.data(12, 0) }
        is(socPeriU(PERI_FB_SELECT))       { fb_select          := cpuData.req.bits.data(0) }
        is(socPeriU(PERI_SCANOUT_FB0))     { scanout_fb_base0   := cpuData.req.bits.data(24, 0) }
        is(socPeriU(PERI_SCANOUT_FB1))     { scanout_fb_base1   := cpuData.req.bits.data(24, 0) }
        is(socPeriU(PERI_DEBUG))           { debug_register_data := cpuData.req.bits.data(0) }
        is(socPeriU(PERI_WARM_RESET))      { warm_reset_pulse   := (cpuData.req.bits.data === WARM_RESET_MAGIC.U) }
      }
    }

    val debug_uart_tx_busy = uartTx.io.uart_tx_busy
    // No link reads 0, not the unmapped 0xFFFFFFFF.
    val linkStatusBits = peripherals.io.link
      .map(l => Cat(0.U(30.W), l.linkErr, l.linkUp))
      .getOrElse(0.U(32.W))
    val socReadData = WireDefault("hffffffff".U(32.W))
    switch(socPeri) {
      is(socPeriU(PERI_ID))                { socReadData := 0x41.U(32.W) }
      is(socPeriU(PERI_GPIO_OUT_SEL))      { socReadData := Cat(0.U(24.W), gpio_out_sel, 0.U(6.W)) }
      is(socPeriU(PERI_DEBUG_UART_STATUS)) { socReadData := Cat(0.U(31.W), debug_uart_tx_busy) }
      is(socPeriU(PERI_DEBUG_UART_BAUD))   { socReadData := Cat(0.U(19.W), debug_baud_divider) }
      is(socPeriU(PERI_FB_SELECT))         { socReadData := Cat(0.U(31.W), scanoutCurBuf) }
      is(socPeriU(PERI_SCANOUT_FB0))       { socReadData := Cat(0.U(7.W), scanout_fb_base0) }
      is(socPeriU(PERI_SCANOUT_FB1))       { socReadData := Cat(0.U(7.W), scanout_fb_base1) }
      is(socPeriU(PERI_TIME_LIMIT))        { socReadData := Cat(0.U(25.W), time_limit, 3.U(2.W)) }
      is(socPeriU(PERI_LINK_STATUS))       { socReadData := linkStatusBits }
    }

    // Inline registers answer one cycle after req.fire.
    val socRespPending = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val socRespData    = withClockAndReset(soc_clk, false.B)         { Reg(UInt(32.W)) }
    withClockAndReset(soc_clk, false.B) {
      when(socFire) {
        socRespPending := true.B
        when(!cpuData.req.bits.write) { socRespData := socReadData }
          .otherwise                   { socRespData := 0.U }
      }
    }

    val debug_uart_tx_start = socFire && cpuData.req.bits.write &&
                              (socPeri === socPeriU(PERI_DEBUG_UART))
    val debug_uart_txd = uartTx.io.uart_txd
    uartTx.io.uart_tx_en   := debug_uart_tx_start
    uartTx.io.uart_tx_data := cpuData.req.bits.data(7, 0)
    uartTx.io.baud_divider := debug_baud_divider

    // Which target owns the in-flight response.
    val activeMem   = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeUser  = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeSoc   = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeClint = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val anyActive   = activeMem || activeUser || activeSoc || activeClint

    cpuData.req.ready := !anyActive && MuxCase(true.B, Seq(
      isMem  -> mem.io.cpuData.req.ready,
      isUser -> peripherals.io.mmio.req.ready,
      isSoc  -> true.B
      // Unmapped: ready, answered with 0xFFFFFFFF as a SoC read.
    ) ++ clintOpt.map(c => isClint -> c.io.mmio.req.ready).toSeq)

    withClockAndReset(soc_clk, false.B) {
      when(cpuData.req.fire) {
        activeMem   := isMem
        activeClint := isClint
        activeUser  := isUser
        activeSoc   := isSoc || (!isMem && !isClint && !isUser)
      }
      when(cpuData.resp.fire) {
        activeMem   := false.B
        activeUser  := false.B
        activeSoc   := false.B
        activeClint := false.B
        socRespPending := false.B
      }
    }

    mem.io.cpuData.resp.ready      := cpuData.resp.ready && activeMem
    peripherals.io.mmio.resp.ready := cpuData.resp.ready && activeUser
    clintOpt.foreach { c => c.io.mmio.resp.ready := cpuData.resp.ready && activeClint }

    cpuData.resp.valid := MuxCase(false.B, Seq(
      activeMem  -> mem.io.cpuData.resp.valid,
      activeUser -> peripherals.io.mmio.resp.valid,
      activeSoc  -> socRespPending
    ) ++ clintOpt.map(c => activeClint -> c.io.mmio.resp.valid).toSeq)
    cpuData.resp.bits := MuxCase(0.U, Seq(
      activeMem  -> mem.io.cpuData.resp.bits,
      activeUser -> peripherals.io.mmio.resp.bits,
      activeSoc  -> socRespData
    ) ++ clintOpt.map(c => activeClint -> c.io.mmio.resp.bits).toSeq)

    wireGpuMem()

    cpu.io.interrupt := clintOpt.map(_.io.timerIrq).getOrElse(false.B)

    val peri_out = peripherals.io.uo_out
    val debug_rd_r = 0.U(4.W)
    val debug_signal = false.B

    val uo_out_val = Cat(
      Mux(gpio_out_sel(1), peri_out(7), debug_signal),
      Mux(gpio_out_sel(0), peri_out(6), debug_uart_txd),
      Mux(debug_register_data, debug_rd_r(3), peri_out(5)),
      Mux(debug_register_data, debug_rd_r(2), peri_out(4)),
      Mux(debug_register_data, debug_rd_r(1), peri_out(3)),
      Mux(debug_register_data, debug_rd_r(0), peri_out(2)),
      peri_out(1),
      peri_out(0)
    )

    uo_out_val
  }
}
