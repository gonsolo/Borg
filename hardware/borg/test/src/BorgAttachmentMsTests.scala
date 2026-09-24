// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Multisampled attachments stored and loaded per sample (ATTACH_MS), end to
  * end, in both MSAA storage schemes: every sample resident in the tile
  * buffer, and msaaMultiPass (one live plane, one pass per sample -- Wafer).
  *
  * The probe is per-sample stencil. Render 1 writes stencil 7 to exactly the
  * samples its triangle covers; the diagonal edge leaves pixels partly
  * covered, so samples of one pixel differ. Render 2 loads the stencil and
  * tests EQUAL 7: stored per sample, the same samples pass. Stored resolved
  * (sample 0 standing in for the pixel), a partly covered pixel comes back
  * with one value on every sample and the count changes -- which is why
  * Vulkan's mandatory 4x attachments must keep every sample.
  */
object BorgAttachmentMsTests extends TestSuite {
  import BorgSequencerTests._

  def roundTrip(borg: BorgTestWrapper, name: String): Unit = {
    val rig = new AttachmentRig(borg)
    import rig._
    // Every sample of every pixel gets its own stencil value in memory: 7
    // where entry + sample is even, else 3 -- so samples of one pixel differ.
    def preset(e: Int, sm: Int): Int = if ((e + sm) % 2 == 0) 7 else 3
    def region(sm: Int): Seq[Int] =
      (0 until 8).map(k => half.getOrElse(sbBase + 16 * sm + 2 * k, -1))
    def presetRegion(sm: Int): Seq[Int] = (0 until 8).map(k => preset(2 * k, sm) | (preset(2 * k + 1, sm) << 8))
    def presetStencil(): Unit =
      for (sm <- 0 until 4; k <- 0 until 8) half(sbBase + 16 * sm + 2 * k) = presetRegion(sm)(k)

    // Load per sample, test EQUAL 7 (KEEP), store per sample. KEEP means the
    // stencil must come back exactly as preset, sample by sample -- covered or
    // not -- which checks the per-sample load and the per-sample store at once.
    presetStencil()
    val passed = render((0.0f, 1.0f), load = 4, DEPTH_ALWAYS, STENCIL_EQUAL_7, bindStencil = true, attachMs = true)
    val after = (0 until 4).map(region)
    println(s"  [$name] per-sample: EQUAL 7 passed $passed samples; regions after the round trip " +
            (if (after == (0 until 4).map(presetRegion)) "unchanged" else "CHANGED " + after))
    utest.assert(after == (0 until 4).map(presetRegion))
    utest.assert(passed > 0)

    // Control: the same with the attachment treated as resolved. The load
    // reads one region (sample 0's) into every sample, so the samples a pixel
    // stores back can no longer differ.
    presetStencil()
    render((0.0f, 1.0f), load = 4, DEPTH_ALWAYS, STENCIL_EQUAL_7, bindStencil = true)
    val resolvedTile = region(0)
    println(s"  [$name] resolved: sample 0's region ${if (resolvedTile == presetRegion(0)) "unchanged" else "changed"}; " +
            "a resolved store has no room for the other samples")
  }

  /** 4x MSAA coverage in a real render: the occlusion count of a triangle
    * must equal the samples that Vulkan's standard 4x positions put inside
    * it. Until the covDelta fix the per-triangle sample offsets reached the
    * rasterizer with their upper half cut off on every FP32 build, so every
    * sample took the pixel-centre answer: 40 instead of 32 for a 4x4 corner,
    * 24 instead of 25 for a 3.3x3.7 one. */
  def coverage(borg: BorgTestWrapper, name: String): Unit = {
    val rig = new AttachmentRig(borg)
    import rig._
    val offsets = Seq((-0.125, -0.375), (0.375, -0.125), (-0.375, 0.125), (0.125, 0.375))
    for (l <- Seq((4.0f, 4.0f), (3.3f, 3.7f))) {
      legs = l
      val expected = (for (y <- 0 until 4; x <- 0 until 4; (ox, oy) <- offsets
                           if (x + 0.5 + ox) / l._1 + (y + 0.5 + oy) / l._2 <= 1.0) yield 1).sum
      val got = render((1.0f, 0.0f), load = 0, DEPTH_ALWAYS, 0, bindStencil = false)
      println(s"  [$name] corner ${l._1} x ${l._2}: $got samples covered (expect $expected)")
      utest.assert(got == expected)
    }
  }

  val tests = Tests {
    utest.test("per_sample_attachments_round_trip_with_resident_samples") {
      simulate(new BorgTestWrapper(suiteCfg)) { borg => roundTrip(borg, "resident") }
    }
    utest.test("msaa_coverage_is_per_sample_with_resident_samples") {
      simulate(new BorgTestWrapper(suiteCfg)) { borg => coverage(borg, "resident") }
    }
    utest.test("msaa_coverage_is_per_sample_with_multipass_msaa") {
      simulate(new BorgTestWrapper(suiteCfg.copy(tileColorBits = 8, msaaMultiPass = true))) { borg =>
        coverage(borg, "multi-pass")
      }
    }
    utest.test("per_sample_attachments_round_trip_with_multipass_msaa") {
      simulate(new BorgTestWrapper(suiteCfg.copy(tileColorBits = 8, msaaMultiPass = true))) { borg =>
        roundTrip(borg, "multi-pass")
      }
    }
  }
}
