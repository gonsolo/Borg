// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** BorgCore's execution status; Flipped on the rasterizer side. */
class CoreStatusIO extends Bundle {
  val running        = Output(Bool())
  val autoRunPending = Output(Bool())
}
