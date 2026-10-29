// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class ExecMaskIO(lanes: Int, slots: Int) extends Bundle {
  val slot  = Input(UInt(log2Ceil(slots).W)) // the quad slot push, else and pop act on
  val push  = Input(Bool())                 // EXPUSH: the lanes whose cond is nonzero
  val els   = Input(Bool())                 // EXELSE
  val pop   = Input(Bool())                 // EXPOP
  val cond  = Input(Vec(lanes, UInt(32.W)))
  val start = Input(Valid(UInt(lanes.W)))   // a new invocation and its lanes
  val startSlot = Input(UInt(log2Ceil(slots).W))
  val mask  = Output(Vec(slots, UInt(lanes.W)))
  val fault = Output(Bool())                // sticky: the stack over- or underflowed
}

/** The execution mask of divergent control flow: both arms of a divergent `if` run, the lanes
  * that should not are masked. The stack holds the enclosing mask, so EXELSE gives
  * `enclosing & ~mask`, never a plain inversion that would wake lanes an outer `if` masked.
  * Each quad slot has its own mask and stack. */
class ExecMask(lanes: Int, slots: Int = 1, depth: Int = 8) extends Module {
  val io = IO(new ExecMaskIO(lanes, slots))
  private val all    = ((1 << lanes) - 1).U(lanes.W)
  private val masks  = RegInit(VecInit(Seq.fill(slots)(all)))
  private val stacks = Reg(Vec(slots, Vec(depth, UInt(lanes.W))))
  private val sps    = RegInit(VecInit(Seq.fill(slots)(0.U(log2Ceil(depth + 1).W))))
  private val fault  = RegInit(false.B)
  io.mask := masks; io.fault := fault
  private val (mask, stack, sp) = (masks(io.slot), stacks(io.slot), sps(io.slot))

  private val cond   = VecInit(io.cond.map(_ =/= 0.U)).asUInt
  // sp is one bit wider than the stack's index: slice, or the index logic doubles.
  private val spIdx  = sp(log2Ceil(depth) - 1, 0)
  private val spPrev = (sp - 1.U)(log2Ceil(depth) - 1, 0)
  when(io.push) {
    when(sp === depth.U) { fault := true.B }
      .otherwise { stack(spIdx) := mask; sp := sp + 1.U; mask := mask & cond }
  }
  when(io.els) {
    when(sp === 0.U) { fault := true.B }
      .otherwise { mask := stack(spPrev) & (~mask).asUInt }
  }
  when(io.pop) {
    when(sp === 0.U) { fault := true.B; mask := all }
      .otherwise { sp := sp - 1.U; mask := stack(spPrev) }
  }
  if (BorgDebug.trace) when(io.push || io.els || io.pop) {
    printf("[EXEC] push=%d else=%d pop=%d cond=0x%x mask=0x%x sp=%d\n", io.push, io.els, io.pop, cond, mask, sp)
  }
  when(io.start.valid) { masks(io.startSlot) := io.start.bits; sps(io.startSlot) := 0.U }
}
