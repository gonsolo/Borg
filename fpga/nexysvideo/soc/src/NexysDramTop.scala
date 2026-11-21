// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import soc.{Emit, GpuMemMig, HostLink, HuttBusSplit}

/** [[NexysLinkTop]] without Borg: the host link, LiteDRAM and HDMI only, for the DDR3 and the
  * place-and-route tools. Borg's register offsets read 0; the host calibrates and tests the memory.
  */
class NexysDramTop extends RawModule with NexysBoard {
  val linkReset = resetIn(clkGen.io.clk25, !locked || usbLost)

  rx.deqClock := clkGen.io.clk25; rx.deqReset := linkReset
  tx.enqClock := clkGen.io.clk25; tx.enqReset := linkReset
  withClockAndReset(clkGen.io.clk25, linkReset) {
    val link  = Module(new HostLink(11))
    val split = Module(new HuttBusSplit(11, 10))
    split.io.in <> link.io.mmio
    dramWindow().io.bus <> split.io.hi

    val lo   = split.io.lo
    val pend = RegInit(false.B)
    lo.req.ready  := !pend
    lo.resp.valid := pend
    lo.resp.bits  := 0.U
    when(lo.req.fire) { pend := true.B }
    when(lo.resp.fire) { pend := false.B }

    link.io.written.valid := false.B
    link.io.written.bits  := DontCare
    link.io.in <> rx.deq
    tx.enq.valid      := link.io.out.valid
    tx.enq.bits       := Cat(link.io.outEnd, link.io.out.bits)
    link.io.out.ready := tx.enq.ready

    val ddr = Module(new GpuMemMig)
    ddr.io.gpu <> link.io.mem
    ddr.io.uiClock := dram.user_clk
    ddr.io.uiReset := dram.user_rst || resetIn(dram.user_clk, usbLost)
    gpuMig <> ddr.io.mig

    led := Cat(0.U(2.W), locked, RegNext(RegNext(dram.init_done)))
  }
}

object NexysDramTopMain extends App {
  val targetDir = "out/nexysvideo_dram/verilog"
  Emit.cleanTargetDir(targetDir)
  ChiselStage.emitSystemVerilogFile(
    gen = new NexysDramTop,
    args = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
