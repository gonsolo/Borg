// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import borg.{Borg, BorgConfig}
import soc.{Emit, GpuMemArbiter, GpuMemMig, HostLink, HuttBusSplit}

/** Borg on the Nexys Video as the host's device (`borg_ftdi`, BORG_HW_RAW): the direct simulator's
  * protocol over the FT2232H's synchronous FIFO, memory on the DDR3, the presented picture on HDMI.
  * No CPU. Register offsets 0x400 and up reach LiteDRAM's calibration registers.
  */
class NexysLinkTop extends RawModule with NexysBoard {
  val borgReset = resetIn(clkGen.io.clk25, !locked || usbLost)

  rx.deqClock := clkGen.io.clk25; rx.deqReset := borgReset
  tx.enqClock := clkGen.io.clk25; tx.enqReset := borgReset
  withClockAndReset(clkGen.io.clk25, borgReset) {
    val b    = Module(new Borg(BorgConfig.Default))
    val link = Module(new HostLink(11))
    val arb  = Module(new GpuMemArbiter(2))
    val split = Module(new HuttBusSplit(11, 10))
    split.io.in <> link.io.mmio
    b.io.mmio <> split.io.lo
    dramWindow().io.bus <> split.io.hi
    arb.io.in(0) <> b.io.gpuMem
    arb.io.in(1) <> link.io.mem
    link.io.written.valid     := b.io.gpuMem.wr && b.io.gpuMem.ready
    link.io.written.bits.addr := b.io.gpuMem.addr
    link.io.written.bits.wlen := b.io.gpuMem.wlen
    link.io.in <> rx.deq
    tx.enq.valid      := link.io.out.valid
    tx.enq.bits       := Cat(link.io.outEnd, link.io.out.bits)
    link.io.out.ready := tx.enq.ready

    val ddr = Module(new GpuMemMig)
    ddr.io.gpu <> arb.io.out
    ddr.io.uiClock := dram.user_clk
    ddr.io.uiReset := dram.user_rst || resetIn(dram.user_clk, usbLost)
    gpuMig <> ddr.io.mig

    val down = Counter(link.io.in.fire, 1 << 12)._1
    val up   = Counter(link.io.out.fire, 1 << 12)._1
    led := Cat(up(11), down(11), locked, RegNext(RegNext(dram.init_done)))
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
