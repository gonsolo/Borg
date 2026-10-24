// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSimFast.{simulate,
  toTestableClock, toTestableSInt, toTestableUInt, toTestableBool,
  toTestableReset, toTestableEnum, toTestableRecord, toTestableVec, toTestableData
}
import utest._
import scala.language.implicitConversions
import BorgGpuMemWordTests.{HalfwordDram, mmioRead, mmioWrite}

/** vkCmdDispatch through BorgComputeSequencer, on full Borg.
  *
  * Each shader stores one word per invocation at an address derived from the
  * invocation's IDs, so the DRAM afterwards shows which invocations ran, with
  * which IDs, and that nothing else was written.
  */
object BorgComputeTests extends TestSuite {

  val lsBase   = 0x1000
  // Programs address words: SLLI and ADD of the base in r28 (r29 scratch), then LW or SW.
  private val Base = 28
  private implicit def one(w: BigInt): Seq[BigInt] = Seq(w)
  private def words(ws: Seq[BigInt]*): Seq[BigInt] = ws.flatten
  private def addr(a: Int): Seq[BigInt] = Seq(Opcodes.SLLI(29, a, 2), Opcodes.ADD(29, 29, Base))
  private def st(a: Int, v: Int): Seq[BigInt] = addr(a) :+ Opcodes.SW(rs1 = 29, rs2 = v)
  private def ld(a: Int, d: Int): Seq[BigInt] = addr(a) :+ Opcodes.LW(rd = d, rs1 = 29)
  val scalar   = BorgConfig.Test                           // fragLanes = 1
  val quad     = BorgConfig.Simt.copy(maxBinTiles = 64)    // fragLanes = 4

  private def off(r: UInt): Int = r.litValue.toInt
  def packed(x: Int, y: Int, z: Int): BigInt = BigInt(x | (y << 10) | (z << 20))

  /** Run `prog` (IMEM 0, HALT appended) over `groups` workgroups of `local`
    * invocations, with the given GPRs preset, and return the DRAM. */
  def dispatch(d: BorgGpuMemWordTests.Dut, prog: Seq[BigInt], gprs: Map[Int, Int],
               groups: (Int, Int, Int), local: (Int, Int, Int),
               setup: HalfwordDram => Unit = _ => ()): HalfwordDram = {
    val dram = new HalfwordDram
    d.io.data_write_n.poke(3.U)
    d.io.data_read_n.poke(3.U)
    d.io.gpuMem.data.poke(0.U)
    dram.step(d)
    mmioWrite(d, dram, off(BorgGpuRegs.control_offset), 2)
    mmioWrite(d, dram, off(BorgGpuRegs.gpr_offset) + 4 * Base, lsBase)
    for ((r, v) <- gprs) mmioWrite(d, dram, off(BorgGpuRegs.gpr_offset) + 4 * r, v)
    setup(dram)
    for ((w, i) <- (prog :+ BigInt(0)).zipWithIndex)
      mmioWrite(d, dram, off(BorgGpuRegs.imem_offset) + 4 * i, w)

    val (gx, gy, gz) = groups
    val (sx, sy, sz) = local
    mmioWrite(d, dram, off(BorgGpuRegs.compute_pc_offset), 0)
    mmioWrite(d, dram, off(BorgGpuRegs.compute_groups_xy_offset), gx | (gy << 16))
    mmioWrite(d, dram, off(BorgGpuRegs.compute_groups_z_offset), gz)
    mmioWrite(d, dram, off(BorgGpuRegs.compute_local_offset), sx | (sy << 8) | ((sx * sy * sz) << 16))
    mmioWrite(d, dram, off(BorgGpuRegs.compute_ctrl_offset), 1)

    var status = BigInt(0)
    var n = 0
    do {
      for (_ <- 0 until 50) dram.step(d)
      status = mmioRead(d, dram, off(BorgGpuRegs.compute_ctrl_offset))
      n += 1
    } while ((status & 1) == 0 && n < 400)
    println(f"  COMPUTE_CTRL = 0x$status%x after $n polls")
    utest.assert((status & 1) == 1) // done
    utest.assert((status & 2) == 0) // not busy
    utest.assert((status & 4) == 4) // present
    dram
  }

  def word(dram: HalfwordDram, i: Int): BigInt = BigInt(dram.word(lsBase + 4 * i))
  def wordsWritten(dram: HalfwordDram): Int = dram.mem.size / 2 // two halfwords per word

  // r1 = WorkgroupID.x (u29), r2 = WorkgroupID.y (u30); store r31 at
  // ((wgY * groupsX + wgX) * count + LocalInvocationIndex).
  val flatIdsProgram = words(
    Opcodes.CSRRS(1, 0, Csr.GroupIdX),
    Opcodes.CSRRS(2, 0, Csr.GroupIdY),
    Instructions.IMUL(rs1 = 2, rs2 = 11, rd = 2),
    Instructions.IADD(rs1 = 2, rs2 = 1, rd = 2),
    Instructions.IMUL(rs1 = 2, rs2 = 12, rd = 2),
    Instructions.IADD(rs1 = 2, rs2 = 30, rd = 2),
    st(2, 31)
  )

  /** 3x2 workgroups of 5 invocations: every (workgroup, invocation) pair stores
    * its own LocalInvocationID exactly once. At fragLanes = 4, 5 invocations is
    * one full quad plus one with three lanes masked -- a masked lane that
    * stored would land at index 30..32. */
  def flatIdsScenario(d: BorgGpuMemWordTests.Dut): Unit = {
    val dram = dispatch(d, flatIdsProgram, Map(10 -> 0, 11 -> 3, 12 -> 5), (3, 2, 1), (5, 1, 1))
    for (g <- 0 until 6; l <- 0 until 5) {
      val got = word(dram, g * 5 + l)
      if (got != packed(l, 0, 0)) println(s"  wg $g inv $l: got 0x${got.toString(16)}")
      utest.assert(got == packed(l, 0, 0))
    }
    println(s"  ${wordsWritten(dram)} words written (expected 30)")
    utest.assert(wordsWritten(dram) == 30)
  }

  val tests = Tests {
    utest.test("dispatch_scalar_stores_every_invocation_once") {
      simulate(new BorgTestWrapper(scalar), additionalResetCycles = 4) { d => flatIdsScenario(d) }
    }

    utest.test("dispatch_quad_masks_a_partial_last_quad") {
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d => flatIdsScenario(d) }
    }

    utest.test("dispatch_quad_3d_local_ids") {
      // LocalSize (2, 3, 2) = 12 invocations = three full quads; two
      // workgroups along z. Store r31 at wgZ * 12 + LocalInvocationIndex.
      val prog = words(
        Opcodes.CSRRS(1, 0, Csr.GroupIdZ),
        Instructions.IMUL(rs1 = 1, rs2 = 12, rd = 1),
        Instructions.IADD(rs1 = 1, rs2 = 30, rd = 1),
        st(1, 31)
      )
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, Map(10 -> 0, 12 -> 12), (1, 1, 2), (2, 3, 2))
        for (z <- 0 until 2; l <- 0 until 12) {
          val want = packed(l % 2, (l / 2) % 3, l / 6)
          utest.assert(word(dram, z * 12 + l) == want)
        }
        utest.assert(wordsWritten(dram) == 24)
      }
    }

    utest.test("dispatch_of_zero_groups_completes_without_running") {
      simulate(new BorgTestWrapper(scalar), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, flatIdsProgram, Map(10 -> 0, 11 -> 3, 12 -> 5), (0, 2, 1), (5, 1, 1))
        utest.assert(wordsWritten(dram) == 0)
      }
    }

    // -- Phase 2: BARRIER ---------------------------------------------------

    /** Every invocation stores 1000+i to shared[100+i], BARRIERs, then reads
      * shared[100 + ((i+count/2) & (count-1))] -- its "opposite half" neighbour
      * -- and stores that into output[i]. For i in the FIRST half this reads a
      * LATER quad's phase-1 write: correct only if BorgComputeSequencer really
      * runs every quad's phase 1 before any quad's phase 2. Without the
      * barrier (or with it broken), the first half would read whatever was in
      * DRAM before the dispatch (0 here), not 1000+neighbour.
      */
    def barrierScenario(d: BorgGpuMemWordTests.Dut, count: Int): Unit = {
      val half = count / 2
      val prog = words(
        Instructions.IADD(rs1 = 10, rs2 = 30, rd = 1),        // r1 = 100 + i
        Instructions.IADD(rs1 = 30, rs2 = 11, rd = 2),        // r2 = 1000 + i
        st(1, 2),                 // shared[100+i] = 1000+i
        Instructions.BARRIER(),
        Instructions.IADD(rs1 = 30, rs2 = 12, rd = 3),        // r3 = i + half
        Instructions.IAND(rs1 = 3, rs2 = 13, rd = 3),         // r3 = (i+half) & (count-1) = j
        Instructions.IADD(rs1 = 10, rs2 = 3, rd = 4),         // r4 = 100 + j
        ld(4, 5),                   // r5 = shared[100+j]
        st(30, 5)                 // output[i] = r5
      )
      val gprs = Map(10 -> 100, 11 -> 1000, 12 -> half, 13 -> (count - 1))
      val dram = dispatch(d, prog, gprs, (1, 1, 1), (count, 1, 1))
      for (i <- 0 until count) {
        val j = (i + half) & (count - 1)
        val got = word(dram, i)
        if (got != 1000 + j) println(s"  output[$i] = $got (expected ${1000 + j}, j=$j)")
        utest.assert(got == 1000 + j)
      }
    }

    utest.test("barrier_synchronizes_across_quads") {
      // count=8, fragLanes=4: two quads. i=0..3 (quad 0) reads shared[104..107],
      // written by quad 1 -- the case that can only pass with a real barrier.
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d => barrierScenario(d, 8) }
    }

    utest.test("barrier_synchronizes_across_quads_scalar") {
      // count=8, fragLanes=1: eight one-lane quads -- an 8-way barrier, not
      // just a 2-way one.
      simulate(new BorgTestWrapper(scalar), additionalResetCycles = 4) { d => barrierScenario(d, 8) }
    }

    utest.test("divergent_barrier_sets_fault_but_still_completes") {
      // quadIdx = LocalInvocationIndex >> 2 is uniform WITHIN a quad (all 4
      // lanes of one quad share it) but differs BETWEEN quads -- a valid,
      // non-branch-divergent condition to build an invalid (non-uniform
      // control flow through OpControlBarrier) program from: quad 0 (i=0..3,
      // quadIdx=0) falls through to BARRIER; quad 1 (i=4..7, quadIdx=1)
      // branches around it straight to HALT.
      val prog = Seq(
        Instructions.ISHR(rs1 = 30, rs2 = 10, rd = 1),  // r1 = i >> 2 = quadIdx
        Instructions.BRNZ(rs1 = 1, offset = 2),         // quadIdx != 0: skip the barrier
        Instructions.BARRIER()
        // index 3 (implicit HALT) is BRNZ's target
      )
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, Map(10 -> 2), (1, 1, 1), (8, 1, 1))
        val status = mmioRead(d, dram, off(BorgGpuRegs.compute_ctrl_offset))
        println(f"  COMPUTE_CTRL = 0x$status%x (expect bit3 barrier_fault set)")
        utest.assert((status & 1) == 1)  // still completed (done), not hung
        utest.assert((status & 8) == 8)  // barrier_fault
      }
    }

    // -- Phase 3: hand-written ISA patterns borgc will need to emit ---------
    //
    // Neither pattern below is new hardware -- both are proofs, ahead of the
    // compiler that will generate them, that the primitives Phase 1/2 already
    // built are actually sufficient. Each has a companion test that removes
    // the pattern and confirms the predicted, specific wrong answer, not just
    // "some assertion fails somewhere" -- the same discipline used to verify
    // the barrier tests themselves above.

    utest.test("atomic_add_via_per_lane_expush_is_race_free") {
      // atomicAdd(counter, 1) once per invocation, all 4 lanes of one quad
      // sharing one address: EXPUSH(LocalInvocationIndex == i) around each
      // lane's own LOAD/IADD/STORE serializes them into the program stream --
      // wireMemStall already skips a masked lane's memory access AND its
      // write-back entirely (BorgCore's own doc), so only one lane's
      // read-modify-write is ever real at a time, and lane i+1's LOAD does
      // not even issue until lane i's STORE has landed in DRAM. r30 differs
      // per lane, so ISEQ(r30, i) is genuinely a per-lane, not per-quad, mask.
      val prog = words(
        Instructions.ISEQ(rs1 = 30, rs2 = 10, rd = 1), Instructions.EXPUSH(rs1 = 1),
        ld(14, 5), Instructions.IADD(rs1 = 5, rs2 = 15, rd = 5),
        st(14, 5), Instructions.EXPOP(),
        Instructions.ISEQ(rs1 = 30, rs2 = 11, rd = 1), Instructions.EXPUSH(rs1 = 1),
        ld(14, 5), Instructions.IADD(rs1 = 5, rs2 = 15, rd = 5),
        st(14, 5), Instructions.EXPOP(),
        Instructions.ISEQ(rs1 = 30, rs2 = 12, rd = 1), Instructions.EXPUSH(rs1 = 1),
        ld(14, 5), Instructions.IADD(rs1 = 5, rs2 = 15, rd = 5),
        st(14, 5), Instructions.EXPOP(),
        Instructions.ISEQ(rs1 = 30, rs2 = 13, rd = 1), Instructions.EXPUSH(rs1 = 1),
        ld(14, 5), Instructions.IADD(rs1 = 5, rs2 = 15, rd = 5),
        st(14, 5), Instructions.EXPOP()
      )
      // r10-13 = the four lane indices; r14 = counter address (index 50,
      // preset to 0 so the counter starts there rather than DRAM garbage);
      // r15 = 1 (the increment).
      val gprs = Map(10 -> 0, 11 -> 1, 12 -> 2, 13 -> 3, 14 -> 50, 15 -> 1)
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, gprs, (1, 1, 1), (4, 1, 1))
        val counter = word(dram, 50)
        println(s"  counter = $counter (expected 4)")
        utest.assert(counter == 4)
      }
    }

    utest.test("atomic_add_without_expush_loses_updates") {
      // Same four lanes, same shared counter, but all issue LOAD/IADD/STORE
      // unconditionally: every lane's LOAD reads the SAME pre-increment value
      // (no lane's STORE has landed yet, since all four LOADs happen in the
      // same instruction), so all four STOREs write the same result and three
      // of the four increments are lost. This is the failure mode
      // atomic_add_via_per_lane_expush_is_race_free's masking exists to
      // prevent, made concrete rather than asserted in the abstract.
      val prog = words(
        ld(14, 5),
        Instructions.IADD(rs1 = 5, rs2 = 15, rd = 5),
        st(14, 5)
      )
      val gprs = Map(14 -> 50, 15 -> 1)
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, gprs, (1, 1, 1), (4, 1, 1))
        val counter = word(dram, 50)
        println(s"  counter = $counter (expected 1, NOT 4 -- three updates lost)")
        utest.assert(counter == 1)
      }
    }

    utest.test("live_register_survives_barrier_only_if_spilled_to_dram") {
      // Every invocation computes a private "live value" (i*100+7), spills it
      // to a per-invocation DRAM slot, BARRIERs, recomputes the slot address
      // (r30 is safe to reuse across a barrier -- re-derived fresh from
      // ComputeLaneIO on every trigger, never register-file state -- but any
      // ordinary GPR is not, see the companion test below), reloads, and
      // writes it to the output. scalar (fragLanes=1): every invocation is
      // its own quad, so between one invocation's barrier-stop and its own
      // resume, the other 7 invocations' full segment-0 runs intervene and
      // physically overwrite the one shared register file -- this only
      // passes if the spill/reload genuinely defeats that, not because
      // nothing ever clobbers the registers in this config.
      val prog = words(
        Instructions.IMUL(rs1 = 30, rs2 = 10, rd = 1),  // r1 = i*100
        Instructions.IADD(rs1 = 1, rs2 = 11, rd = 1),   // r1 = i*100+7 (the live value)
        Instructions.IADD(rs1 = 12, rs2 = 30, rd = 2),  // r2 = scratchBase + i
        st(2, 1),           // scratch[i] = live value
        Instructions.BARRIER(),
        Instructions.IADD(rs1 = 12, rs2 = 30, rd = 3),  // r3 = scratchBase + i, recomputed fresh
        ld(3, 4),             // r4 = reloaded live value
        st(30, 4)           // output[i] = r4
      )
      val gprs = Map(10 -> 100, 11 -> 7, 12 -> 200)
      simulate(new BorgTestWrapper(scalar), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, gprs, (1, 1, 1), (8, 1, 1))
        for (i <- 0 until 8) {
          val got = word(dram, i)
          if (got != i * 100 + 7) println(s"  output[$i] = $got (expected ${i * 100 + 7})")
          utest.assert(got == i * 100 + 7)
        }
      }
    }

    utest.test("live_register_without_spill_reads_back_stale_after_barrier") {
      // Same shape, but reuses r1 directly after the barrier instead of
      // spilling/reloading through DRAM. Segment 0 runs all 8 invocations in
      // order (0..7), each overwriting the one shared r1 with its own
      // i*100+7; by the time segment 1 starts, r1 holds whatever invocation 7
      // (the last to run segment 0) left there -- 707 -- and nothing in
      // segment 1 writes r1 again, so every invocation's output reads that
      // same frozen, wrong value.
      val prog = words(
        Instructions.IMUL(rs1 = 30, rs2 = 10, rd = 1),
        Instructions.IADD(rs1 = 1, rs2 = 11, rd = 1),
        Instructions.BARRIER(),
        st(30, 1)  // output[i] = r1, NOT recomputed/reloaded
      )
      val gprs = Map(10 -> 100, 11 -> 7)
      simulate(new BorgTestWrapper(scalar), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, gprs, (1, 1, 1), (8, 1, 1))
        for (i <- 0 until 8) {
          val got = word(dram, i)
          if (got != 707) println(s"  output[$i] = $got (expected the stale 707)")
          utest.assert(got == 707)
        }
      }
    }

    utest.test("tex_takes_the_descriptor_address_of_the_lowest_active_lane") {
      // Lane 0 is masked off while lanes 1-3 load texture 1's descriptor address and sample;
      // lane 0's register still holds texture 0's. Texture 0 is red 0, texture 1 red 255.
      val (tex, desc, samp) = (0x2000, 0x3000, 0x3100)
      val ctl = SamplerCtl.LodExplicit << 21
      val prog = words(
        Opcodes.TEXAD(rs1 = 10, rs2 = 10, rs3 = 10), Opcodes.TEXS(16, 12),
        Instructions.IADD(rs1 = 17, rs2 = 10, rd = 1),
        Instructions.ISEQ(rs1 = 30, rs2 = 10, rd = 2), Instructions.EXPUSH(rs1 = 2), Instructions.EXELSE(),
        Opcodes.ADDI(1, 17, 64),
        Opcodes.TEXD(20, 14, 14, 1),
        Instructions.EXPOP(),
        st(30, 20)
      )
      val gprs = Map(10 -> 0, 12 -> ctl, 14 -> java.lang.Float.floatToRawIntBits(0.5f), 16 -> samp, 17 -> desc, 20 -> 0x12345)
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        def put(dram: HalfwordDram, a: Int, w: Long): Unit = { dram.mem(a) = (w & 0xFFFF).toInt; dram.mem(a + 2) = ((w >> 16) & 0xFFFF).toInt }
        val dram = dispatch(d, prog, gprs, (1, 1, 1), (4, 1, 1), dram => {
          val fm = TexFormat.byName("R8G8B8A8_UNORM")
          for (k <- 0 until 2) {
            put(dram, tex + 16 * k, if (k == 0) 0xFF000000L else 0xFF0000FFL)
            for ((w, i) <- SamplerCtl.descHeader(tex + 16 * k, 1, 1, 1, 1, fm.code, SamplerCtl.Linear, SamplerCtl.T2D, 4).zipWithIndex)
              put(dram, desc + 64 * k + 4 * i, w)
          }
          put(dram, samp, SamplerCtl.ClampEdge << 3 | SamplerCtl.ClampEdge << 6)
        })
        val got = (0 until 4).map(word(dram, _))
        println(s"  red per lane: ${got.map(_.toString(16)).mkString(" ")}")
        utest.assert(got == Seq(BigInt(0x12345), BigInt(0x3f800000), BigInt(0x3f800000), BigInt(0x3f800000)))
      }
    }

    utest.test("flog2_and_fexp2_per_lane") {
      import Opcodes._
      // Lane i: r2 = log2(i + 1), r3 = exp2(r2) = i + 1, each lane its own value.
      val prog = words(ADDI(1, 30, 1), FCVT_S_W(1, 1), FLOG2(2, 1), FEXP2(3, 2), st(30, 2), ADDI(4, 30, 8), st(4, 3))
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, Map(10 -> 0), (1, 1, 1), (4, 1, 1))
        val l = (0 until 4).map(i => java.lang.Float.intBitsToFloat(word(dram, i).toInt))
        val e = (0 until 4).map(i => java.lang.Float.intBitsToFloat(word(dram, 8 + i).toInt))
        println(s"  log2: $l  exp2: $e")
        utest.assert((0 until 4).forall(i => math.abs(l(i) - math.log(i + 1) / math.log(2)) < 1e-6 && e(i) == (i + 1).toFloat))
      }
    }

    utest.test("qballot_and_qshuf") {
      import Opcodes._
      // QBALLOT of (lane & 1): 0b1010 in every lane. QSHUF: lane i reads lane i ^ 3's 10 * lane.
      val prog = words(ANDI(1, 30, 1), QBALLOT(2, 1), SLLI(3, 30, 3), SLLI(4, 30, 1), ADD(3, 3, 4),
        XORI(5, 30, 3), QSHUF(6, 3, 5), st(30, 2), ADDI(7, 30, 8), st(7, 6))
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        val dram = dispatch(d, prog, Map(10 -> 0), (1, 1, 1), (4, 1, 1))
        val b = (0 until 4).map(word(dram, _)); val sh = (8 until 12).map(word(dram, _))
        println(s"  ballot $b  shuffle $sh")
        utest.assert(b == Seq.fill(4)(BigInt(10)) && sh == Seq(30, 20, 10, 0).map(BigInt(_)))
      }
    }

    utest.test("amoadd_and_amocas_serialize_the_lanes") {
      import Opcodes._
      // r14 counter (0x2000, starts 0), r19 a second word (0x2010, starts 100); old values to 0x3000 / 0x3010 + 4 * lane.
      val prog = Seq(
        AMOADD_W(5, 14, 16), SLLI(6, 30, 2), ADD(8, 6, 15), SW(0, 8, 5),
        AMOCAS_W(7, 19, 18), ADDI(8, 8, 16), SW(0, 8, 7))
      val gprs = Map(14 -> 0x2000, 15 -> 0x3000, 16 -> 1, 18 -> 200, 19 -> 0x2010, 7 -> 100)
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        def put(dram: HalfwordDram, a: Int, w: Long): Unit = { dram.mem(a) = (w & 0xFFFF).toInt; dram.mem(a + 2) = ((w >> 16) & 0xFFFF).toInt }
        val dram = dispatch(d, prog, gprs, (1, 1, 1), (4, 1, 1), dram => put(dram, 0x2010, 100))
        val (counter, cas) = (dram.word(0x2000), dram.word(0x2010))
        val olds = (0 until 4).map(l => dram.word(0x3000 + 4 * l)); val casOlds = (0 until 4).map(l => dram.word(0x3010 + 4 * l))
        println(s"  counter $counter olds $olds; cas word $cas olds $casOlds")
        utest.assert(counter == 4 && olds == Seq(0L, 1L, 2L, 3L) && cas == 200 && casOlds == Seq(100L, 200L, 200L, 200L))
      }
    }

    utest.test("tex_by_descriptor_address_and_texs") {
      import Opcodes._
      // Lanes 2-3 sample the texture at 0x3040 (red 255), lanes 0-1 the one at 0x3000 (red 0);
      // TEXS gives the sampler at 0x3100 and the control word (explicit LOD 0).
      val (tex, desc, samp) = (0x2000, 0x3000, 0x3100)
      val prog = words(
        TEXAD(rs1 = 10, rs2 = 10, rs3 = 10), TEXS(16, 12),
        SRLI(1, 30, 1), Instructions.EXPUSH(rs1 = 1), ADDI(2, 17, 64), TEXD(20, 14, 14, 2),
        Instructions.EXELSE(), TEXD(20, 14, 14, 17), Instructions.EXPOP(),
        st(30, 20))
      val gprs = Map(10 -> 0, 12 -> (SamplerCtl.LodExplicit << 21), 14 -> java.lang.Float.floatToRawIntBits(0.5f),
                     16 -> samp, 17 -> desc)
      simulate(new BorgTestWrapper(quad), additionalResetCycles = 4) { d =>
        def put(dram: HalfwordDram, a: Int, w: Long): Unit = { dram.mem(a) = (w & 0xFFFF).toInt; dram.mem(a + 2) = ((w >> 16) & 0xFFFF).toInt }
        val dram = dispatch(d, prog, gprs, (1, 1, 1), (4, 1, 1), dram => {
          val fm = TexFormat.byName("R8G8B8A8_UNORM")
          for (k <- 0 until 2) {
            put(dram, tex + 16 * k, if (k == 0) 0xFF000000L else 0xFF0000FFL)
            for ((w, i) <- SamplerCtl.descHeader(tex + 16 * k, 1, 1, 1, 1, fm.code, SamplerCtl.Linear, SamplerCtl.T2D, 4).zipWithIndex)
              put(dram, desc + 64 * k + 4 * i, w)
          }
          put(dram, samp, SamplerCtl.ClampEdge << 3 | SamplerCtl.ClampEdge << 6)
        })
        val got = (0 until 4).map(word(dram, _))
        println(s"  red per lane: ${got.map(_.toString(16)).mkString(" ")}")
        utest.assert(got == Seq(BigInt(0), BigInt(0), BigInt(0x3f800000), BigInt(0x3f800000)))
      }
    }

    utest.test("a_build_without_compute_reports_it_absent") {
      simulate(new BorgTestWrapper(scalar.copy(hasCompute = false)), additionalResetCycles = 4) { d =>
        val dram = new HalfwordDram
        d.io.data_write_n.poke(3.U)
        d.io.data_read_n.poke(3.U)
        d.io.gpuMem.data.poke(0.U)
        dram.step(d)
        utest.assert((mmioRead(d, dram, off(BorgGpuRegs.compute_ctrl_offset)) & 4) == 0)
      }
    }
  }
}
