// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import hutt.HuttBus

/** A Wishbone classic master, 32-bit data on word addresses. */
class WishboneMaster extends Bundle {
  val adr  = Output(UInt(30.W))
  val datW = Output(UInt(32.W))
  val datR = Input(UInt(32.W))
  val sel  = Output(UInt(4.W))
  val cyc  = Output(Bool())
  val stb  = Output(Bool())
  val we   = Output(Bool())
  val ack  = Input(Bool())
}

class HuttWishboneIO(addrWidth: Int) extends Bundle {
  val bus     = Flipped(new HuttBus(addrWidth))
  val wbClock = Input(Clock())
  val wbReset = Input(Bool())
  val wb      = new WishboneMaster
}

/** [[HuttBus]] word accesses onto a Wishbone bus on `wbClock`, one at a time: the request crosses
  * by a toggle through two flops while its fields stand still, the answer comes back the same way.
  * `adr` is the bus address in words.
  */
class HuttWishbone(addrWidth: Int) extends Module {
  val io = IO(new HuttWishboneIO(addrWidth))

  val busy  = RegInit(false.B)
  val req   = Reg(new hutt.HuttBusReq(addrWidth))
  val reqT  = RegInit(false.B)
  val doneT = Wire(Bool())
  val datR  = Wire(UInt(32.W))
  val seen  = RegInit(false.B)
  val back  = ShiftRegister(doneT, 2, false.B, true.B)
  io.bus.req.ready  := !busy
  io.bus.resp.valid := busy && back =/= seen
  io.bus.resp.bits  := datR
  when(io.bus.req.fire) { req := io.bus.req.bits; reqT := !reqT; busy := true.B }
  when(io.bus.resp.fire) { seen := back; busy := false.B }

  withClockAndReset(io.wbClock, io.wbReset) {
    val r     = ShiftRegister(reqT, 2, false.B, true.B)
    val taken = RegInit(false.B)
    val cyc   = RegInit(false.B)
    val dT    = RegInit(false.B)
    val d     = Reg(UInt(32.W))
    doneT := dT
    datR  := d
    io.wb.adr  := req.addr >> 2
    io.wb.datW := req.data
    io.wb.sel  := "hf".U
    io.wb.we   := req.write
    io.wb.cyc  := cyc
    io.wb.stb  := cyc
    when(!cyc && r =/= taken) { taken := r; cyc := true.B }
    when(cyc && io.wb.ack) { cyc := false.B; d := io.wb.datR; dT := !dT }
  }
}
