// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** The draw front end's core instructions (docs/B1_geometry_front_end.md):
  * SOUT writes a vertex's or the setup ROM's outputs to the triangle record,
  * FATTR reads a varying's three per-vertex values back, and a vertex shader
  * sees VertexIndex/InstanceIndex in r30/r31. */
object BorgCoreDrawTests extends TestSuite {
  import BorgCoreTestHelpers._

  val quad = config.copy(fragLanes = 4)

  def ids(core: BorgCore, mode: Boolean, r30: Int => Int, r31: Int => Int): Unit =
    core.io.ids.foreach { c =>
      c.mode.poke(mode.B)
      c.laneMask.poke(((1 << core.cfg.fragLanes) - 1).U)
      for (i <- 0 until core.cfg.fragLanes) {
        c.r30(i).poke(r30(i).U); c.r31(i).poke(r31(i).U)
      }
    }

  val tests = Tests {

    utest.test("vertex_stage_reads_its_indices_and_interleaves_sout_by_corner") {
      simulate(new BorgCore(quad)) { core =>
        println("\n--- BorgCore: VertexIndex/InstanceIndex, SOUT by corner ---")
        idleInputs(core)
        resetCore(core)
        // A sequencer-run shader: r30/r31 read 0 there -- unless the draw
        // walker supplies the indices.
        core.io.seqBusy.poke(true.B)
        ids(core, mode = true, i => 100 + i, i => 7)
        val rec = core.io.record.get
        rec.outBase.poke(0x2000.U); rec.outInterleave.poke(true.B); rec.outCorner.poke(0.U)

        // Component 37 (index > 31: both halves of the split immediate) =
        // VertexIndex, component 2 = InstanceIndex.
        writeImem(core, 0, Instructions.SOUT(rs2 = 30, index = 37))
        writeImem(core, 1, Instructions.SOUT(rs2 = 31, index = 2))
        writeImem(core, 2, 0)
        val mem = scala.collection.mutable.Map[BigInt, BigInt]()
        startAndWaitWithMem(core, mem)

        for (lane <- 0 until 4) {
          val a = BigInt(0x2000 + 4 * (3 * 37 + lane))
          val b = BigInt(0x2000 + 4 * (3 * 2 + lane))
          println(f"  corner $lane: [0x${a.toInt}%x] = ${mem.get(a)}, [0x${b.toInt}%x] = ${mem.get(b)}")
          utest.assert(mem.get(a).contains(BigInt(100 + lane)))
          utest.assert(mem.get(b).contains(BigInt(7)))
        }
        utest.assert(mem.size == 8)
        println("  PASSED")
      }
    }

    utest.test("sout_without_interleave_is_a_plain_record_store") {
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgCore: SOUT, setup stride ---")
        idleInputs(core)
        resetCore(core)
        val rec = core.io.record.get
        rec.outBase.poke(0x4000.U); rec.outInterleave.poke(false.B)
        writeReg(core, 3, floatToBits(2.5f))
        writeImem(core, 0, Instructions.SOUT(rs2 = 3, index = 9))
        writeImem(core, 1, 0)
        val mem = scala.collection.mutable.Map[BigInt, BigInt]()
        startAndWaitWithMem(core, mem)
        println(s"  record = $mem")
        utest.assert(mem == scala.collection.mutable.Map(BigInt(0x4000 + 36) -> floatToBits(2.5f)))
        println("  PASSED")
      }
    }

    utest.test("fattr_loads_a_components_three_vertex_values_into_every_lane") {
      simulate(new BorgCore(quad)) { core =>
        println("\n--- BorgCore: FATTR ---")
        idleInputs(core)
        resetCore(core)
        val rec = core.io.record.get
        rec.attrBase.poke(0x3000.U)
        rec.outBase.poke(0x5000.U); rec.outInterleave.poke(true.B)
        val idx = 41
        val vals = Seq(floatToBits(1.5f), floatToBits(-2.0f), floatToBits(0.25f))
        val mem = scala.collection.mutable.Map[BigInt, BigInt]()
        for (k <- 0 until 3) mem(BigInt(0x3000 + 4 * (3 * idx + k))) = vals(k)
        // Every lane gets all three words; SOUT then shows each lane's r11.
        writeImem(core, 0, Instructions.FATTR(rd = 10, index = idx))
        writeImem(core, 1, Instructions.SOUT(rs2 = 11, index = 0))
        writeImem(core, 2, 0)
        startAndWaitWithMem(core, mem)
        val regs = (10 to 12).map(readReg(core, _))
        println(s"  lane 0 r10..r12 = $regs (expect $vals)")
        utest.assert(regs == vals)
        for (lane <- 0 until 4)
          utest.assert(mem.get(BigInt(0x5000 + 4 * lane)).contains(vals(1)))
        println("  PASSED")
      }
    }
  }
}
