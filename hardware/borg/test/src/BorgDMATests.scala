// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Standalone tests for BorgDMA's uniform-buffer write path (`dest === 1.U`),
  * the width-mismatch bug fixed for the `feat/fp32-datapath` branch plan's
  * item 2: `io.uniformWrite.data := io.gpuMem.data(cfg.totalBits - 1, 0)`
  * used to be a hardcoded `(15, 0)`, silently dropping the top half of any
  * FP32 uniform word DMA'd in from DRAM. The fix is already in place; no
  * test exercised it directly before this file.
  */
object BorgDMATests extends TestSuite {

  def driveUniformDma(dma: BorgDMA, word: BigInt): (Boolean, BigInt) = {
    dma.reset.poke(true.B)
    dma.clock.step(1)
    dma.reset.poke(false.B)
    dma.io.gpuMem.ready.poke(false.B)
    dma.io.gpuMem.data.poke(0.U)
    dma.io.uniformWritePage.poke(0.U)
    dma.io.desc.baseAddr.poke(0.U)
    dma.io.desc.length.poke(1.U)
    dma.io.desc.dest.poke(1.U) // uniform page 0
    dma.io.desc.offset.poke(0.U)
    dma.clock.step(1)

    dma.io.start.poke(true.B)
    dma.clock.step(1)
    dma.io.start.poke(false.B)

    // sRead: assert ready + present the word on the same cycle the DMA reads it.
    dma.io.gpuMem.ready.poke(true.B)
    dma.io.gpuMem.data.poke((word & 0xffffffffL).U(32.W))
    val en   = dma.io.uniformWrite.en.peek().litToBoolean
    val data = dma.io.uniformWrite.data.peek().litValue
    dma.clock.step(1)
    dma.io.gpuMem.ready.poke(false.B)
    (en, data)
  }

  val tests = Tests {

    utest.test("uniform_dma_carries_full_32_bits_at_fp32") {
      simulate(new BorgDMA(BorgConfig.Default)) { dma => // Default is FP32
        val word = BigInt("89ABCDEF", 16) // both halves set -- a 16-bit truncation would lose 0x89AB
        val (en, data) = driveUniformDma(dma, word)
        utest.assert(en)
        utest.assert(data == word)
      }
    }

    // Companion regression: FP16 still truncates to the low 16 bits, the
    // historical behaviour -- confirms the fix is config-gated by
    // cfg.totalBits, not an unconditional widen that would change FP16 too.
    utest.test("uniform_dma_truncates_to_16_bits_at_fp16") {
      // Explicitly FP16, not BorgConfig.Default: this test is about the FP16
      // half of the config gate, and Default became FP32 on 2026-09-15.
      simulate(new BorgDMA(BorgConfig.Default.copy(fp = FloatConfig.FP16))) { dma =>
        val word = BigInt("89ABCDEF", 16)
        val (en, data) = driveUniformDma(dma, word)
        utest.assert(en)
        utest.assert(data == (word & 0xffffL))
      }
    }
  }
}
