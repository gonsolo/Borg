// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** Two-flop synchronizer for a level crossing the link. The chips are
  * mesochronous, so this is insurance against a board with its own oscillator. */
class Sync2IO extends Bundle {
  val in  = Input(Bool())
  val out = Output(Bool())
}

class Sync2 extends Module {
  val io = IO(new Sync2IO)
  val meta = RegNext(io.in, false.B)
  io.out := RegNext(meta, false.B)
}

object Sync2 {
  def apply(in: Bool): Bool = {
    val s = Module(new Sync2)
    s.io.in := in
    s.io.out
  }
}

/** Sender-side credits for one direction: a far `ready` is a round trip stale
  * off-chip. Returns are toggle-encoded, since a beat-rate pulse can be missed
  * or double-counted at the core rate.
  *
  * @param depth outstanding packets permitted; also the reset credit count.
  */
class CreditCounterIO(val depth: Int) extends Bundle {

  val consume = Input(Bool())

  /** Unsynchronized. */
  val returnPin = Input(Bool())

  val available = Output(Bool())

  val count = Output(UInt(log2Ceil(depth + 1).W))
}

class CreditCounter(val depth: Int) extends Module {
  require(depth >= 1)

  val io = IO(new CreditCounterIO(depth))

  val credits = RegInit(depth.U(log2Ceil(depth + 1).W))

  // Either edge is one returned credit.
  val syncd    = Sync2(io.returnPin)
  val syncdDel = RegNext(syncd, false.B)
  val returned = syncd =/= syncdDel

  when(returned && !io.consume) {
    credits := credits + 1.U
  }.elsewhen(!returned && io.consume) {
    credits := credits - 1.U
  }

  io.available := credits =/= 0.U
  io.count     := credits

  assert(!(io.consume && credits === 0.U), "CreditCounter: spent a credit it did not hold")
  assert(credits <= depth.U, "CreditCounter: credit count overflowed depth")
}

/** Receiver-side credit return: flip a level once per retired packet. */
class CreditReturnIO extends Bundle {
  val retire = Input(Bool())
  val pin    = Output(Bool())
}

class CreditReturn extends Module {
  val io = IO(new CreditReturnIO)
  val level = RegInit(false.B)
  when(io.retire) { level := !level }
  io.pin := level
}
