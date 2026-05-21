// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** Access size encoding shared by data and peripheral buses.
  *  0 = byte, 1 = halfword, 2 = word.  Size 3 reserved.
  */
object HuttSize {
  val Byte = 0.U(2.W)
  val Half = 1.U(2.W)
  val Word = 2.U(2.W)
}

/** Read/write request payload.
  *
  * @param addrWidth byte-address width (24 for memory, 28 for peripherals).
  */
class HuttBusReq(val addrWidth: Int) extends Bundle {
  val addr  = UInt(addrWidth.W)
  val write = Bool()
  val size  = UInt(2.W)
  val data  = UInt(32.W)
}

/** Single-outstanding Decoupled req/resp bus.
  *
  * Producer drives `req` (Decoupled out).  Consumer drives `resp` (Decoupled in
  * from this side, out from the consumer).  Reads complete when `resp.fire`;
  * writes complete the same way (data field on resp is don't-care).
  */
class HuttBus(val addrWidth: Int) extends Bundle {
  val req  = Decoupled(new HuttBusReq(addrWidth))
  val resp = Flipped(Decoupled(UInt(32.W)))
}

/** Instruction fetch bus — read-only, word-aligned.
  *
  * `req` carries a word address (`addrWidth` bits, units of 4 bytes).  `resp`
  * returns the 32-bit instruction word.  Branch redirect is expressed by the
  * CPU dropping the in-flight response (asserting a new `req` with a different
  * address); the memory controller must accept the new request even if the
  * previous response has not been consumed.
  *
  * @param addrWidth word-address width.  ULX3S SDRAM uses 23 (32 MB / 4 B).
  */
class HuttInstrBus(val addrWidth: Int) extends Bundle {
  val req  = Decoupled(UInt(addrWidth.W))
  val resp = Flipped(Decoupled(UInt(32.W)))
}
