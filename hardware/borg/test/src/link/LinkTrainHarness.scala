// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

class LinkTrainHarnessIO extends Bundle {
  val linkFast   = Input(Bool())
  val slaveReset = Input(Bool())

  val masterBeat = Output(Bool())
  val slaveBeat  = Output(Bool())
  val linkUp     = Output(Bool())
}

/** Master and slave [[BorgLinkClockGen]] wired through the training pins.
  *
  * `slaveReset` is separate from the module reset so a test can release the slave
  * late and start it in a deliberately wrong beat phase -- which is the situation
  * training exists to fix, and the one reset timing cannot be trusted to avoid on
  * real silicon.
  */
class LinkTrainHarness(val p: LinkParams) extends Module {
  val io = IO(new LinkTrainHarnessIO)

  val master = Module(new BorgLinkClockGen(p, isMaster = true))
  val slave  = withReset(io.slaveReset) { Module(new BorgLinkClockGen(p, isMaster = false)) }

  master.io.linkFast := io.linkFast
  slave.io.linkFast  := io.linkFast

  // Master reads the slave's link_up back on a pin before sending real traffic.
  master.io.farLinkUp := slave.io.linkUp
  slave.io.farLinkUp  := false.B

  // Master drives the training pattern; slave recovers phase from it.
  slave.io.rxPins  := master.io.trainPins
  master.io.rxPins := DontCare

  io.masterBeat := master.io.beatEn
  io.slaveBeat  := slave.io.beatEn
  io.linkUp     := slave.io.linkUp
}
