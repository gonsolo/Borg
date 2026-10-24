// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._

object BorgCoreTestsC extends TestSuite {
  import BorgCoreTestHelpers._

  val tests = Tests {

    utest.test("load_reads_dram_into_a_register") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: LW ---")
        idleInputs(core)
        resetCore(core)

        val mem = scala.collection.mutable.Map[BigInt, BigInt](
          BigInt(LS_BASE + 3 * 4) -> BigInt("beef", 16))

        writeReg(core, 4, LS_BASE)
        writeImem(core, 0, Opcodes.LW(rd = 2, rs1 = 4, imm12 = 3 * 4))
        writeImem(core, 1, 0)

        startAndWaitWithMem(core, mem)
        val got = readReg(core, 2)
        println(f"  r2 = 0x${got.toInt.toHexString} (expect 0xbeef)")
        utest.assert(got == BigInt("beef", 16))
        println("  PASSED")
      }
    }

    utest.test("store_writes_a_register_to_dram") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: SW ---")
        idleInputs(core)
        resetCore(core)

        val mem = scala.collection.mutable.Map[BigInt, BigInt]()
        writeReg(core, 0, 5)
        writeReg(core, 3, LS_BASE)
        writeReg(core, 1, BigInt("1234", 16))     // r1 = payload
        writeImem(core, 0, Opcodes.SW(simm = 5 * 4, rs1 = 3, rs2 = 1))
        writeImem(core, 1, 0)

        startAndWaitWithMem(core, mem)
        val addr = BigInt(LS_BASE + 5 * 4)
        println(f"  mem[0x${addr.toInt.toHexString}] = 0x${mem.getOrElse(addr, BigInt(0)).toInt.toHexString} (expect 0x1234)")
        utest.assert(mem.get(addr).contains(BigInt("1234", 16)))
        // SW keeps immediate bits in the rd field; they must not be taken for a destination.
        println(f"  r0 still ${readReg(core, 0)} (expect 5)")
        utest.assert(readReg(core, 0) == 5)
        println("  PASSED")
      }
    }

    utest.test("ztest_stalls_until_done_and_writes_no_register") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: ZTEST stall ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 5); writeReg(core, 2, 5)
        writeImem(core, 0, Instructions.ZTEST())
        writeImem(core, 1, Instructions.IADD(rs1 = 2, rs2 = 2, rd = 1))  // r1 = 10
        writeImem(core, 2, 0)
        core.io.control.start.poke(true.B); core.clock.step(1); core.io.control.start.poke(false.B)

        var wd = 0
        while (!core.io.zTestReq.peek().litToBoolean && wd < 100) { core.clock.step(1); wd += 1 }
        utest.assert(wd < 100)
        // Held: the core must wait however long the dispatcher takes.
        for (_ <- 0 until 20) {
          utest.assert(core.io.zTestReq.peek().litToBoolean)
          utest.assert(core.io.status.running.peek().litToBoolean)
          core.clock.step(1)
        }
        core.io.zTestDone.poke(true.B); core.clock.step(1); core.io.zTestDone.poke(false.B)
        wd = 0
        while (core.io.status.running.peek().litToBoolean && wd < 200) {
          utest.assert(!core.io.zTestReq.peek().litToBoolean)   // no second request
          core.clock.step(1); wd += 1
        }
        utest.assert(wd < 200)
        println(s"  r0=${readReg(core, 0)} (expect 5, rd=0 is not a write)  r1=${readReg(core, 1)} (expect 10)")
        utest.assert(readReg(core, 0) == 5)
        utest.assert(readReg(core, 1) == 10)
      }
    }

    utest.test("helper_lane_stores_are_suppressed_loads_are_not") {
      simulate(new BorgCore(BorgConfig.Simt)) { core =>
        println("\n--- BorgCore: helper lanes ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 3, LS_BASE + 8)
        writeReg(core, 1, BigInt("77", 16))

        def run(op: BigInt, helpers: Seq[Boolean]): (Int, Int) = {
          core.io.laneHelper.get.zip(helpers).foreach { case (p, h) => p.poke(h.B) }
          resetCore(core)                      // PC back to 0 for each run
          writeImem(core, 0, op)
          writeImem(core, 1, 0)
          core.io.control.start.poke(true.B); core.clock.step(1); core.io.control.start.poke(false.B)
          var reads = 0; var writes = 0; var wd = 0
          while (core.io.status.running.peek().litToBoolean && wd < 500) {
            val rd = core.io.gpuMem.get.req.peek().litToBoolean
            val wr = core.io.gpuMem.get.wr.peek().litToBoolean
            if (rd) reads += 1
            if (wr) writes += 1
            core.io.gpuMem.get.ready.poke((rd || wr).B)
            core.clock.step(1); wd += 1
          }
          core.io.gpuMem.get.ready.poke(false.B)
          utest.assert(wd < 500)
          (reads, writes)
        }
        val (_, allW)    = run(Opcodes.SW(rs1 = 3, rs2 = 1), Seq(false, false, false, false))
        val (_, helperW) = run(Opcodes.SW(rs1 = 3, rs2 = 1), Seq(false, true, false, true))
        val (helperR, _) = run(Opcodes.LW(rd = 2, rs1 = 3), Seq(false, true, false, true))
        println(s"  stores: $allW without helpers (expect 4), $helperW with lanes 1,3 helpers (expect 2); loads $helperR (expect 4)")
        utest.assert(allW == 4)
        utest.assert(helperW == 2)
        utest.assert(helperR == 4)
        core.io.laneHelper.get.foreach(_.poke(false.B))
      }
    }

    // --- Shader instruction cache (BorgConfig.hasShaderICache) -------------
    //
    // A program image in "DRAM" (a Scala map) at CODE_BASE, IMEM preloaded
    // with however much of it fits -- exactly what the sequencer's shader DMA
    // does -- and the rest fetched on demand. Returns the number of
    // instruction fetches that went to memory.
    val CODE_BASE = 0x8000
    val small = config.copy(maxInstructions = 72)   // MMIO preloads 72 words at most
    def runFromImage(core: BorgCore, image: Seq[BigInt], preload: Int): Int = {
      resetCore(core)
      core.io.codeBase.get.poke(CODE_BASE.U)
      core.io.icacheFlush.get.poke(true.B); core.clock.step(1); core.io.icacheFlush.get.poke(false.B)
      for (i <- 0 until preload) writeImem(core, i, image(i))
      core.io.control.start.poke(true.B); core.clock.step(1); core.io.control.start.poke(false.B)
      var fetches = 0; var wd = 0
      while (core.io.status.running.peek().litToBoolean && wd < 20000) {
        val rd = core.io.gpuMem.get.req.peek().litToBoolean
        if (rd) {
          val a = core.io.gpuMem.get.addr.peek().litValue.toInt
          val w = (a - CODE_BASE) / 4
          utest.assert(a >= CODE_BASE && w < image.length)
          core.io.gpuMem.get.data.poke(image(w).U)
          fetches += 1
        }
        core.io.gpuMem.get.ready.poke(rd.B)
        core.clock.step(1); wd += 1
      }
      core.io.gpuMem.get.ready.poke(false.B)
      utest.assert(wd < 20000)
      fetches
    }
    /** Word i adds r(2 + i%3) = 1, 10 or 100 to r1, then HALT. The words
      * differ, so executing the wrong one -- a stale line from the wrong PC
      * -- changes the sum. */
    def countingProgram(n: Int): Seq[BigInt] =
      (0 until n).map(i => Instructions.IADD(rs1 = 1, rs2 = 2 + i % 3, rd = 1)) :+ BigInt(0)
    def countingSum(n: Int): Int = (0 until n).map(i => Seq(1, 10, 100)(i % 3)).sum
    def countingRegs(core: BorgCore): Unit = {
      writeReg(core, 1, 0); writeReg(core, 2, 1); writeReg(core, 3, 10); writeReg(core, 4, 100)
    }

    utest.test("icache_runs_a_program_longer_than_imem") {
      for (cfg <- Seq(small, BorgConfig.Wafer, config)) {  // 72, 64 and 512 words (line state in a RAM)
        simulate(new BorgCore(cfg)) { core =>
          val n = cfg.maxInstructions
          val len = math.max(150, n + 78)
          val pre = math.min(n, small.maxInstructions)
          println(s"\n--- BorgCore: ${n}-word IMEM, ${len}-instruction program ---")
          idleInputs(core)
          val prog = countingProgram(len)
          countingRegs(core)
          val f1 = runFromImage(core, prog, preload = pre)
          val r1 = readReg(core, 1)
          println(s"  run 1: r1=$r1 (expect ${countingSum(len)}), $f1 fetches from memory (expect ${prog.length - pre})")
          utest.assert(r1 == countingSum(len))
          utest.assert(f1 == prog.length - pre)
          // Same program again without a flush or reload: far words that
          // survived are hits, near words they evicted are refetched -- the
          // result must not change either way.
          countingRegs(core)
          resetCore(core)
          core.io.control.start.poke(true.B); core.clock.step(1); core.io.control.start.poke(false.B)
          var wd = 0; var f2 = 0
          while (core.io.status.running.peek().litToBoolean && wd < 20000) {
            val rd = core.io.gpuMem.get.req.peek().litToBoolean
            if (rd) {
              val w = (core.io.gpuMem.get.addr.peek().litValue.toInt - CODE_BASE) / 4
              core.io.gpuMem.get.data.poke(prog(w).U); f2 += 1
            }
            core.io.gpuMem.get.ready.poke(rd.B)
            core.clock.step(1); wd += 1
          }
          core.io.gpuMem.get.ready.poke(false.B)
          println(s"  run 2: r1=${readReg(core, 1)} (expect ${countingSum(len)}), $f2 fetches")
          utest.assert(readReg(core, 1) == countingSum(len))
        }
      }
    }

    utest.test("programs_past_1024_words_relative_jmp_and_branches") {
      // Targets are relative to the branch: JMP by 18 bits, BRZ/BRNZ by 10.
      // 0: JMP 2100. 2100: ten r1 += 10, BRNZ r2 to 2200, skipping
      // r1 += 100 poison. 2200: five r1 += 1, JMP 1500. 1500: three
      // r1 += 100, HALT. r1 = 405; any wrong target changes it.
      import Instructions._
      for (cfg <- Seq(small, BorgConfig.Wafer)) {
        simulate(new BorgCore(cfg)) { core =>
          println(s"\n--- BorgCore: a 2300-word program, pcBits ${cfg.pcBits} ---")
          idleInputs(core)
          val img = Array.fill[BigInt](2300)(BigInt(0))
          img(0) = JMP(2100)
          for (i <- 0 until 10) img(2100 + i) = IADD(rs1 = 1, rs2 = 3, rd = 1)
          img(2110) = BRNZ(rs1 = 2, offset = 2200 - 2110)
          for (i <- 2111 until 2200) img(i) = IADD(rs1 = 1, rs2 = 4, rd = 1)
          for (i <- 0 until 5) img(2200 + i) = IADD(rs1 = 1, rs2 = 2, rd = 1)
          img(2205) = JMP(1500 - 2205)
          for (i <- 0 until 3) img(1500 + i) = IADD(rs1 = 1, rs2 = 4, rd = 1)
          countingRegs(core)
          val fetches = runFromImage(core, img.toSeq, preload = 1)
          val r1 = readReg(core, 1)
          println(s"  r1 = $r1 (expect 405), $fetches fetches from memory")
          utest.assert(r1 == 405)
        }
      }
    }

    utest.test("icache_short_preload_fetches_everything_it_did_not_cover") {
      // A preload that stops short of IMEM (the MMIO DMA's length field
      // cannot even reach 72 words) must not run whatever the previous
      // program left in the lines it did not write.
      simulate(new BorgCore(small)) { core =>
        idleInputs(core)
        // Leave a different program's words in IMEM first.
        for (i <- 0 until small.maxInstructions) writeImem(core, i, Instructions.IADD(rs1 = 1, rs2 = 1, rd = 1))
        val prog = countingProgram(150)
        countingRegs(core)
        val f = runFromImage(core, prog, preload = 20)
        println(s"  r1=${readReg(core, 1)} (expect ${countingSum(150)}), fetches=$f (expect ${prog.length - 20})")
        utest.assert(readReg(core, 1) == countingSum(150))
        utest.assert(f == prog.length - 20)
      }
    }

    utest.test("icache_epoch_wrap_invalidates_every_line") {
      // Lines in a RAM are valid by epoch; 256 flushes bring the epoch back
      // to the one an old program's lines were written in.
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        val old = Seq.fill(config.maxInstructions - 1)(Instructions.IADD(rs1 = 1, rs2 = 4, rd = 1)) :+ BigInt(0)
        countingRegs(core)
        runFromImage(core, old, preload = small.maxInstructions)
        for (_ <- 0 until 255) { core.io.icacheFlush.get.poke(true.B); core.clock.step(1) }
        core.io.icacheFlush.get.poke(false.B)
        val prog = countingProgram(150)
        countingRegs(core)
        val f = runFromImage(core, prog, preload = 20)
        println(s"  r1=${readReg(core, 1)} (expect ${countingSum(150)}), fetches=$f (expect ${prog.length - 20})")
        utest.assert(readReg(core, 1) == countingSum(150))
        utest.assert(f == prog.length - 20)
      }
    }

    utest.test("icache_program_that_fits_never_touches_memory") {
      simulate(new BorgCore(small)) { core =>
        idleInputs(core)
        val prog = countingProgram(small.maxInstructions - 1)   // exactly fills IMEM
        countingRegs(core)
        val f = runFromImage(core, prog, preload = prog.length)
        println(s"  r1=${readReg(core, 1)} (expect ${countingSum(prog.length - 1)}), fetches=$f (expect 0)")
        utest.assert(readReg(core, 1) == countingSum(prog.length - 1))
        utest.assert(f == 0)
      }
    }

    utest.test("icache_loop_past_imem_hits_after_the_first_iteration") {
      simulate(new BorgCore(small)) { core =>
        idleInputs(core)
        // Words 0..89 straight-line padding (r1 += r2), then a 4-word loop at
        // 90..93 that counts r3 down from 10, all past the 72-word IMEM.
        val pad = Seq.fill(90)(Instructions.IADD(rs1 = 1, rs2 = 2, rd = 1))
        val loop = Seq(
          Instructions.IADD(rs1 = 4, rs2 = 2, rd = 4),            // r4 += 1 (loop body)
          Instructions.ISUB(rs1 = 3, rs2 = 2, rd = 3),            // r3 -= 1
          Instructions.BRNZ(rs1 = 3, offset = -2),
          BigInt(0))
        val prog = pad ++ loop
        writeReg(core, 1, 0); writeReg(core, 2, 1); writeReg(core, 3, 10); writeReg(core, 4, 0)
        val f = runFromImage(core, prog, preload = small.maxInstructions)
        println(s"  r1=${readReg(core, 1)} (expect 90) r4=${readReg(core, 4)} (expect 10), fetches=$f (expect ${prog.length - small.maxInstructions}: each far word once)")
        utest.assert(readReg(core, 1) == 90)
        utest.assert(readReg(core, 4) == 10)
        utest.assert(f == prog.length - small.maxInstructions)
      }
    }

    utest.test("store_then_load_round_trips_through_memory") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: SW then LW ---")
        idleInputs(core)
        resetCore(core)

        val mem = scala.collection.mutable.Map[BigInt, BigInt]()
        writeReg(core, 2, LS_BASE + 7 * 4)
        writeReg(core, 1, BigInt("cafe", 16))
        // Two memory instructions back to back: the second must not inherit
        // any state from the first (the FSM has to have fully returned to
        // idle and the resume-delay has to have cleared).
        writeImem(core, 0, Opcodes.SW(rs1 = 2, rs2 = 1))
        writeImem(core, 1, Opcodes.LW(rd = 3, rs1 = 2))
        writeImem(core, 2, 0)

        startAndWaitWithMem(core, mem)
        val got = readReg(core, 3)
        println(f"  r3 = 0x${got.toInt.toHexString} (expect 0xcafe)")
        utest.assert(got == BigInt("cafe", 16))
        println("  PASSED")
      }
    }

    utest.test("effective_address_is_rs1_plus_the_immediate") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: effective address ---")
        idleInputs(core)
        resetCore(core)

        // The addressing rule is the part a compiler has to agree with, so it
        // is checked directly against emitted addresses rather than inferred
        // from a value round-tripping.
        for ((base, imm) <- Seq((0x1000, 0), (0x1000, 4), (0x2000, 2044), (0x2000, -2048))) {
          // Each iteration is a fresh program: without the reset the program
          // counter stays past the previous halt and nothing executes.
          resetCore(core)
          val mem = scala.collection.mutable.Map[BigInt, BigInt]()
          writeReg(core, 1, base)
          writeImem(core, 0, Opcodes.SW(simm = imm, rs1 = 1, rs2 = 1))
          writeImem(core, 1, 0)
          startAndWaitWithMem(core, mem)
          val expected = BigInt(base + imm)
          val touched = mem.keys.toSeq
          println(f"  base=0x$base%x imm=$imm%5d -> ${touched.map(a => "0x" + a.toInt.toHexString).mkString(",")} (expect 0x${expected.toInt.toHexString})")
          utest.assert(touched == Seq(expected))
        }
        println("  PASSED")
      }
    }

    utest.test("a_stalled_load_does_not_advance_until_memory_answers") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: LW stalls the pipeline ---")
        idleInputs(core)
        resetCore(core)

        writeReg(core, 1, LS_BASE + 4)
        writeImem(core, 0, Opcodes.LW(rd = 2, rs1 = 1))
        writeImem(core, 1, 0)

        core.io.control.start.poke(true.B)
        core.clock.step(1)
        core.io.control.start.poke(false.B)

        // Withhold `ready` and confirm the core sits there requesting rather
        // than running off the end. A memory instruction that did not stall
        // would finish and drop `running` within a handful of cycles.
        var sawRequest = false
        for (_ <- 0 until 60) {
          core.io.gpuMem.get.ready.poke(false.B)
          if (core.io.gpuMem.get.req.peek().litToBoolean) sawRequest = true
          core.clock.step(1)
        }
        val stillRunning = core.io.status.running.peek().litToBoolean
        println(f"  after 60 cycles with ready held low: req seen=$sawRequest running=$stillRunning")
        utest.assert(sawRequest)
        utest.assert(stillRunning)

        // Release it and the instruction completes normally.
        val mem = scala.collection.mutable.Map[BigInt, BigInt](
          BigInt(LS_BASE + 4) -> BigInt("00ff", 16))
        var idle = false
        var wd = 0
        while (!idle && wd < 200) {
          val rq = core.io.gpuMem.get.req.peek().litToBoolean
          if (rq) {
            val a = core.io.gpuMem.get.addr.peek().litValue
            core.io.gpuMem.get.data.poke((mem.getOrElse(a, BigInt(0))).U)
            core.io.gpuMem.get.ready.poke(true.B)
          } else core.io.gpuMem.get.ready.poke(false.B)
          core.clock.step(1)
          idle = !core.io.status.running.peek().litToBoolean
          wd += 1
        }
        core.io.gpuMem.get.ready.poke(false.B)
        utest.assert(idle)
        println(f"  released: r2 = 0x${readReg(core, 2).toInt.toHexString} (expect 0xff)")
        utest.assert(readReg(core, 2) == BigInt("00ff", 16))
        println("  PASSED")
      }
    }

    utest.test("brz_taken_skips_instructions") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: BRZ taken ---")
        idleInputs(core)
        resetCore(core)

        writeReg(core, 8, 0)              // condition == 0 -> branch taken
        writeReg(core, 1, floatToBits(1.0f))
        writeReg(core, 2, floatToBits(0.0f))
        // 0: BRZ r0 -> 2      (skip the add at slot 1)
        // 1: r2 = r1 + r1     (must NOT execute)
        // 2: halt
        writeImem(core, 0, Instructions.BRZ(rs1 = 8, offset = 2))
        writeImem(core, 1, Instructions.ADD(1, 1, 2))
        writeImem(core, 2, 0)

        startAndWait(core)
        val r2 = bitsToFloat(readReg(core, 2))
        println(f"  r2 = $r2%.2f (expect 0.0 -- the skipped add would make it 2.0)")
        utest.assert(math.abs(r2) < 0.01f)
        println("  PASSED")
      }
    }

    utest.test("brz_not_taken_falls_through") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: BRZ not taken ---")
        idleInputs(core)
        resetCore(core)

        writeReg(core, 8, 1)              // condition != 0 -> fall through
        writeReg(core, 1, floatToBits(1.0f))
        writeReg(core, 2, floatToBits(0.0f))
        writeImem(core, 0, Instructions.BRZ(rs1 = 8, offset = 2))
        writeImem(core, 1, Instructions.ADD(1, 1, 2))
        writeImem(core, 2, 0)

        startAndWait(core)
        val r2 = bitsToFloat(readReg(core, 2))
        println(f"  r2 = $r2%.2f (expect 2.0 -- the add ran)")
        utest.assert(math.abs(r2 - 2.0f) < 0.01f)
        println("  PASSED")
      }
    }

    utest.test("branch_does_not_write_the_register_its_target_names") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: branch has no destination ---")
        idleInputs(core)
        resetCore(core)

        // BEQ keeps offset bits in the rd field: offset 3 (12 bytes) names r12,
        // which an ALU write-back would clobber (BorgLane's `!opFlags.branch` guard).
        writeReg(core, 8, 1)                            // not taken
        writeReg(core, 12, floatToBits(7.0f))       // sentinel in r12
        writeImem(core, 0, Instructions.BRZ(rs1 = 8, offset = 3))
        writeImem(core, 1, 0)

        startAndWait(core)
        val r12 = bitsToFloat(readReg(core, 12))
        println(f"  r12 = $r12%.2f (expect 7.0 -- untouched)")
        utest.assert(math.abs(r12 - 7.0f) < 0.01f)
        println("  PASSED")
      }
    }

  }
}
