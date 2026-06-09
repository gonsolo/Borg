// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Standalone tests for BorgRasterizer — no FPU, no MMIO, no shaders.
  *
  * Verifies tile-origin traversal, inside-flag snooping, stall logic,
  * and shader chaining FSM (Step 10.6.2).
  */
object BorgRasterizerTests extends TestSuite {

  val config = FloatConfig.FP16

  /** Set all control inputs to idle (no clock step). */
  def pokeIdle(rast: BorgRasterizer): Unit = {
    rast.io.cmdPop.valid.poke(false.B)
    rast.io.advance.poke(false.B)
    rast.io.pipeWrite(0).en.poke(false.B)
    rast.io.pipeWrite(0).addr.poke(0.U)
    rast.io.pipeWrite(0).data.poke(0.U)
    rast.io.coreStatus.running.poke(false.B)
    rast.io.coreStatus.autoRunPending.poke(false.B)
    // Step 19.2 GPU read-port defaults
    rast.io.gpuMem.data.poke(0.U)
    rast.io.gpuMem.ready.poke(false.B)
    rast.io.texConfig.mortonIndex.poke(0.U)
    rast.io.texConfig.baseAddr.poke(0.U)
    rast.io.texConfig.en.poke(false.B)
    // Register-driven frag_pc and uniform_page
    rast.io.fragPcReg.poke(0.U)
    rast.io.uniformPageReg.poke(0.U)
    // Step 25.5C: tile read port — provide max depth so depth test passes
    rast.io.tileRead.data.r.poke(0.U)
    rast.io.tileRead.data.g.poke(0.U)
    rast.io.tileRead.data.b.poke(0.U)
    rast.io.tileRead.data.z.poke(0x7BFF.U)
  }

  /** Command the rasterizer with tile origin via cmdPop. */
  def setTileCommand(rast: BorgRasterizer, tx: Int, ty: Int, fragPc: Int = 0): Unit = {
    pokeIdle(rast)
    rast.io.cmdPop.valid.poke(true.B)
    rast.io.cmdPop.bits.tileOrigin.x.poke(tx.U)
    rast.io.cmdPop.bits.tileOrigin.y.poke(ty.U)
    rast.io.fragPcReg.poke(fragPc.U)
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

    utest.test("tile_origin_init") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: tile_origin_init ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        setTileCommand(rast, 4, 8)

        val x = rast.io.iter.x.peek().litValue.toInt
        val y = rast.io.iter.y.peek().litValue.toInt
        println(f"  After setTileCommand(4,8): iterX=$x, iterY=$y")
        utest.assert(x == 4)
        utest.assert(y == 8)
        utest.assert(rast.io.iterValid.peek().litToBoolean)
        println("  PASSED")
      }
    }

    utest.test("tile_walk_4x4") {
      simulate(new BorgRasterizer(config)) { rast =>
        println("\n--- BorgRasterizer: tile_walk_4x4 ---")
        pokeIdle(rast)
        rast.reset.poke(true.B)
        rast.clock.step(2)
        rast.reset.poke(false.B)
        rast.clock.step(1)
        setTileCommand(rast, 0, 0)  // 4×4 tile at origin

        // Expected: (0,0), advance → (1,0), (2,0), (3,0), (0,1), ...
        val expected = Seq(
          (1, 0), (2, 0), (3, 0),
          (0, 1), (1, 1), (2, 1), (3, 1),
          (0, 2), (1, 2), (2, 2), (3, 2),
          (0, 3), (1, 3), (2, 3), (3, 3),
        )

        val ix = rast.io.iter.x.peek().litValue.toInt
        val iy = rast.io.iter.y.peek().litValue.toInt
        println(f"  Start: ($ix, $iy)")
        utest.assert(ix == 0 && iy == 0)

        for ((ex, ey) <- expected) {
          advance(rast)
          val ax = rast.io.iter.x.peek().litValue.toInt
          val ay = rast.io.iter.y.peek().litValue.toInt
          println(f"  Advance -> ($ax, $ay)  expected ($ex, $ey)")
          utest.assert(ax == ex && ay == ey)
        }

        advance(rast)
        val valid = rast.io.iterValid.peek().litToBoolean
        println(f"  After all 16 pixels: valid=$valid")
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

        // Enter sRast phase so snooping is active.
        // Must load a tile first so iter_valid=true → pixelReady fires → dispatcher enters sRast.
        setTileCommand(rast, 0, 0)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Write a negative (outside) value to edge 0: -1.0 FP16 = 0xBC00
        rast.io.pipeWrite(0).en.poke(true.B)
        rast.io.pipeWrite(0).addr.poke(0.U)
        rast.io.pipeWrite(0).data.poke(0xBC00.U)
        rast.clock.step(1)
        rast.io.pipeWrite(0).en.poke(false.B)
        rast.clock.step(1)

        utest.assert(!rast.io.insideFlag.peek().litToBoolean)
        println("  After e0=-1.0: insideFlag=false ✓")

        // Write a positive (inside) value to edge 0: +1.0 FP16 = 0x3C00
        rast.io.pipeWrite(0).en.poke(true.B)
        rast.io.pipeWrite(0).addr.poke(0.U)
        rast.io.pipeWrite(0).data.poke(0x3C00.U)
        rast.clock.step(1)
        rast.io.pipeWrite(0).en.poke(false.B)
        rast.clock.step(1)

        utest.assert(rast.io.insideFlag.peek().litToBoolean)
        println("  After e0=+1.0: insideFlag=true ✓")

        // Write negative to edge 1
        rast.io.pipeWrite(0).en.poke(true.B)
        rast.io.pipeWrite(0).addr.poke(1.U)
        rast.io.pipeWrite(0).data.poke(0xC000.U)  // -2.0
        rast.clock.step(1)
        rast.io.pipeWrite(0).en.poke(false.B)
        rast.clock.step(1)

        utest.assert(!rast.io.insideFlag.peek().litToBoolean)
        println("  After e1=-2.0: insideFlag=false ✓")

        // Zero should count as inside (sign=0, magnitude=0 → not outside)
        rast.io.pipeWrite(0).en.poke(true.B)
        rast.io.pipeWrite(0).addr.poke(1.U)
        rast.io.pipeWrite(0).data.poke(0.U)
        rast.clock.step(1)
        rast.io.pipeWrite(0).en.poke(false.B)
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
        setTileCommand(rast, 0, 0)

        // Advance should pulse triggerCoreValid and set autoRunStall.
        // coreTrigger.valid is combinational (driven by pixelReady in the same
        // cycle).  After step(1), auto_run_stall becomes true and gates the
        // iterator, so pixelReady (and coreTrigger.valid) drop to false.
        // Therefore: peek the combinational output BEFORE stepping.
        pokeIdle(rast)
        rast.io.advance.poke(true.B)
        val triggered = rast.io.coreTrigger.valid.peek().litToBoolean
        println(f"  During advance: coreTrigger.valid=$triggered")
        utest.assert(triggered)
        rast.clock.step(1)

        // Now simulate: advance done, core starts running
        rast.io.advance.poke(false.B)
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)

        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)

        val stalled = rast.io.autoRunStall.peek().litToBoolean
        println(f"  While core running: autoRunStall=$stalled")
        utest.assert(stalled)

        rast.clock.step(5)

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
        setTileCommand(rast, 0, 0, fragPc = 13)
        // Keep fragPcReg set for the duration
        rast.io.fragPcReg.poke(13.U)

        // Advance → enters sRast phase
        pokeIdle(rast)
        rast.io.fragPcReg.poke(13.U)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)  // must de-assert before pipeWrite: advance resets edge flags every cycle it is high

        // Set edge 0 to outside (negative sign)
        rast.io.pipeWrite(0).en.poke(true.B)
        rast.io.pipeWrite(0).addr.poke(0.U)
        rast.io.pipeWrite(0).data.poke(0xBC00.U)  // -1.0
        rast.clock.step(1)
        rast.io.pipeWrite(0).en.poke(false.B)
        rast.clock.step(1)
        utest.assert(!rast.io.insideFlag.peek().litToBoolean)

        val trigPC = rast.io.coreTrigger.pc.peek().litValue.toInt
        println(f"  Advance: coreTrigger.pc=$trigPC (expect 0)")
        utest.assert(trigPC == 0)

        // Simulate rast shader running and finishing
        rast.io.advance.poke(false.B)
        simulateShaderRun(rast)

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
        setTileCommand(rast, 0, 0, fragPc = 13)
        rast.io.fragPcReg.poke(13.U)

        // Advance → triggers rast shader at PC=0
        pokeIdle(rast)
        rast.io.fragPcReg.poke(13.U)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Simulate rast shader running
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)

        // Write edges inside
        for (i <- 0 until 3) {
          rast.io.pipeWrite(0).en.poke(true.B)
          rast.io.pipeWrite(0).addr.poke(i.U)
          rast.io.pipeWrite(0).data.poke(0x3C00.U)  // +1.0
          rast.clock.step(1)
        }
        rast.io.pipeWrite(0).en.poke(false.B)

        utest.assert(rast.io.insideFlag.peek().litToBoolean)
        
        rast.clock.step(1)
        rast.io.coreStatus.running.poke(false.B)

        val trigValid = rast.io.coreTrigger.valid.peek().litToBoolean
        val trigPC = rast.io.coreTrigger.pc.peek().litValue.toInt

        rast.clock.step(1)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        utest.assert(trigValid)
        utest.assert(trigPC == 13)
        utest.assert(stall)
        simulateShaderRun(rast)
        
        // Wait for depth test (sZRead → sZWait1 → sZWait2 → sTileWrite) + 1 for sTileWrite→sIdle
        rast.clock.step(4)

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

        val texBase    = 0x0100
        val mortonIdx  = 0x005
        val expectedW0 = texBase + (mortonIdx << 3)
        val expectedW1 = expectedW0 | 4

        val gpuWord0   = (0x3C00 << 16) | 0x4000
        val gpuWord1   = 0x4200

        // Enable texturing
        rast.io.texConfig.baseAddr.poke(texBase.U)
        rast.io.texConfig.mortonIndex.poke(mortonIdx.U)
        rast.io.texConfig.en.poke(true.B)
        rast.io.fragPcReg.poke(13.U)

        setTileCommand(rast, 0, 0, fragPc = 13)

        // Advance → sRast
        pokeIdle(rast)
        rast.io.texConfig.baseAddr.poke(texBase.U)
        rast.io.texConfig.mortonIndex.poke(mortonIdx.U)
        rast.io.texConfig.en.poke(true.B)
        rast.io.fragPcReg.poke(13.U)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Simulate rast shader — write all edges inside
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)
        for (i <- 0 until 3) {
          rast.io.pipeWrite(0).en.poke(true.B)
          rast.io.pipeWrite(0).addr.poke(i.U)
          rast.io.pipeWrite(0).data.poke(0x3C00.U)
          rast.clock.step(1)
        }
        rast.io.pipeWrite(0).en.poke(false.B)
        rast.clock.step(1)
        rast.io.coreStatus.running.poke(false.B)

        val trigFrag = rast.io.coreTrigger.valid.peek().litToBoolean
        val trigPC   = rast.io.coreTrigger.pc.peek().litValue.toInt
        println(f"  After rast: coreTrigger.valid=$trigFrag, coreTrigger.pc=$trigPC (expect 13)")
        utest.assert(trigFrag)
        utest.assert(trigPC == 13)
        rast.clock.step(1)

        // Simulate frag shader
        simulateShaderRun(rast)

        // sTexFetch: B word first, then RG
        rast.io.gpuMem.ready.poke(false.B)
        rast.clock.step(1)

        val req0  = rast.io.gpuMem.req.peek().litToBoolean
        val addr0 = rast.io.gpuMem.addr.peek().litValue.toInt
        println(f"  sTexFetch Read0 (B): gpu_read_req=$req0, gpu_addr=0x${addr0.toHexString} (expect 0x${expectedW1.toHexString})")
        utest.assert(req0)
        utest.assert(addr0 == expectedW1)

        rast.io.gpuMem.data.poke(gpuWord1.U)
        rast.io.gpuMem.ready.poke(true.B)
        rast.clock.step(1)
        rast.io.gpuMem.ready.poke(false.B)

        rast.clock.step(1)
        val req1  = rast.io.gpuMem.req.peek().litToBoolean
        val addr1 = rast.io.gpuMem.addr.peek().litValue.toInt
        println(f"  sTexFetch Read1 (RG): gpu_read_req=$req1, gpu_addr=0x${addr1.toHexString} (expect 0x${expectedW0.toHexString})")
        utest.assert(req1)
        utest.assert(addr1 == expectedW0)

        rast.io.gpuMem.data.poke(gpuWord0.U)
        rast.io.gpuMem.ready.poke(true.B)
        rast.clock.step(1)
        rast.io.gpuMem.ready.poke(false.B)

        // BorgTextureUnit needs one cycle to transition sReadRG→sDone and pulse done;
        // the dispatcher then enters sZRead (Step 25.5C).
        rast.clock.step(1)

        // Step through depth test (3 cycles: sZRead → sZWait1 → sZWait2 → sTileWrite)
        rast.clock.step(3)

        val tileWr = rast.io.tileWrite.en.peek().litToBoolean
        println(f"  sTileWrite: tileWrite.en=$tileWr")
        utest.assert(tileWr)

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
        setTileCommand(rast, 0, 0, fragPc = 0)
        // Advance -> enter sRast
        pokeIdle(rast)
        rast.io.fragPcReg.poke(0.U)
        rast.io.advance.poke(true.B)
        rast.clock.step(1)
        rast.io.advance.poke(false.B)

        // Set all edges inside
        for (i <- 0 until 3) {
          rast.io.pipeWrite(0).en.poke(true.B)
          rast.io.pipeWrite(0).addr.poke(i.U)
          rast.io.pipeWrite(0).data.poke(0x3C00.U)
          rast.clock.step(1)
        }
        rast.io.pipeWrite(0).en.poke(false.B)
        rast.clock.step(1)

        // Simulate rast shader
        rast.io.coreStatus.autoRunPending.poke(true.B)
        rast.clock.step(1)
        rast.io.coreStatus.autoRunPending.poke(false.B)
        rast.io.coreStatus.running.poke(true.B)
        rast.clock.step(3)

        rast.io.coreStatus.running.poke(false.B)
        rast.clock.step(1)
        val stall = rast.io.autoRunStall.peek().litToBoolean
        utest.assert(!stall)
        println("  PASSED")
      }
    }
  }
}
