// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import utest._

/** Quad slots (docs/B9_pipeline.md, step 2). One slot makes the slot logic zero-width, so only
  * more slots check its widths. */
object BorgQuadSlotTests extends TestSuite {
  private def elaborates(n: Int): Boolean =
    circt.stage.ChiselStage.emitSystemVerilog(new BorgCore(BorgConfig.Test.copy(quadSlots = n))).nonEmpty

  val tests = Tests {
    utest.test("core_elaborates_2_slots") { utest.assert(elaborates(2)) }
    utest.test("core_elaborates_4_slots") { utest.assert(elaborates(4)) }
  }
}
