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
  }
  unit.io.log := isLog
  unit.io.in.valid := active && sent < N.U
  unit.io.in.bits  := opA(sent((log2Up(N) max 1) - 1, 0))
  when(active) {
    when(sent < N.U) { sent := sent + 1.U }
  }
  // BorgExpLog cannot stall: a result the port does not take waits in its lane's buffer.
  private val res  = Reg(Vec(N, UInt(32.W)))
  private val pend = RegInit(0.U(N.W))
  private val arrive = Mux(active && unit.io.out.valid, UIntToOH(back(log2Up(N) - 1, 0), N), 0.U)
  private val want = pend | arrive
  io.wb.zipWithIndex.foreach { case (w, i) =>
    w.en := io.wbReady && want(i) && mask(i); w.addr := rd; w.data := Mux(arrive(i), unit.io.out.bits, res(i))
  }
  for (i <- 0 until N) when(arrive(i)) { res(i) := unit.io.out.bits }
  pend := Mux(io.wbReady, 0.U, want)
  io.wbWant := want.orR
  when(active && unit.io.out.valid) { back := back + 1.U }
  private val allBack = back === N.U || (active && unit.io.out.valid && back === (N - 1).U)
  when(active && allBack && io.wbReady) {
    active := false.B; isOp := false.B
    io.finish := true.B
  }
}
