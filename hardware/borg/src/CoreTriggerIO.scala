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
  // Wide enough for any program counter (BorgConfig.pcBits <= 16); the core
  // keeps its own width. Entry points are inside IMEM; a BARRIER resume
  // point need not be.
  val pc     = Output(UInt(CoreTriggerIO.PcBits.W))
  // True when this trigger should fetch from BorgRasterRom (the baked edge-test
  // shader) instead of the writable instructionMemory.  Only the dispatcher's
  // sRast trigger ever sets this; sequencer (vert/setup) and sFrag triggers
  // always leave it false.
  val isRast = Output(Bool())
  // True when this trigger should fetch from BorgSetupRom (the draw front
  // end's triangle setup). Only the draw walker sets it.
  val isSetup = Output(Bool())
}

object CoreTriggerIO {
  /** Width of a program counter crossing a module boundary. */
  val PcBits = 16
}
