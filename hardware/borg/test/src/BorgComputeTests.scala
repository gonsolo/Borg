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
