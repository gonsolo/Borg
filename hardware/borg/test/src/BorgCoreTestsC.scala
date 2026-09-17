// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgCoreTestsC extends TestSuite {
  import BorgCoreTestHelpers._

  val tests = Tests {

    utest.test("ftex_narrows_fp32_coordinates") {
      // FTEX's operands are datapath floats: the fragment shader interpolates
      // texture coordinates in the FP32 ALU. The texture unit is FP16-native,
      // so the core must hand it FP16 values, not the low 16 bits of the FP32
      // pattern (0.5f = 0x3F000000 -> 0x0000, i.e. texel (0,0) everywhere).
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: FTEX narrows FP32 coordinates ---")
        idleInputs(core)
        resetCore(core)
        writeReg(core, 0, BigInt("3F000000", 16))   // u = 0.5
        writeReg(core, 1, BigInt("3E800000", 16))   // v = 0.25
        writeImem(core, 0, Instructions.FTEX(rs1 = 0, rs2 = 1, rd = 2))
        writeImem(core, 1, 0)

        core.io.control.start.poke(true.B); core.clock.step(1); core.io.control.start.poke(false.B)
        val seen = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
        var wd = 0
        while (core.io.status.running.peek().litToBoolean && wd < 2000) {
          if (core.io.texReq.peek().litToBoolean) {
            seen += ((core.io.texU.peek().litValue.toInt, core.io.texV.peek().litValue.toInt))
            core.io.texR.poke(0x3C00.U); core.io.texG.poke(0x3C00.U); core.io.texB.poke(0x3C00.U)
            core.io.texDone.poke(true.B)
          } else core.io.texDone.poke(false.B)
          core.clock.step(1); wd += 1
        }
        core.io.texDone.poke(false.B)
        println(f"  texture requests (u, v): ${seen.distinct.map { case (u, v) => f"(0x$u%04x, 0x$v%04x)" }.mkString(" ")}")
        utest.assert(wd < 2000)
        utest.assert(seen.nonEmpty)
        utest.assert(seen.forall(_ == ((0x3800, 0x3400))))
        // The FP16 texel comes back widened: 1.0 in the datapath's own format.
        utest.assert(readReg(core, 2) == BigInt("3F800000", 16))
      }
    }

    utest.test("borgc_fragment_cube") {
      // End-to-end execution of the borgc-compiled cube.frag (56-word blob) in the
      // 4-lane SIMT core. Per-lane edge functions come from a 3-instr preamble
      // (r0=coordX, r1=coordY, r2=coordX+coordY) so ddx/ddy of the interpolated
      // frag_pos are non-zero. FTEX returns a constant texel. The chosen inputs make
      // the lighting hand-computable: ddx=(2,1,1), ddy=(3,3,0), normal∝(-1,1,1),
      // light=dot(lightDir,normal)≈0.490, so colour≈sRGB(0.490·texel).
      // Pinned to FP16 on BOTH sides -- the DUT config here and the
      // floatToFp16Bits() calls below, rather than the config-aware
      // floatToBits(). Pinning only the DUT left the helpers still emitting
      // FP32 patterns (they follow BorgCoreTestHelpers' own `config`, which
      // is Default), poking 32-bit values into 16-bit registers.
      //
      // Pinned to FP16: the 56-word blob below is literal borgc output
      // compiled for the FP16 datapath, and the expectations are that exact
      // program's exact results. BorgConfig.Simt inherits fp from Default,
      // which became FP32 on 2026-09-15 -- running an FP16-compiled blob on
      // an FP32 core tests neither one.
      simulate(new BorgCore(BorgConfig.Simt.copy(fp = FloatConfig.FP16))) { core =>
        println("\n--- BorgCore: borgc_fragment_cube ---")
        idleInputs(core)
        val qx = Seq(4, 5, 4, 5); val qy = Seq(4, 4, 5, 5) // 2×2 quad
        for (i <- 0 until 4) { core.io.iter(i).x.poke(qx(i).U); core.io.iter(i).y.poke(qy(i).U) }
        core.io.seqBusy.poke(false.B) // r30/r31 = per-lane pixel centre
        resetCore(core)

        def wu(idx: Int, f: Float): Unit = writeCore(core, 432 + idx * 4, floatToFp16Bits(f))
        // lightDir → reserved GPRs r17/18/19 (firmware writes these via MMIO before
        // the autonomous render).  They must survive the WHOLE pipeline: the vertex
        // shaders use r24/25/26 and setup/rast use r0-11, so r17-19 is the safe home
        // (r23-25 would be clobbered by the vertex pass — invisible to this isolated
        // fragment test, caught by decoding the vertex shaders' register use).
        writeReg(core, 17, floatToFp16Bits(0.424f))
        writeReg(core, 18, floatToFp16Bits(0.566f))
        writeReg(core, 19, floatToFp16Bits(0.707f))
        wu(12, 1.0f)                                  // inv_area
        for (u <- 13 to 18) wu(u, 0.5f)               // texcoord (unused — constant texel)
        // frag_pos per vertex, (v2,v1,v0): x=u19-21, y=u22-24, z=u25-27.
        wu(19, 2.0f); wu(20, 1.0f); wu(21, 0.0f)      // x: v2=2, v1=1, v0=0
        wu(22, 1.0f); wu(23, 2.0f); wu(24, 0.0f)      // y: v2=1, v1=2, v0=0
        wu(25, 0.0f); wu(26, 0.0f); wu(27, 1.0f)      // z: v2=0, v1=0, v0=1
        for (u <- 28 to 30) wu(u, 0.5f)               // z varying
        wu(31, 32.0f)                                 // DDX rescale constant (firmware ABI)
        writeReg(core, 4, 0)                          // preamble zero scratch (r4 reserved)

        // Preamble → per-lane edges in r0/r1/r2.
        writeImem(core, 0, Instructions.ADD(30, 4, 0))  // r0 = coordX
        writeImem(core, 1, Instructions.ADD(31, 4, 1))  // r1 = coordY
        writeImem(core, 2, Instructions.ADD(30, 31, 2)) // r2 = coordX+coordY
        // The EXACT 59 words emitted by borgc (BORGC_DUMP_ISA), including the
        // 3 DDX-rescale FMULs (×u31=32.0, right after the 3 DDX ops) — the
        // FP16-underflow fix; must match borgc_frag_shader in
        // software/borg/borg_driver.c word-for-word.
        val frag = Seq(
          0x08c02180L, 0x08c0a280L, 0x08c12300L, 0x0951a380L, 0x3942a404L, 0x41332384L,
          0x0981a400L, 0x4172a484L, 0x49632404L, 0x09b1a480L, 0x49a2a504L, 0x51932484L,
          0x3c038500L, 0x3c040580L, 0x3c048600L, 0x09f52680L, 0x09f5a500L, 0x09f62580L,
          0x40038600L, 0x40040380L, 0x40048400L, 0x08758480L, 0x08868700L, 0x08c50780L,
          0x0c048800L, 0x0c070480L, 0x0c078700L, 0x80850784L, 0x48c58804L, 0x70768484L,
          0x09080700L, 0x70948384L, 0x38f78704L, 0x34070380L, 0x08778700L, 0x08780780L,
          0x08748800L, 0x08f90380L, 0x39098784L, 0x78e88384L, 0x10038780L, 0x08f38700L,
          0x08f1a780L, 0x78e2a384L, 0x38d32784L, 0x0921a380L, 0x3912a804L, 0x81032384L,
          0x18778a00L, 0x08ea0380L, 0x08ea8800L, 0x08eb0780L, 0x38038d00L, 0x38080d80L,
          0x38078e00L, 0x09e1a780L, 0x79d2a184L, 0x19c32e84L, 0x00000000L)
        for ((w, i) <- frag.zipWithIndex) writeImem(core, 3 + i, BigInt(w))

        def runFrag(texel: Float): (Float, Float, Float, Float) = {
          resetCore(core)
          core.io.texDone.poke(true.B) // FTEX completes same-cycle with this texel
          core.io.texR.poke(floatToFp16Bits(texel).U)
          core.io.texG.poke(floatToFp16Bits(texel).U)
          core.io.texB.poke(floatToFp16Bits(texel).U)
          core.io.control.start.poke(true.B); core.clock.step(1); core.io.control.start.poke(false.B)
          var wd = 0
          while (core.io.status.running.peek().litToBoolean && wd < 1000) { core.clock.step(1); wd += 1 }
          utest.assert(wd < 1000) // finished
          (fp16BitsToFloat(readReg(core, 26)), fp16BitsToFloat(readReg(core, 27)),
           fp16BitsToFloat(readReg(core, 28)), fp16BitsToFloat(readReg(core, 29)))
        }

        val (r1, g1, b1, z1) = runFrag(0.5f)
        val (r2, g2, b2, _) = runFrag(1.0f)
        println(f"  texel=0.5 → colour=($r1%.3f, $g1%.3f, $b1%.3f) z=$z1%.3f  (expect ~0.53)")
        println(f"  texel=1.0 → colour=($r2%.3f, $g2%.3f, $b2%.3f)         (expect ~0.73)")
        // Colour: the full lighting+texture+sRGB pipeline, hand-computed exact.
        for (v <- Seq(r1, g1, b1, r2, g2, b2)) utest.assert(!v.isNaN && v >= -0.02f && v <= 1.02f)
        utest.assert(math.abs(r1 - 0.53f) < 0.06f && math.abs(g1 - 0.53f) < 0.06f && math.abs(b1 - 0.53f) < 0.06f)
        utest.assert(math.abs(r2 - 0.73f) < 0.06f && math.abs(g2 - 0.73f) < 0.06f && math.abs(b2 - 0.73f) < 0.06f)
        // z-interp output (now that IMEM is 72-deep, the full 62-instruction program
        // loads): z = 0.5·Σwᵢ = 0.5·(4.5+4.5+9.0) = 9.0 with these synthetic edges.
        utest.assert(math.abs(z1 - 9.0f) < 0.05f)

        // Small-derivative regression (the 128×128 "white cube" bug): shrink the
        // frag_pos vertex values ×1/32 so |cross(ddx,ddy)|² lands in the FP16
        // SUBNORMAL range (≈2.6e-5 < 6.1e-5).  Without the DDX-rescale FMULs the
        // FRSQ sees a subnormal and returns +Inf → colour saturates to 1.0; with
        // them the result must match the large-derivative case (normalize is
        // scale-invariant).
        wu(19, 2.0f / 32); wu(20, 1.0f / 32); wu(21, 0.0f)
        wu(22, 1.0f / 32); wu(23, 2.0f / 32); wu(24, 0.0f)
        wu(25, 0.0f);      wu(26, 0.0f);      wu(27, 1.0f / 32)
        val (r3, g3, b3, _) = runFrag(0.5f)
        println(f"  texel=0.5, frag_pos/32 → colour=($r3%.3f, $g3%.3f, $b3%.3f) (expect ~0.53, NOT 1.0)")
        utest.assert(math.abs(r3 - 0.53f) < 0.06f && math.abs(g3 - 0.53f) < 0.06f && math.abs(b3 - 0.53f) < 0.06f)
        println("  PASSED — faithful cube.frag lighting+texture+sRGB+depth bit-correct")
      }
    }

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
