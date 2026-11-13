// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.simulator.EphemeralSimulator._
import utest._

object DataCacheTests extends TestSuite {
  val tests = Tests {
    // Random reads and writes of every size against a byte array; memory also changes behind the
    // cache's back, followed by `flush`. 64 lines, mostly over 1 KB: hits, misses and evictions.
    test("random_accesses_read_what_memory_holds") {
      simulate(new DataCache(addrWidth = 25, numLines = 64)) { dut =>
        val rnd = new scala.util.Random(11)
        val mem = Array.fill(4096)(rnd.nextInt(256))
        var pending: Option[(Int, Boolean, Int, BigInt)] = None   // addr, write, size, data
        var delay = 0
        var memReqs = 0
        var hits = 0
        def step(): Unit = {
          val m = dut.io.mem
          m.resp.valid.poke(false.B)
          pending match {
            case Some((a, w, s, d)) if delay == 0 =>
              val n = 1 << s
              if (w) for (i <- 0 until n) mem(a + i) = ((d >> (8 * i)) & 0xff).toInt
              m.resp.valid.poke(true.B)
              m.resp.bits.poke((if (w) BigInt(0) else (0 until n).map(i => BigInt(mem(a + i)) << (8 * i)).sum).U)
              if (m.resp.ready.peek().litToBoolean) pending = None
            case Some(_) => delay -= 1
            case None =>
              if (m.req.valid.peek().litToBoolean) {
                val r = m.req.bits
                memReqs += 1
                pending = Some((r.addr.peek().litValue.toInt, r.write.peek().litToBoolean, r.size.peek().litValue.toInt, r.data.peek().litValue))
                delay = rnd.nextInt(4)
              }
          }
          m.req.ready.poke(pending.isEmpty.B)
          dut.clock.step()
        }
        def access(a: Int, w: Boolean, s: Int, d: BigInt): BigInt = {
          val c = dut.io.cpu
          c.req.valid.poke(true.B); c.req.bits.addr.poke(a.U); c.req.bits.write.poke(w.B)
          c.req.bits.size.poke(s.U); c.req.bits.data.poke(d.U); c.resp.ready.poke(true.B)
          var n = 0
          while (!c.req.ready.peek().litToBoolean) { step(); n += 1; assert(n < 100) }
          step(); c.req.valid.poke(false.B)
          while (!c.resp.valid.peek().litToBoolean) { step(); n += 1; assert(n < 100) }
          val v = c.resp.bits.peek().litValue
          step(); v
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.flush.poke(false.B); dut.io.cpu.req.valid.poke(false.B)
        // A byte written into a cached word leaves the word's other bytes.
        access(0x370, false, 2, 0)
        access(0x37c, true, 0, 0xce)
        assert(access(0x37c, false, 2, 0) == (0 until 4).map(i => BigInt(mem(0x37c + i)) << (8 * i)).sum)
        for (_ <- 0 until 2000) {
          val s = rnd.nextInt(3)
          val span = if (rnd.nextInt(10) == 0) 4096 else 1024   // mostly what the cache holds
          val a = rnd.nextInt(span >> s) << s | (if (s == 2 && rnd.nextInt(8) == 0) 2 else 0)
          rnd.nextInt(50) match {
            case 0 =>
              mem(rnd.nextInt(4096)) = rnd.nextInt(256)
              dut.io.flush.poke(true.B); step(); dut.io.flush.poke(false.B)
            case k if k < 16 =>
              access(a, true, s, BigInt(32, rnd) & ((BigInt(1) << (8 << s)) - 1))
            case _ =>
              val n = 1 << s
              val want = (0 until n).map(i => BigInt(mem(a + i)) << (8 * i)).sum
              val before = memReqs
              val got = access(a, false, s, 0)
              if (memReqs == before) hits += 1
              if (got != want) println(f"read $a%#x size $s: got $got%#x, memory $want%#x")
              assert(got == want)
          }
        }
        println(s"  $hits reads of about 1350 answered without memory")
        assert(hits > 300)
      }
    }
  }
}
