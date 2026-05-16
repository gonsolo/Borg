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
  *   val gpuMem = new GpuMemIO
  *   // MemoryController IO bundle (slave):
  *   val gpuMem = Flipped(new GpuMemIO)
  * }}}
  */
class GpuMemIO extends Bundle {
  val addr  = Output(UInt(25.W))  // 25-bit: 32 MB SDRAM address space
  val req   = Output(Bool())
  val data  = Input(UInt(32.W))
  val ready = Input(Bool())
  // Step 25.2: GPU write path
  val wr    = Output(Bool())      // assert to start a 4-byte QSPI write
  val wdata = Output(UInt(32.W)) // data word to write
}
