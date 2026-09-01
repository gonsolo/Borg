// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._
import borg.GpuMemIO
import hutt.HuttBus

class LinkLoopbackHarnessIO(val p: LinkParams) extends Bundle {
  val linkFast = Input(Bool())
  val linkUp   = Output(Bool())
  val linkErr  = Output(Bool())

  /** FPGA side: drive this like Hutt/the SoC would. */
  val socMmio = Flipped(new HuttBus(10))

  /** FPGA side: respond to this like MemoryController would. */
  val memGpu = new GpuMemIO

  /** ASIC side: respond to this like Borg's mmio port would. */
  val borgMmio = new HuttBus(10)

  /** ASIC side: drive this like Borg's gpuMem master would. */
  val borgGpu = Flipped(new GpuMemIO)
}

/** [[BorgLinkMaster]] and [[BorgLinkSlave]] wired pin-to-pin.
  *
  * This is rung A in miniature: no Borg, no SoC, just the two adapters with all
  * four endpoints brought out so a test can impersonate the SoC, Borg, and SDRAM
  * and check the protocol end to end -- including the interface hazards that the
  * adapters exist to absorb.
  */
class LinkLoopbackHarness(val p: LinkParams) extends Module {
  val io = IO(new LinkLoopbackHarnessIO(p))

  val master = Module(new BorgLinkMaster(p))
  val slave  = Module(new BorgLinkSlave(p))

  // Pins, both directions, plus the credit return lines.
  slave.io.dnPins  := master.io.dnPins
  master.io.upPins := slave.io.upPins
  master.io.dnCred := slave.io.dnCred
  slave.io.upCred  := master.io.upCred

  // The ASIC reports phase lock on a pin; the master reads it back.
  master.io.farLinkUp := slave.io.linkUp

  master.io.linkFast := io.linkFast
  slave.io.linkFast  := io.linkFast

  io.linkUp  := master.io.linkUp
  io.linkErr := master.io.linkErr || slave.io.linkErr

  master.io.mmio   <> io.socMmio
  master.io.gpuMem <> io.memGpu
  slave.io.mmio    <> io.borgMmio
  slave.io.gpuMem  <> io.borgGpu
}
