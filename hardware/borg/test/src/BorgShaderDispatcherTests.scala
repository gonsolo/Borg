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
  *   - sRast → sFrag → sTileWrite for inside pixels without texturing
  *   - sRast → sFrag → sTexFetch → sTileWrite for inside pixels with texturing
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
  val PHASE_TEX_FETCH  = 3
  val PHASE_Z_READ     = 4
  val PHASE_Z_WAIT1    = 5
  val PHASE_Z_WAIT2    = 6
  val PHASE_TILE_WRITE = 7

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
    // Step 25.5C: tile read port — provide max depth so depth test passes
    d.io.tileRead.data.r.poke(0.U)
    d.io.tileRead.data.g.poke(0.U)
    d.io.tileRead.data.b.poke(0.U)
    d.io.tileRead.data.z.poke(FP16_MAX_DEPTH.U)
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
    // Texture path: sRast → sFrag → sTexFetch → sTileWrite
    // =========================================================================

    utest.test("texture_path_b_first_then_rg") {
      simulate(new BorgShaderDispatcher(BorgConfig.Default)) { d =>
        println("\n--- BorgShaderDispatcher: texture_path_b_first_then_rg ---")
        pokeIdle(d)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)

        val texBase   = 0x0100
        val mortonIdx = 0x005
        val wordB     = texBase + (mortonIdx << 3) | 4  // B word: offset +4
        val wordRG    = texBase + (mortonIdx << 3)       // RG word: offset +0

        d.io.texConfig.baseAddr.poke(texBase.U)
        d.io.texConfig.mortonIndex.poke(mortonIdx.U)
        d.io.texConfig.en.poke(true.B)

        firePixelReady(d, fragPc = 13, tileIdx = 3)
        d.io.fragPcReg.poke(13.U)
        d.io.texConfig.en.poke(true.B)
        d.io.texConfig.baseAddr.poke(texBase.U)
        d.io.texConfig.mortonIndex.poke(mortonIdx.U)

        // rast shader: all edges inside
        d.io.coreStatus.autoRunPending.poke(true.B)
        d.clock.step(1)
        d.io.coreStatus.autoRunPending.poke(false.B)
        d.io.coreStatus.running.poke(true.B)
        pokeAllEdges(d, FP16_POS_ONE, FP16_POS_ONE, FP16_POS_ONE)
        d.io.coreStatus.running.poke(false.B)
        d.clock.step(1)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_FRAG)

        // frag shader finishes
        simulateShaderRun(d)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TEX_FETCH)
        println("  Entered sTexFetch ✓")

        // --- Read 0: B word first (offset +4) ---
        d.io.gpuMem.ready.poke(false.B)
        d.clock.step(1)
        val req0  = d.io.gpuMem.req.peek().litToBoolean
        val addr0 = d.io.gpuMem.addr.peek().litValue.toInt
        println(f"  Read0 addr=0x${addr0.toHexString} (expect 0x${wordB.toHexString}), req=$req0")
        utest.assert(req0)
        utest.assert(addr0 == wordB)

        d.io.gpuMem.data.poke(0x00005555.U)  // B=0x5555 in low 16 bits
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        // --- Read 1: RG word (offset +0) ---
        d.clock.step(1)
        val req1  = d.io.gpuMem.req.peek().litToBoolean
        val addr1 = d.io.gpuMem.addr.peek().litValue.toInt
        println(f"  Read1 addr=0x${addr1.toHexString} (expect 0x${wordRG.toHexString}), req=$req1")
        utest.assert(req1)
        utest.assert(addr1 == wordRG)

        d.io.gpuMem.data.poke(0x22221111.U)  // G=0x2222 in high 16, R=0x1111 in low 16
        d.io.gpuMem.ready.poke(true.B)
        d.clock.step(1)
        d.io.gpuMem.ready.poke(false.B)

        // BorgTextureUnit needs one cycle to transition sReadRG→sDone and pulse done;
        // the dispatcher then sees done and enters sZRead.
        d.clock.step(1)

        // Should now be in sZRead (Step 25.5C)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_Z_READ)

        // Step through depth test
        stepThroughDepthTest(d)

        // Now in sTileWrite
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_TILE_WRITE)
        val tileEn = d.io.tileWrite.en.peek().litToBoolean
        val tileR  = d.io.tileWrite.data.r.peek().litValue.toInt
        val tileG  = d.io.tileWrite.data.g.peek().litValue.toInt
        val tileB  = d.io.tileWrite.data.b.peek().litValue.toInt
        println(f"  sTileWrite: en=$tileEn, R=0x${tileR.toHexString}, G=0x${tileG.toHexString}, B=0x${tileB.toHexString}")
        utest.assert(tileEn)
        utest.assert(tileR == 0x1111)
        utest.assert(tileG == 0x2222)
        utest.assert(tileB == 0x5555)

        d.clock.step(1)
        utest.assert(!d.io.autoRunStall.peek().litToBoolean)
        utest.assert(d.io.phase.peek().litValue.toInt == PHASE_IDLE)
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

  }
}
