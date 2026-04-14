// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** GPU PSRAM read port — from the perspective of the bus master (GPU side).
  *
  * The GPU asserts [[req]] and presents [[addr]]; the memory controller
  * responds with [[data]] and pulses [[ready]] for one cycle when the word
  * is available.
  *
  * Use as-is at the GPU master end; use [[chisel3.util.Flipped]] at the
  * memory-controller (slave) end:
  *
  * {{{
  *   // GPU / peripherals IO bundle (master):
  *   val gpuRead = new GpuReadIO
  *   // MemoryController IO bundle (slave):
  *   val gpuRead = Flipped(new GpuReadIO)
  * }}}
  */
class GpuReadIO extends Bundle {
  val addr  = Output(UInt(16.W))  // 16-bit: 64 KB texture space
  val req   = Output(Bool())
  val data  = Input(UInt(32.W))
  val ready = Input(Bool())
}
