// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The GPU's memory port, master side: hold [[req]] until [[ready]] pulses with the read [[data]]
  * or the write's completion. A write word is one halfword, wdata(15, 0): every memory behind the
  * port stores halfwords, so Borg sends a 32-bit word as a two-beat burst, low half first.
  */
class GpuMemIO extends Bundle {
  val addr  = Output(UInt(GpuMemIO.AddrBits.W))  // byte address
  val req   = Output(Bool())
  val data  = Input(UInt(32.W))
  val ready = Input(Bool())
  val wr    = Output(Bool())
  val wdata = Output(UInt(32.W)) // the current burst beat: a halfword in the low 16 bits
  // A burst of `wlen` words advances on each `waccept`; `ready` pulses once at its end.
  val wlen    = Output(UInt(7.W))   // burst word count (1..64)
  val waccept = Input(Bool())       // controller pulled the current word; present next
}

object GpuMemIO {

  /** Every GPU byte address; a board decodes only the memory it has. Vulkan's
    * maxStorageBufferRange (2^27) and a 4096^2 RGBA32F image need more than 25 bits.
    */
  val AddrBits = 32

  /** Shares `port` between `users`, the first first; a request keeps it until it is answered. */
  def arbitrate(users: Seq[GpuMemIO], port: GpuMemIO): Unit = {
    val want  = VecInit(users.map(m => m.req || m.wr)).asUInt
    val owner = RegInit(0.U(users.length.W))
    val grant = Mux((owner & want).orR, owner, PriorityEncoderOH(want))
    owner := Mux(port.ready, 0.U, grant)
    users.zip(grant.asBools).foreach { case (m, g) => m.data := port.data; m.ready := port.ready && g; m.waccept := port.waccept && g }
    def pick[T <: Data](f: GpuMemIO => T): T = Mux1H(grant, users.map(f))
    port.req   := pick(_.req)
    port.addr  := pick(_.addr)
    port.wr    := pick(_.wr)
    port.wdata := pick(_.wdata)
    port.wlen  := pick(_.wlen)
  }
}
