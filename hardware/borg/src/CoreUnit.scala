// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The instruction issuing this cycle, as a unit latches it. */
class IssuedInstr extends Bundle {
  val word = UInt(32.W)
  val regs = new RegIndices
}

/** A multi-cycle unit of the core (memory, TEX, FEXP2/FLOG2): it latches its instruction at
  * issue, starts once that instruction is at its operands, holds the pipeline while it works,
  * writes the lanes' registers, and finishes; the core then resumes past the instruction. */
class CoreUnitIO(val cfg: BorgConfig) extends Bundle {
  private val N = cfg.fragLanes
  val issue  = Input(Valid(new IssuedInstr))
  val atOps  = Input(Bool())
  val mask   = Input(UInt(N.W))
  val a      = Input(Vec(N, UInt(cfg.totalBits.W)))
  val b      = Input(Vec(N, UInt(cfg.totalBits.W)))
  val c      = Input(Vec(N, UInt(cfg.totalBits.W)))
  val hold   = Output(Bool())
  val finish = Output(Bool())
  val wb     = Vec(N, new MemWritePort(5, cfg.totalBits))

  def idle(): Unit = {
    hold := false.B; finish := false.B
    wb.foreach { w => w.en := false.B; w.addr := 0.U; w.data := 0.U }
  }
}
