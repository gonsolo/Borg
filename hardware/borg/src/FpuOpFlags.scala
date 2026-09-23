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
  val isub   = Bool()
  val iand   = Bool()
  val ior    = Bool()
  val ixor   = Bool()
  val islt   = Bool()
  val iseq   = Bool()
  val i2f    = Bool()
  val f2i    = Bool()
  val frsq   = Bool()
  val fsrgb  = Bool()
  val ddx    = Bool() // quad derivative d/dx (cross-lane: lane1 - lane0)
  val ddy    = Bool() // quad derivative d/dy (cross-lane: lane2 - lane0)
  // Memory access (LS_BASE + (rs1 << 2)). Both stall the core like FTEX; the
  // lane's own ALU write-back never fires for them, because the shared
  // memory FSM freezes busy_counter at 4 and write-back happens at 1.
  val load   = Bool()
  val store  = Bool()
  // Control flow. `branch` is brz||brnz and exists so BorgLane can suppress
  // write-back without decoding either: a branch packs its target into the
  // rd field, so an ALU write-back would clobber whatever register that
  // bit pattern happens to name.
  val brz    = Bool()
  val brnz   = Bool()
  val branch = Bool()
  // Execution-mask ops. Like `branch`, `execOp` exists so BorgLane can
  // suppress write-back for all three without decoding each: none of them
  // has a destination register.
  val expush = Bool()
  val exelse = Bool()
  val expop  = Bool()
  val execOp = Bool()
  val funct3 = UInt(3.W)
}
