// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class FpuOpFlags extends Bundle {
  val fma    = Bool()
  val mul    = Bool()
  val fneg   = Bool()
  val fstep  = Bool()
  val frcp   = Bool()
  val ftex   = Bool()
  // Integer ALU ops (16-bit, operate on the raw register bits).
  val iadd   = Bool()
  val ishl   = Bool()
  val ishr   = Bool()
  val imul   = Bool()
  val i2f    = Bool()
  val f2i    = Bool()
  val frsq   = Bool()
  val funct3 = UInt(3.W)
}
