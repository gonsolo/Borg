// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import utest._

/** Elaboration-only checks for the FP32 datapath (`feat/fp32-datapath`
  * branch plan, `fp = FloatConfig.FP32`).
  *
  * Same rationale as `BorgTilePathElabTests`: `emitSystemVerilog` catches
  * the unconnected-port / width-mismatch class of bug -- exactly the shape
  * of the covDeltaOut / uniformWrite / DMA bugs this plan item fixed -- in
  * seconds, without paying for a full-pipeline simulation. Before this file,
  * FP32 had never been elaborated at the top-level `Borg` module at all;
  * only individual submodules (BorgCore, BorgFp16Fma) were covered by
  * `fp32_tests` in `BorgTests.scala`.
  *
  * Built on BorgConfig.Test rather than Default/Fp32 for its maxBinTiles=64:
  * these elaborate the whole design through firtool, and at 4096 tiles
  * BorgTileSequencer's dirty-bit registers are 94% of the emitted Verilog.
  */
object BorgFp32ElabTests extends TestSuite {

  private val Fp32 = BorgConfig.Test.copy(fp = FloatConfig.FP32)

  private def elaborate(cfg: BorgConfig): String =
    circt.stage.ChiselStage.emitSystemVerilog(new Borg(cfg))

  val tests = Tests {

    utest.test("Borg elaborates at FP32") {
      val chirrtl = elaborate(Fp32)
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(FP32) elaborated cleanly")
    }

    // The combination the plan's covDelta fix specifically targeted: FP32
    // widens covDeltaOut's element width (BorgGeometrySequencerIO) and
    // samples>1 is what makes BorgSequencer actually instantiate and read
    // it (see covDeltaOut's `if (cfg.samples > 1)` guard) -- elaborating
    // each alone does not exercise the same wiring this does together.
    utest.test("Borg elaborates at FP32 with samples=4 (covDelta width path)") {
      val chirrtl = elaborate(Fp32.copy(samples = 4))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(FP32, samples=4) elaborated cleanly")
    }

    // FP32 combined with the other Step 50 fixed-function features this
    // session landed, all at once -- the realistic "everything on" build
    // this branch is working toward, not just FP32 in isolation.
    utest.test("Borg elaborates at FP32 with blend+stencil+depthFlush") {
      val chirrtl = elaborate(Fp32.copy(
        hasBlend = true, hasStencil = true, hasDepthFlush = true))
      utest.assert(chirrtl.nonEmpty)
      println("  Borg(FP32, hasBlend/hasStencil/hasDepthFlush) elaborated cleanly")
    }
  }
}
