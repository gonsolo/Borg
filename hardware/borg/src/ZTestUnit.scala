// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class ZTestUnitIO(cfg: BorgConfig) extends CoreUnitIO(cfg) {
  val req  = Output(Bool())
  val done = Input(Bool())
}

/** ZTEST: the early per-fragment tests. The dispatcher owns the tile buffer, tests the whole quad
  * and answers once; ZTEST writes no register. */
class ZTestUnit(cfg: BorgConfig) extends Module {
  val io = IO(new ZTestUnitIO(cfg))
  io.idle()
  private val isZTest = RegInit(false.B)
  when(io.issue.valid) { isZTest := RvInt(io.issue.bits.word).ztest }
  io.req := io.atOps && isZTest
  when(io.req) {
    io.hold := true.B
    when(io.done) { isZTest := false.B; io.finish := true.B }
  }
}
