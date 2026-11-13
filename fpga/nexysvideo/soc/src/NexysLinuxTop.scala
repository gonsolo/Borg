// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import borg.BorgConfig
import hutt.{HuttBus, HuttInstrBus}
import soc.{Emit, GpuMemArbiter, GpuMemMig, HostLink, HuttGpuMem, MinimalSoCLogic}

/** 64-bit Hutt with Borg on the Nexys Video, for Linux (docs/B15_nexys_linux.md): the minimal
  * SoC on the DDR3, sharing one memory port with the host link. The host loads memory over USB
  * and starts Hutt with a write of 1 to any link register (0 holds it in reset); a host session
  * ending leaves the SoC running.
  */
class NexysLinuxTop extends RawModule with NexysBoard with MinimalSoCLogic {
  val uart_rx_out = IO(Output(Bool()))   // the PC's receive line: what the FPGA sends
  val uart_tx_in  = IO(Input(Bool()))    // what the PC sends

  def CLOCK_MHZ = 25
  override def xlen: Int = 64
  override def borgCfg: Option[BorgConfig] = Some(BorgConfig.Default)
  // The DDR3, at RISC-V's usual RAM base; Borg's addresses land on the same bytes.
  override def ramBase: BigInt  = BigInt("80000000", 16)
  override def ramBytes: BigInt = BigInt("20000000", 16)
  // Borg's addresses from 0, its 128 MB layout and the scan-out after it, in a window of the DDR3
  // at 256 MB (0x90000000 for the CPU), which Linux leaves to the driver.
  def gpuWindow = 0x10000000L
  override def scanoutDesc: Long = gpuWindow + 0x8000000L

  val boardReset = resetIn(clkGen.io.clk25, !locked)
  val linkReset  = resetIn(clkGen.io.clk25, !locked || usbLost)
  val run = withClockAndReset(clkGen.io.clk25, boardReset) { RegInit(false.B) }

  def soc_clk   = clkGen.io.clk25
  def soc_rst_n = run
  lazy val soc_rst_reg_n: Bool = withClockAndReset(clkGen.io.clk25, boardReset) { RegNext(run, false.B) }
  lazy val soc_ui_in: UInt = withClockAndReset(clkGen.io.clk25, boardReset) {
    Cat(ShiftRegister(uart_tx_in, 2, true.B, true.B), 0.U(7.W))
  }

  // Hutt's caches and Borg each on the memory port; MemoryController keeps only the MMIO decode.
  lazy val cpuPort = withClockAndReset(clkGen.io.clk25, !soc_rst_reg_n) { Module(new HuttGpuMem(dataAddrBits, instrAddrBits, ramBase)) }
  override def wireGpuMem(): Unit = {
    mem.io.gpuMem.req   := false.B
    mem.io.gpuMem.wr    := false.B
    mem.io.gpuMem.addr  := 0.U
    mem.io.gpuMem.wdata := 0.U
    mem.io.gpuMem.wlen  := 1.U
  }
  override def wireCpuMem(instr: HuttInstrBus, data: HuttBus): HuttBus = {
    cpuPort.io.instr <> instr
    mem.io.instr.req.valid  := false.B
    mem.io.instr.req.bits   := 0.U
    mem.io.instr.resp.ready := false.B
    val mmio  = Wire(new HuttBus(dataAddrBits, 64))
    val toMem = isRam(data.req.bits.addr)
    val route = withClockAndReset(clkGen.io.clk25, !soc_rst_reg_n) { RegInit(false.B) }
    cpuPort.io.data.req.valid := data.req.valid && toMem
    cpuPort.io.data.req.bits  := data.req.bits
    mmio.req.valid := data.req.valid && !toMem
    mmio.req.bits  := data.req.bits
    data.req.ready := Mux(toMem, cpuPort.io.data.req.ready, mmio.req.ready)
    when(data.req.fire) { route := toMem }
    data.resp.valid := Mux(route, cpuPort.io.data.resp.valid, mmio.resp.valid)
    data.resp.bits  := Mux(route, cpuPort.io.data.resp.bits, mmio.resp.bits)
    cpuPort.io.data.resp.ready := data.resp.ready && route
    mmio.resp.ready := data.resp.ready && !route
    mmio
  }

  uart_rx_out := wireSoC()(6)
  mem.io.backend.busy    := false.B
  mem.io.backend.done    := false.B
  mem.io.backend.accept  := false.B
  mem.io.backend.dataOut := 0.U

  rx.deqClock := clkGen.io.clk25; rx.deqReset := linkReset
  tx.enqClock := clkGen.io.clk25; tx.enqReset := linkReset
  withClockAndReset(clkGen.io.clk25, boardReset) {
    val arb = Module(new GpuMemArbiter(3))
    arb.io.in(0) <> cpuPort.io.mem
    arb.io.in(1) <> borg.io.gpuMem
    arb.io.in(1).addr := borg.io.gpuMem.addr + gpuWindow.U

    val link = withReset(linkReset) { Module(new HostLink) }
    arb.io.in(2) <> link.io.mem
    link.io.in <> rx.deq
    tx.enq.valid      := link.io.out.valid
    tx.enq.bits       := Cat(link.io.outEnd, link.io.out.bits)
    link.io.out.ready := tx.enq.ready

    // The link's registers: one, Hutt's run bit.
    val c    = link.io.mmio
    val pend = RegInit(false.B)
    c.req.ready  := !pend
    c.resp.valid := pend
    c.resp.bits  := run
    when(c.req.fire) { pend := true.B; when(c.req.bits.write) { run := c.req.bits.data(0) } }
    when(c.resp.fire) { pend := false.B }

    val ddr = Module(new GpuMemMig)
    ddr.io.gpu <> arb.io.out
    ddr.io.uiClock := mig.ui_clk
    ddr.io.uiReset := mig.ui_clk_sync_rst
    gpuMig <> ddr.io.mig

    led := Cat(uart_rx_out, run, locked, RegNext(RegNext(mig.init_calib_complete)))
  }
}

object NexysLinuxTopMain extends App {
  val targetDir = "out/nexysvideo_linux/verilog"
  Emit.cleanTargetDir(targetDir)
  ChiselStage.emitSystemVerilogFile(
    gen = new NexysLinuxTop,
    args = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
