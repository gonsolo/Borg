// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{Bundle, Flipped, IO, Module, fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromLongToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import borg.GpuMemIO
import utest._

class LiteDramMigUiRigIO extends Bundle {
  val gpu  = Flipped(new GpuMemIO)
  val dram = new LiteDramNative
}

/** GpuMemMig on LiteDRAM's native port through the adapter, one clock. */
class LiteDramMigUiRig extends Module {
  val io = IO(new LiteDramMigUiRigIO)
  val g  = Module(new GpuMemMig)
  val a  = Module(new LiteDramMigUi)
  g.io.uiClock := clock
  g.io.uiReset := reset.asBool
  g.io.gpu <> io.gpu
  a.io.ui <> g.io.mig
  a.io.initDone := true.B
  io.dram <> a.io.dram
}

object LiteDramMigUiTests extends TestSuite {
  val tests = Tests {
    test("bursts_and_reads_match_a_flat_memory") {
      simulate(new LiteDramMigUiRig) { dut =>
        val rnd   = new scala.util.Random(13)
        val ref   = scala.collection.mutable.Map[Long, Int]().withDefaultValue(0)
        val lines = scala.collection.mutable.Map[Long, BigInt]().withDefaultValue(BigInt(0))
        val wpend = scala.collection.mutable.Queue[(Int, Long)]()       // accepted writes awaiting data: seq, line
        val rq    = scala.collection.mutable.Queue[(Int, Int, Long)]()  // reads: seq, cycle due, line
        var seq   = 0
        def base = if (rnd.nextInt(4) == 0) 0x1FFFF000L else 0x1000L
        val g = dut.io.gpu
        val d = dut.io.dram
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        d.rdata.valid.poke(false.B)
        var cycle = 0
        var checked = 0
        var last = 0x1000L
        // LiteDRAM: commands at random, a write's data only once it is accepted, reads back in order
        // and after the writes before them.
        def step(): Unit = {
          val cmdRdy = rnd.nextInt(3) != 0
          val wRdy   = wpend.nonEmpty && rnd.nextInt(2) == 0
          d.cmd.ready.poke(cmdRdy.B)
          d.wdata.ready.poke(wRdy.B)
          val due = rq.headOption.exists(r => r._2 <= cycle && wpend.headOption.forall(_._1 > r._1))
          d.rdata.valid.poke(due.B)
          d.rdata.bits.poke(if (due) lines(rq.head._3).U(128.W) else 0.U(128.W))
          if (due) rq.dequeue()
          if (wRdy && d.wdata.valid.peek().litToBoolean) {
            val l    = wpend.dequeue()._2
            val data = d.wdata.bits.data.peek().litValue
            val we   = d.wdata.bits.we.peek().litValue.toInt
            var v    = lines(l)
            for (b <- 0 until 16 if ((we >> b) & 1) == 1)
              v = v & ~(BigInt(0xff) << (8 * b)) | (((data >> (8 * b)) & 0xff) << (8 * b))
            lines(l) = v
          }
          if (cmdRdy && d.cmd.valid.peek().litToBoolean) {
            val l = d.cmd.bits.addr.peek().litValue.toLong
            if (d.cmd.bits.we.peek().litToBoolean) wpend.enqueue((seq, l))
            else rq.enqueue((seq, cycle + 3 + rnd.nextInt(10), l))
            seq += 1
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
