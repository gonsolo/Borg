// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Rasterizer → Core shader trigger (from rasterizer's perspective: Output).
  * Use Flipped() in BorgCoreIO.
  * Note: BorgCoreIO previously named these `triggerShader*`;
  *       BorgRasterizerIO named them `triggerCore*`. Unified here.
  */
class CoreTriggerIO extends Bundle {
  val valid = Output(Bool())
  val pc    = Output(UInt(6.W))
}
