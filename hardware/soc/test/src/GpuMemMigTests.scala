// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{Bundle, Flipped, IO, Module, fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromLongToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import borg.GpuMemIO
import utest._

class GpuMemMigRigIO extends Bundle {
  val gpu = Flipped(new GpuMemIO)
  val mig = new MigUi
}

/** Both clocks the same: the protocol, not the crossing. */
class GpuMemMigRig extends Module {
  val io = IO(new GpuMemMigRigIO)
  val a  = Module(new GpuMemMig)
  a.io.uiClock := clock
  a.io.uiReset := reset.asBool
  a.io.gpu <> io.gpu
  io.mig <> a.io.mig
}

object GpuMemMigTests extends TestSuite {
  val tests = Tests {
    test("bursts_and_reads_match_a_flat_memory") {
      simulate(new GpuMemMigRig) { dut =>
        val rnd   = new scala.util.Random(11)
        val ref   = scala.collection.mutable.Map[Long, Int]().withDefaultValue(0)  // byte address -> byte
        val lines = scala.collection.mutable.Map[Long, BigInt]().withDefaultValue(BigInt(0))
        val wq    = scala.collection.mutable.Queue[(BigInt, Int)]()
        val rq    = scala.collection.mutable.Queue[(Int, BigInt)]()  // cycle due, line
        def base = if (rnd.nextInt(4) == 0) 0x1FFFF000L else 0x1000L
        val g = dut.io.gpu
        val m = dut.io.mig
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        m.init_calib_complete.poke(true.B)
        var cycle = 0
        var checked = 0
        var last = 0x1000L  // half of the accesses land next to the last read, on its line
        def step(): Unit = {
          val rdy = rnd.nextInt(3) != 0
          val wdfRdy = rnd.nextInt(3) != 0
          m.app_rdy.poke(rdy.B)
          m.app_wdf_rdy.poke(wdfRdy.B)
          val due = rq.headOption.exists(_._1 <= cycle)
          m.app_rd_data_valid.poke(due.B)
          m.app_rd_data.poke(if (due) rq.head._2.U(128.W) else 0.U(128.W))
          if (due) rq.dequeue()
          if (wdfRdy && m.app_wdf_wren.peek().litToBoolean)
            wq.enqueue((m.app_wdf_data.peek().litValue, m.app_wdf_mask.peek().litValue.toInt))
          if (rdy && m.app_en.peek().litToBoolean) {
            val a = m.app_addr.peek().litValue.toLong
            assert((a & 7) == 0)
            val l = a >> 3
            if (m.app_cmd.peek().litValue == 0) {
              val (d, mask) = wq.dequeue()
              var v = lines(l)
              for (b <- 0 until 16 if ((mask >> b) & 1) == 0) {
                v = v & ~(BigInt(0xff) << (8 * b)) | (((d >> (8 * b)) & 0xff) << (8 * b))
              }
              lines(l) = v
            } else rq.enqueue((cycle + 3 + rnd.nextInt(10), lines(l)))
          }
          dut.clock.step()
          cycle += 1
        }
        for (_ <- 0 until 400) {
          if (rnd.nextInt(3) == 0) {
            val n = 1 + rnd.nextInt(64)
            val a = if (rnd.nextBoolean()) (last & ~1L) - 2 * rnd.nextInt(16) else base + 2 * rnd.nextInt(256)
            val beats = Seq.fill(n)(rnd.nextInt(1 << 16))
            for ((h, i) <- beats.zipWithIndex) { ref(a + 2 * i) = h & 0xff; ref(a + 2 * i + 1) = h >> 8 }
            var i = 0
            var busy = true
            while (busy) {
              g.wr.poke(true.B); g.req.poke(false.B)
              g.addr.poke(a.U); g.wlen.poke(n.U); g.wdata.poke(beats(i).U)
              val acc = g.waccept.peek().litToBoolean
              busy = !g.ready.peek().litToBoolean
              step()
              if (acc) i += 1
            }
            assert(i == n - 1)
          } else {
            val a = if (rnd.nextBoolean()) (last & ~15L) + 2 * rnd.nextInt(8) else base + 2 * rnd.nextInt(256)
            last = a
            var busy = true
            while (busy) {
              g.wr.poke(false.B); g.req.poke(true.B); g.addr.poke(a.U)
              if (g.ready.peek().litToBoolean) {
                val want = (0 until 4).map(b => ref(a + b).toLong << (8 * b)).sum
                assert(g.data.peek().litValue.toLong == want)
                checked += 1
                busy = false
              }
              step()
            }
          }
          g.wr.poke(false.B); g.req.poke(false.B)
          step()
        }
        assert(checked > 200)
      }
    }
  }
}
