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
  val startDepth = Input(UInt(8.W))        // the levels a BARRIER resume starts inside, all lanes on
  val depth = Output(Vec(slots, UInt(8.W)))
  val mask  = Output(Vec(slots, UInt(lanes.W)))
  val fault = Output(Bool())                // sticky: the stack over- or underflowed
}

/** The execution mask of divergent control flow: both arms of a divergent `if` run, the lanes
  * that should not are masked. Each lane counts the levels it is off for (0: on), so EXELSE turns
  * on only the lanes this level turned off, never ones an outer `if` masked, and nesting is as deep
  * as the counter (255 with 8 bits). Each quad slot has its own counters. */
class ExecMask(lanes: Int, slots: Int = 1, bits: Int = 8) extends Module {
  val io = IO(new ExecMaskIO(lanes, slots))
  private val max    = ((1 << bits) - 1).U(bits.W)
  private val levels = RegInit(VecInit(Seq.fill(slots)(VecInit(Seq.fill(lanes)(0.U(bits.W))))))
  private val live   = RegInit(VecInit(Seq.fill(slots)(((1 << lanes) - 1).U(lanes.W))))
  private val depths = RegInit(VecInit(Seq.fill(slots)(0.U(bits.W))))   // a lane's count never exceeds it
  private val fault  = RegInit(false.B)
  io.mask := VecInit((0 until slots).map(s => VecInit(levels(s).map(_ === 0.U)).asUInt & live(s)))
  io.fault := fault
  io.depth := depths
  private val (level, depth) = (levels(io.slot), depths(io.slot))
  private val over  = io.push && depth === max
  private val under = (io.els || io.pop) && depth === 0.U
  when(over || under) { fault := true.B }
  when(io.push && !over) { depth := depth + 1.U }
  when(io.pop && !under) { depth := depth - 1.U }
  for ((l, k) <- level.zipWithIndex) {
    when(io.push && !over && (l =/= 0.U || io.cond(k) === 0.U)) { l := l + 1.U }
    when(io.els && !under) {
      when(l === 0.U) { l := 1.U }.elsewhen(l === 1.U) { l := 0.U }
    }
    when(io.pop && l =/= 0.U) { l := l - 1.U }
  }
  if (BorgDebug.trace) when(io.push || io.els || io.pop) {
    printf("[EXEC] push=%d else=%d pop=%d mask=0x%x\n", io.push, io.els, io.pop, io.mask(io.slot))
  }
  when(io.start.valid) { live(io.startSlot) := io.start.bits; levels(io.startSlot).foreach(_ := 0.U); depths(io.startSlot) := io.startDepth }
}
