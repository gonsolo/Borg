// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{Bundle, Flipped, IO, Module, Vec, fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromLongToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import borg.GpuMemIO
import utest._

class GpuMemSharedRigIO extends Bundle {
  val in   = Flipped(Vec(2, new GpuMemIO))
  val scan = Flipped(new MigUi)   // a reader first on the MIG, as MigScanout
  val mig  = new MigUi
}

/** Two masters on the arbiter in front of GpuMemMig, as the CPU and Borg share the Nexys DDR3. */
class GpuMemSharedRig extends Module {
  val io  = IO(new GpuMemSharedRigIO)
  val arb = Module(new GpuMemArbiter(2))
  val mig = Module(new GpuMemMig)
  mig.io.uiClock := clock
  mig.io.uiReset := reset.asBool
  for (i <- 0 until 2) arb.io.in(i) <> io.in(i)
  mig.io.gpu <> arb.io.out
  val ui = Module(new MigUiArbiter)
  ui.io.a <> mig.io.mig
  ui.io.b <> io.scan
  io.mig <> ui.io.mig
}

object GpuMemSharedTests extends TestSuite {
  val tests = Tests {
    // Each master writes bursts and reads words in one small region, so their lines collide; a
    // write counts at its `ready` (the arbiter serves one request at a time) and a read must
    // return what the region holds at its own.
    test("two_masters_read_what_was_written_last") {
      simulate(new GpuMemSharedRig) { dut =>
        val rnd   = new scala.util.Random(3)
        val ref   = scala.collection.mutable.Map[Long, Int]().withDefaultValue(0)
        val lines = scala.collection.mutable.Map[Long, BigInt]().withDefaultValue(BigInt(0))
        val wq    = scala.collection.mutable.Queue[(BigInt, Int)]()
        val rq    = scala.collection.mutable.Queue[(Int, BigInt)]()
        val m     = dut.io.mig
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        m.init_calib_complete.poke(true.B)
        // A master's request: write (address, beats) or read (address); None while idle.
        case class Op(write: Boolean, addr: Long, beats: Seq[Int], var beat: Int = 0, var delay: Int)
        val ops = Array.fill[Option[Op]](2)(None)
        var cycle = 0
        var checked = 0
        def newOp(): Op = {
          val a = 0x2000L + 2 * rnd.nextInt(48)
          if (rnd.nextInt(3) == 0) Op(true, a, Seq.fill(1 + rnd.nextInt(8))(rnd.nextInt(1 << 16)), delay = rnd.nextInt(4))
          else Op(false, a & ~1L, Nil, delay = rnd.nextInt(4))
        }
        // The scan-out's reads: a line now and then, answered only to it.
        val s = dut.io.scan
        s.app_cmd.poke(1.U); s.app_wdf_data.poke(0.U); s.app_wdf_mask.poke(0.U)
        s.app_wdf_wren.poke(false.B); s.app_wdf_end.poke(false.B)
        var scanWant = 0
        while (checked < 1500) {
          val scanOn = scanWant > 0 || rnd.nextInt(6) == 0
          if (scanWant == 0 && scanOn) scanWant = 1 + rnd.nextInt(4)
          s.app_en.poke((scanWant > 0).B)
          s.app_addr.poke((0x8000L << 3).U)
          for (i <- 0 until 2) {
            val g = dut.io.in(i)
            if (ops(i).isEmpty) ops(i) = Some(newOp())
            val o = ops(i).get
            val on = o.delay == 0
            g.req.poke((on && !o.write).B)
            g.wr.poke((on && o.write).B)
            g.addr.poke(o.addr.U)
            g.wlen.poke((if (o.write) o.beats.size else 1).U)
            g.wdata.poke((if (o.write) o.beats(o.beat min (o.beats.size - 1)) else 0).U)
          }
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
          if (scanWant > 0 && s.app_rdy.peek().litToBoolean) scanWant -= 1
          if (rdy && m.app_en.peek().litToBoolean) {
            val l = m.app_addr.peek().litValue.toLong >> 3
            if (m.app_cmd.peek().litValue == 0) {
              val (d, mask) = wq.dequeue()
              var v = lines(l)
              for (b <- 0 until 16 if ((mask >> b) & 1) == 0)
                v = v & ~(BigInt(0xff) << (8 * b)) | (((d >> (8 * b)) & 0xff) << (8 * b))
              lines(l) = v
            } else rq.enqueue((cycle + 3 + rnd.nextInt(10), lines(l)))
          }
          for (i <- 0 until 2) {
            val g = dut.io.in(i)
            val o = ops(i).get
            if (o.delay > 0) o.delay -= 1
            else {
              if (g.waccept.peek().litToBoolean) o.beat += 1
              if (g.ready.peek().litToBoolean) {
                if (o.write) {
                  for ((h, k) <- o.beats.zipWithIndex) { ref(o.addr + 2 * k) = h & 0xff; ref(o.addr + 2 * k + 1) = h >> 8 }
                } else {
                  val want = (0 until 4).map(b => ref(o.addr + b).toLong << (8 * b)).sum
                  val got = g.data.peek().litValue.toLong
                  if (got != want) println(f"cycle $cycle master $i read ${o.addr}%#x: $got%08x, memory $want%08x")
                  assert(got == want)
                  checked += 1
                }
                ops(i) = None
              }
            }
          }
          dut.clock.step()
          cycle += 1
        }
      }
    }
  }
}
