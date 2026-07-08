// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Minimal SoC backbone for fast iteration on UART / HDMI / CPU bring-up.
// Hutt + MemoryController + DEBUG UART + SoC inline regs only — no Borg,
// no user-peripheral router.  Build time is a fraction of the full SoC.

package soc

import chisel3._
import chisel3.util._
import hutt.{Hutt, HuttBus, HuttBusReq, HuttDataWidthAdapter, HuttInstrBus}
import memory.MemoryController

/** Slimmed-down SoCLogic — same address map as the full version but with no
  * Borg / no Peripherals / no GPU memory port consumer.  Suitable for testing
  * UART output, HDMI scanout, and CPU bring-up without paying for the GPU
  * pipeline's synthesis time (~80% of total).
  *
  * The CPU's MMIO writes to PERI_DEBUG_UART (addr 0x08000018) still drive
  * the debug UART exactly as the full SoC.  Reads from un-implemented user
  * peripherals return 0xFFFFFFFF.
  */
trait MinimalSoCLogic { self: RawModule =>
  def CLOCK_MHZ: Int
  def xlen: Int = 32   // 32 = RV32I, 64 = RV64I (override with def, not val)

  // Abstract — provided by the top module.
  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt

  lazy val cpu = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Hutt(xlen = xlen))
  }
  lazy val mem = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new MemoryController())
  }
  lazy val uartTx = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new peri.uart.UartTx(13))
  }
  lazy val clint = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new Clint) }

  /** GPU port tied off — no scanout / no Borg.  Overridable by the top for
    * HDMI bring-up (where scanout owns the gpuMem port).
    */
  def wireGpuMem(): Unit = {
    mem.io.gpuMem.req   := false.B
    mem.io.gpuMem.wr    := false.B
    mem.io.gpuMem.addr  := 0.U
    mem.io.gpuMem.wdata := 0.U
    mem.io.gpuMem.wlen  := 1.U
  }

  def wireSoC(): UInt = {
    import SoCDecode._

    val iCache = withClockAndReset(soc_clk, !soc_rst_reg_n) { Module(new hutt.InstrCache(23)) }
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
    val isClint = cpuAddr(27, 24) === 2.U   // 0x02000000–0x02FFFFFF: CLINT (mtime/mtimecmp)
    val isSoc   = SoCDecode.socRegion.matches(cpuAddr)
    val isUser  = SoCDecode.userRegion.matches(cpuAddr)

    // CLINT instance — mtime / mtimecmp at 0x02000000–0x0200000F.
    clint.io.mmio.req.valid      := cpuData.req.valid && isClint
    clint.io.mmio.req.bits.addr  := cpuAddr(23, 0)
    clint.io.mmio.req.bits.write := cpuData.req.bits.write
    clint.io.mmio.req.bits.size  := cpuData.req.bits.size
    clint.io.mmio.req.bits.data  := cpuData.req.bits.data(31, 0)

    // Memory port from CPU.
    mem.io.cpuData.req.valid           := cpuData.req.valid && isMem
    mem.io.cpuData.req.bits.addr       := cpuAddr(24, 0)
    mem.io.cpuData.req.bits.write      := cpuData.req.bits.write
    mem.io.cpuData.req.bits.size       := cpuData.req.bits.size
    mem.io.cpuData.req.bits.data       := cpuData.req.bits.data

    val socPeri = SoCDecode.socRegion.index(cpuAddr)

    // Inline registers — minimum needed for the debug UART path.
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
    val anyActive   = activeMem || activeSoc || activeClint

    // User-region reads/writes get auto-acked with 0xFFFFFFFF (treated as SoC).
    cpuData.req.ready := !anyActive && MuxCase(true.B, Seq(
      isMem   -> mem.io.cpuData.req.ready,
      isClint -> clint.io.mmio.req.ready,
      isSoc   -> true.B
    ))

    withClockAndReset(soc_clk, false.B) {
      when(cpuData.req.fire) {
        activeMem   := isMem
        activeClint := isClint
        activeSoc   := !isMem && !isClint  // SoC OR user → treat as SoC for response
      }
      when(cpuData.resp.fire) {
        activeMem   := false.B
        activeSoc   := false.B
        activeClint := false.B
        socRespPending := false.B
      }
    }

    mem.io.cpuData.resp.ready := cpuData.resp.ready && activeMem
    clint.io.mmio.resp.ready  := cpuData.resp.ready && activeClint

    cpuData.resp.valid := MuxCase(false.B, Seq(
      activeMem   -> mem.io.cpuData.resp.valid,
      activeClint -> clint.io.mmio.resp.valid,
      activeSoc   -> socRespPending
    ))
    cpuData.resp.bits := MuxCase(0.U, Seq(
      activeMem   -> mem.io.cpuData.resp.bits,
      activeClint -> clint.io.mmio.resp.bits,
      activeSoc   -> socRespData
    ))

    wireGpuMem()

    cpu.io.interrupt := clint.io.timerIrq

    // uo_out: bit 6 = debug UART TX (consumed by the ULX3S top to drive ftdi_rxd).
    val uo_out_val = Cat(0.U(1.W), debug_uart_txd, 0.U(6.W))
    uo_out_val
  }
}
