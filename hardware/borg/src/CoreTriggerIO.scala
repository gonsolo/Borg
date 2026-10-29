// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Sequencer → core shader trigger, from the sequencer's side. */
class CoreTriggerIO extends Bundle {
  val valid  = Output(Bool())
  // Wide enough for any pc (pcBits <= 16); a BARRIER resume point may lie past IMEM.
  val pc     = Output(UInt(CoreTriggerIO.PcBits.W))
  // Fetch from BorgRasterRom; only the dispatcher's sRast trigger sets it.
  val isRast = Output(Bool())
  // Fetch from BorgSetupRom; only the draw walker sets it.
  val isSetup = Output(Bool())
}

object CoreTriggerIO {
  val PcBits = 16
}
