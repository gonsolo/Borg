// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgShaderDispatcherTestsC extends TestSuite {
  import BorgShaderDispatcherTestHelpers._

  val tests = Tests {

    utest.test("src_over_blends_against_the_stored_tile_colour") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: src-over against the tile buffer ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // Half-transparent red over opaque blue.
        d.io.blendCfg.get.enable.poke(true.B)
        d.io.blendCfg.get.srcColorFactor.poke(BorgBlend.SRC_ALPHA.U)
        d.io.blendCfg.get.dstColorFactor.poke(BorgBlend.ONE_MINUS_SRC_ALPHA.U)
        d.io.blendCfg.get.colorOp.poke(BorgBlend.OP_ADD.U)

        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true,
          srcRgb = (FP16_ONE, FP16_ZERO, FP16_ZERO),
          dstRgb = (FP16_ZERO, FP16_ZERO, FP16_ONE),
          fragA = Some(FP16_HALF))

        val (rf, gf, bf) = (f16ToFloat(w.r), f16ToFloat(w.g), f16ToFloat(w.b))
        println(f"  result = ($rf%.3f, $gf%.3f, $bf%.3f), expect ~(0.502, 0.0, 0.498)")
        utest.assert(w.en)
        // 128/255 and 127/255 -- the exact UNORM8 answers, checked as numbers
        // because the FP16 that carries them back is the nearest representable
        // value rather than a fixed bit pattern.
        utest.assert(math.abs(rf - 128.0f / 255.0f) < 0.01f)
        utest.assert(gf == 0.0f)
        utest.assert(math.abs(bf - 127.0f / 255.0f) < 0.01f)
        println("  PASSED")
      }
    }

    utest.test("alpha_defaults_to_opaque_when_the_shader_never_writes_r24") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: r24 defaults to 1.0 ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.blendCfg.get.enable.poke(true.B)
        d.io.blendCfg.get.srcColorFactor.poke(BorgBlend.SRC_ALPHA.U)
        d.io.blendCfg.get.dstColorFactor.poke(BorgBlend.ONE_MINUS_SRC_ALPHA.U)
        d.io.blendCfg.get.colorOp.poke(BorgBlend.OP_ADD.U)

        // No fragA: an existing three-component shader must still render
        // opaquely under src-over, i.e. the destination must vanish.
        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true,
          srcRgb = (FP16_ONE, FP16_ZERO, FP16_ZERO),
          dstRgb = (FP16_ZERO, FP16_ZERO, FP16_ONE),
          fragA = None)

        val (rf, bf) = (f16ToFloat(w.r), f16ToFloat(w.b))
        println(f"  result r=$rf%.3f b=$bf%.3f, expect ~(1.0, 0.0)")
        utest.assert(math.abs(rf - 1.0f) < 0.01f)
        utest.assert(bf == 0.0f)
        println("  PASSED")
      }
    }

    utest.test("r24_alpha_reaches_the_blend_unit_and_changes_the_result") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: r24 alpha sweep ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.blendCfg.get.enable.poke(true.B)
        d.io.blendCfg.get.srcColorFactor.poke(BorgBlend.SRC_ALPHA.U)
        d.io.blendCfg.get.dstColorFactor.poke(BorgBlend.ONE_MINUS_SRC_ALPHA.U)
        d.io.blendCfg.get.colorOp.poke(BorgBlend.OP_ADD.U)

        // Alpha 0 -> pure destination, 1 -> pure source; monotone in between.
        // Also proves the per-quad re-arm works: each call re-runs pixelReady,
        // so a leaked previous alpha would show up as a wrong first result.
        for ((aBits, aName, expR) <- Seq(
              (FP16_ZERO, "0.0", 0.0f), (FP16_HALF, "0.5", 128.0f / 255.0f),
              (FP16_ONE, "1.0", 1.0f))) {
          val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
            compareOp = CMP_LESS, writeEn = true,
            srcRgb = (FP16_ONE, FP16_ZERO, FP16_ZERO),
            dstRgb = (FP16_ZERO, FP16_ZERO, FP16_ONE),
            fragA = Some(aBits))
          val rf = f16ToFloat(w.r)
          println(f"  alpha=$aName%-4s -> r=$rf%.3f (expect $expR%.3f)")
          utest.assert(math.abs(rf - expR) < 0.01f)
        }
        println("  PASSED")
      }
    }

    utest.test("blending_does_not_bypass_the_depth_test") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: blend + failing depth test ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.blendCfg.get.enable.poke(true.B)
        d.io.blendCfg.get.srcColorFactor.poke(BorgBlend.ONE.U)
        d.io.blendCfg.get.dstColorFactor.poke(BorgBlend.ONE.U)
        d.io.blendCfg.get.colorOp.poke(BorgBlend.OP_ADD.U)

        // Blending is a write-path transform, not a write-enable: a fragment
        // behind the stored depth must still be rejected outright.
        val w = runPixelFull(d, fragZ = 0x4000, oldZ = 0x3000,
          compareOp = CMP_LESS, writeEn = true,
          srcRgb = (FP16_ONE, FP16_ONE, FP16_ONE),
          dstRgb = (FP16_ONE, FP16_ONE, FP16_ONE),
          fragA = Some(FP16_ONE))
        println(f"  occluded fragment: tileWrite.en=${w.en} (expect false)")
        utest.assert(!w.en)
        println("  PASSED")
      }
    }

    utest.test("stencil_disabled_leaves_the_plane_untouched") {
      simulate(new BorgShaderDispatcher(STENCIL)) { d =>
        println("\n--- BorgShaderDispatcher: stencil disabled ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // A NEVER test with a ZERO fail op: if the enable gate leaked, both
        // the fragment and the stored value would visibly change.
        pokeFrontFace(d, CMP_NEVER, BorgStencil.ZERO, BorgStencil.ZERO,
                      BorgStencil.ZERO, reference = 0)
        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true, storedStencil = 0x42)
        println(f"  en=${w.en} stencil=0x${w.stencil.toHexString} (expect true, 0x42)")
        utest.assert(w.en)
        utest.assert(w.stencil == 0x42)
        println("  PASSED")
      }
    }

    utest.test("failing_stencil_kills_the_fragment_but_still_writes_the_plane") {
      simulate(new BorgShaderDispatcher(STENCIL)) { d =>
        println("\n--- BorgShaderDispatcher: stencil fail arm ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.stencilCfg.get.enable.poke(true.B)
        // EQUAL against reference 0x55 with 0x11 stored -> the test fails, so
        // failOp (INCREMENT) runs and the colour write is suppressed. This is
        // the arm an implementation that gates stencil writes on tileWrite.en
        // gets wrong.
        pokeFrontFace(d, CMP_EQUAL, BorgStencil.INCREMENT_AND_CLAMP,
                      BorgStencil.ZERO, BorgStencil.ZERO, reference = 0x55)
        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true, storedStencil = 0x11)
        println(f"  en=${w.en} stencilEn=${w.stencilEn} stencil=0x${w.stencil.toHexString}")
        utest.assert(!w.en)          // fragment killed
        utest.assert(w.stencilEn)    // plane still written
        utest.assert(w.stencil == 0x12)
        println("  PASSED")
      }
    }

    utest.test("depth_fail_arm_runs_depthFailOp_not_passOp") {
      simulate(new BorgShaderDispatcher(STENCIL)) { d =>
        println("\n--- BorgShaderDispatcher: depthFail arm ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.stencilCfg.get.enable.poke(true.B)
        // Stencil passes (ALWAYS) but the fragment is behind the stored depth,
        // so depthFailOp (REPLACE with 0x77) must run rather than passOp.
        pokeFrontFace(d, CMP_ALWAYS, BorgStencil.ZERO, BorgStencil.INVERT,
                      BorgStencil.REPLACE, reference = 0x77)
        val w = runPixelFull(d, fragZ = 0x4000, oldZ = 0x3000,
          compareOp = CMP_LESS, writeEn = true, storedStencil = 0x11)
        println(f"  en=${w.en} stencil=0x${w.stencil.toHexString} (expect false, 0x77)")
        utest.assert(!w.en)
        utest.assert(w.stencilEn)
        utest.assert(w.stencil == 0x77)
        println("  PASSED")
      }
    }

    utest.test("both_pass_arm_writes_the_fragment_and_runs_passOp") {
      simulate(new BorgShaderDispatcher(STENCIL)) { d =>
        println("\n--- BorgShaderDispatcher: pass arm ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        d.io.stencilCfg.get.enable.poke(true.B)
        pokeFrontFace(d, CMP_ALWAYS, BorgStencil.ZERO, BorgStencil.INCREMENT_AND_CLAMP,
                      BorgStencil.ZERO, reference = 0)
        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true, storedStencil = 0x11)
        println(f"  en=${w.en} stencil=0x${w.stencil.toHexString} (expect true, 0x12)")
        utest.assert(w.en)
        utest.assert(w.stencilEn)
        utest.assert(w.stencil == 0x12)
        println("  PASSED")
      }
    }

  }
}
