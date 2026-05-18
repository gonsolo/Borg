// SPDX-FileCopyrightText: © 2024 Michael Bell
// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
package soc

import chisel3._
import chisel3.util._
import hutt.{Hutt, HuttBus, HuttBusReq, HuttInstrBus}
import memory.{MemoryController, MemoryControllerIO, QspiPinsIO}
import borg.BorgConfig

// ---------------------------------------------------------------------------
// SoC-internal bus decoder constants.  Inherited from Michael Bell's TinyQV
// SoC address-decode scheme so existing firmware keeps working.
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
  val PERI_TIME_LIMIT        = 0xB
  val PERI_DEBUG             = 0xC
  val PERI_USER              = 0xF

  def socPeriU(idx: Int): UInt = idx.U(4.W)
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
  def BORG_CFG: BorgConfig = BorgConfig.Sim

  // --- Abstract members provided by each top-level ---
  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt

  // --- Core + peripherals ---
  lazy val cpu = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Hutt())
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

  /** Wire the GPU memory port.  Default: direct Borg↔MemoryController. */
  def wireGpuMem(): Unit = {
    mem.io.gpuMem <> peripherals.io.gpuMem
  }

  /** Wire up the entire SoC. Call this from the top-level module body. */
  def wireSoC(): UInt = {

    // -------------------------------------------------------------------------
    // Instruction fetch — direct CPU↔MemoryController.
    // -------------------------------------------------------------------------
    mem.io.instr <> cpu.io.instr

    // -------------------------------------------------------------------------
    // Synchronized ui_in for peripheral interrupts.
    // -------------------------------------------------------------------------
    val ui_in_sync0 = withClockAndReset(soc_clk, false.B) { RegNext(soc_ui_in) }
    val ui_in_sync  = withClockAndReset(soc_clk, false.B) { RegNext(ui_in_sync0) }

    // -------------------------------------------------------------------------
    // Data bus router — demux CPU data port to memory / SoC peri / user peri.
    // -------------------------------------------------------------------------
    val cpuData = cpu.io.data
    val cpuAddr = cpuData.req.bits.addr  // 28-bit byte address

    val isMem  = cpuAddr(27, 25) === 0.U
    val isSoc  = SoCDecode.socRegion.matches(cpuAddr)
    val isUser = SoCDecode.userRegion.matches(cpuAddr)

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

    when(socFire && cpuData.req.bits.write) {
      switch(socPeri) {
        is(socPeriU(PERI_GPIO_OUT_SEL))    { gpio_out_sel       := cpuData.req.bits.data(7, 6) }
        is(socPeriU(PERI_TIME_LIMIT))      { time_limit         := cpuData.req.bits.data(6, 2) }
        is(socPeriU(PERI_DEBUG_UART_BAUD)) { debug_baud_divider := cpuData.req.bits.data(12, 0) }
        is(socPeriU(PERI_DEBUG))           { debug_register_data := cpuData.req.bits.data(0) }
      }
    }

    val debug_uart_tx_busy = uartTx.io.uart_tx_busy
    val socReadData = WireDefault("hffffffff".U(32.W))
    switch(socPeri) {
      is(socPeriU(PERI_ID))                { socReadData := 0x41.U(32.W) }
      is(socPeriU(PERI_GPIO_OUT_SEL))      { socReadData := Cat(0.U(24.W), gpio_out_sel, 0.U(6.W)) }
      is(socPeriU(PERI_DEBUG_UART_STATUS)) { socReadData := Cat(0.U(31.W), debug_uart_tx_busy) }
      is(socPeriU(PERI_DEBUG_UART_BAUD))   { socReadData := Cat(0.U(19.W), debug_baud_divider) }
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
    val activeMem  = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeUser = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeSoc  = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val anyActive  = activeMem || activeUser || activeSoc

    cpuData.req.ready := !anyActive && MuxCase(true.B, Seq(
      isMem  -> mem.io.cpuData.req.ready,
      isUser -> peripherals.io.mmio.req.ready,
      isSoc  -> true.B
      // Address outside any region → drop into "ready=true" so the CPU isn't
      // stuck; resp returns 0xFFFFFFFF the next cycle via socRespData fallback.
    ))

    withClockAndReset(soc_clk, false.B) {
      when(cpuData.req.fire) {
        activeMem  := isMem
        activeUser := isUser
        activeSoc  := isSoc || (!isMem && !isUser)  // fallback: treat unknown as SoC
      }
      when(cpuData.resp.fire) {
        activeMem  := false.B
        activeUser := false.B
        activeSoc  := false.B
        socRespPending := false.B
      }
    }

    mem.io.cpuData.resp.ready         := cpuData.resp.ready && activeMem
    peripherals.io.mmio.resp.ready    := cpuData.resp.ready && activeUser

    cpuData.resp.valid := MuxCase(false.B, Seq(
      activeMem  -> mem.io.cpuData.resp.valid,
      activeUser -> peripherals.io.mmio.resp.valid,
      activeSoc  -> socRespPending
    ))
    cpuData.resp.bits := MuxCase(0.U, Seq(
      activeMem  -> mem.io.cpuData.resp.bits,
      activeUser -> peripherals.io.mmio.resp.bits,
      activeSoc  -> socRespData
    ))

    // GPU port (overridable by ULX3S for scanout mux).
    wireGpuMem()

    // -------------------------------------------------------------------------
    // Interrupt + timer.
    // -------------------------------------------------------------------------
    val time_count = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(0.U(7.W)) }
    val time_pulse = Wire(Bool())
    time_pulse := (time_count === Cat(time_limit, 3.U(2.W)))
    withClockAndReset(soc_clk, false.B) {
      when(time_pulse) { time_count := 0.U }
        .otherwise    { time_count := time_count + 1.U }
    }

    // Hutt currently only consumes a single interrupt level; OR together
    // peripheral interrupts and ui_in[1:0] (firmware can poll the time_pulse
    // via a CSR or status register later).  For now this drives the level
    // line; Hutt has no CSR/trap so it's a stub for future expansion.
    val interrupt_req = Cat(peripherals.io.user_interrupts, ui_in_sync(1, 0))
    cpu.io.interrupt := interrupt_req.orR

    // -------------------------------------------------------------------------
    // uo_out construction.
    // -------------------------------------------------------------------------
    val peri_out = peripherals.io.uo_out
    // Hutt has no debug_rd; just use zero placeholders for the lanes that
    // TinyQV mapped to its debug register file.
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
