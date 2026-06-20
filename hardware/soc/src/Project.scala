// SPDX-FileCopyrightText: © 2024 Michael Bell
// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
package soc

import chisel3._
import chisel3.util._
import hutt.{Hutt, HuttBus, HuttBusReq, HuttDataWidthAdapter, HuttInstrBus}
import memory.{MemoryController, MemoryControllerIO, QspiPinsIO}
import borg.BorgConfig

// ---------------------------------------------------------------------------
// SoC-internal bus decoder constants.  Inherited from the original SoC
// address-decode scheme so existing firmware keeps working.
// ---------------------------------------------------------------------------
private[soc] object SoCDecode {
  // Magic comparison values for SoC vs User region detection
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
  val PERI_SCANOUT_FB0       = 0x4   // scanout framebuffer base (buffer 0), firmware-programmed
  val PERI_FB_SELECT         = 0x9
  val PERI_SCANOUT_FB1       = 0xA   // scanout framebuffer base (buffer 1), firmware-programmed
  val PERI_TIME_LIMIT        = 0xB
  val PERI_DEBUG             = 0xC
  val PERI_WARM_RESET        = 0x5   // write WARM_RESET_MAGIC → pulse warm reset (serial firmware reload)
  val PERI_USER              = 0xF

  def socPeriU(idx: Int): UInt = idx.U(4.W)

  // Magic value the firmware writes to PERI_WARM_RESET to request a warm reboot.
  // A non-trivial constant so a stray store can't accidentally reset the SoC.
  val WARM_RESET_MAGIC: Long = 0x0B16B007L
}

/** Platform-independent SoC backbone shared by all target top-level modules.
  *
  * Wires together the three peer components:
  *   - [[Hutt]]             — RV32I CPU; clean Decoupled buses
  *   - [[MemoryController]] — owns SPI/QSPI pins; arbitrates CPU instr-fetch,
  *                            CPU data, and GPU read/write
  *   - [[Peripherals]]      — Borg GPU + UART + GPIO (user-peripheral region)
  *
  * The CPU's single data bus is demuxed in this layer to three destinations:
  *   - Memory region        (addr[27:25] == 0)        → MemoryController.cpuData
  *   - SoC peripheral region (addr matches SOC_REGION_ID) → inline regs
  *   - User peripheral region (addr matches USER_REGION_ID) → Peripherals.mmio
  */
trait SoCLogic { self: RawModule =>
  def CLOCK_MHZ: Int
  def BORG_CFG: BorgConfig = BorgConfig.Default
  def xlen: Int = 32   // 32 = RV32I, 64 = RV64I (override with def, not val)

  // --- Abstract members provided by each top-level ---
  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt

  // --- Core + peripherals ---
  lazy val cpu = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Hutt(xlen = xlen))
  }
  lazy val mem = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new MemoryController())
  }
  lazy val peripherals = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Peripherals(CLOCK_MHZ, BORG_CFG))
  }
  lazy val uartTx = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new peri.uart.UartTx(13))
  }

  // fb_select register — populated by wireSoC().  Concrete targets (e.g. ULX3S)
  // that need to read it after wireSoC() returns access it here.
  protected var _fbSelectReg: Bool = null.asInstanceOf[Bool]
  def fbSelectReg: Bool = _fbSelectReg

  // Buffer the scanout is currently displaying.  Overridden by targets with a
  // double-buffered scanout; read back via PERI_FB_SELECT so firmware can wait
  // for the scanout to release the back buffer before the GPU re-renders it.
  def scanoutCurBuf: Bool = false.B

  // Scanout framebuffer base registers — written by firmware (from the same
  // borg_layout.h constants that drive the GPU flush base) so the scanout and the
  // GPU can never drift apart.  Exposed to the board top (ULX3S) via these vars.
  protected var _scanoutFbBase0: UInt = null
  protected var _scanoutFbBase1: UInt = null
  def scanoutFbBase0: UInt = _scanoutFbBase0
  def scanoutFbBase1: UInt = _scanoutFbBase1

  // One-cycle pulse: firmware wrote WARM_RESET_MAGIC to PERI_WARM_RESET, asking
  // the board top to warm-reboot the CPU/bootloader (for serial firmware reload)
  // without disturbing the video clock domain.  Populated by wireSoC(); targets
  // that don't implement warm reload simply ignore it.
  protected var _warmReset: Bool = null
  def warmReset: Bool = if (_warmReset != null) _warmReset else false.B

  /** Wire the GPU memory port.  Default: direct Borg↔MemoryController. */
  def wireGpuMem(): Unit = {
    mem.io.gpuMem <> peripherals.io.gpuMem
  }

  /** Wire up the entire SoC. Call this from the top-level module body. */
  def wireSoC(): UInt = {

    // -------------------------------------------------------------------------
    // Instruction fetch — via InstrCache (bypassed when icacheLines == 0).
    // -------------------------------------------------------------------------
    if (BORG_CFG.icacheLines > 0) {
      val iCache = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new hutt.InstrCache(23, BORG_CFG.icacheLines)) }
      iCache.io.cpu <> cpu.io.instr
      iCache.io.mem <> mem.io.instr
    } else {
      cpu.io.instr <> mem.io.instr
    }

    // -------------------------------------------------------------------------
    // Synchronized ui_in for peripheral interrupts.
    // -------------------------------------------------------------------------
    val ui_in_sync0 = withClockAndReset(soc_clk, false.B) { RegNext(soc_ui_in) }
    val ui_in_sync  = withClockAndReset(soc_clk, false.B) { RegNext(ui_in_sync0) }

    // -------------------------------------------------------------------------
    // Data bus router — demux CPU data port to memory / SoC peri / user peri.
    // -------------------------------------------------------------------------
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
    val isClint = cpuAddr(27, 24) === 2.U   // 0x02000000–0x02FFFFFF: CLINT (mtime/mtimecmp)
    val isSoc   = SoCDecode.socRegion.matches(cpuAddr)
    // The SoC inline-reg window is a strict subset of the user-peripheral
    // window (same addr[27:12]=0x8000), so exclude isSoc from isUser to make
    // the regions mutually exclusive.  Without this, a SoC write also fires
    // peripherals, where addr[11:10]==0 (PERI_NONE) never produces a resp —
    // bus locks waiting for an ack that never arrives.  Proved by
    // hardware.soc.test SoCRoutingTests.
    val isUser  = SoCDecode.userRegion.matches(cpuAddr) && !isSoc

    // CLINT instance — mtime / mtimecmp at 0x02000000–0x0200000F.
    val clint = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new Clint) }
    clint.io.mmio.req.valid      := cpuData.req.valid && isClint
    clint.io.mmio.req.bits.addr  := cpuAddr(23, 0)
    clint.io.mmio.req.bits.write := cpuData.req.bits.write
    clint.io.mmio.req.bits.size  := cpuData.req.bits.size
    clint.io.mmio.req.bits.data  := cpuData.req.bits.data(31, 0)

    // Per-target req.valid gating
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

    // -------------------------------------------------------------------------
    // SoC-internal MMIO: PERI_ID, DEBUG_UART, GPIO_OUT_SEL, TIME_LIMIT, ...
    // -------------------------------------------------------------------------
    import SoCDecode._
    val socPeri = SoCDecode.socRegion.index(cpuAddr)  // addr[5:2]

    // Configuration registers
    val gpio_out_sel = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(Cat(!soc_ui_in(0), 0.U(1.W)))
    }
    val fb_select = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    _fbSelectReg = fb_select
    // Scanout framebuffer bases — firmware writes these from DRAM_OUT_SPI(0) and
    // DRAM_OUT_SPI(FRAME_STRIDE); power-on default 0 (blank) until programmed.
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

    // Warm-reset request: 1-cycle pulse when firmware writes the magic value to
    // PERI_WARM_RESET.  Routed to the board top (ULX3S) which holds the bootloader
    // + CPU in reset just long enough to re-copy the serial-streamed image.
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
    }

    // SoC inline registers respond one cycle after req.fire (matches the
    // single-outstanding pattern used by UART / Borg / Peripherals).
    val socRespPending = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val socRespData    = withClockAndReset(soc_clk, false.B)         { Reg(UInt(32.W)) }
    withClockAndReset(soc_clk, false.B) {
      when(socFire) {
        socRespPending := true.B
        when(!cpuData.req.bits.write) { socRespData := socReadData }
          .otherwise                   { socRespData := 0.U }
      }
    }

    // Debug UART TX write — special-cased so we can emit a byte without
    // wiring the peripherals module to it.
    val debug_uart_tx_start = socFire && cpuData.req.bits.write &&
                              (socPeri === socPeriU(PERI_DEBUG_UART))
    val debug_uart_txd = uartTx.io.uart_txd
    uartTx.io.uart_tx_en   := debug_uart_tx_start
    uartTx.io.uart_tx_data := cpuData.req.bits.data(7, 0)
    uartTx.io.baud_divider := debug_baud_divider

    // -------------------------------------------------------------------------
    // CPU req.ready and resp muxing.
    // -------------------------------------------------------------------------
    // Track which target owns the current in-flight response so we route
    // resp.valid/bits/ready correctly.
    val activeMem   = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeUser  = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeSoc   = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeClint = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val anyActive   = activeMem || activeUser || activeSoc || activeClint

    cpuData.req.ready := !anyActive && MuxCase(true.B, Seq(
      isMem   -> mem.io.cpuData.req.ready,
      isClint -> clint.io.mmio.req.ready,
      isUser  -> peripherals.io.mmio.req.ready,
      isSoc   -> true.B
      // Address outside any region → drop into "ready=true" so the CPU isn't
      // stuck; resp returns 0xFFFFFFFF the next cycle via socRespData fallback.
    ))

    withClockAndReset(soc_clk, false.B) {
      when(cpuData.req.fire) {
        activeMem   := isMem
        activeClint := isClint
        activeUser  := isUser
        activeSoc   := isSoc || (!isMem && !isClint && !isUser)  // fallback: treat unknown as SoC
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
    clint.io.mmio.resp.ready       := cpuData.resp.ready && activeClint

    cpuData.resp.valid := MuxCase(false.B, Seq(
      activeMem   -> mem.io.cpuData.resp.valid,
      activeUser  -> peripherals.io.mmio.resp.valid,
      activeClint -> clint.io.mmio.resp.valid,
      activeSoc   -> socRespPending
    ))
    cpuData.resp.bits := MuxCase(0.U, Seq(
      activeMem   -> mem.io.cpuData.resp.bits,
      activeUser  -> peripherals.io.mmio.resp.bits,
      activeClint -> clint.io.mmio.resp.bits,
      activeSoc   -> socRespData
    ))

    // GPU port (overridable by ULX3S for scanout mux).
    wireGpuMem()

    cpu.io.interrupt := clint.io.timerIrq

    // -------------------------------------------------------------------------
    // uo_out construction.
    // -------------------------------------------------------------------------
    val peri_out = peripherals.io.uo_out
    // Hutt has no debug_rd; just use zero placeholders for the lanes that
    // Old CPU mapped to its debug register file; Hutt uses zero placeholders.
    val debug_rd_r = 0.U(4.W)
    val debug_signal = false.B  // no Hutt debug signals exposed yet

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
