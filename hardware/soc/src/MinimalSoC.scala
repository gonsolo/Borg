// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import hutt.{Hutt, HuttBus, HuttBusReq, HuttDataWidthAdapter, HuttInstrBus}
import memory.MemoryController
import borg.{Borg, BorgConfig}

/** SoCLogic's address map with Hutt, memory, CLINT and the debug UART, and an
  * optional Borg: fast builds for CPU, UART and HDMI bring-up. Unmapped user
  * peripherals read 0xFFFFFFFF. */
trait MinimalSoCLogic { self: RawModule =>
  def CLOCK_MHZ: Int
  def CLOCK_HZ: Long = CLOCK_MHZ.toLong * 1000000L   // exact when the PLL cannot hit CLOCK_MHZ
  def xlen: Int = 32   // override with def, not val
  def borgCfg: Option[BorgConfig] = None   // Some(cfg) attaches Borg at BORG_BASE (0x08000C00)

  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt

  lazy val cpu = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Hutt(xlen = xlen))
  }
  lazy val mem = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    // SDRAM: no flash/PSRAM bit, except that a Borg sees SDRAM 16 MB up
    // (bit 24), the Linux carve-out.
    Module(new MemoryController(gpuVramRegionBit = borgCfg.isDefined))
  }
  lazy val uartTx = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new peri.uart.UartTx(13))
  }
  lazy val borg = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new Borg(borgCfg.get)) }
  lazy val clint = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new Clint) }
  lazy val iCache = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new hutt.InstrCache(23)) }

  /** Overridden by HDMI bring-up tops, where scanout owns the port. */
  def wireGpuMem(): Unit = {
    if (borgCfg.isDefined) {
      mem.io.gpuMem <> borg.io.gpuMem
    } else {
      mem.io.gpuMem.req   := false.B
      mem.io.gpuMem.wr    := false.B
      mem.io.gpuMem.addr  := 0.U
      mem.io.gpuMem.wdata := 0.U
      mem.io.gpuMem.wlen  := 1.U
    }
  }

  def wireSoC(): UInt = {
    import SoCDecode._

    iCache.io.cpu <> cpu.io.instr
    iCache.io.mem <> mem.io.instr

    val cpuData: HuttBus = if (xlen > 32) {
      val adapter = withClockAndReset(soc_clk, !soc_rst_reg_n) {
        Module(new HuttDataWidthAdapter(28))
      }
      adapter.io.cpu <> cpu.io.data
      adapter.io.mem
    } else {
      cpu.io.data
    }
    val cpuAddr = cpuData.req.bits.addr

    val isMem   = cpuAddr(27, 25) === 0.U
    val isClint = cpuAddr(27, 24) === 2.U   // 0x02000000-0x02FFFFFF
    val isSoc   = SoCDecode.socRegion.matches(cpuAddr)
    val isUser  = SoCDecode.userRegion.matches(cpuAddr)
    // Borg is user slot 3 (addr[11:10]); the others stay unmapped.
    val isBorg  = isUser && !isSoc && cpuAddr(11, 10) === 3.U && borgCfg.isDefined.B

    clint.io.mmio.req.valid      := cpuData.req.valid && isClint
    clint.io.mmio.req.bits.addr  := cpuAddr(23, 0)
    clint.io.mmio.req.bits.write := cpuData.req.bits.write
    clint.io.mmio.req.bits.size  := cpuData.req.bits.size
    clint.io.mmio.req.bits.data  := cpuData.req.bits.data(31, 0)

    if (borgCfg.isDefined) {
      borg.io.mmio.req.valid      := cpuData.req.valid && isBorg
      borg.io.mmio.req.bits.addr  := cpuAddr(9, 0)
      borg.io.mmio.req.bits.write := cpuData.req.bits.write
      borg.io.mmio.req.bits.size  := cpuData.req.bits.size
      borg.io.mmio.req.bits.data  := cpuData.req.bits.data(31, 0)
    }

    mem.io.cpuData.req.valid           := cpuData.req.valid && isMem
    mem.io.cpuData.req.bits.addr       := cpuAddr(24, 0)
    mem.io.cpuData.req.bits.write      := cpuData.req.bits.write
    mem.io.cpuData.req.bits.size       := cpuData.req.bits.size
    mem.io.cpuData.req.bits.data       := cpuData.req.bits.data

    val socPeri = SoCDecode.socRegion.index(cpuAddr)

    val gpio_out_sel = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(Cat(!soc_ui_in(0), 0.U(1.W)))
    }
    val time_limit = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(math.max((CLOCK_MHZ / 4) - 1, 1).U(5.W))
    }
    val default_baud_divider = (CLOCK_HZ / 115200).U(13.W)
    val debug_baud_divider = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(default_baud_divider)
    }

    val socFire = cpuData.req.fire && isSoc

    withClockAndReset(soc_clk, false.B) {
      when(socFire && cpuData.req.bits.write) {
        switch(socPeri) {
          is(socPeriU(PERI_GPIO_OUT_SEL))    { gpio_out_sel       := cpuData.req.bits.data(7, 6) }
          is(socPeriU(PERI_TIME_LIMIT))      { time_limit         := cpuData.req.bits.data(6, 2) }
          is(socPeriU(PERI_DEBUG_UART_BAUD)) { debug_baud_divider := cpuData.req.bits.data(12, 0) }
        }
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

    val activeMem   = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeSoc   = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeClint = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val activeBorg  = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(false.B) }
    val anyActive   = activeMem || activeSoc || activeClint || activeBorg
    val borgReady   = if (borgCfg.isDefined) borg.io.mmio.req.ready else true.B

    // Unmapped accesses are answered as SoC reads of 0xFFFFFFFF.
    cpuData.req.ready := !anyActive && MuxCase(true.B, Seq(
      isMem   -> mem.io.cpuData.req.ready,
      isClint -> clint.io.mmio.req.ready,
      isBorg  -> borgReady,
      isSoc   -> true.B
    ))

    withClockAndReset(soc_clk, false.B) {
      when(cpuData.req.fire) {
        activeMem   := isMem
        activeClint := isClint
        activeBorg  := isBorg
        activeSoc   := !isMem && !isClint && !isBorg
      }
      when(cpuData.resp.fire) {
        activeMem   := false.B
        activeSoc   := false.B
        activeClint := false.B
        activeBorg  := false.B
        socRespPending := false.B
      }
    }

    mem.io.cpuData.resp.ready := cpuData.resp.ready && activeMem
    clint.io.mmio.resp.ready  := cpuData.resp.ready && activeClint
    if (borgCfg.isDefined) borg.io.mmio.resp.ready := cpuData.resp.ready && activeBorg

    cpuData.resp.valid := MuxCase(false.B, Seq(
      activeMem   -> mem.io.cpuData.resp.valid,
      activeClint -> clint.io.mmio.resp.valid,
      activeBorg  -> (if (borgCfg.isDefined) borg.io.mmio.resp.valid else false.B),
      activeSoc   -> socRespPending
    ))
    cpuData.resp.bits := MuxCase(0.U, Seq(
      activeMem   -> mem.io.cpuData.resp.bits,
      activeClint -> clint.io.mmio.resp.bits,
      activeBorg  -> (if (borgCfg.isDefined) borg.io.mmio.resp.bits else 0.U),
      activeSoc   -> socRespData
    ))

    wireGpuMem()

    cpu.io.interrupt := clint.io.timerIrq

    // Bit 6: debug UART TX, the ULX3S top's ftdi_rxd.
    val uo_out_val = Cat(0.U(1.W), debug_uart_txd, 0.U(6.W))
    uo_out_val
  }
}
