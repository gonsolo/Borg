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
  val valid  = Output(Bool())
  val pc     = Output(UInt(6.W))
  // True when this trigger should fetch from BorgRasterRom (the baked edge-test
  // shader) instead of the writable instructionMemory.  Only the dispatcher's
  // sRast trigger ever sets this; sequencer (vert/setup) and sFrag triggers
  // always leave it false.
  val isRast = Output(Bool())
}
