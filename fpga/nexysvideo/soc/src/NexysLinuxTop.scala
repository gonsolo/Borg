// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import borg.BorgConfig
import soc.{Emit, GpuMemArbiter, GpuMemBackend, GpuMemMig, HostLink, MinimalSoCLogic}

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

  val boardReset = resetIn(clkGen.io.clk25, !locked)
  val linkReset  = resetIn(clkGen.io.clk25, !locked || usbLost)
  val run = withClockAndReset(clkGen.io.clk25, boardReset) { RegInit(false.B) }

  def soc_clk   = clkGen.io.clk25
  def soc_rst_n = run
  lazy val soc_rst_reg_n: Bool = withClockAndReset(clkGen.io.clk25, boardReset) { RegNext(run, false.B) }
  lazy val soc_ui_in: UInt = withClockAndReset(clkGen.io.clk25, boardReset) {
    Cat(ShiftRegister(uart_tx_in, 2, true.B, true.B), 0.U(7.W))
  }

  uart_rx_out := wireSoC()(6)

  rx.deqClock := clkGen.io.clk25; rx.deqReset := linkReset
  tx.enqClock := clkGen.io.clk25; tx.enqReset := linkReset
  withClockAndReset(clkGen.io.clk25, boardReset) {
    val back = Module(new GpuMemBackend)
    val arb  = Module(new GpuMemArbiter(2))
    back.io.be <> mem.io.backend
    arb.io.in(0) <> back.io.mem

    val link = withReset(linkReset) { Module(new HostLink) }
    arb.io.in(1) <> link.io.mem
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
