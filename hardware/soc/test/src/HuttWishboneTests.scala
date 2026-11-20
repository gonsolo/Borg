// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{Bundle, Flipped, IO, Module, fromBooleanToLiteral, fromIntToLiteral, fromLongToLiteral}
import chisel3.simulator.EphemeralSimulator._
import hutt.HuttBus
import utest._

class HuttWishboneRigIO extends Bundle {
  val bus = Flipped(new HuttBus(10))
  val wb  = new WishboneMaster
}

/** Both sides on one clock: the handshakes, not the crossing. */
class HuttWishboneRig extends Module {
  val io = IO(new HuttWishboneRigIO)
  val b  = Module(new HuttWishbone(10))
  b.io.wbClock := clock
  b.io.wbReset := reset.asBool
  b.io.bus <> io.bus
  io.wb <> b.io.wb
}

object HuttWishboneTests extends TestSuite {
  val tests = Tests {
    test("reads_return_what_writes_left") {
      simulate(new HuttWishboneRig) { dut =>
        val rnd = new scala.util.Random(5)
        val mem = scala.collection.mutable.Map[Long, Long]().withDefaultValue(0L)
        val c   = dut.io.bus
        val wb  = dut.io.wb
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        c.req.valid.poke(false.B); c.resp.ready.poke(false.B); wb.ack.poke(false.B)
        var wait = 0  // the slave acks a random number of cycles into a cycle
        def step(): Unit = {
          val cyc = wb.cyc.peek().litToBoolean && wb.stb.peek().litToBoolean
          val ack = cyc && wait == 0
          wb.ack.poke(ack.B)
          val a = wb.adr.peek().litValue.toLong
          wb.datR.poke(mem(a).U)
          if (ack) {
            if (wb.we.peek().litToBoolean) mem(a) = wb.datW.peek().litValue.toLong
            wait = rnd.nextInt(4)
          } else if (cyc) wait -= 1
          dut.clock.step()
        }
        val ref = scala.collection.mutable.Map[Long, Long]().withDefaultValue(0L)
        var checked = 0
        for (_ <- 0 until 300) {
          val word  = rnd.nextInt(256).toLong
          val write = rnd.nextBoolean()
          val v     = rnd.nextLong() & 0xffffffffL
          c.req.valid.poke(true.B); c.req.bits.addr.poke((4 * word).U); c.req.bits.write.poke(write.B)
          c.req.bits.size.poke(2.U); c.req.bits.data.poke(v.U)
          while (!c.req.ready.peek().litToBoolean) step()
          step(); c.req.valid.poke(false.B)
          c.resp.ready.poke(true.B)
          var t = 0
          while (!c.resp.valid.peek().litToBoolean) { step(); t += 1; assert(t < 50) }
          if (write) ref(word) = v
          else { assert(c.resp.bits.peek().litValue.toLong == ref(word)); checked += 1 }
          step(); c.resp.ready.poke(false.B)
        }
        assert(checked > 100)
        assert(mem == ref)
      }
    }
  }
}
