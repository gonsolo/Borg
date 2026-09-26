// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Pass 2's setup cache must hand every triangle its OWN uniforms, on every
  * uniform-memory size.
  *
  * Two triangles, A and B, both span two tiles, so the tile-major walk loads
  * them A, B, A, B -- the interleaving the 2-entry cache exists for. A and B
  * split an 8x4 rectangle along its diagonal and cover different samples in
  * the second tile, so an A shaded with B's edge uniforms counts B's samples.
  * The occlusion window counts one triangle at a time.
  *
  * The 32-uniform build (Wafer) has ONE uniform page: its core drops the page
  * bit on read and write. A cache that still kept two tags answered A's second
  * load from page 0 -- which B's load had just overwritten.
  */
object BorgSetupCacheTests extends TestSuite {
  import BorgSequencerTests._

  def interleaved(borg: BorgTestWrapper, name: String): Unit = {
    val rig = new AttachmentRig(borg)
    import rig._
    val a = Seq((0.0, 0.0), (0.0, 4.0), (8.0, 0.0))
    val b = Seq((8.0, 4.0), (8.0, 0.0), (0.0, 4.0))   // A turned by 180 degrees
    scene = Seq(a, b)
    tilesPerRow = 2
    val offsets = Seq((-0.125, -0.375), (0.375, -0.125), (-0.375, 0.125), (0.125, 0.375))
    val samples = for (y <- 0 until 4; x <- 0 until 8; (ox, oy) <- offsets)
                  yield (x + 0.5 + ox) / 8 + (y + 0.5 + oy) / 4
    // No sample lies on the shared diagonal, so the tie rule never decides.
    val expectA = samples.count(_ < 1.0)
    val expectB = samples.count(_ > 1.0)
    occRange = (0, 1); val gotA = render((1.0f, 0.0f), load = 0, DEPTH_ALWAYS, 0, bindStencil = false)
    occRange = (1, 2); val gotB = render((1.0f, 0.0f), load = 0, DEPTH_ALWAYS, 0, bindStencil = false)
    println(s"  [$name] A: $gotA samples (expect $expectA), B: $gotB (expect $expectB)")
    utest.assert(gotA == expectA)
    utest.assert(gotB == expectB)
  }

  val tests = Tests {
    utest.test("interleaved_triangles_keep_their_setup_with_two_uniform_pages") {
      simulate(new BorgTestWrapper(suiteCfg)) { borg => interleaved(borg, "64 uniforms") }
    }
    utest.test("interleaved_triangles_keep_their_setup_with_one_uniform_page") {
      simulate(new BorgTestWrapper(suiteCfg.copy(maxUniforms = 32))) { borg =>
        interleaved(borg, "32 uniforms")
      }
    }
    utest.test("interleaved_triangles_keep_their_setup_with_one_uniform_page_multipass") {
      simulate(new BorgTestWrapper(suiteCfg.copy(maxUniforms = 32, tileColorBits = 8,
                                                 msaaMultiPass = true))) { borg =>
        interleaved(borg, "32 uniforms, multi-pass")
      }
    }
  }
}
