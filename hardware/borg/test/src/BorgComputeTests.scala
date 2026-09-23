// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator.{
  toTestableClock, toTestableSInt, toTestableUInt, toTestableBool,
  toTestableReset, toTestableEnum, toTestableRecord, toTestableVec, toTestableData
}
import utest._
import BorgGpuMemWordTests.{HalfwordDram, mmioRead, mmioWrite}

/** vkCmdDispatch through BorgComputeSequencer, on full Borg.
  *
  * Each shader stores one word per invocation at an address derived from the
  * invocation's IDs, so the DRAM afterwards shows which invocations ran, with
  * which IDs, and that nothing else was written.
  */
object BorgComputeTests extends TestSuite with FastBuildSimulator {

  val lsBase   = 0x1000
  val scalar   = BorgConfig.Test                           // fragLanes = 1
  val quad     = BorgConfig.Simt.copy(maxBinTiles = 64)    // fragLanes = 4

  private def off(r: UInt): Int = r.litValue.toInt
  def packed(x: Int, y: Int, z: Int): BigInt = BigInt(x | (y << 10) | (z << 20))

  /** Run `prog` (IMEM 0, HALT appended) over `groups` workgroups of `local`
    * invocations, with the given GPRs preset, and return the DRAM. */
  def dispatch(d: BorgGpuMemWordTests.Dut, prog: Seq[BigInt], gprs: Map[Int, Int],
               groups: (Int, Int, Int), local: (Int, Int, Int)): HalfwordDram = {
    val dram = new HalfwordDram
    d.io.data_write_n.poke(3.U)
    d.io.data_read_n.poke(3.U)
    d.io.gpuMem.data.poke(0.U)
    dram.step(d)
    mmioWrite(d, dram, off(BorgGpuRegs.control_offset), 2)
    mmioWrite(d, dram, off(BorgGpuRegs.ls_base_offset), lsBase)
    for ((r, v) <- gprs) mmioWrite(d, dram, off(BorgGpuRegs.gpr_offset) + 4 * r, v)
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
  val flatIdsProgram = Seq(
    Instructions.IADD(rs1 = 29, rs2 = 10, rd = 1, funct3 = 1),
    Instructions.IADD(rs1 = 30, rs2 = 10, rd = 2, funct3 = 1),
    Instructions.IMUL(rs1 = 2, rs2 = 11, rd = 2),
    Instructions.IADD(rs1 = 2, rs2 = 1, rd = 2),
    Instructions.IMUL(rs1 = 2, rs2 = 12, rd = 2),
    Instructions.IADD(rs1 = 2, rs2 = 30, rd = 2),
    Instructions.STORE(rs1 = 2, rs2 = 31)
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
      val prog = Seq(
        Instructions.IADD(rs1 = 31, rs2 = 10, rd = 1, funct3 = 1),
        Instructions.IMUL(rs1 = 1, rs2 = 12, rd = 1),
        Instructions.IADD(rs1 = 1, rs2 = 30, rd = 1),
        Instructions.STORE(rs1 = 1, rs2 = 31)
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
      val prog = Seq(
        Instructions.IADD(rs1 = 10, rs2 = 30, rd = 1),        // r1 = 100 + i
        Instructions.IADD(rs1 = 30, rs2 = 11, rd = 2),        // r2 = 1000 + i
        Instructions.STORE(rs1 = 1, rs2 = 2),                 // shared[100+i] = 1000+i
        Instructions.BARRIER(),
        Instructions.IADD(rs1 = 30, rs2 = 12, rd = 3),        // r3 = i + half
        Instructions.IAND(rs1 = 3, rs2 = 13, rd = 3),         // r3 = (i+half) & (count-1) = j
        Instructions.IADD(rs1 = 10, rs2 = 3, rd = 4),         // r4 = 100 + j
        Instructions.LOAD(rs1 = 4, rd = 5),                   // r5 = shared[100+j]
        Instructions.STORE(rs1 = 30, rs2 = 5)                 // output[i] = r5
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
        Instructions.BRNZ(rs1 = 1, target = 3),         // quadIdx != 0: skip the barrier
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
