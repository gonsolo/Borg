// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import borg.{Borg, BorgConfig}
import soc.{Emit, GpuMemArbiter, GpuMemMig, HostLink}

/** Borg on the Nexys Video as the host's device (`borg_ftdi`, BORG_HW_RAW): the direct simulator's
  * protocol over the FT2232H's synchronous FIFO, memory on the DDR3, the presented picture on HDMI.
  * No CPU.
  */
class NexysLinkTop extends RawModule with NexysBoard {
  val borgReset = resetIn(clkGen.io.clk25, !locked || usbLost)

  rx.deqClock := clkGen.io.clk25; rx.deqReset := borgReset
  tx.enqClock := clkGen.io.clk25; tx.enqReset := borgReset
  withClockAndReset(clkGen.io.clk25, borgReset) {
    val b    = Module(new Borg(BorgConfig.Default))
    val link = Module(new HostLink)
    val arb  = Module(new GpuMemArbiter(2))
    link.io.mmio <> b.io.mmio
    arb.io.in(0) <> b.io.gpuMem
    arb.io.in(1) <> link.io.mem
    link.io.in <> rx.deq
    tx.enq.valid      := link.io.out.valid
    tx.enq.bits       := Cat(link.io.outEnd, link.io.out.bits)
    link.io.out.ready := tx.enq.ready

    val ddr = Module(new GpuMemMig)
    ddr.io.gpu <> arb.io.out
    ddr.io.uiClock := mig.ui_clk
    ddr.io.uiReset := mig.ui_clk_sync_rst || resetIn(mig.ui_clk, usbLost)
    gpuMig <> ddr.io.mig

    val down = Counter(link.io.in.fire, 1 << 12)._1
    val up   = Counter(link.io.out.fire, 1 << 12)._1
    led := Cat(up(11), down(11), locked, RegNext(RegNext(mig.init_calib_complete)))
  }
}

object NexysLinkTopMain extends App {
  val targetDir = "out/nexysvideo_link/verilog"
  Emit.cleanTargetDir(targetDir)
  ChiselStage.emitSystemVerilogFile(
    gen = new NexysLinkTop,
    args = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
