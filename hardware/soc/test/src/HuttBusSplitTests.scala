// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromLongToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object HuttBusSplitTests extends TestSuite {
  val tests = Tests {
    test("each_side_sees_only_its_accesses") {
      simulate(new HuttBusSplit(10, 9)) { dut =>
        val rnd = new scala.util.Random(3)
        val c   = dut.io.in
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        c.req.valid.poke(false.B); c.resp.ready.poke(false.B)
        val mems = Seq(scala.collection.mutable.Map[Long, Long]().withDefaultValue(0L),
                       scala.collection.mutable.Map[Long, Long]().withDefaultValue(0L))
        val sides = Seq(dut.io.lo, dut.io.hi)
        val pend  = Array[Option[Long]](None, None)   // a side's answer, once it accepted an access
        sides.foreach { s => s.resp.valid.poke(false.B) }
        // Each side answers one access at a time after a random delay with its own memory.
        def drive(): Unit = for (i <- 0 to 1) {
          val s = sides(i)
          s.req.ready.poke((pend(i).isEmpty && rnd.nextBoolean()).B)
          s.resp.valid.poke((pend(i).nonEmpty && rnd.nextBoolean()).B)
          s.resp.bits.poke(pend(i).getOrElse(0L).U)
        }
        def step(): Unit = {
          for (i <- 0 to 1) {
            val s = sides(i)
            if (s.resp.valid.peek().litToBoolean && s.resp.ready.peek().litToBoolean) pend(i) = None
            if (s.req.valid.peek().litToBoolean && s.req.ready.peek().litToBoolean) {
              val a = s.req.bits.addr.peek().litValue.toLong
              assert(((a >> 9) & 1) == i)
              if (s.req.bits.write.peek().litToBoolean) { mems(i)(a) = s.req.bits.data.peek().litValue.toLong; pend(i) = Some(0L) }
              else pend(i) = Some(mems(i)(a))
            }
          }
          dut.clock.step()
          drive()
        }
        drive()
        val ref = scala.collection.mutable.Map[Long, Long]().withDefaultValue(0L)
        var checked = 0
        for (_ <- 0 until 400) {
          val a = 4L * rnd.nextInt(256)
          val write = rnd.nextBoolean()
          val v = rnd.nextLong() & 0xffffffffL
          c.req.valid.poke(true.B); c.req.bits.addr.poke(a.U); c.req.bits.write.poke(write.B)
          c.req.bits.size.poke(2.U); c.req.bits.data.poke(v.U)
          var t = 0
          while (!c.req.ready.peek().litToBoolean) { step(); t += 1; assert(t < 100) }
          step(); c.req.valid.poke(false.B); c.resp.ready.poke(true.B)
          while (!c.resp.valid.peek().litToBoolean) { step(); t += 1; assert(t < 100) }
          if (write) ref(a) = v else { assert(c.resp.bits.peek().litValue.toLong == ref(a)); checked += 1 }
          step(); c.resp.ready.poke(false.B)
        }
        assert(checked > 150)
      }
    }
  }
}
