// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import utest._

/** Quad slots (docs/B9_pipeline.md, step 2). One slot makes the slot logic zero-width, so only
  * more slots check its widths. */
object BorgQuadSlotTests extends TestSuite {
  private def slots(n: Int) = BorgConfig.Test.copy(quadSlots = n)
  private def elaborates(m: => chisel3.RawModule): Boolean = circt.stage.ChiselStage.emitSystemVerilog(m).nonEmpty

  val tests = Tests {
    utest.test("core_elaborates_2_slots") { utest.assert(elaborates(new BorgCore(slots(2)))) }
    utest.test("core_elaborates_4_slots") { utest.assert(elaborates(new BorgCore(slots(4)))) }
    utest.test("dispatcher_elaborates_2_slots") { utest.assert(elaborates(new BorgShaderDispatcher(slots(2)))) }
    utest.test("dispatcher_elaborates_4_slots") { utest.assert(elaborates(new BorgShaderDispatcher(slots(4)))) }
  }
}
