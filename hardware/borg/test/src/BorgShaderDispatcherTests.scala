// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgShaderDispatcher — drives the dispatcher directly.
  *
  * These tests exercise the per-pixel FSM in complete isolation from
  * BorgIterator and BorgRasterizer.  A caller drives `pixelReady` directly
  * instead of going through advance/cmdPop, making each test minimal and
  * unambiguous about what it is verifying.
  *
  * Coverage (Step 25.3d):
  *   - sRast → sIdle for outside pixels (no frag trigger)
  *   - sRast → sFrag → sTileWrite for inside pixels (texturing, when enabled,
  *     happens inline mid-sFrag via FTEX -- no separate phase)
  *   - autoRunStall held across all phases and released on sIdle
  *   - fragPcReg=0 disables chaining even for inside pixels
  *   - FP16 negative-zero (0x8000) is treated as inside (not outside)
  *   - Tile write uses shaderTileIndex (pre-advance index), not current index
  *   - phase output observable from parent (debugability contract)
  */
object BorgShaderDispatcherTests extends TestSuite {

  val config = FloatConfig.FP16

  // FP16 constants
  val FP16_POS_ONE = 0x3C00  // +1.0
  val FP16_NEG_ONE = 0xBC00  // -1.0
  val FP16_NEG_TWO = 0xC000  // -2.0
  val FP16_NEG_ZERO = 0x8000 // -0.0  ← MUST count as inside

  // Phase enum values (matches Enum(8) in BorgShaderDispatcher, Step 25.5C)
  val PHASE_IDLE       = 0
  val PHASE_RAST       = 1
  val PHASE_FRAG       = 2
  // sTexFetch (legacy autonomous fetch) removed -- texturing is FTEX-inline
  // only now, so the remaining phases shift down by one.
  val PHASE_Z_READ     = 3
  val PHASE_Z_WAIT1    = 4
  val PHASE_Z_WAIT2    = 5
  val PHASE_TILE_WRITE = 6

  // FP16 max depth for tile buffer clear value
  val FP16_MAX_DEPTH = 0x7BFF

  /** Set all inputs to safe idle defaults (no clock step). */
  def pokeIdle(d: BorgShaderDispatcher): Unit = {
    d.io.pixelReady.poke(false.B)
    d.io.shaderTileIndex(0).poke(0.U)
    d.io.pipeWrite(0).en.poke(false.B)
    d.io.pipeWrite(0).addr.poke(0.U)
    d.io.pipeWrite(0).data.poke(0.U)
    d.io.coreStatus.running.poke(false.B)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.fragPcReg.poke(0.U)
    d.io.texConfig.en.poke(false.B)
    d.io.texConfig.mortonIndex.poke(0.U)
    d.io.texConfig.baseAddr.poke(0.U)
    d.io.gpuMem.data.poke(0.U)
    d.io.gpuMem.ready.poke(false.B)
    // Step 25.5C: tile read port — provide max depth so depth test passes.
    // Per-sample since MSAA: every sample starts at the far plane.
    d.io.tileRead.data.foreach { s =>
      s.r.poke(0.U)
      s.g.poke(0.U)
      s.b.poke(0.U)
      s.z.poke(FP16_MAX_DEPTH.U)
    }
  }

  /** Step through the 3 depth-test wait states (sZRead → sZWait1 → sZWait2 → sTileWrite). */
  def stepThroughDepthTest(d: BorgShaderDispatcher): Unit = {
    d.clock.step(3)  // sZRead → sZWait1 → sZWait2 → sTileWrite
  }

  /** Fire pixelReady for one cycle, then restore idle.
    * Optionally sets fragPcReg before the pulse.
    */
  def firePixelReady(d: BorgShaderDispatcher, fragPc: Int = 13, tileIdx: Int = 5): Unit = {
    d.io.pixelReady.poke(true.B)
    d.io.fragPcReg.poke(fragPc.U)
    d.io.shaderTileIndex(0).poke(tileIdx.U)
    d.clock.step(1)
    d.io.pixelReady.poke(false.B)
  }

  /** Simulate a complete shader execution: autoRunPending → running → halt. */
  def simulateShaderRun(d: BorgShaderDispatcher, cycles: Int = 3): Unit = {
    d.io.coreStatus.autoRunPending.poke(true.B)
    d.clock.step(1)
    d.io.coreStatus.autoRunPending.poke(false.B)
    d.io.coreStatus.running.poke(true.B)
    d.clock.step(cycles)
    d.io.coreStatus.running.poke(false.B)
    d.clock.step(1)
  }

  /** Write a single edge-function result via pipeline snoop. */
  def pokeEdge(d: BorgShaderDispatcher, reg: Int, value: Int): Unit = {
    d.io.pipeWrite(0).en.poke(true.B)
    d.io.pipeWrite(0).addr.poke(reg.U)
    d.io.pipeWrite(0).data.poke(value.U)
    d.clock.step(1)
    d.io.pipeWrite(0).en.poke(false.B)
  }

  /** Write all three edge values and check resulting insideFlag. */
  def pokeAllEdges(d: BorgShaderDispatcher, e0: Int, e1: Int, e2: Int): Unit = {
    pokeEdge(d, 0, e0)
    pokeEdge(d, 1, e1)
    pokeEdge(d, 2, e2)
  }

  val tests = Tests {

    // =========================================================================
    // Basic FSM: sRast → sIdle for outside pixels
    // =========================================================================

    utest.test("pixel_ready_triggers_rast_shader") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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

    // =========================================================================
    // sRast → sFrag → sTileWrite (no texturing)
    // =========================================================================

    utest.test("inside_pixel_chains_to_frag_then_tile_write") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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

    // =========================================================================
    // stall held across all phases
    // =========================================================================

    utest.test("stall_held_across_all_phases") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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

    // =========================================================================
    // fragPcReg=0 disables chaining even for inside pixels
    // =========================================================================

    utest.test("frag_pc_zero_disables_chain") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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

    // =========================================================================
    // FP16 negative-zero must count as INSIDE
    // =========================================================================

    utest.test("negative_zero_fp16_is_inside") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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

    // =========================================================================
    // shaderTileIndex forwarded correctly to sTileWrite
    // =========================================================================

    utest.test("tile_index_uses_shader_tile_index") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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

    // =========================================================================
    // phase output is observable at every FSM step (debugability contract)
    // =========================================================================

    utest.test("phase_observable_at_every_step") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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

    // =========================================================================
    // Step 25.3f: inside_flag guard — tileWrite.en stays low for outside pixels
    // =========================================================================
    //
    // Currently the FSM routes outside pixels sRast→sIdle and they never reach
    // sTileWrite.  Step 25.4d will change this (autonomous iteration processes
    // every pixel).  This test simulates a hypothetical sTileWrite entry with
    // outside edges and verifies that tileWrite.en stays low.
    //
    // Implementation note: we cannot directly force phase=sTileWrite from the
    // outside — the FSM only reaches it after sFrag.  We therefore test the
    // guard by running a full inside→frag path, then checking that the SAME
    // logic also blocks the write when inside_flag is false.  The most robust
    // approach is a separate outside-pixel run where we intercept at sFrag and
    // corrupt inside_flag by poking a negative edge DURING sFrag execution.
    //
    // Simpler alternative used here: run with all edges outside from the
    // start.  The FSM takes sRast→sIdle, so tileWrite.en must never assert.
    // Then separately test that inside pixels DO produce tileWrite.en=true
    // (regression check on the guard itself).

    utest.test("inside_flag_guard_blocks_tile_write") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
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


    // =========================================================================
    // discard: r25 hardware-ABI kill flag blocks tile write-back
    // =========================================================================

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

    // =========================================================================
    // discard: r25 write of exactly 0 must NOT discard (only nonzero kills)
    // =========================================================================

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

    // =========================================================================
    // Step 50.2: 4× MSAA per-sample coverage
    // =========================================================================
    //
    // The whole point of per-sample coverage is that a pixel STRADDLING a
    // triangle edge is partially covered — some samples in, some out.  A
    // pixel-centre-only test can never produce that, so these tests drive edge
    // values whose sign differs from the sign of (edge + sample offset).
    //
    // Sample s's edge value is e + δ_s, tested as e >= -δ_s.  With the standard
    // Vulkan/D3D 4× positions, δ_s2 = -δ_s1 and δ_s3 = -δ_s0, so the hardware
    // derives all four thresholds from the two base deltas per edge.

    val MSAA = BorgConfig.Default.copy(samples = 4)

    /** FP16 bits for a float (test-side reference, finite normals only). */
    def f16(f: Float): Int = {
      val h = java.lang.Float.floatToIntBits(f)
      val sign = (h >>> 16) & 0x8000
      val expF = ((h >>> 23) & 0xff) - 127 + 15
      val mantF = h & 0x7fffff
      if (f == 0.0f) sign
      else if (expF <= 0) sign
      else if (expF >= 0x1f) sign | 0x7bff
      else sign | (expF << 10) | (mantF >> 13)
    }

    /** Drive the two base deltas for all three edges. */
    def pokeCovDelta(d: BorgShaderDispatcher, d0: Int, d1: Int): Unit =
      for (e <- 0 until 3) {
        d.io.covDelta.get(e)(0).poke(d0.U)
        d.io.covDelta.get(e)(1).poke(d1.U)
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
  }
}
