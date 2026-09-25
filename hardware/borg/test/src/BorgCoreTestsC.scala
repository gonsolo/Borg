// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgCoreTestsC extends TestSuite {
  import BorgCoreTestHelpers._

  val tests = Tests {

    utest.test("fsrgb_fp16") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: fsrgb_fp16 ---")
        idleInputs(core)
        resetCore(core)

        def testSrgb(input: Float, label: String): Unit = {
          resetCore(core)
          writeReg(core, 0, floatToBits(input))
          writeImem(core, 0, Instructions.FSRGB(rs1 = 0, rd = 2))
          writeImem(core, 1, 0) // halt
          startAndWait(core)
          val result = bitsToFloat(readReg(core, 2))
          val expected = linearToSrgb(input)
          println(f"  $label: srgb($input%.4f) actual=$result%.5f expected=$expected%.5f")
          utest.assert(math.abs(result - expected) < 0.01f) // < ~2.5/255
        }

        testSrgb(0.0f,       "srgb(0)")
        testSrgb(0.0031308f, "srgb(linear-knee)")
        testSrgb(0.05f,      "srgb(0.05)")
        testSrgb(0.2f,       "srgb(0.2)")
        testSrgb(0.5f,       "srgb(0.5)")
        testSrgb(0.8f,       "srgb(0.8)")
        testSrgb(1.0f,       "srgb(1.0)")

        println("  PASSED")
      }
    }

    utest.test("borgc_vertex_shader_mvp") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: borgc_vertex_shader_mvp (compiled cube.c vertex shader) ---")
        idleInputs(core)
        resetCore(core)

        def writeUniform(idx: Int, bits: BigInt): Unit =
          writeCore(core, 432 + idx * 4, bits)

        // The exact program borgc emits from cube.c's SPIR-V: 3 position pre-loads
        // (FADD r24..r26 = u2..u0; pos.w folded to the constant 1.0), 16-op
        // column-major MVP·pos accumulation (col3 loaded as the direct bias), then
        // the 5-word epilogue FRCP r4,r3 / FMUL r0/r1/r2 *= r4 / HALT.
        val prog = Seq(
          0x01e11c00L, 0x01e09c80L, 0x01e01d00L,
          0x01ea1280L, 0x01ea9300L, 0x01eb1380L, 0x01eb9400L,
          0x29881484L, 0x31889284L, 0x39891304L, 0x41899384L,
          0x49961404L, 0x29969484L, 0x31971284L, 0x39979304L,
          0x41a41004L, 0x49a49084L, 0x29a51104L, 0x31a59184L,
          0x14018200L, 0x08400000L, 0x08408080L, 0x08410100L,
          0x00000000L)
        for ((w, i) <- prog.zipWithIndex) writeImem(core, i, BigInt(w))

        // r30/r31 read 0 only when seqBusy — the position pre-loads (u + r30) need it.
        core.io.seqBusy.poke(true.B)

        // position (u0..u2) = (1, 2, 3); pos.w is folded to 1.0 by the compiler, so
        // u3 is NOT read — set it to garbage (on real HW u3 holds color.r) to prove
        // the shader is independent of it.
        writeUniform(0, floatToBits(1.0f))
        writeUniform(1, floatToBits(2.0f))
        writeUniform(2, floatToBits(3.0f))
        writeUniform(3, floatToBits(7.0f)) // garbage — must not affect the result

        // Viewport-baked MVP, column-major in u8..u23 (u = 8 + col*4 + row):
        //   col0=[1,0,0,0] col1=[0,1,0,0] col2=[0,0,1,0] col3=[0,0,0,2]
        // → identity on x/y/z, clip_w = 2·pos.w (a power of two → exact 1/w).
        val mvp = Array(
          1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 2f)
        for (i <- 0 until 16) writeUniform(8 + i, floatToBits(mvp(i)))

        startAndWait(core)

        val sx = bitsToFloat(readReg(core, 0))
        val sy = bitsToFloat(readReg(core, 1))
        val sz = bitsToFloat(readReg(core, 2))
        // clip = MVP·pos = (1, 2, 3, 2); inv_w = 0.5; screen = (0.5, 1.0, 1.5).
        println(f"  screen = ($sx%.3f, $sy%.3f, $sz%.3f)  expected (0.5, 1.0, 1.5)")
        utest.assert(math.abs(sx - 0.5f) < 0.02f)
        utest.assert(math.abs(sy - 1.0f) < 0.03f)
        utest.assert(math.abs(sz - 1.5f) < 0.04f)
        println("  PASSED")
      }
    }

    utest.test("load_reads_dram_into_a_register") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: LOAD ---")
        idleInputs(core)
        resetCore(core)
        core.io.lsBase.get.poke(LS_BASE.U)

        val mem = scala.collection.mutable.Map[BigInt, BigInt](
          BigInt(LS_BASE + 3 * 4) -> BigInt("beef", 16))

        writeReg(core, 0, 3)                      // r0 = element index 3
        writeImem(core, 0, Instructions.LOAD(rs1 = 0, rd = 2))
        writeImem(core, 1, 0)

        startAndWaitWithMem(core, mem)
        val got = readReg(core, 2)
        println(f"  r2 = 0x${got.toInt.toHexString} (expect 0xbeef)")
        // Also proves no spurious ALU write-back: BorgLane has no decode for
        // LOAD, so an unfrozen pipeline would have written an ADD result here.
        utest.assert(got == BigInt("beef", 16))
        println("  PASSED")
      }
    }

    utest.test("store_writes_a_register_to_dram") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: STORE ---")
        idleInputs(core)
        resetCore(core)
        core.io.lsBase.get.poke(LS_BASE.U)

        val mem = scala.collection.mutable.Map[BigInt, BigInt]()
        writeReg(core, 0, 5)                      // r0 = element index 5
        writeReg(core, 1, BigInt("1234", 16))     // r1 = payload
        writeImem(core, 0, Instructions.STORE(rs1 = 0, rs2 = 1))
        writeImem(core, 1, 0)

        startAndWaitWithMem(core, mem)
        val addr = BigInt(LS_BASE + 5 * 4)
        println(f"  mem[0x${addr.toInt.toHexString}] = 0x${mem.getOrElse(addr, BigInt(0)).toInt.toHexString} (expect 0x1234)")
        utest.assert(mem.get(addr).contains(BigInt("1234", 16)))
        // STORE encodes rd = 0; that must not be mistaken for a destination.
        println(f"  r0 still ${readReg(core, 0)} (expect 5 -- rd=0 is not a write)")
        utest.assert(readReg(core, 0) == 5)
        println("  PASSED")
      }
    }

    utest.test("ztest_stalls_until_done_and_writes_no_register") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: ZTEST stall ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, 5)
        writeImem(core, 0, Instructions.ZTEST())
        writeImem(core, 1, Instructions.IADD(rs1 = 0, rs2 = 0, rd = 1))  // r1 = 10
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
        core.io.lsBase.get.poke(LS_BASE.U)
        writeReg(core, 0, 2)
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
        val (_, allW)    = run(Instructions.STORE(rs1 = 0, rs2 = 1), Seq(false, false, false, false))
        val (_, helperW) = run(Instructions.STORE(rs1 = 0, rs2 = 1), Seq(false, true, false, true))
        val (helperR, _) = run(Instructions.LOAD(rs1 = 0, rd = 2), Seq(false, true, false, true))
        // Private memory (spills, local arrays) is written by helpers too.
        val (_, privW)   = run(Instructions.STORE(rs1 = 0, rs2 = 1, `private` = true), Seq(false, true, false, true))
        println(s"  stores: $allW without helpers (expect 4), $helperW with lanes 1,3 helpers (expect 2); " +
                s"private stores $privW (expect 4); loads $helperR (expect 4)")
        utest.assert(allW == 4)
        utest.assert(helperW == 2)
        utest.assert(helperR == 4)
        utest.assert(privW == 4)
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
      for (cfg <- Seq(config, BorgConfig.Wafer)) {        // 72-word and 64-word IMEM
        simulate(new BorgCore(cfg)) { core =>
          val n = cfg.maxInstructions
          println(s"\n--- BorgCore: ${n}-word IMEM, 150-instruction program ---")
          idleInputs(core)
          val prog = countingProgram(150)
          countingRegs(core)
          val f1 = runFromImage(core, prog, preload = n)
          val r1 = readReg(core, 1)
          println(s"  run 1: r1=$r1 (expect ${countingSum(150)}), $f1 fetches from memory (expect ${prog.length - n})")
          utest.assert(r1 == countingSum(150))
          utest.assert(f1 == prog.length - n)
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
          println(s"  run 2: r1=${readReg(core, 1)} (expect ${countingSum(150)}), $f2 fetches")
          utest.assert(readReg(core, 1) == countingSum(150))
        }
      }
    }

    utest.test("programs_past_1024_words_jmp_and_page_branches") {
      // JMP reaches anywhere (18 bits); BRZ/BRNZ their own 1024-word page.
      // 0: JMP 2100. 2100: ten r1 += 10, BRNZ r2 to 2200 (page 2), skipping
      // r1 += 100 poison. 2200: five r1 += 1, JMP 1500. 1500: three
      // r1 += 100, HALT. r1 = 405; any wrong target changes it.
      import Instructions._
      for (cfg <- Seq(config, BorgConfig.Wafer)) {
        simulate(new BorgCore(cfg)) { core =>
          println(s"\n--- BorgCore: a 2300-word program, pcBits ${cfg.pcBits} ---")
          idleInputs(core)
          val img = Array.fill[BigInt](2300)(BigInt(0))
          img(0) = JMP(2100)
          for (i <- 0 until 10) img(2100 + i) = IADD(rs1 = 1, rs2 = 3, rd = 1)
          img(2110) = BRNZ(rs1 = 2, target = pageTarget(2200))
          for (i <- 2111 until 2200) img(i) = IADD(rs1 = 1, rs2 = 4, rd = 1)
          for (i <- 0 until 5) img(2200 + i) = IADD(rs1 = 1, rs2 = 2, rd = 1)
          img(2205) = JMP(1500)
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
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        // Leave a different program's words in IMEM first.
        for (i <- 0 until config.maxInstructions) writeImem(core, i, Instructions.IADD(rs1 = 1, rs2 = 1, rd = 1))
        val prog = countingProgram(150)
        countingRegs(core)
        val f = runFromImage(core, prog, preload = 20)
        println(s"  r1=${readReg(core, 1)} (expect ${countingSum(150)}), fetches=$f (expect ${prog.length - 20})")
        utest.assert(readReg(core, 1) == countingSum(150))
        utest.assert(f == prog.length - 20)
      }
    }

    utest.test("icache_program_that_fits_never_touches_memory") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        val prog = countingProgram(config.maxInstructions - 1)   // exactly fills IMEM
        countingRegs(core)
        val f = runFromImage(core, prog, preload = prog.length)
        println(s"  r1=${readReg(core, 1)} (expect ${countingSum(prog.length - 1)}), fetches=$f (expect 0)")
        utest.assert(readReg(core, 1) == countingSum(prog.length - 1))
        utest.assert(f == 0)
      }
    }

    utest.test("icache_loop_past_imem_hits_after_the_first_iteration") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        // Words 0..89 straight-line padding (r1 += r2), then a 4-word loop at
        // 90..93 that counts r3 down from 10, all past the 72-word IMEM.
        val pad = Seq.fill(90)(Instructions.IADD(rs1 = 1, rs2 = 2, rd = 1))
        val loop = Seq(
          Instructions.IADD(rs1 = 4, rs2 = 2, rd = 4),            // r4 += 1 (loop body)
          Instructions.ISUB(rs1 = 3, rs2 = 2, rd = 3),            // r3 -= 1
          Instructions.BRNZ(rs1 = 3, target = 90),
          BigInt(0))
        val prog = pad ++ loop
        writeReg(core, 1, 0); writeReg(core, 2, 1); writeReg(core, 3, 10); writeReg(core, 4, 0)
        val f = runFromImage(core, prog, preload = config.maxInstructions)
        println(s"  r1=${readReg(core, 1)} (expect 90) r4=${readReg(core, 4)} (expect 10), fetches=$f (expect ${prog.length - config.maxInstructions}: each far word once)")
        utest.assert(readReg(core, 1) == 90)
        utest.assert(readReg(core, 4) == 10)
        utest.assert(f == prog.length - config.maxInstructions)
      }
    }

    utest.test("store_then_load_round_trips_through_memory") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: STORE then LOAD ---")
        idleInputs(core)
        resetCore(core)
        core.io.lsBase.get.poke(LS_BASE.U)

        val mem = scala.collection.mutable.Map[BigInt, BigInt]()
        writeReg(core, 0, 7)
        writeReg(core, 1, BigInt("cafe", 16))
        // Two memory instructions back to back: the second must not inherit
        // any state from the first (the FSM has to have fully returned to
        // idle and the resume-delay has to have cleared).
        writeImem(core, 0, Instructions.STORE(rs1 = 0, rs2 = 1))
        writeImem(core, 1, Instructions.LOAD(rs1 = 0, rd = 3))
        writeImem(core, 2, 0)

        startAndWaitWithMem(core, mem)
        val got = readReg(core, 3)
        println(f"  r3 = 0x${got.toInt.toHexString} (expect 0xcafe)")
        utest.assert(got == BigInt("cafe", 16))
        println("  PASSED")
      }
    }

    utest.test("effective_address_is_base_plus_index_times_four") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: effective address ---")
        idleInputs(core)
        resetCore(core)

        // The addressing rule is the part a compiler has to agree with, so it
        // is checked directly against emitted addresses rather than inferred
        // from a value round-tripping.
        for ((base, index) <- Seq((0x1000, 0), (0x1000, 1), (0x2000, 255), (0x0, 1024))) {
          // Each iteration is a fresh program: without the reset the program
          // counter stays past the previous halt and nothing executes.
          resetCore(core)
          core.io.lsBase.get.poke(base.U)
          val mem = scala.collection.mutable.Map[BigInt, BigInt]()
          writeReg(core, 0, index)
          writeImem(core, 0, Instructions.STORE(rs1 = 0, rs2 = 0))
          writeImem(core, 1, 0)
          startAndWaitWithMem(core, mem)
          val expected = BigInt(base + index * 4)
          val touched = mem.keys.toSeq
          println(f"  base=0x$base%x index=$index%4d -> ${touched.map(a => "0x" + a.toInt.toHexString).mkString(",")} (expect 0x${expected.toInt.toHexString})")
          utest.assert(touched == Seq(expected))
        }
        println("  PASSED")
      }
    }

    utest.test("a_stalled_load_does_not_advance_until_memory_answers") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: LOAD stalls the pipeline ---")
        idleInputs(core)
        resetCore(core)
        core.io.lsBase.get.poke(LS_BASE.U)

        writeReg(core, 0, 1)
        writeImem(core, 0, Instructions.LOAD(rs1 = 0, rd = 2))
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

        writeReg(core, 0, 0)              // condition == 0 -> branch taken
        writeReg(core, 1, floatToBits(1.0f))
        writeReg(core, 2, floatToBits(0.0f))
        // 0: BRZ r0 -> 2      (skip the add at slot 1)
        // 1: r2 = r1 + r1     (must NOT execute)
        // 2: halt
        writeImem(core, 0, Instructions.BRZ(rs1 = 0, target = 2))
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

        writeReg(core, 0, 1)              // condition != 0 -> fall through
        writeReg(core, 1, floatToBits(1.0f))
        writeReg(core, 2, floatToBits(0.0f))
        writeImem(core, 0, Instructions.BRZ(rs1 = 0, target = 2))
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

        // The target's low 5 bits land in the rd field. Target 3 therefore
        // names r3; an ALU write-back would clobber it. This is the failure
        // mode BorgLane's `!opFlags.branch` guard exists for.
        writeReg(core, 0, 1)                            // not taken
        writeReg(core, 3, floatToBits(7.0f))        // sentinel in r3
        writeImem(core, 0, Instructions.BRZ(rs1 = 0, target = 3))
        writeImem(core, 1, 0)

        startAndWait(core)
        val r3 = bitsToFloat(readReg(core, 3))
        println(f"  r3 = $r3%.2f (expect 7.0 -- untouched)")
        utest.assert(math.abs(r3 - 7.0f) < 0.01f)
        println("  PASSED")
      }
    }

  }
}
