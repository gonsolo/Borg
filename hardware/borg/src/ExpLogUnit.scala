// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** FEXP2 and FLOG2: the lanes through one [[BorgExpLog]], one per cycle. */
class ExpLogUnit(cfg: BorgConfig) extends Module {
  val io = IO(new CoreUnitIO(cfg))
  private val N = cfg.fragLanes
  io.idle()

  private val unit = Module(new BorgExpLog)
  private val isOp = RegInit(false.B); private val isLog = RegInit(false.B)
  private val rd = RegInit(0.U(5.W))
  when(io.issue.valid) {
    val rv = RvInt(io.issue.bits.word)
    isOp := rv.fexp2 || rv.flog2; isLog := rv.flog2; rd := io.issue.bits.regs.rd
  }
  private val active = RegInit(false.B)
  private val sent = RegInit(0.U(log2Up(N + 1).W)); private val back = RegInit(0.U(log2Up(N + 1).W))
  private val start = io.atOps && isOp && !active
  private val (opA, mask) = (RegEnable(io.a, start), RegEnable(io.mask, start))
  when(start) {
    active := true.B; sent := 0.U; back := 0.U
    io.hold := true.B
  }
  unit.io.log := isLog
  unit.io.in.valid := active && sent < N.U
  unit.io.in.bits  := opA(sent((log2Up(N) max 1) - 1, 0))
  when(active) {
    io.hold := true.B
    when(sent < N.U) { sent := sent + 1.U }
  }
  when(active && unit.io.out.valid) {
    io.wb.zipWithIndex.foreach { case (w, i) =>
      w.en := i.U === back && mask(i); w.addr := rd; w.data := unit.io.out.bits
    }
    back := back + 1.U
    when(back === (N - 1).U) {
      active := false.B; isOp := false.B
      io.finish := true.B
    }
  }
}
