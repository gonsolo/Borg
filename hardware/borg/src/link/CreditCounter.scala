// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** Two-flop synchronizer for a single level-encoded bit crossing the link.
  *
  * The two chips are mesochronous -- same frequency, fixed board phase offset --
  * so setup and hold are met by construction and this is insurance rather than a
  * true CDC.  It is cheap, and it is what makes the credit path safe if a later
  * board ever does force a separate oscillator.
  */
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

/** Sender-side credit accounting for one A-channel direction.
  *
  * Flow control is credit-based because `Decoupled`'s combinational
  * `ready`/`valid` backpressure cannot survive an off-chip hop -- the far side's
  * `ready` is a full round trip stale by the time it arrives.  The sender may
  * launch a request only while it holds a credit; the receiver returns one when
  * it retires the packet.
  *
  * Credit returns are **toggle-encoded**, not pulsed: a one-beat pulse launched
  * at the beat rate can be missed or double-counted by a receiver sampling at the
  * core rate, whereas a level that flips once per event is unambiguous however it
  * is sampled.  The line is synchronized and XOR edge-detected on arrival, so
  * this logic runs at the core clock and needs no beat gating.
  *
  * @param depth outstanding packets permitted; also the reset credit count.
  */
class CreditCounterIO(val depth: Int) extends Bundle {

  /** A packet was handed to [[LinkTx]] this cycle -- spend a credit. */
  val consume = Input(Bool())

  /** Raw credit-return line from the far side (unsynchronized). */
  val returnPin = Input(Bool())

  /** At least one credit is in hand. */
  val available = Output(Bool())

  /** Current credit count, for assertions and debug. */
  val count = Output(UInt(log2Ceil(depth + 1).W))
}

class CreditCounter(val depth: Int) extends Module {
  require(depth >= 1)

  val io = IO(new CreditCounterIO(depth))

  val credits = RegInit(depth.U(log2Ceil(depth + 1).W))

  // Synchronize the far side's toggle, then edge-detect it.  Any transition --
  // rising or falling -- is one returned credit.
  val syncd    = Sync2(io.returnPin)
  val syncdDel = RegNext(syncd, false.B)
  val returned = syncd =/= syncdDel

  // A return and a consume can land on the same cycle; the counter must net them
  // rather than let one clobber the other.
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
