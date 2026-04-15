// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Standalone tests for BorgRasterizer — no FPU, no MMIO, no shaders.
  *
  * Verifies bounding-box traversal, inside-flag snooping, stall logic,
  * and shader chaining FSM (Step 10.6.2).
  */
object BorgRasterizerTests extends TestSuite {

  val config = FloatConfig.FP16



  /** Set all control inputs to idle (no clock step). */
  def pokeIdle(rast: BorgRasterizer): Unit = {
    rast.io.cmdPop.valid.poke(false.B)
    rast.io.advance.poke(false.B)
    rast.io.pipeWrite.en.poke(false.B)
    rast.io.pipeWrite.addr.poke(0.U)
    rast.io.pipeWrite.data.poke(0.U)
    rast.io.coreStatus.running.poke(false.B)
    rast.io.coreStatus.autoRunPending.poke(false.B)
    // Step 19.2 GPU read-port defaults
    rast.io.gpuRead.data.poke(0.U)
    rast.io.gpuRead.ready.poke(false.B)
    rast.io.texConfig.mortonIndex.poke(0.U)
    rast.io.texConfig.baseAddr.poke(0.U)
    rast.io.texConfig.en.poke(false.B)
  }

  /** Command the rasterizer with bbox and pc via cmdPop. */
  def setCommand(rast: BorgRasterizer, x0: Int, y0: Int, x1: Int, y1: Int, pc: Int): Unit = {
    pokeIdle(rast)
    rast.io.cmdPop.valid.poke(true.B)
    rast.io.cmdPop.bits.bbox.min.x.poke(x0.U)
    rast.io.cmdPop.bits.bbox.min.y.poke(y0.U)
    rast.io.cmdPop.bits.bbox.max.x.poke(x1.U)
    rast.io.cmdPop.bits.bbox.max.y.poke(y1.U)
    rast.io.cmdPop.bits.fragPC.poke(pc.U)
    rast.io.cmdPop.bits.uniformPage.poke(0.U)
    rast.clock.step(1)
    rast.io.cmdPop.valid.poke(false.B)
    rast.clock.step(1)
  }

  /** Pulse advance for one cycle, then idle one cycle.
    * Holds coreRunning=true to prevent the stall from immediately clearing.
    */
  def advance(rast: BorgRasterizer): Unit = {
    pokeIdle(rast)
    rast.io.advance.poke(true.B)
    rast.clock.step(1)
    // After advance, the core would be running (triggered by triggerCoreValid),
    // so hold coreRunning=true to keep auto_run_stall alive.
    rast.io.advance.poke(false.B)
    rast.io.coreStatus.running.poke(true.B)
    rast.clock.step(1)
    // Then core finishes
    rast.io.coreStatus.running.poke(false.B)
    rast.clock.step(1)
  }

  /** Simulate a complete shader execution: auto_run_pending → running → halt. */
  def simulateShaderRun(rast: BorgRasterizer, cycles: Int = 3): Unit = {
    rast.io.coreStatus.autoRunPending.poke(true.B)
    rast.clock.step(1)
    rast.io.coreStatus.autoRunPending.poke(false.B)
    rast.io.coreStatus.running.poke(true.B)
    rast.clock.step(cycles)
    rast.io.coreStatus.running.poke(false.B)
    rast.clock.step(1)
  }

  val tests = Tests {

    utest.test("bbox_init") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: bbox_init ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        setCommand(rast, 2, 3, 5, 6, 0)

        val x = rast.io.iter.x.peek().litValue.toInt
        val y = rast.io.iter.y.peek().litValue.toInt
        println(f"  After setBbox(2,3,5,6): iterX=$x, iterY=$y")
        utest.assert(x == 2)
        utest.assert(y == 3)
        utest.assert(rast.io.iterValid.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("bbox_walk_3x3") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: bbox_walk_3x3 ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        setCommand(rast, 1, 1, 4, 4, 0)  // 3×3 box: (1,1) to (3,3)

        val expected = Seq(
          (2, 1), (3, 1),
          (1, 2), (2, 2), (3, 2),
          (1, 3), (2, 3), (3, 3),
        )

        val ix = rast.io.iter.x.peek().litValue.toInt
        val iy = rast.io.iter.y.peek().litValue.toInt
        println(f"  Start: ($ix, $iy)")
        utest.assert(ix == 1 && iy == 1)

        for ((ex, ey) <- expected) {
          advance(rast)
          val ax = rast.io.iter.x.peek().litValue.toInt
          val ay = rast.io.iter.y.peek().litValue.toInt
          println(f"  Advance -> ($ax, $ay)  expected ($ex, $ey)")
          utest.assert(ax == ex && ay == ey)
        }

        advance(rast)
        val fx = rast.io.iter.x.peek().litValue.toInt
        val fy = rast.io.iter.y.peek().litValue.toInt
        val valid = rast.io.iterValid.peek().litToBoolean
        println(f"  Final: ($fx, $fy) valid=$valid")
        utest.assert(!valid)
        println("  PASSED")
      }
    }

    utest.test("inside_flag_snooping") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: inside_flag_snooping ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)

        // Initially all edges have e*_outside = false, so inside_flag = true
        val initFlag = rast.io.insideFlag.peek().litToBoolean
        println(f"  Initial: insideFlag=$initFlag")
        utest.assert(initFlag)
        println("  Initial: insideFlag=true ✓")

        // Enter sRast phase so snooping is active
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Write a negative (outside) value to edge 0: -1.0 FP16 = 0xBC00
        rast.io.pipeWrite.en.poke(true.B)
        rast.io.pipeWrite.addr.poke(0.U)
        rast.io.pipeWrite.data.poke(0xBC00.U)
        rast.clock.step(1)
        rast.io.pipeWrite.en.poke(false.B)
        rast.clock.step(1)

        utest.assert(!rast.io.insideFlag.peek().litToBoolean)
        println("  After e0=-1.0: insideFlag=false ✓")

        // Write a positive (inside) value to edge 0: +1.0 FP16 = 0x3C00
        rast.io.pipeWrite.en.poke(true.B)
        rast.io.pipeWrite.addr.poke(0.U)
        rast.io.pipeWrite.data.poke(0x3C00.U)
        rast.clock.step(1)
        rast.io.pipeWrite.en.poke(false.B)
        rast.clock.step(1)

        utest.assert(rast.io.insideFlag.peek().litToBoolean)
        println("  After e0=+1.0: insideFlag=true ✓")

        // Write negative to edge 1
        rast.io.pipeWrite.en.poke(true.B)
        rast.io.pipeWrite.addr.poke(1.U)
        rast.io.pipeWrite.data.poke(0xC000.U)  // -2.0
        rast.clock.step(1)
        rast.io.pipeWrite.en.poke(false.B)
        rast.clock.step(1)

        utest.assert(!rast.io.insideFlag.peek().litToBoolean)
        println("  After e1=-2.0: insideFlag=false ✓")

        // Zero should count as inside (sign=0, magnitude=0 → not outside)
        rast.io.pipeWrite.en.poke(true.B)
        rast.io.pipeWrite.addr.poke(1.U)
        rast.io.pipeWrite.data.poke(0.U)
        rast.clock.step(1)
        rast.io.pipeWrite.en.poke(false.B)
        rast.clock.step(1)

        utest.assert(rast.io.insideFlag.peek().litToBoolean)
        println("  After e1=0.0: insideFlag=true ✓")
        println("  PASSED")
      }
    }

    utest.test("trigger_and_stall") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: trigger_and_stall ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        setCommand(rast, 0, 0, 4, 4, 0)

        // Advance should pulse triggerCoreValid and set autoRunStall
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        // Don't step yet — check triggerCoreValid is combinationally high during advance
        rast.clock.step(1)

        val triggered = rast.io.coreTrigger.valid.peek().litToBoolean
        println(f"  During advance: coreTrigger.valid=$triggered")
        utest.assert(triggered)

        // Now simulate: advance done, core starts running (auto_run_pending→running)
        rast.io.advance.poke(false.B)
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)

        // Core is now running
        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)

        val stalled = rast.io.autoRunStall.peek().litToBoolean
        println(f"  While core running: autoRunStall=$stalled")
        utest.assert(stalled)

        // Core runs for a few cycles
        rast.clock.step(5)

        // Core halts
        rast.io.coreStatus.running.poke(false.B)
        rast.clock.step(1)

        val clearedStall = !rast.io.autoRunStall.peek().litToBoolean
        println(f"  After core halts: autoRunStall cleared=$clearedStall")
        utest.assert(clearedStall)
        println("  PASSED")
      }
    }

    // --- Step 10.6.2: Shader chaining tests ---

    utest.test("chain_outside_pixel_no_frag") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: chain_outside_pixel_no_frag ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        setCommand(rast, 0, 0, 4, 4, 13) // frag shader at IMEM slot 13

        // Advance → enters sRast phase where snooping happens
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)

        // Set edge 0 to outside (negative sign)
        rast.io.pipeWrite.en.poke(true.B)
        rast.io.pipeWrite.addr.poke(0.U)
        rast.io.pipeWrite.data.poke(0xBC00.U)  // -1.0
        rast.clock.step(1)
        rast.io.pipeWrite.en.poke(false.B)
        rast.clock.step(1)
        utest.assert(!rast.io.insideFlag.peek().litToBoolean)

        // Check: triggerCoreValid fired with PC=0
        val trigPC = rast.io.coreTrigger.pc.peek().litValue.toInt
        println(f"  Advance: coreTrigger.pc=$trigPC (expect 0)")
        utest.assert(trigPC == 0)

        // Simulate rast shader running and finishing
        rast.io.advance.poke(false.B)
        simulateShaderRun(rast)

        // After rast completes for an outside pixel: stall should be cleared,
        // and NO second trigger should fire (frag skipped)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        val trig = rast.io.coreTrigger.valid.peek().litToBoolean
        println(f"  After rast done (outside): stall=$stall, coreTrigger.valid=$trig")
        utest.assert(!stall)
        println("  PASSED")
      }
    }

    utest.test("chain_inside_pixel_triggers_frag") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: chain_inside_pixel_triggers_frag ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        setCommand(rast, 0, 0, 4, 4, 13)

        // Advance → triggers rast shader at PC=0
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Simulate rast shader running
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)

        // --- DURING CORE RUNNING, WRITE EDGES ---
        for (i <- 0 until 3) {
          rast.io.pipeWrite.en.poke(true.B)
          rast.io.pipeWrite.addr.poke(i.U)
          rast.io.pipeWrite.data.poke(0x3C00.U)  // +1.0
          rast.clock.step(1)
        }
        rast.io.pipeWrite.en.poke(false.B)

        utest.assert(rast.io.insideFlag.peek().litToBoolean)
        
        rast.clock.step(1)
        rast.io.coreStatus.running.poke(false.B)

        // Read combinatorial values BEFORE clock ticks
        val trigValid = rast.io.coreTrigger.valid.peek().litToBoolean
        val trigPC = rast.io.coreTrigger.pc.peek().litValue.toInt
        val in = rast.io.insideFlag.peek().litToBoolean

        rast.clock.step(1)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        utest.assert(trigValid)
        utest.assert(trigPC == 13)
        utest.assert(stall)
        // Simulate frag shader running
        simulateShaderRun(rast)
        
        // Wait 1 extra cycle for sTileWrite phase (auto-write to tile buffer)
        rast.clock.step(1)

        // After frag and auto-write completes: stall cleared
        val finalStall = rast.io.autoRunStall.peek().litToBoolean
        println(f"  After frag done: stall=$finalStall")
        utest.assert(!finalStall)
        println("  PASSED")
      }
    }

    // --- Step 19.2: sTexFetch FSM tests ---

    utest.test("tex_fetch_path") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: tex_fetch_path ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)

        // Texture configuration: base=0x0100, morton=0x005 → tex_base = 0x0100 + (5<<3) = 0x0128
        // Word 0 addr = 0x0128, Word 1 addr = 0x012C (0x0128 | 4)
        val texBase    = 0x0100
        val mortonIdx  = 0x005
        val expectedW0 = texBase + (mortonIdx << 3)          // 0x0128
        val expectedW1 = expectedW0 | 4                       // 0x012C

        // Packed texel data: word0 = {G=0x3C00, R=0x4000}, word1 = {pad=0, B=0x4200}
        val gpuWord0   = (0x3C00 << 16) | 0x4000             // G in [31:16], R in [15:0]
        val gpuWord1   = 0x4200                               // B in [15:0]

        // Enable texturing
        rast.io.texConfig.baseAddr.poke(texBase.U)
        rast.io.texConfig.mortonIndex.poke(mortonIdx.U)
        rast.io.texConfig.en.poke(true.B)

        // Set up a bbox command with a non-zero fragPC
        setCommand(rast, 0, 0, 4, 4, 13)

        // Advance → sRast
        pokeIdle(rast)
        rast.io.texConfig.baseAddr.poke(texBase.U)
        rast.io.texConfig.mortonIndex.poke(mortonIdx.U)
        rast.io.texConfig.en.poke(true.B)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Simulate rast shader — write all edges inside (+1.0)
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)
        for (i <- 0 until 3) {
          rast.io.pipeWrite.en.poke(true.B)
          rast.io.pipeWrite.addr.poke(i.U)
          rast.io.pipeWrite.data.poke(0x3C00.U)  // +1.0 FP16
          rast.clock.step(1)
        }
        rast.io.pipeWrite.en.poke(false.B)
        rast.clock.step(1)
        rast.io.coreStatus.running.poke(false.B)

        // Read triggerCoreValid before the clock edge — should fire for sFrag
        val trigFrag = rast.io.coreTrigger.valid.peek().litToBoolean
        val trigPC   = rast.io.coreTrigger.pc.peek().litValue.toInt
        println(f"  After rast: coreTrigger.valid=$trigFrag, coreTrigger.pc=$trigPC (expect 13)")
        utest.assert(trigFrag)
        utest.assert(trigPC == 13)
        rast.clock.step(1)

        // Simulate frag shader
        simulateShaderRun(rast)

        // After frag, tex_en=true → FSM should enter sTexFetch.
        // Read order is swapped: B (Word 1, addr|4) first, then RG (Word 0, addr)
        // to avoid corrupting the Morton encoder (fragU/fragV are wired from frag_r/frag_g).
        rast.io.gpuRead.ready.poke(false.B)
        rast.clock.step(1)  // settle into sTexFetch

        val req0  = rast.io.gpuRead.req.peek().litToBoolean
        val addr0 = rast.io.gpuRead.addr.peek().litValue.toInt
        println(f"  sTexFetch Read0 (B): gpu_read_req=$req0, gpu_addr=0x${addr0.toHexString} (expect 0x${expectedW1.toHexString})")
        utest.assert(req0)
        utest.assert(addr0 == expectedW1)  // B word first (offset +4)

        // Feed back Word 1 data (B)
        rast.io.gpuRead.data.poke(gpuWord1.U)
        rast.io.gpuRead.ready.poke(true.B)
        rast.clock.step(1)
        rast.io.gpuRead.ready.poke(false.B)

        // Now FSM should request RG (Word 0, base addr)
        rast.clock.step(1)
        val req1  = rast.io.gpuRead.req.peek().litToBoolean
        val addr1 = rast.io.gpuRead.addr.peek().litValue.toInt
        println(f"  sTexFetch Read1 (RG): gpu_read_req=$req1, gpu_addr=0x${addr1.toHexString} (expect 0x${expectedW0.toHexString})")
        utest.assert(req1)
        utest.assert(addr1 == expectedW0)  // RG word second (offset +0)

        // Feed back Word 0 data (RG); on this clock edge the FSM transitions to sTileWrite
        rast.io.gpuRead.data.poke(gpuWord0.U)
        rast.io.gpuRead.ready.poke(true.B)
        rast.clock.step(1)
        rast.io.gpuRead.ready.poke(false.B)

        // FSM is now in sTileWrite — tileWriteEn combinationally asserted (before next edge)
        val tileWr = rast.io.tileWrite.en.peek().litToBoolean
        println(f"  sTileWrite: tileWrite.en=$tileWr")
        utest.assert(tileWr)

        // Clock through sTileWrite → sIdle; stall is cleared
        rast.clock.step(1)
        val finalStall = rast.io.autoRunStall.peek().litToBoolean
        println(f"  After tile write: autoRunStall=$finalStall")
        utest.assert(!finalStall)

        println("  PASSED")
      }
    }

    utest.test("chain_disabled_when_frag_pc_zero") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: chain_disabled_when_frag_pc_zero ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        // Setup frag_start_pc = 0 (disabled) explicitly to avoid uninitialized state cross-talk
        setCommand(rast, 0, 0, 4, 4, 0)
        // Advance -> enter sRast phase where snooping happens
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Set all edges inside (positive sign)
        for (i <- 0 until 3) {
          rast.io.pipeWrite.en.poke(true.B)
          rast.io.pipeWrite.addr.poke(i.U)
          rast.io.pipeWrite.data.poke(0x3C00.U)  // +1.0
          rast.clock.step(1)
        }
        rast.io.pipeWrite.en.poke(false.B)
        rast.clock.step(1)

        // Simulate rast shader manually
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)
        rast.clock.step(3)

        // Ready to finish
        rast.io.coreStatus.running.poke(false.B)
        // Check state before clock edge!
        val tv = rast.io.coreTrigger.valid.peek().litToBoolean
        val tp = rast.io.coreTrigger.pc.peek().litValue
        val in = rast.io.insideFlag.peek().litToBoolean

        rast.clock.step(1)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        val tv_after = rast.io.coreTrigger.valid.peek().litToBoolean
        utest.assert(!stall)
        println("  PASSED")
      }
    }
  }
}
