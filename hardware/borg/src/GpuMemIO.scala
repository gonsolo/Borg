// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** GPU VRAM read port — from the perspective of the bus master (GPU side).
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
  val addr  = Output(UInt(GpuMemIO.AddrBits.W))  // byte address
  val req   = Output(Bool())
  val data  = Input(UInt(32.W))
  val ready = Input(Bool())
  // Step 25.2: GPU write path
  val wr    = Output(Bool())      // assert to start a write
  val wdata = Output(UInt(32.W)) // data word to write (current burst beat, low 16 bits)
  // Burst write: a master asserts `wr` with `wlen` words; it presents the first
  // word on `wdata` and advances to the next word whenever the controller pulses
  // `waccept`.  `ready` pulses once when the whole burst is done.  wlen === 1 is a
  // plain single-word write (the existing behaviour).
  val wlen    = Output(UInt(7.W))   // burst word count (1..64)
  val waccept = Input(Bool())       // controller pulled the current word; present next
}

object GpuMemIO {

  /** Width of every GPU byte address: this bus, the base registers, the DMA,
    * the texture unit, LOAD/STORE. 32 bits, RV32's address space -- a 32-bit
    * register holds any address, and a board decodes only the memory it has.
    * Vulkan's maxStorageBufferRange (2^27) and a 4096^2 RGBA32F image
    * (256 MiB) both need more than the 25 bits of today's 32 MiB boards.
    */
  val AddrBits = 32
}
