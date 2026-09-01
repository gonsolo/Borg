// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** Test harness: a [[LinkTx]] wired to a [[LinkRx]] through injectable pins.
  *
  * The injection points let a test produce the two error classes the receiver
  * must distinguish:
  *
  *   - `flipD` XORs the data lanes but leaves the transmitted parity bit alone,
  *     so parity no longer matches -- a '''parity error'''.
  *   - `forceV0` drops `valid` and '''recomputes''' parity to match, so parity is
  *     clean and only the framing is wrong -- a '''framing error'''.  Without the
  *     recompute this would land as a parity error instead, since parity covers
  *     `v`, and the test would not be exercising what it claims to.
  */
class LinkPhyHarnessIO(val p: LinkParams) extends Bundle {
  val beatEn = Input(Bool())
  val a      = Flipped(Decoupled(new LinkFlitStream))
  val d      = Flipped(Decoupled(new LinkFlitStream))

  val flipD   = Input(UInt(p.w.W))
  val forceV0 = Input(Bool())

  /** Drive all three pin groups low, modelling an unplugged or dead cable. */
  val forceDead = Input(Bool())

  val out       = Valid(new LinkRxFlit(p.maxPacketFlits))
  val hdr       = Output(new LinkHeader)
  val err       = Output(Bool())
  val errParity = Output(Bool())
  val txBusy    = Output(Bool())
}

class LinkPhyHarness(val p: LinkParams, val isDn: Boolean) extends Module {
  val io = IO(new LinkPhyHarnessIO(p))

  val tx = Module(new LinkTx(p))
  val rx = Module(new LinkRx(p, isDn))

  tx.io.beatEn := io.beatEn
  tx.io.a <> io.a
  tx.io.d <> io.d
  io.txBusy := tx.io.busy

  val wireD = tx.io.pins.d ^ io.flipD
  val wireV = tx.io.pins.v && !io.forceV0

  rx.io.beatEn := io.beatEn
  rx.io.pins.d := Mux(io.forceDead, 0.U, wireD)
  rx.io.pins.v := Mux(io.forceDead, false.B, wireV)
  rx.io.pins.p := Mux(
    io.forceDead,
    false.B, // all-zero is even parity, which is exactly the point
    Mux(io.forceV0, LinkFlit.parity(wireD, wireV), tx.io.pins.p)
  )

  io.out       := rx.io.out
  io.hdr       := rx.io.hdr
  io.err       := rx.io.err
  io.errParity := rx.io.errParity
}
