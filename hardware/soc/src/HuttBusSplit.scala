// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import hutt.HuttBus

class HuttBusSplitIO(addrWidth: Int, bit: Int) extends Bundle {
  val in = Flipped(new HuttBus(addrWidth))
  val lo = new HuttBus(bit)
  val hi = new HuttBus(bit)
}

/** One [[HuttBus]] onto two by address bit `bit`: `hi` takes the accesses that set it. Both see the
  * address bits below it.
  */
class HuttBusSplit(addrWidth: Int, bit: Int) extends Module {
  val io   = IO(new HuttBusSplitIO(addrWidth, bit))
  val toHi = io.in.req.bits.addr(bit)
  val hiOwns = RegInit(false.B)   // the outstanding access's side
  io.lo.req.valid := io.in.req.valid && !toHi
  io.hi.req.valid := io.in.req.valid && toHi
  for (s <- Seq(io.lo, io.hi)) {
    s.req.bits.addr  := io.in.req.bits.addr(bit - 1, 0)
    s.req.bits.write := io.in.req.bits.write
    s.req.bits.size  := io.in.req.bits.size
    s.req.bits.data  := io.in.req.bits.data
  }
  io.in.req.ready := Mux(toHi, io.hi.req.ready, io.lo.req.ready)
  when(io.in.req.fire) { hiOwns := toHi }
  io.in.resp.valid := Mux(hiOwns, io.hi.resp.valid, io.lo.resp.valid)
  io.in.resp.bits  := Mux(hiOwns, io.hi.resp.bits, io.lo.resp.bits)
  io.lo.resp.ready := io.in.resp.ready && !hiOwns
  io.hi.resp.ready := io.in.resp.ready && hiOwns
}
