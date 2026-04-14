// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Core execution status (from BorgCore's perspective: Output).
  * Use Flipped() in BorgRasterizerIO.
  * Note: BorgCoreIO had `running/autoRunPending`;
  *       BorgRasterizerIO had `coreRunning/coreAutoRunPending`. Unified here.
  */
class CoreStatusIO extends Bundle {
  val running        = Output(Bool())
  val autoRunPending = Output(Bool())
}
