// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** Access size of the data and peripheral buses. */
object HuttSize {
  val Byte   = 0.U(2.W)
  val Half   = 1.U(2.W)
  val Word   = 2.U(2.W)
  val Double = 3.U(2.W)
}

/** @param addrWidth byte-address width (24 for memory, 28 for peripherals). */
class HuttBusReq(val addrWidth: Int, val dataWidth: Int = 32) extends Bundle {
  val addr  = UInt(addrWidth.W)
  val write = Bool()
  val size  = UInt(2.W)
  val data  = UInt(dataWidth.W)
}

/** Single-outstanding req/resp bus; writes also complete on `resp.fire`. */
class HuttBus(val addrWidth: Int, val dataWidth: Int = 32) extends Bundle {
  val req  = Decoupled(new HuttBusReq(addrWidth, dataWidth))
  val resp = Flipped(Decoupled(UInt(dataWidth.W)))
}

/** Instruction fetch bus on word addresses. A branch drops the in-flight response by
  * sending a new `req`, which the memory side must accept even then.
  */
class HuttInstrBus(val addrWidth: Int) extends Bundle {
  val req  = Decoupled(UInt(addrWidth.W))
  val resp = Flipped(Decoupled(UInt(32.W)))
}
