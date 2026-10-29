// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** Header flit -- the first 16 bits of every packet.
  *
  * {{{
  *   [15]    chan     0 = M (MMIO), 1 = V (VRAM/gpuMem)
  *   [14:12] opcode   TLOpcode, interpreted per direction (see TLOpcode's comment)
  *   [11:0]  payload  channel-specific, see below
  * }}}
  *
  * Payloads: M.A `{size[1:0], addr[9:0]}`; V.A `{wlenLog2[2:0], 9'b0}`, followed by the byte
  * address low half first; D zero. A log2 burst length keeps the packet length a function
  * of the header alone, so [[LinkRx]] stays a two-state FSM.
  */
class LinkHeader extends Bundle {
  val chan    = UInt(1.W)
  val opcode  = UInt(3.W)
  val payload = UInt(12.W)
}

object LinkHeader {
  def apply(chan: UInt, opcode: UInt, payload: UInt): LinkHeader = {
    val h = Wire(new LinkHeader)
    h.chan    := chan
    h.opcode  := opcode
    h.payload := payload
    h
  }

  def fromBits(bits: UInt): LinkHeader = bits.asTypeOf(new LinkHeader)

  def mmioAddr(h: LinkHeader): UInt = h.payload(9, 0)
  def mmioSize(h: LinkHeader): UInt = h.payload(11, 10)

  def vramWlenLog2(h: LinkHeader): UInt = h.payload(11, 9)

  def mmioPayload(size: UInt, addr: UInt): UInt = Cat(size(1, 0), addr(9, 0))
  def vramPayload(wlenLog2: UInt): UInt = Cat(wlenLog2(2, 0), 0.U(9.W))
}

/** One 16-bit flit; [[LinkTx]] does not interleave another channel before `last`. */
class LinkFlitStream extends Bundle {
  val flit = UInt(16.W)
  val last = Bool()
}

/** The pins for one direction. `p` is odd parity over `{d, v}`, so an unplugged or stuck-low
  * cable fails parity on every beat, idle beats included.
  */
class LinkPins(val w: Int) extends Bundle {
  val d = UInt(w.W)
  val v = Bool()
  val p = Bool()
}

object LinkFlit {

  def parity(d: UInt, v: Bool): Bool = !(Cat(d, v).xorR)

  // Narrow mode excludes the dead d[15:8], which would otherwise mask or fake lane faults.
  def parityW(d: UInt, v: Bool, narrow: Bool, p: LinkParams): Bool =
    if (!p.narrowCapable) parity(d, v)
    else Mux(narrow, parity(d(7, 0), v), parity(d, v))

  /** Flits in an FPGA→ASIC packet (M.A requests, V.D responses). */
  def flitsDn(h: LinkHeader): UInt =
    Mux(
      h.chan === LinkChan.M,
      // M.A: Get is header-only; a write appends two data flits.
      Mux(h.opcode === TLOpcode.Get, 1.U, 3.U),
      // V.D: read data appends two flits, a burst-write ack is header-only.
      Mux(h.opcode === TLOpcode.AccessAckData, 3.U, 1.U)
    )

  /** Flits in an ASIC→FPGA packet (V.A requests, M.D responses). */
  def flitsUp(h: LinkHeader): UInt =
    Mux(
      h.chan === LinkChan.V,
      // V.A: header and two address flits, then one flit per burst word on writes: a write word
      // is a halfword (GpuMemIO).
      Mux(
        h.opcode === TLOpcode.Get,
        3.U,
        3.U + (1.U << LinkHeader.vramWlenLog2(h))
      ),
      // M.D: read data appends two flits, a write ack is header-only.
      Mux(h.opcode === TLOpcode.AccessAckData, 3.U, 1.U)
    )

  def isRequestDn(h: LinkHeader): Bool = h.chan === LinkChan.M
  def isRequestUp(h: LinkHeader): Bool = h.chan === LinkChan.V
}
