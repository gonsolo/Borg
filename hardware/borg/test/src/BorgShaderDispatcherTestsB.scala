// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgShaderDispatcherTestsB extends TestSuite {
  import BorgShaderDispatcherTestHelpers._

  val tests = Tests {

    utest.test("discard_kill_flag_blocks_tile_write") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
        println("\n--- BorgShaderDispatcher: discard_kill_flag_blocks_tile_write ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // --- Part 1: inside pixel, fragment shader writes nonzero to r25 (discard) ---
        println("  Part 1: inside pixel, shader discards (writes r25=1.0)")
        firePixelReady(d, fragPc = 13, tileIdx = 5)
        d.io.fragPcReg.poke(13.U)

        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        utest.assert(d.io.insideFlag.peek().litToBoolean)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1) // -> sFrag

        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        // Write r25 (kill, nonzero) then RGBZ as usual — discard doesn't stop
        // the rest of the shader from running (matches GLSL semantics).
        for ((reg, value) <- Seq((25, 0x3C00), (26, 0x1111), (27, 0x2222), (28, 0x3333), (29, 0x4444))) {
          d.io.pipeWrite(0).en.poke(true.B)
          d.io.pipeWrite(0).addr.poke(reg.U)
          d.io.pipeWrite(0).data.poke(value.U)
          d.clock.step(1)
        }
        d.io.pipeWrite(0).en.poke(false.B)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1) // -> sZRead

        stepThroughDepthTest(d)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)

        val enDiscarded = d.io.tileWrite.en.peek().litToBoolean
        println(f"  sTileWrite after discard: tileWrite.en=$enDiscarded (expect false)")
        utest.assert(!enDiscarded)
        println("  tileWrite.en=false despite inside_flag=true ✓")

        d.clock.step(1)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)

        // --- Part 2: inside pixel, no r25 write — must NOT discard (regression) ---
        println("  Part 2: inside pixel, shader does not touch r25")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13, tileIdx = 5)
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
        for ((reg, value) <- Seq((26, 0x1111), (27, 0x2222), (28, 0x3333), (29, 0x4444))) {
          d.io.pipeWrite(0).en.poke(true.B)
          d.io.pipeWrite(0).addr.poke(reg.U)
          d.io.pipeWrite(0).data.poke(value.U)
          d.clock.step(1)
        }
        d.io.pipeWrite(0).en.poke(false.B)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)

        stepThroughDepthTest(d)
        val enNotDiscarded = d.io.tileWrite.en.peek().litToBoolean
        println(f"  sTileWrite without discard: tileWrite.en=$enNotDiscarded (expect true)")
        utest.assert(enNotDiscarded)
        println("  tileWrite.en=true when r25 untouched ✓ (regression check)")
        println("  PASSED")
      }
    }

    utest.test("discard_zero_write_to_r25_does_not_kill") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
        println("\n--- BorgShaderDispatcher: discard_zero_write_to_r25_does_not_kill ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13, tileIdx = 5)
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
        for ((reg, value) <- Seq((25, 0x0000), (26, 0x1111), (27, 0x2222), (28, 0x3333), (29, 0x4444))) {
          d.io.pipeWrite(0).en.poke(true.B)
          d.io.pipeWrite(0).addr.poke(reg.U)
          d.io.pipeWrite(0).data.poke(value.U)
          d.clock.step(1)
        }
        d.io.pipeWrite(0).en.poke(false.B)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)

        stepThroughDepthTest(d)
        val en = d.io.tileWrite.en.peek().litToBoolean
        println(f"  sTileWrite after r25=0x0000: tileWrite.en=$en (expect true)")
        utest.assert(en)
        println("  PASSED")
      }
    }

    utest.test("msaa_partial_coverage_on_edge") {
      simulate(new BorgShaderDispatcher(MSAA)) { d =>
        println("\n--- BorgShaderDispatcher: msaa_partial_coverage_on_edge ---")
        pokeIdle(d)
        // Base deltas ±0.5: thresholds become {-0.5, -0.5, +0.5, +0.5} per edge
        // (samples 0,1 shifted one way, samples 2,3 the other).
        pokeCovDelta(d, f16(0.5f), f16(0.5f))
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13, tileIdx = 0)

        // e = 0.0 for every edge: exactly on the centre.  Samples whose
        // threshold is -0.5 are covered (0 >= -0.5); samples whose threshold
        // is +0.5 are not (0 >= 0.5 is false).  So coverage must be PARTIAL —
        // impossible under a centre-only test, which would say fully inside.
        pokeAllEdges(d, f16(0.0f), f16(0.0f), f16(0.0f))

        val inside = d.io.insideFlag.peek().litToBoolean
        println(s"  e=0.0, thresholds {-0.5,-0.5,+0.5,+0.5}: insideFlag=$inside")
        utest.assert(inside)   // some samples covered → shade the pixel

        simulateShaderRun(d)   // rast → frag
        simulateShaderRun(d)   // frag → depth test
        stepThroughDepthTest(d)

        val cov = d.io.tileWrite.coverage.peek().litValue.toInt
        println(f"  tileWrite.coverage=0b${cov.toBinaryString}%4s (expect 0b0011)")
        utest.assert(cov == 0x3)  // samples 0,1 covered; 2,3 not
        utest.assert(d.io.tileWrite.en.peek().litToBoolean)
        println("  Partial coverage across a sub-pixel edge ✓")
        println("  PASSED")
      }
    }

    utest.test("msaa_fully_outside_covers_nothing") {
      simulate(new BorgShaderDispatcher(MSAA)) { d =>
        println("\n--- BorgShaderDispatcher: msaa_fully_outside_covers_nothing ---")
        pokeIdle(d)
        pokeCovDelta(d, f16(0.5f), f16(0.5f))
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13, tileIdx = 0)

        // e = -4.0: far outside, beyond every ±0.5 sample offset, so NO sample
        // can be covered and the fragment must not be shaded at all.
        pokeAllEdges(d, f16(-4.0f), f16(-4.0f), f16(-4.0f))
        val inside = d.io.insideFlag.peek().litToBoolean
        println(s"  e=-4.0 (beyond all sample offsets): insideFlag=$inside (expect false)")
        utest.assert(!inside)

        simulateShaderRun(d)
        utest.assert(!d.io.tileWrite.en.peek().litToBoolean)
        println("  No sample covered, no tile write ✓")
        println("  PASSED")
      }
    }

    utest.test("msaa_fully_inside_covers_all_samples") {
      simulate(new BorgShaderDispatcher(MSAA)) { d =>
        println("\n--- BorgShaderDispatcher: msaa_fully_inside_covers_all_samples ---")
        pokeIdle(d)
        pokeCovDelta(d, f16(0.5f), f16(0.5f))
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13, tileIdx = 0)

        // e = +4.0: deep inside, every sample covered → full mask.
        pokeAllEdges(d, f16(4.0f), f16(4.0f), f16(4.0f))
        utest.assert(d.io.insideFlag.peek().litToBoolean)

        simulateShaderRun(d)
        simulateShaderRun(d)
        stepThroughDepthTest(d)

        val cov = d.io.tileWrite.coverage.peek().litValue.toInt
        println(f"  tileWrite.coverage=0b${cov.toBinaryString}%4s (expect 0b1111)")
        utest.assert(cov == 0xF)
        println("  Interior pixel covers all 4 samples ✓")
        println("  PASSED")
      }
    }

    utest.test("all_eight_compare_ops_against_less_equal_greater") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
        println("\n--- BorgShaderDispatcher: all_eight_compare_ops ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // Positive FP16 bit patterns compare correctly as raw unsigned ints,
        // which is exactly what the hardware does -- so these three stand in
        // for newZ < oldZ, newZ == oldZ, newZ > oldZ.
        val LO = 0x3000
        val MID = 0x4000
        val HI = 0x5000

        // (op name, op value, expected pass for (LO, MID, HI) vs oldZ = MID)
        val cases = Seq(
          ("NEVER",            CMP_NEVER,            (false, false, false)),
          ("LESS",             CMP_LESS,             (true,  false, false)),
          ("EQUAL",            CMP_EQUAL,            (false, true,  false)),
          ("LESS_OR_EQUAL",    CMP_LESS_OR_EQUAL,    (true,  true,  false)),
          ("GREATER",          CMP_GREATER,          (false, false, true )),
          ("NOT_EQUAL",        CMP_NOT_EQUAL,        (true,  false, true )),
          ("GREATER_OR_EQUAL", CMP_GREATER_OR_EQUAL, (false, true,  true )),
          ("ALWAYS",           CMP_ALWAYS,           (true,  true,  true ))
        )

        for ((name, op, (expLo, expEq, expHi)) <- cases) {
          val (gotLo, _) = runPixel(d, fragZ = LO,  oldZ = MID, compareOp = op, writeEn = true)
          val (gotEq, _) = runPixel(d, fragZ = MID, oldZ = MID, compareOp = op, writeEn = true)
          val (gotHi, _) = runPixel(d, fragZ = HI,  oldZ = MID, compareOp = op, writeEn = true)
          println(f"  $name%-17s new<old=$gotLo%-5s new==old=$gotEq%-5s new>old=$gotHi%-5s")
          utest.assert(gotLo == expLo)
          utest.assert(gotEq == expEq)
          utest.assert(gotHi == expHi)
        }
        println("  All 8 VkCompareOp values behave per spec ✓")
        println("  PASSED")
      }
    }

    utest.test("depth_write_enable_gates_z_but_not_the_test") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
        println("\n--- BorgShaderDispatcher: depth_write_enable ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        val NEW = 0x3000
        val OLD = 0x4000

        // writeEn = 1: passing fragment stores the new depth (the historical,
        // and still default, behaviour).
        val (enOn, zOn) = runPixel(d, NEW, OLD, CMP_LESS, writeEn = true)
        println(f"  writeEn=1: en=$enOn z=0x${zOn.toHexString} (expect 0x${NEW.toHexString})")
        utest.assert(enOn)
        utest.assert(zOn == NEW)

        // writeEn = 0: the depth *test* still runs and the fragment's colour
        // is still written -- only the Z store is suppressed, so the tile
        // buffer must be handed back its own existing depth.  Writing NEW
        // here would be the classic depthWriteEnable bug: a read-only depth
        // pass that quietly mutates the buffer.
        val (enOff, zOff) = runPixel(d, NEW, OLD, CMP_LESS, writeEn = false)
        println(f"  writeEn=0: en=$enOff z=0x${zOff.toHexString} (expect 0x${OLD.toHexString})")
        utest.assert(enOff)
        utest.assert(zOff == OLD)

        // A failing test is still a failing test with writes disabled.
        val (enFail, _) = runPixel(d, OLD, NEW, CMP_LESS, writeEn = false)
        println(f"  writeEn=0, failing fragment: en=$enFail (expect false)")
        utest.assert(!enFail)
        println("  PASSED")
      }
    }

    utest.test("blend_disabled_writes_the_fragment_colour_bit_exactly") {
      simulate(new BorgShaderDispatcher(BLEND)) { d =>
        println("\n--- BorgShaderDispatcher: blend disabled is bit-exact ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // A hasBlend build with blending off must be indistinguishable from a
        // build compiled without it: no FP16 -> UNORM8 -> FP16 round trip, so
        // arbitrary bit patterns (not just representable colours) survive.
        val w = runPixelFull(d, fragZ = 0x3000, oldZ = 0x4000,
          compareOp = CMP_LESS, writeEn = true,
          srcRgb = (0x1111, 0x2222, 0x3333), dstRgb = (FP16_ONE, FP16_ONE, FP16_ONE))
        println(f"  wrote 0x${w.r.toHexString}/0x${w.g.toHexString}/0x${w.b.toHexString}")
        utest.assert(w.en)
        utest.assert(w.r == 0x1111 && w.g == 0x2222 && w.b == 0x3333)
        println("  PASSED")
      }
    }

  }
}
