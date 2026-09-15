// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgShaderDispatcherTestsD extends TestSuite {
  import BorgShaderDispatcherTestHelpers._

  val tests = Tests {

    utest.test("a_discarded_fragment_performs_no_stencil_operation") {
      simulate(new BorgShaderDispatcher(STENCIL)) { d =>
        println("\n--- BorgShaderDispatcher: discard suppresses the stencil write ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.stencilCfg.get.enable.poke(true.B)
        pokeFrontFace(d, CMP_ALWAYS, BorgStencil.ZERO, BorgStencil.INCREMENT_AND_CLAMP,
                      BorgStencil.ZERO, reference = 0)
        d.io.tileRead.data.foreach(_.z.poke(0x4000.U))
        d.io.stencilRead.foreach(_.foreach(_.poke(0x11.U)))

        firePixelReady(d, fragPc = 13, tileIdx = 7)
        d.io.fragPcReg.poke(13.U)
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)

        // Fragment shader writes r25 (the discard/kill register) as well as
        // its colour: a `discard`ed fragment performs no per-fragment
        // operations at all, so the stencil buffer must not advance either.
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        for ((reg, value) <- Seq((25, 1), (26, 0x1111), (27, 0x2222), (28, 0x3333), (29, 0x3000))) {
          d.io.pipeWrite(0).en.poke(true.B)
          d.io.pipeWrite(0).addr.poke(reg.U)
          d.io.pipeWrite(0).data.poke(value.U)
          d.clock.step(1)
        }
        d.io.pipeWrite(0).en.poke(false.B)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)
        stepThroughDepthTest(d)

        val en   = d.io.tileWrite.en.peek().litToBoolean
        val sEn  = d.io.stencilWriteMask.get.peek().litValue != 0
        println(f"  discarded: tileWrite.en=$en stencilWriteEn=$sEn (expect false, false)")
        utest.assert(!en)
        utest.assert(!sEn)
        println("  PASSED")
      }
    }

    utest.test("color_write_mask_keeps_the_destination_on_masked_channels") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: colorWriteMask ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        val SRC = (0x1111, 0x2222, 0x3333)
        val DST = (0x4444, 0x5555, 0x6666)

        // Blending stays OFF: Vulkan applies the write mask regardless of
        // blendEnable, and that is the configuration the channel-isolating
        // passes it exists for actually use.
        for ((mask, name) <- Seq((0x7, "RGB"), (0x1, "R only"), (0x6, "GB"), (0x0, "none"))) {
          d.io.blendCfg.get.colorWriteMask.poke(mask.U)
          val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
            compareOp = CMP_LESS, writeEn = true, srcRgb = SRC, dstRgb = DST)
          val exp = (if ((mask & 1) != 0) SRC._1 else DST._1,
                     if ((mask & 2) != 0) SRC._2 else DST._2,
                     if ((mask & 4) != 0) SRC._3 else DST._3)
          println(f"  mask=0x$mask%x ($name%-6s) -> 0x${w.r.toHexString}/0x${w.g.toHexString}/0x${w.b.toHexString}")
          utest.assert((w.r, w.g, w.b) == exp)
          // A fully masked write still writes: the fragment passed its tests,
          // and the mask does not cancel the depth/stencil side effects.
          utest.assert(w.en)
        }
        println("  PASSED")
      }
    }

    utest.test("scissor_reject_kills_the_fragment_and_its_stencil_op") {
      simulate(new BorgShaderDispatcher(STENCIL)) { d =>
        println("\n--- BorgShaderDispatcher: scissor verdict ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.stencilCfg.get.enable.poke(true.B)
        pokeFrontFace(d, CMP_ALWAYS, BorgStencil.ZERO, BorgStencil.INCREMENT_AND_CLAMP,
                      BorgStencil.ZERO, reference = 0)

        // Passing scissor: the fragment and its stencil op both happen.
        d.io.scissorPass.foreach(_.poke(true.B))
    // Destination alpha: opaque, which is what the hardware behaved as
    // before the plane existed -- so every pre-existing blend test keeps its
    // original meaning.
    d.io.alphaRead.foreach(_.foreach(_.poke(0xFF.U)))
        val inRect = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true, storedStencil = 0x11)
        println(f"  inside scissor:  en=${inRect.en} stencilEn=${inRect.stencilEn}")
        utest.assert(inRect.en && inRect.stencilEn)

        // Scissored out: the fragment is not rasterized at all, so it performs
        // no per-fragment operations -- the stencil buffer must not advance
        // either. Getting only the colour half of this right is the easy bug.
        d.io.scissorPass.foreach(_.poke(false.B))
        val outRect = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true, storedStencil = 0x11)
        println(f"  outside scissor: en=${outRect.en} stencilEn=${outRect.stencilEn}")
        utest.assert(!outRect.en && !outRect.stencilEn)
        println("  PASSED")
      }
    }

    utest.test("dst_alpha_factors_read_the_alpha_plane_not_a_hardcoded_one") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: DST_ALPHA reads the plane ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // src = white, dst colour = black, factors DST_ALPHA / ZERO: the
        // result is exactly the destination alpha scaled into the colour, so
        // a hardwired Ad = 1.0 would give white for every stored alpha.
        d.io.blendCfg.get.enable.poke(true.B)
        d.io.blendCfg.get.srcColorFactor.poke(BorgBlend.DST_ALPHA.U)
        d.io.blendCfg.get.dstColorFactor.poke(BorgBlend.ZERO.U)
        d.io.blendCfg.get.colorOp.poke(BorgBlend.OP_ADD.U)

        for ((storedA, expected) <- Seq((0xFF, 1.0f), (128, 128.0f / 255.0f), (0, 0.0f))) {
          val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
            compareOp = CMP_LESS, writeEn = true,
            srcRgb = (FP16_ONE, FP16_ONE, FP16_ONE),
            dstRgb = (FP16_ZERO, FP16_ZERO, FP16_ZERO),
            fragA = Some(FP16_ONE), dstAlpha = storedA)
          val rf = f16ToFloat(w.r)
          println(f"  stored Ad=$storedA%3d -> r=$rf%.3f (expect $expected%.3f)")
          utest.assert(math.abs(rf - expected) < 0.01f)
        }
        println("  PASSED")
      }
    }

    utest.test("blended_alpha_is_stored_back_and_gated_by_the_mask_A_bit") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: alpha write-back ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // Alpha equation ONE/ONE_MINUS_SRC_ALPHA ADD -- the standard
        // src-over alpha accumulation. As = 0.5 (128), Ad = 128:
        // 128 + round(128 * 127/255) = 128 + 64 = 192.
        d.io.blendCfg.get.enable.poke(true.B)
        d.io.blendCfg.get.srcAlphaFactor.poke(BorgBlend.ONE.U)
        d.io.blendCfg.get.dstAlphaFactor.poke(BorgBlend.ONE_MINUS_SRC_ALPHA.U)
        d.io.blendCfg.get.alphaOp.poke(BorgBlend.OP_ADD.U)

        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true,
          fragA = Some(FP16_HALF), dstAlpha = 128)
        println(f"  As=128 Ad=128 -> stored ${w.alpha} (expect 192), mask=${w.alphaMask}")
        utest.assert(w.alpha == 192)
        utest.assert(w.alphaMask)

        // colorWriteMask's A bit suppresses the alpha store while leaving the
        // colour store alone -- the two must not share one enable.
        d.io.blendCfg.get.colorWriteMask.poke(0x7.U)
        val masked = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true,
          fragA = Some(FP16_HALF), dstAlpha = 128)
        println(f"  mask A cleared -> alphaMask=${masked.alphaMask}, colour still written=${masked.en}")
        utest.assert(!masked.alphaMask)
        utest.assert(masked.en)
        println("  PASSED")
      }
    }

    utest.test("alpha_passes_through_unblended_when_blending_is_off") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: alpha with blending off ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // enable=0: the fragment's own alpha is stored, matching how the
        // colour channels behave. Anything else would make a non-blended
        // draw's alpha depend on the (unused) blend factors.
        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true,
          fragA = Some(FP16_HALF), dstAlpha = 0x11)
        println(f"  As=0.5 -> stored ${w.alpha} (expect 128)")
        utest.assert(w.alpha == 128)
        println("  PASSED")
      }
    }

    utest.test("msaa_serialized_writes_use_each_samples_own_destination") {
      simulate(new BorgShaderDispatcher(MSAA_BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: per-sample MSAA writes ---")
        pokeIdle(d)
        pokeCovDelta(d, f16(0.5f), f16(0.5f))
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // Four DIFFERENT stored destinations, one per sample. If the hardware
        // still blended against sample 0 and broadcast, every cycle would
        // report the same colour.
        val dstR = Seq(0.0f, 0.25f, 0.5f, 1.0f)
        val dstZ = Seq(0x3000, 0x3400, 0x3800, 0x3C00)
        val dstS = Seq(0x10, 0x20, 0x30, 0x40)
        for (s <- 0 until 4) {
          d.io.tileRead.data(s).r.poke(f16(dstR(s)).U)
          d.io.tileRead.data(s).g.poke(0.U)
          d.io.tileRead.data(s).b.poke(0.U)
          d.io.tileRead.data(s).z.poke(dstZ(s).U)
          d.io.stencilRead.get(s).poke(dstS(s).U)
          d.io.alphaRead.get(s).poke(0xFF.U)
        }

        // ZERO/ONE ADD makes the result exactly the destination colour, so
        // each cycle's output names the sample it read.
        d.io.blendCfg.get.enable.poke(true.B)
        d.io.blendCfg.get.srcColorFactor.poke(BorgBlend.ZERO.U)
        d.io.blendCfg.get.dstColorFactor.poke(BorgBlend.ONE.U)
        d.io.blendCfg.get.colorOp.poke(BorgBlend.OP_ADD.U)
        // ALWAYS depth with writes OFF: the stored Z must come back untouched,
        // per sample -- the case the broadcast path could not express at all.
        d.io.depthCompareOp.poke(CMP_ALWAYS.U)
        d.io.depthWriteEn.poke(false.B)
        // Stencil INCREMENT on pass, so each sample's own stored value moves.
        d.io.stencilCfg.get.enable.poke(true.B)
        pokeFrontFace(d, CMP_ALWAYS, BorgStencil.ZERO, BorgStencil.INCREMENT_AND_CLAMP,
                      BorgStencil.ZERO, reference = 0)

        firePixelReady(d, fragPc = 13, tileIdx = 0)
        // e = +4.0: deep inside, every sample covered.
        pokeAllEdges(d, f16(4.0f), f16(4.0f), f16(4.0f))
        simulateShaderRun(d)
        simulateShaderRun(d)
        stepThroughDepthTest(d)

        for (s <- 0 until 4) {
          utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)
          val cov  = d.io.tileWrite.coverage.peek().litValue.toInt
          val r    = f16ToFloat(d.io.tileWrite.data.r.peek().litValue.toInt)
          val z    = d.io.tileWrite.data.z.peek().litValue.toInt
          val sMsk = d.io.stencilWriteMask.get.peek().litValue.toInt
          val sVal = d.io.stencilWrite.get.peek().litValue.toInt
          println(f"  sample $s: cov=0b${cov.toBinaryString}%4s r=$r%.3f z=0x${z.toHexString} " +
                  f"stencilMask=0b${sMsk.toBinaryString}%4s stencil=0x${sVal.toHexString}")
          // One-hot, and the hot bit is this sample.
          utest.assert(cov == (1 << s))
          utest.assert(sMsk == (1 << s))
          // Blended against THIS sample's destination colour.
          utest.assert(math.abs(r - dstR(s)) < 0.01f)
          // depthWriteEnable=0 preserved THIS sample's own stored Z.
          utest.assert(z == dstZ(s))
          // Stencil incremented THIS sample's own stored value.
          utest.assert(sVal == dstS(s) + 1)
          d.clock.step(1)
        }

        // Four samples done -> the lane (and, at fragLanes=1, the quad) is
        // finished and the stall is released.
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        utest.assert(!d.io.autoRunStall.peek().litToBoolean)
        println("  4 one-hot writes, each against its own sample, then idle ✓")
        println("  PASSED")
      }
    }

    utest.test("msaa_partial_coverage_only_writes_the_covered_samples") {
      simulate(new BorgShaderDispatcher(MSAA_BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: partial coverage, serialized ---")
        pokeIdle(d)
        // Same thresholds as the existing partial-coverage test: e = 0.0 with
        // deltas {-0.5,-0.5,+0.5,+0.5} covers samples 0 and 1 only.
        pokeCovDelta(d, f16(0.5f), f16(0.5f))
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.depthCompareOp.poke(CMP_ALWAYS.U)
        d.io.stencilCfg.get.enable.poke(true.B)
        pokeFrontFace(d, CMP_ALWAYS, BorgStencil.ZERO, BorgStencil.INCREMENT_AND_CLAMP,
                      BorgStencil.ZERO, reference = 0)

        firePixelReady(d, fragPc = 13, tileIdx = 0)
        pokeAllEdges(d, f16(0.0f), f16(0.0f), f16(0.0f))
        simulateShaderRun(d)
        simulateShaderRun(d)
        stepThroughDepthTest(d)

        // Serializing must not turn an uncovered sample into a written one:
        // the union of the four one-hot masks has to equal the coverage the
        // broadcast path would have produced in a single cycle.
        var union = 0
        var stencilUnion = 0
        for (_ <- 0 until 4) {
          union |= d.io.tileWrite.coverage.peek().litValue.toInt
          stencilUnion |= d.io.stencilWriteMask.get.peek().litValue.toInt
          d.clock.step(1)
        }
        println(f"  colour coverage union=0b${union.toBinaryString}%4s " +
                f"stencil union=0b${stencilUnion.toBinaryString}%4s (expect 0b0011)")
        utest.assert(union == 0x3)
        utest.assert(stencilUnion == 0x3)
        println("  PASSED")
      }
    }

  }
}
