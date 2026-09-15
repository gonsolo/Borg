// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgShaderDispatcherTestsA extends TestSuite {
  import BorgShaderDispatcherTestHelpers._

  val tests = Tests {

    utest.test("pixel_ready_triggers_rast_shader") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: pixel_ready_triggers_rast_shader ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // Before pixelReady: phase=sIdle, no trigger
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        utest.assert(!d.io.coreTrigger.valid.peek().litToBoolean)
        utest.assert(!d.io.autoRunStall.peek().litToBoolean)

        // Fire pixelReady — must see coreTrigger.valid and PC=0 in same cycle
        d.io.pixelReady.poke(true.B)
        d.io.fragPcReg.poke(13.U)
        d.clock.step(1)

        val trigValid = d.io.coreTrigger.valid.peek().litToBoolean
        val trigPc    = d.io.coreTrigger.pc.peek().litValue.toInt
        val phase     = d.io.phase.peek().litValue.toInt
        println(f"  During pixelReady: coreTrigger.valid=$trigValid, pc=$trigPc, phase=$phase")
        utest.assert(trigValid)
        utest.assert(trigPc == 0)
        utest.assert(phase == PHASE_RAST)
        utest.assert(d.io.autoRunStall.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("outside_pixel_releases_stall_without_frag") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: outside_pixel_releases_stall_without_frag ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13)
        d.io.fragPcReg.poke(13.U)

        // Snoop a negative edge (pixel is outside)
        pokeEdge(d, 0, FP16_NEG_ONE)
        utest.assert(!d.io.insideFlag.peek().litToBoolean)
        println(f"  After e0=-1.0: insideFlag=false ✓")

        // Simulate rast shader finishing
        simulateShaderRun(d)

        val stall = d.io.autoRunStall.peek().litToBoolean
        val trig  = d.io.coreTrigger.valid.peek().litToBoolean
        val phase = d.io.phase.peek().litValue.toInt
        println(f"  After rast done (outside): stall=$stall, coreTrigger.valid=$trig, phase=$phase")
        utest.assert(!stall)
        utest.assert(!trig)
        utest.assert(phase == PHASE_IDLE)
        println("  PASSED")
      }
    }

    utest.test("inside_pixel_chains_to_frag_then_tile_write") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: inside_pixel_chains_to_frag_then_tile_write ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13, tileIdx = 7)
        d.io.fragPcReg.poke(13.U)

        // Simulate rast shader — write all edges inside (+1.0)
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        utest.assert(d.io.insideFlag.peek().litToBoolean)
        // core_just_finished fires combinationally when running goes low;
        // sample coreTrigger in the SAME cycle before clocking forward.
        d.io.coreStatus.running.poke(false.B)

        val trigValid = d.io.coreTrigger.valid.peek().litToBoolean
        val trigPc    = d.io.coreTrigger.pc.peek().litValue.toInt
        println(f"  core_just_finished: coreTrigger.valid=$trigValid, pc=$trigPc")
        utest.assert(trigValid)
        utest.assert(trigPc == 13)
        utest.assert(d.io.autoRunStall.peek().litToBoolean)

        d.clock.step(1)  // clock the FSM transition to sFrag
        val phase = d.io.phase.peek().litValue.toInt
        println(f"  phase after clock: $phase (expect FRAG=$PHASE_FRAG)")
        utest.assert(phase == PHASE_FRAG)

        // Simulate frag shader — snoop RGBZ outputs
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        // Write fragment outputs (r26=R, r27=G, r28=B, r29=Z)
        for ((reg, value) <- Seq((26, 0x1111), (27, 0x2222), (28, 0x3333), (29, 0x4444))) {
          d.io.pipeWrite(0).en.poke(true.B)
          d.io.pipeWrite(0).addr.poke(reg.U)
          d.io.pipeWrite(0).data.poke(value.U)
          d.clock.step(1)
        }
        d.io.pipeWrite(0).en.poke(false.B)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)

        // Should be in sZRead (Step 25.5C: depth test before tile write)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_Z_READ)

        // Step through depth test wait states
        stepThroughDepthTest(d)

        // Now in sTileWrite
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)

        val tileEn  = d.io.tileWrite.en.peek().litToBoolean
        val tileIdx = d.io.tileWrite.idx.peek().litValue.toInt
        val tileR   = d.io.tileWrite.data.r.peek().litValue.toInt
        val tileG   = d.io.tileWrite.data.g.peek().litValue.toInt
        val tileB   = d.io.tileWrite.data.b.peek().litValue.toInt
        val tileZ   = d.io.tileWrite.data.z.peek().litValue.toInt
        println(f"  sTileWrite: en=$tileEn, idx=$tileIdx, R=0x${tileR.toHexString}, G=0x${tileG.toHexString}, B=0x${tileB.toHexString}, Z=0x${tileZ.toHexString}")
        utest.assert(tileEn)
        utest.assert(tileIdx == 7)
        utest.assert(tileR == 0x1111)
        utest.assert(tileG == 0x2222)
        utest.assert(tileB == 0x3333)
        utest.assert(tileZ == 0x4444)

        // After sTileWrite: back to sIdle, stall released
        d.clock.step(1)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        utest.assert(!d.io.autoRunStall.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("stall_held_across_all_phases") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: stall_held_across_all_phases ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13)
        d.io.fragPcReg.poke(13.U)
        utest.assert(d.io.autoRunStall.peek().litToBoolean)  // set on pixelReady

        // Through sRast
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        simulateShaderRun(d)
        utest.assert(d.io.autoRunStall.peek().litToBoolean)  // still set in sFrag
        println("  stall held through sRast ✓")

        // Through sFrag (no tex) → sZRead
        simulateShaderRun(d)
        utest.assert(d.io.autoRunStall.peek().litToBoolean)  // still set in sZRead
        println("  stall held through sFrag ✓")

        // Step through depth test (sZRead → sZWait1 → sZWait2 → sTileWrite)
        stepThroughDepthTest(d)
        utest.assert(d.io.autoRunStall.peek().litToBoolean)  // still set in sTileWrite
        println("  stall held through depth test ✓")

        d.clock.step(1)  // sTileWrite → sIdle
        utest.assert(!d.io.autoRunStall.peek().litToBoolean)
        println("  stall released in sIdle ✓")
        println("  PASSED")
      }
    }

    utest.test("frag_pc_zero_disables_chain") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: frag_pc_zero_disables_chain ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 0)   // fragment chaining disabled
        d.io.fragPcReg.poke(0.U)

        // All edges inside
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        utest.assert(d.io.insideFlag.peek().litToBoolean)

        simulateShaderRun(d)

        val stall = d.io.autoRunStall.peek().litToBoolean
        val phase = d.io.phase.peek().litValue.toInt
        println(f"  After rast done (fragPc=0, inside): stall=$stall, phase=$phase")
        utest.assert(!stall)
        utest.assert(phase == PHASE_IDLE)
        println("  PASSED")
      }
    }

    utest.test("negative_zero_fp16_is_inside") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: negative_zero_fp16_is_inside ---")
        println("  ⚠ FP16 -0.0 (0x8000): sign_bit=1, magnitude=0 → NOT outside → inside")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13)

        // Write -0.0 to all three edges — pixel must still appear inside
        pokeAllEdges(d, FP16_NEG_ZERO, FP16_NEG_ZERO, FP16_NEG_ZERO)

        val inside = d.io.insideFlag.peek().litToBoolean
        println(f"  After e0=e1=e2=-0.0 (0x8000): insideFlag=$inside (expect true)")
        utest.assert(inside)

        // Contrast: -1.0 (non-zero magnitude) must be outside
        pokeEdge(d, 0, FP16_NEG_ONE)
        val outsideAfterNegOne = !d.io.insideFlag.peek().litToBoolean
        println(f"  After e0=-1.0: insideFlag=${!outsideAfterNegOne} (expect false)")
        utest.assert(outsideAfterNegOne)
        println("  PASSED")
      }
    }

    utest.test("tile_index_uses_shader_tile_index") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: tile_index_uses_shader_tile_index ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        val expectedIdx = 11  // arbitrary non-zero tile index
        firePixelReady(d, fragPc = 13, tileIdx = expectedIdx)
        d.io.fragPcReg.poke(13.U)

        // rast + frag shaders (inside pixel, no tex)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        simulateShaderRun(d)  // through sRast
        simulateShaderRun(d)  // through sFrag → sZRead
        stepThroughDepthTest(d)  // sZRead → sTileWrite

        val idx = d.io.tileWrite.idx.peek().litValue.toInt
        println(f"  tileWrite.idx=$idx (expect $expectedIdx)")
        utest.assert(idx == expectedIdx)
        println("  PASSED")
      }
    }

    utest.test("phase_observable_at_every_step") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: phase_observable_at_every_step ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        println(f"  Reset: phase=IDLE ✓")

        // → sRast
        d.io.pixelReady.poke(true.B); d.clock.step(1); d.io.pixelReady.poke(false.B)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_RAST)
        println(f"  pixelReady: phase=RAST ✓")

        // all edges inside, rast shader → sFrag
        d.io.fragPcReg.poke(13.U)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        simulateShaderRun(d)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_FRAG)
        println(f"  rast done (inside): phase=FRAG ✓")

        // frag shader → sZRead (Step 25.5C)
        simulateShaderRun(d)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_Z_READ)
        println(f"  frag done: phase=Z_READ ✓")

        // sZRead → sZWait1 → sZWait2 → sTileWrite
        stepThroughDepthTest(d)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)
        println(f"  depth test done: phase=TILE_WRITE ✓")

        // → sIdle
        d.clock.step(1)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        println(f"  tile write done: phase=IDLE ✓")
        println("  PASSED")
      }
    }

    utest.test("inside_flag_guard_blocks_tile_write") {
      simulate(new BorgShaderDispatcher(BASE)) { d =>
        println("\n--- BorgShaderDispatcher: inside_flag_guard_blocks_tile_write ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        // --- Part 1: outside pixel — tileWrite.en must NEVER assert ---
        println("  Part 1: outside pixel (all 3 edges negative)")
        firePixelReady(d, fragPc = 13, tileIdx = 0)

        // Snoop all three edges as negative
        pokeAllEdges(d, FP16_NEG_ONE, FP16_NEG_TWO, FP16_NEG_ONE)
        utest.assert(!d.io.insideFlag.peek().litToBoolean)

        // Run rast shader; FSM should take sRast → sIdle (no frag, no tile write)
        simulateShaderRun(d)

        val phaseAfterOutside = d.io.phase.peek().litValue.toInt
        val enAfterOutside    = d.io.tileWrite.en.peek().litToBoolean
        println(f"  After outside rast done: phase=$phaseAfterOutside (expect IDLE=$PHASE_IDLE), tileWrite.en=$enAfterOutside")
        utest.assert(phaseAfterOutside == PHASE_IDLE)
        utest.assert(!enAfterOutside)
        println("  tileWrite.en=false ✓  phase=IDLE ✓")

        // --- Part 2: inside pixel — tileWrite.en MUST assert (guard regression) ---
        println("  Part 2: inside pixel — guard must not block the write")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        firePixelReady(d, fragPc = 13, tileIdx = 5)
        d.io.fragPcReg.poke(13.U)

        // Rast shader: all edges inside
        d.io.coreStatus.autoRunPending.poke(true.B); d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)  // FSM: sRast → sFrag

        // Frag shader → sZRead
        simulateShaderRun(d)
        // FSM: sFrag → sZRead → sZWait1 → sZWait2 → sTileWrite
        stepThroughDepthTest(d)

        val enInside = d.io.tileWrite.en.peek().litToBoolean
        println(f"  sTileWrite with inside pixel: tileWrite.en=$enInside (expect true)")
        utest.assert(enInside)

        // FSM returns to sIdle, stall released
        d.clock.step(1)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
        utest.assert(!d.io.autoRunStall.peek().litToBoolean)
        println("  tileWrite.en=true ✓  phase→IDLE ✓  stall released ✓")
        println("  PASSED")
      }
    }

  }
}
