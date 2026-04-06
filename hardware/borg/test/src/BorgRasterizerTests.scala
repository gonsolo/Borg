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

  /** Pack bbox fields into 24-bit MMIO word. */
  def packBbox(x0: Int, y0: Int, x1: Int, y1: Int): BigInt =
    BigInt(x0 & 0x3F) |
    (BigInt(y0 & 0x3F) << 6) |
    (BigInt(x1 & 0x3F) << 12) |
    (BigInt(y1 & 0x3F) << 18)

  /** Set all control inputs to idle (no clock step). */
  def pokeIdle(rast: BorgRasterizer): Unit = {
    rast.io.setBbox.poke(false.B)
    rast.io.setFragPC.poke(false.B)
    rast.io.fragPCData.poke(0.U)
    rast.io.advance.poke(false.B)
    rast.io.pipeWriteEn.poke(false.B)
    rast.io.pipeWriteAddr.poke(0.U)
    rast.io.pipeWriteData.poke(0.U)
    rast.io.coreRunning.poke(false.B)
    rast.io.coreAutoRunPending.poke(false.B)
  }

  /** Set the bounding box and let it take effect. */
  def setBbox(rast: BorgRasterizer, x0: Int, y0: Int, x1: Int, y1: Int): Unit = {
    pokeIdle(rast)
    rast.io.setBbox.poke(true.B)
    rast.io.bboxData.poke(packBbox(x0, y0, x1, y1).U)
    rast.clock.step(1)
    rast.io.setBbox.poke(false.B)
    rast.clock.step(1)
  }

  /** Set the fragment shader PC. */
  def setFragPC(rast: BorgRasterizer, pc: Int): Unit = {
    pokeIdle(rast)
    rast.io.setFragPC.poke(true.B)
    rast.io.fragPCData.poke(pc.U)
    rast.clock.step(1)
    rast.io.setFragPC.poke(false.B)
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
    rast.io.coreRunning.poke(true.B)
    rast.clock.step(1)
    // Then core finishes
    rast.io.coreRunning.poke(false.B)
    rast.clock.step(1)
  }

  /** Simulate a complete shader execution: auto_run_pending → running → halt. */
  def simulateShaderRun(rast: BorgRasterizer, cycles: Int = 3): Unit = {
    rast.io.coreAutoRunPending.poke(true.B)
    rast.clock.step(1)
    rast.io.coreAutoRunPending.poke(false.B)
    rast.io.coreRunning.poke(true.B)
    rast.clock.step(cycles)
    rast.io.coreRunning.poke(false.B)
    rast.clock.step(1)
  }

  val tests = Tests {

    utest.test("bbox_init") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: bbox_init ---")
        pokeIdle(rast)
        rast.clock.step(1)  // let reset take effect
        setBbox(rast, 2, 3, 5, 6)

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
        rast.clock.step(1)
        setBbox(rast, 1, 1, 4, 4)  // 3×3 box: (1,1) to (3,3)

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
        rast.io.pipeWriteEn.poke(true.B)
        rast.io.pipeWriteAddr.poke(0.U)
        rast.io.pipeWriteData.poke(0xBC00.U)
        rast.clock.step(1)
        rast.io.pipeWriteEn.poke(false.B)
        rast.clock.step(1)

        utest.assert(!rast.io.insideFlag.peek().litToBoolean)
        println("  After e0=-1.0: insideFlag=false ✓")

        // Write a positive (inside) value to edge 0: +1.0 FP16 = 0x3C00
        rast.io.pipeWriteEn.poke(true.B)
        rast.io.pipeWriteAddr.poke(0.U)
        rast.io.pipeWriteData.poke(0x3C00.U)
        rast.clock.step(1)
        rast.io.pipeWriteEn.poke(false.B)
        rast.clock.step(1)

        utest.assert(rast.io.insideFlag.peek().litToBoolean)
        println("  After e0=+1.0: insideFlag=true ✓")

        // Write negative to edge 1
        rast.io.pipeWriteEn.poke(true.B)
        rast.io.pipeWriteAddr.poke(1.U)
        rast.io.pipeWriteData.poke(0xC000.U)  // -2.0
        rast.clock.step(1)
        rast.io.pipeWriteEn.poke(false.B)
        rast.clock.step(1)

        utest.assert(!rast.io.insideFlag.peek().litToBoolean)
        println("  After e1=-2.0: insideFlag=false ✓")

        // Zero should count as inside (sign=0, magnitude=0 → not outside)
        rast.io.pipeWriteEn.poke(true.B)
        rast.io.pipeWriteAddr.poke(1.U)
        rast.io.pipeWriteData.poke(0.U)
        rast.clock.step(1)
        rast.io.pipeWriteEn.poke(false.B)
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
        rast.clock.step(1)
        setBbox(rast, 0, 0, 4, 4)

        // Advance should pulse triggerCoreValid and set autoRunStall
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        // Don't step yet — check triggerCoreValid is combinationally high during advance
        rast.clock.step(1)

        val triggered = rast.io.triggerCoreValid.peek().litToBoolean
        println(f"  During advance: triggerCoreValid=$triggered")
        utest.assert(triggered)

        // Now simulate: advance done, core starts running (auto_run_pending→running)
        rast.io.advance.poke(false.B)
        rast.io.coreAutoRunPending.poke(true.B)
        rast.clock.step(1)

        // Core is now running
        rast.io.coreAutoRunPending.poke(false.B)
        rast.io.coreRunning.poke(true.B)

        val stalled = rast.io.autoRunStall.peek().litToBoolean
        println(f"  While core running: autoRunStall=$stalled")
        utest.assert(stalled)

        // Core runs for a few cycles
        rast.clock.step(5)

        // Core halts
        rast.io.coreRunning.poke(false.B)
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
        rast.clock.step(1)
        setBbox(rast, 0, 0, 4, 4)
        setFragPC(rast, 13)  // frag shader at IMEM slot 13

        // Set edge 0 to outside (negative sign)
        rast.io.pipeWriteEn.poke(true.B)
        rast.io.pipeWriteAddr.poke(0.U)
        rast.io.pipeWriteData.poke(0xBC00.U)  // -1.0
        rast.clock.step(1)
        rast.io.pipeWriteEn.poke(false.B)
        rast.clock.step(1)
        utest.assert(!rast.io.insideFlag.peek().litToBoolean)

        // Advance → triggers rast shader
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)

        // Check: triggerCoreValid fired with PC=0
        val trigPC = rast.io.triggerCorePC.peek().litValue.toInt
        println(f"  Advance: triggerCorePC=$trigPC (expect 0)")
        utest.assert(trigPC == 0)

        // Simulate rast shader running and finishing
        rast.io.advance.poke(false.B)
        simulateShaderRun(rast)

        // After rast completes for an outside pixel: stall should be cleared,
        // and NO second trigger should fire (frag skipped)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        val trig = rast.io.triggerCoreValid.peek().litToBoolean
        println(f"  After rast done (outside): stall=$stall, triggerCoreValid=$trig")
        utest.assert(!stall)
        println("  PASSED")
      }
    }

    utest.test("chain_inside_pixel_triggers_frag") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: chain_inside_pixel_triggers_frag ---")
        pokeIdle(rast)
        rast.clock.step(1)
        setBbox(rast, 0, 0, 4, 4)
        setFragPC(rast, 13)

        // Advance → triggers rast shader at PC=0
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Simulate rast shader running
        rast.io.coreAutoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreAutoRunPending.poke(false.B)
        rast.io.coreRunning.poke(true.B)

        // --- DURING CORE RUNNING, WRITE EDGES ---
        for (i <- 0 until 3) {
          rast.io.pipeWriteEn.poke(true.B)
          rast.io.pipeWriteAddr.poke(i.U)
          rast.io.pipeWriteData.poke(0x3C00.U)  // +1.0
          rast.clock.step(1)
        }
        rast.io.pipeWriteEn.poke(false.B)

        utest.assert(rast.io.insideFlag.peek().litToBoolean)
        
        rast.clock.step(1)
        rast.io.coreRunning.poke(false.B)

        // Read combinatorial values BEFORE clock ticks
        val trigValid = rast.io.triggerCoreValid.peek().litToBoolean
        val trigPC = rast.io.triggerCorePC.peek().litValue.toInt
        val in = rast.io.insideFlag.peek().litToBoolean

        rast.clock.step(1)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        utest.assert(trigValid)
        utest.assert(trigPC == 13)
        utest.assert(stall)
        // Simulate frag shader running
        simulateShaderRun(rast)

        // After frag completes: stall cleared
        val finalStall = rast.io.autoRunStall.peek().litToBoolean
        println(f"  After frag done: stall=$finalStall")
        utest.assert(!finalStall)
        println("  PASSED")
      }
    }

    utest.test("chain_disabled_when_frag_pc_zero") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: chain_disabled_when_frag_pc_zero ---")
        pokeIdle(rast)
        rast.clock.step(1)
        setBbox(rast, 0, 0, 4, 4)
        // Setup frag_start_pc = 0 (disabled) explicitly to avoid uninitialized state cross-talk
        setFragPC(rast, 0)
        // Set all edges inside (positive sign)
        for (i <- 0 until 3) {
          rast.io.pipeWriteEn.poke(true.B)
          rast.io.pipeWriteAddr.poke(i.U)
          rast.io.pipeWriteData.poke(0x3C00.U)  // +1.0
          rast.clock.step(1)
        }
        rast.io.pipeWriteEn.poke(false.B)
        rast.clock.step(1)

        // Advance
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Simulate rast shader manually
        rast.io.coreAutoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreAutoRunPending.poke(false.B)
        rast.io.coreRunning.poke(true.B)
        rast.clock.step(3)

        // Ready to finish
        rast.io.coreRunning.poke(false.B)
        // Check state before clock edge!
        val tv = rast.io.triggerCoreValid.peek().litToBoolean
        val tp = rast.io.triggerCorePC.peek().litValue
        val in = rast.io.insideFlag.peek().litToBoolean

        rast.clock.step(1)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        val tv_after = rast.io.triggerCoreValid.peek().litToBoolean
        utest.assert(!stall)
        println("  PASSED")
      }
    }
  }
}
