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
  * Payload layouts, each exactly 12 bits:
  *
  *   - M.A: `{size[1:0], addr[9:0]}`         -- HuttBus(10) request
  *   - V.A: `{wlenLog2[2:0], addr[24:16]}`   -- gpuMem request, high address bits
  *   - D:   zero
  *
  * The burst length is encoded as a log2 so that the total packet length stays a
  * pure function of the header alone.  That is the whole reason [[LinkRx]] can be
  * a two-state FSM instead of having to decode a length that arrives mid-packet.
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

  /** Reinterpret a raw 16-bit flit as a header. */
  def fromBits(bits: UInt): LinkHeader = bits.asTypeOf(new LinkHeader)

  // -- payload accessors ----------------------------------------------------
  def mmioAddr(h: LinkHeader): UInt = h.payload(9, 0)
  def mmioSize(h: LinkHeader): UInt = h.payload(11, 10)

  def vramAddrHi(h: LinkHeader): UInt = h.payload(8, 0)   // addr[24:16]
  def vramWlenLog2(h: LinkHeader): UInt = h.payload(11, 9)

  def mmioPayload(size: UInt, addr: UInt): UInt = Cat(size(1, 0), addr(9, 0))
  def vramPayload(wlenLog2: UInt, addrHi: UInt): UInt = Cat(wlenLog2(2, 0), addrHi(8, 0))
}

/** One 16-bit flit of a packet, with an end-of-packet marker.
  *
  * Producers (the adapters) emit header-then-payload and raise `last` on the
  * final flit.  [[LinkTx]] arbitrates at packet granularity and will not
  * interleave another channel until it has seen `last`.
  */
class LinkFlitStream extends Bundle {
  val flit = UInt(16.W)
  val last = Bool()
}

/** The pins for one direction.
  *
  * `p` is *odd* parity over `{d, v}`.  Odd is deliberate: an unplugged or
  * stuck-low cable presents `d=0, v=0, p=0`, which is even, so it fails parity
  * on every beat and `link_up` can never assert.  Parity is therefore checked on
  * idle beats too, not just during packets.
  */
class LinkPins(val w: Int) extends Bundle {
  val d = UInt(w.W)
  val v = Bool()
  val p = Bool()
}

object LinkFlit {

  /** Odd parity over the concatenation of `d` and `v`. */
  def parity(d: UInt, v: Bool): Bool = !(Cat(d, v).xorR)

  /** Flits in a packet travelling FPGA→ASIC, from its header alone.
    *
    * DN carries M.A (request) and V.D (response).
    */
  def flitsDn(h: LinkHeader): UInt =
    Mux(
      h.chan === LinkChan.M,
      // M.A: Get is header-only; a write appends two data flits.
      Mux(h.opcode === TLOpcode.Get, 1.U, 3.U),
      // V.D: read data appends two flits, a burst-write ack is header-only.
      Mux(h.opcode === TLOpcode.AccessAckData, 3.U, 1.U)
    )

  /** Flits in a packet travelling ASIC→FPGA, from its header alone.
    *
    * UP carries V.A (request) and M.D (response).
    */
  def flitsUp(h: LinkHeader): UInt =
    Mux(
      h.chan === LinkChan.V,
      // V.A: header + addr[15:0], then one flit per burst word on writes.
      // gpuMem.wdata only carries 16 meaningful bits, so a word is one flit.
      Mux(
        h.opcode === TLOpcode.Get,
        2.U,
        2.U + (1.U << LinkHeader.vramWlenLog2(h))
      ),
      // M.D: read data appends two flits, a write ack is header-only.
      Mux(h.opcode === TLOpcode.AccessAckData, 3.U, 1.U)
    )

  /** True when the header names an A-type (request) packet in this direction. */
  def isRequestDn(h: LinkHeader): Bool = h.chan === LinkChan.M
  def isRequestUp(h: LinkHeader): Bool = h.chan === LinkChan.V
}
