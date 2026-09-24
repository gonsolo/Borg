// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** ZTEST: early per-fragment tests (SPIR-V EarlyFragmentTests).
  *
  * The harness plays the core: it runs the rasterizer shader, writes the
  * fragment outputs, raises zTestReq mid-shader and holds the core running
  * until zTestDone, exactly as BorgCore stalls on the instruction. It also
  * plays the tile buffer, re-presenting the depth the early pass wrote when
  * the late pass reads it back.
  */
object BorgShaderDispatcherZTestTests extends TestSuite {
  import BorgShaderDispatcherTestHelpers._

  val SRC = (0x1111, 0x2222, 0x3333)
  val DST = (0x0AAA, 0x0BBB, 0x0CCC)

  /** What one sTileWrite cycle asked the tile buffer to do. */
  case class Write(en: Boolean, r: Int, g: Int, b: Int, z: Int, stencilEn: Boolean, occ: Int)

  def capture(d: BorgShaderDispatcher): Write = Write(
    d.io.tileWrite.en.peek().litToBoolean,
    d.io.tileWrite.data.r.peek().litValue.toInt,
    d.io.tileWrite.data.g.peek().litValue.toInt,
    d.io.tileWrite.data.b.peek().litValue.toInt,
    d.io.tileWrite.data.z.peek().litValue.toInt,
    d.io.stencilWriteMask.map(_.peek().litValue != 0).getOrElse(false),
    d.io.occSamples.peek().litValue.toInt)

  def writeReg(d: BorgShaderDispatcher, reg: Int, value: Int): Unit = {
    d.io.pipeWrite(0).en.poke(true.B)
    d.io.pipeWrite(0).addr.poke(reg.U)
    d.io.pipeWrite(0).data.poke(value.U)
    d.clock.step(1)
    d.io.pipeWrite(0).en.poke(false.B)
  }

  /** One pixel with ZTEST issued after r29. Returns the early write, the
    * helper flag after ZTEST, the helper flag at the end of the shader, and
    * the late write. */
  def runEarly(d: BorgShaderDispatcher, fragZ: Int, oldZ: Int,
               discardAfter: Boolean = false): (Write, Boolean, Boolean, Write) = {
    d.io.tileRead.data.foreach { s =>
      s.z.poke(oldZ.U); s.r.poke(DST._1.U); s.g.poke(DST._2.U); s.b.poke(DST._3.U)
    }
    firePixelReady(d, fragPc = 13, tileIdx = 7)
    d.io.fragPcReg.poke(13.U)
    d.io.coreStatus.autoRunPending.poke(true.B)
    d.clock.step(1)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.coreStatus.running.poke(true.B)
    pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
    d.io.coreStatus.running.poke(false.B)
    d.clock.step(1)

    // Fragment shader up to ZTEST: depth first, as the compiler must emit it.
    d.io.coreStatus.autoRunPending.poke(true.B)
    d.clock.step(1)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.coreStatus.running.poke(true.B)
    writeReg(d, 29, fragZ)
    utest.assert(!d.io.laneHelper(0).peek().litToBoolean)   // covered, not yet tested

    // ZTEST: the core stalls with zTestReq high until zTestDone.
    d.io.zTestReq.poke(true.B)
    d.clock.step(1)
    utest.assert(d.io.phase.peek().litValue.toInt == PHASE_Z_READ)
    stepThroughDepthTest(d)
    utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)
    val early = capture(d)
    utest.assert(d.io.zTestDone.peek().litToBoolean)
    d.clock.step(1)
    d.io.zTestReq.poke(false.B)
    utest.assert(d.io.phase.peek().litValue.toInt == PHASE_FRAG)
    val helperAfterTest = d.io.laneHelper(0).peek().litToBoolean

    // Rest of the shader: colour, optionally a discard.
    for ((reg, v) <- Seq((26, SRC._1), (27, SRC._2), (28, SRC._3))) writeReg(d, reg, v)
    if (discardAfter) writeReg(d, 25, 1)
    val helperAtEnd = d.io.laneHelper(0).peek().litToBoolean
    d.io.coreStatus.running.poke(false.B)
    d.clock.step(1)

    // The tile buffer now holds whatever the early pass wrote.
    if (early.en) d.io.tileRead.data.foreach(_.z.poke(early.z.U))
    utest.assert(d.io.phase.peek().litValue.toInt == PHASE_Z_READ)
    stepThroughDepthTest(d)
    val late = capture(d)
    d.clock.step(1)
    utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
    (early, helperAfterTest, helperAtEnd, late)
  }

  val tests = Tests {

    utest.test("passing_fragment_writes_depth_early_and_colour_late_without_retesting") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- ZTEST: pass ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        val (early, helper, _, late) = runEarly(d, fragZ = 0x3000, oldZ = 0x3800)
        println(s"  early: $early helper=$helper")
        println(s"  late:  $late")
        // Early: depth written, colour rewritten exactly as read.
        utest.assert(early.en && early.z == 0x3000)
        utest.assert((early.r, early.g, early.b) == DST)
        utest.assert(!helper)
        // Late: the tile now holds Z = fragZ, so a re-run LESS test would FAIL
        // (0x3000 < 0x3000). Writing anyway proves the result was taken from
        // ZTEST, not re-tested; and depth is kept, not rewritten.
        utest.assert(late.en && late.z == 0x3000)
        utest.assert((late.r, late.g, late.b) == SRC)
        // Occlusion queries count where the test ran: once, at ZTEST.
        utest.assert(early.occ == 1 && late.occ == 0)
        println("  PASSED")
      }
    }

    utest.test("failing_fragment_becomes_a_helper_and_writes_nothing") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- ZTEST: fail ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        val (early, helper, _, late) = runEarly(d, fragZ = 0x3800, oldZ = 0x3000)
        println(s"  early: $early helper=$helper late: $late")
        utest.assert(!early.en)
        utest.assert(helper)      // its STOREs are suppressed from here on
        utest.assert(!late.en)
        utest.assert(early.occ == 0 && late.occ == 0)
        println("  PASSED")
      }
    }

    utest.test("discard_after_ztest_keeps_depth_and_drops_colour") {
      // With early tests the depth write has already happened; a later
      // discard only drops the colour (and makes the lane a helper).
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- ZTEST: pass, then discard ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        val (early, helper, helperEnd, late) =
          runEarly(d, fragZ = 0x3000, oldZ = 0x3800, discardAfter = true)
        println(s"  early: $early helper=$helper/$helperEnd late: $late")
        utest.assert(early.en && early.z == 0x3000)
        utest.assert(!helper && helperEnd)
        utest.assert(!late.en)
        println("  PASSED")
      }
    }

    utest.test("stencil_is_updated_once_at_ztest_not_again_at_the_end") {
      simulate(new BorgShaderDispatcher(STENCIL)) { d =>
        println("\n--- ZTEST: stencil ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)
        d.io.stencilCfg.get.enable.poke(true.B)
        pokeFrontFace(d, CMP_ALWAYS, BorgStencil.ZERO, BorgStencil.INCREMENT_AND_CLAMP,
                      BorgStencil.ZERO, reference = 0)
        d.io.stencilRead.foreach(_.foreach(_.poke(0x11.U)))

        val (early, _, _, late) = runEarly(d, fragZ = 0x3000, oldZ = 0x3800)
        println(s"  early stencilEn=${early.stencilEn} late stencilEn=${late.stencilEn}")
        utest.assert(early.stencilEn)
        // A second update would increment the stencil twice for one fragment.
        utest.assert(!late.stencilEn)
        utest.assert(late.en)
        println("  PASSED")
      }
    }

    utest.test("ztest_outside_a_fragment_completes_at_once") {
      // An MMIO- or compute-run shader has no fragment to test: ZTEST must
      // not wedge the core, and must not start a tile access.
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- ZTEST: idle dispatcher ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)
        d.io.zTestReq.poke(true.B)
        utest.assert(d.io.zTestDone.peek().litToBoolean)
        d.clock.step(1)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        utest.assert(!d.io.tileRead.en.peek().litToBoolean)
        d.io.zTestReq.poke(false.B)
        println("  PASSED")
      }
    }

    utest.test("uncovered_lane_is_a_helper_even_without_ztest") {
      // Helper invocations (lanes of the quad with no covered sample) run for
      // derivatives only; Vulkan forbids their stores from having any effect.
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- helper lanes ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)
        firePixelReady(d, fragPc = 13, tileIdx = 7)
        d.io.fragPcReg.poke(13.U)
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_FRAG)
        utest.assert(!d.io.laneHelper(0).peek().litToBoolean)
        d.io.scissorPass(0).poke(false.B)            // scissored out
        utest.assert(d.io.laneHelper(0).peek().litToBoolean)
        d.io.scissorPass(0).poke(true.B)
        println("  PASSED")
      }
    }
    utest.test("per_sample_path_tests_every_sample_early_and_colours_them_late") {
      // The serialized per-sample write path (samples > 1 with blend or
      // stencil) -- what Wafer and the ULX3S build use. The early sub-phase
      // must walk all four samples of the lane before returning to the
      // shader, and the late pass must colour exactly the samples that
      // passed. Samples 0,1 are behind the stored depth, samples 2,3 in
      // front, so the recorded mask is visibly per sample.
      simulate(new BorgShaderDispatcher(MSAA_BLEND)) { d =>
        println("\n--- ZTEST: 4x per-sample ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)
        pokeCovDelta(d, 0, 0)                           // every sample covered
        val oldZ = Seq(0x2000, 0x2000, 0x3800, 0x3800)  // fragment Z is 0x3000
        d.io.tileRead.data.zipWithIndex.foreach { case (s, i) =>
          s.z.poke(oldZ(i).U); s.r.poke(DST._1.U); s.g.poke(DST._2.U); s.b.poke(DST._3.U)
        }
        firePixelReady(d, fragPc = 13, tileIdx = 7)
        d.io.fragPcReg.poke(13.U)
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        writeReg(d, 29, 0x3000)

        d.io.zTestReq.poke(true.B)
        d.clock.step(1)
        stepThroughDepthTest(d)
        var earlyOcc = 0
        val earlyEn = (0 until 4).map { s =>
          utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)
          val en = d.io.tileWrite.en.peek().litToBoolean
          earlyOcc += d.io.occSamples.peek().litValue.toInt
          utest.assert(d.io.zTestDone.peek().litToBoolean == (s == 3))
          d.clock.step(1)
          en
        }
        d.io.zTestReq.poke(false.B)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_FRAG)
        utest.assert(!d.io.laneHelper(0).peek().litToBoolean)   // two samples passed

        for ((reg, v) <- Seq((26, SRC._1), (27, SRC._2), (28, SRC._3))) writeReg(d, reg, v)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)
        // The passing samples now hold Z = 0x3000; a re-test with LESS would fail them.
        d.io.tileRead.data.zipWithIndex.foreach { case (s, i) =>
          s.z.poke((if (i >= 2) 0x3000 else oldZ(i)).U)
        }
        stepThroughDepthTest(d)
        var lateOcc = 0
        val lateEn = (0 until 4).map { _ =>
          val en = d.io.tileWrite.en.peek().litToBoolean
          lateOcc += d.io.occSamples.peek().litValue.toInt
          d.clock.step(1)
          en
        }
        println(s"  early writes per sample: $earlyEn, late: $lateEn (expect F,F,T,T both)")
        utest.assert(earlyEn == Seq(false, false, true, true))
        utest.assert(lateEn == Seq(false, false, true, true))
        println(s"  occlusion samples counted: early $earlyOcc (expect 2), late $lateOcc (expect 0)")
        utest.assert(earlyOcc == 2 && lateOcc == 0)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        println("  PASSED")
      }
    }
    utest.test("late_test_counts_each_passing_sample_once") {
      // Without ZTEST the tests run at the end of the shader, and that is
      // where samples are counted: 1 for a passing fragment, 0 for a failing
      // one, and 0 on every cycle that is not the test.
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- occlusion: late test ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)
        var nonTestCycles = 0
        def run(fragZ: Int, oldZ: Int): Int = {
          d.io.tileRead.data.foreach(_.z.poke(oldZ.U))
          firePixelReady(d, fragPc = 13, tileIdx = 7)
          d.io.fragPcReg.poke(13.U)
          d.io.coreStatus.autoRunPending.poke(true.B); d.clock.step(1)
          d.io.coreStatus.autoRunPending.poke(false.B); d.io.coreStatus.running.poke(true.B)
          pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
          d.io.coreStatus.running.poke(false.B); d.clock.step(1)
          d.io.coreStatus.autoRunPending.poke(true.B); d.clock.step(1)
          d.io.coreStatus.autoRunPending.poke(false.B); d.io.coreStatus.running.poke(true.B)
          writeReg(d, 29, fragZ)
          if (d.io.occSamples.peek().litValue != 0) nonTestCycles += 1
          d.io.coreStatus.running.poke(false.B); d.clock.step(1)
          stepThroughDepthTest(d)
          val occ = d.io.occSamples.peek().litValue.toInt
          d.clock.step(1)
          if (d.io.occSamples.peek().litValue != 0) nonTestCycles += 1
          occ
        }
        val pass = run(0x3000, 0x3800)
        val fail = run(0x3800, 0x3000)
        println(s"  pass counts $pass (expect 1), fail counts $fail (expect 0), stray counts $nonTestCycles (expect 0)")
        utest.assert(pass == 1 && fail == 0 && nonTestCycles == 0)
        println("  PASSED")
      }
    }
  }
}
