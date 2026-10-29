// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** The GPU's memory port, master side: hold [[req]] until [[ready]] pulses with the read [[data]]
  * or the write's completion.
  */
class GpuMemIO extends Bundle {
  val addr  = Output(UInt(GpuMemIO.AddrBits.W))  // byte address
  val req   = Output(Bool())
  val data  = Input(UInt(32.W))
  val ready = Input(Bool())
  val wr    = Output(Bool())
  val wdata = Output(UInt(32.W)) // the current burst beat, low 16 bits
  // A burst of `wlen` words advances on each `waccept`; `ready` pulses once at its end.
  val wlen    = Output(UInt(7.W))   // burst word count (1..64)
  val waccept = Input(Bool())       // controller pulled the current word; present next
}

object GpuMemIO {

  /** Every GPU byte address; a board decodes only the memory it has. Vulkan's
    * maxStorageBufferRange (2^27) and a 4096^2 RGBA32F image need more than 25 bits.
    */
  val AddrBits = 32
}
