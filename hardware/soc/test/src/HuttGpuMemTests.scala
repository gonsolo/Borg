// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object HuttGpuMemTests extends TestSuite {
  val tests = Tests {
    // Every size at every alignment, reads and writes, against a byte array behind a GpuMemIO
    // model as GpuMemMig answers: 32-bit reads at halfword addresses, halfword write bursts.
    test("any_size_at_any_alignment") {
      val base = 0x1000
      simulate(new HuttGpuMem(28, 26, base)) { dut =>
        val rnd = new scala.util.Random(5)
        val mem = Array.fill(512)(rnd.nextInt(256))
        val m = dut.io.mem
        var wait = -1; var left = 0; var beat = 0; var wAddr = 0
        def step(): Unit = {
          m.ready.poke(false.B); m.waccept.poke(false.B)
          if (wait == 0) { m.ready.poke(true.B); wait = -1 }
          else if (wait > 0) wait -= 1
          else if (left > 0) {
            val v = m.wdata.peek().litValue.toInt
            mem(wAddr + 2 * beat) = v & 0xff; mem(wAddr + 2 * beat + 1) = v >> 8
            beat += 1; left -= 1
            m.waccept.poke((left > 0).B)
            if (left == 0) wait = 1
          } else if (m.wr.peek().litToBoolean) {
            wAddr = m.addr.peek().litValue.toInt; left = m.wlen.peek().litValue.toInt; beat = 0
            assert(wAddr % 2 == 0)
            val v = m.wdata.peek().litValue.toInt
            mem(wAddr) = v & 0xff; mem(wAddr + 1) = v >> 8
            beat = 1; left -= 1
            m.waccept.poke((left > 0).B)
            if (left == 0) wait = 1
          } else if (m.req.peek().litToBoolean) {
            val a = m.addr.peek().litValue.toInt
            assert(a % 2 == 0)
            m.data.poke((0 until 4).map(i => BigInt(mem(a + i)) << (8 * i)).sum.U)
            wait = rnd.nextInt(3)
          }
          dut.clock.step()
        }
        def data(a: Int, w: Boolean, s: Int, d: BigInt): BigInt = {
          val c = dut.io.data
          c.req.valid.poke(true.B); c.req.bits.addr.poke((a + base).U); c.req.bits.write.poke(w.B)
          c.req.bits.size.poke(s.U); c.req.bits.data.poke(d.U); c.resp.ready.poke(true.B)
          step(); c.req.valid.poke(false.B)
          var n = 0
          while (!c.resp.valid.peek().litToBoolean) { step(); n += 1; assert(n < 200) }
          val v = c.resp.bits.peek().litValue
          step(); v
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.data.req.valid.poke(false.B); dut.io.instr.req.valid.poke(false.B)
        dut.io.instr.resp.ready.poke(true.B)
        for (_ <- 0 until 1500) {
          val s = rnd.nextInt(4); val n = 1 << s
          val a = rnd.nextInt(512 - 12)
          if (rnd.nextBoolean()) {
            val d = BigInt(8 * n, rnd)
            data(a, true, s, d)
            val want = (0 until n).map(i => ((d >> (8 * i)) & 0xff).toInt)
            assert((0 until n).map(i => mem(a + i)) == want)
          } else {
            val want = (0 until n).map(i => BigInt(mem(a + i)) << (8 * i)).sum
            val got = data(a, false, s, 0)
            if (got != want) println(f"read $a%#x size $s: got $got%#x, memory $want%#x")
            assert(got == want)
          }
        }
        // An instruction fetch: a word address.
        val i = dut.io.instr
        i.req.valid.poke(true.B); i.req.bits.poke(((base + 0x40) / 4).U)
        step(); i.req.valid.poke(false.B)
        var t = 0
        while (!i.resp.valid.peek().litToBoolean) { step(); t += 1; assert(t < 50) }
        assert(i.resp.bits.peek().litValue == (0 until 4).map(k => BigInt(mem(0x40 + k)) << (8 * k)).sum)
      }
    }
  }
}
