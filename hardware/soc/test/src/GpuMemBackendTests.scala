// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{fromBooleanToLiteral, fromIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object GpuMemBackendTests extends TestSuite {
  val tests = Tests {
    test("halfwords_bytes_and_bursts_reach_memory") {
      simulate(new GpuMemBackend) { dut =>
        val mem = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
        val be = dut.io.be
        val m  = dut.io.mem
        // GpuMemMig's side: a write's first beat is taken with `wr`, `waccept` asks for the next;
        // `ready` ends a request a few cycles later.
        var got = 0
        var wait = -1; var beat = 0; var left = 0; var wAddr = 0
        var done = false
        var acc = false
        def step(): Unit = {
          m.ready.poke(false.B); m.waccept.poke(false.B)
          if (wait == 0) { m.ready.poke(true.B); wait = -1 }
          else if (wait > 0) wait -= 1
          else if (left > 0) {
            mem(wAddr / 2 + beat) = m.wdata.peek().litValue.toInt & 0xffff
            beat += 1; left -= 1
            m.waccept.poke((left > 0).B)
            if (left == 0) wait = 2
          } else if (m.wr.peek().litToBoolean) {
            wAddr = m.addr.peek().litValue.toInt; beat = 0; left = m.wlen.peek().litValue.toInt
            mem(wAddr / 2) = m.wdata.peek().litValue.toInt & 0xffff
            beat = 1; left -= 1
            m.waccept.poke((left > 0).B)
            if (left == 0) wait = 2
          } else if (m.req.peek().litToBoolean) {
            val a = m.addr.peek().litValue.toInt / 2
            m.data.poke(((mem(a + 1) << 16) | mem(a)).U)
            wait = 3
          }
          done = be.done.peek().litToBoolean
          acc = be.accept.peek().litToBoolean
          if (done) got = be.dataOut.peek().litValue.toInt
          dut.clock.step()
          be.startRead.poke(false.B); be.startWrite.poke(false.B)
        }
        def finish(): Unit = { var n = 0; while (!done) { step(); n += 1; assert(n < 50) } }
        def write(hw: Int, v: Int, en: Int): Unit = {
          be.addrIn.poke(hw.U); be.dataIn.poke(v.U); be.byteEnIn.poke(en.U); be.lenIn.poke(1.U)
          be.startWrite.poke(true.B); step(); finish()
        }
        def read(hw: Int): Int = {
          be.addrIn.poke(hw.U); be.startRead.poke(true.B); step(); finish(); got
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        be.startRead.poke(false.B); be.startWrite.poke(false.B)

        write(8, 0x1234, 3)
        assert(read(8) == 0x1234)
        write(8, 0xab00, 2)
        assert(read(8) == 0xab34)
        write(8, 0x00cd, 1)
        assert(read(8) == 0xabcd)

        val burst = Seq(0x1111, 0x2222, 0x3333, 0x4444, 0x5555)
        be.addrIn.poke(16.U); be.lenIn.poke(burst.size.U); be.byteEnIn.poke(3.U)
        be.dataIn.poke(burst.head.U); be.startWrite.poke(true.B)
        var i = 0; var n = 0
        done = false
        while (!done) {
          step(); n += 1; assert(n < 50)
          if (acc) { i += 1; be.dataIn.poke(burst(i min (burst.size - 1)).U) }
        }
        assert(burst.indices.map(k => read(16 + k)) == burst)
      }
    }
  }
}
