// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{fromBigIntToLiteral, fromBooleanToLiteral, fromIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object HostLinkTests extends TestSuite {
  /** Sends 'D' and collects the 2048 words of dirty pages as a set of page numbers. */
  def dirtyPages(dut: HostLink): Set[Int] = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.poke('D'.toInt.U)
    while (!dut.io.in.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(true.B)
    val bytes = scala.collection.mutable.ArrayBuffer[Int]()
    var steps = 0
    while (bytes.size < 8192 && steps < 100000) {
      if (dut.io.out.valid.peek().litToBoolean) {
        bytes += dut.io.out.bits.peek().litValue.toInt
        assert(dut.io.outEnd.peek().litToBoolean == (bytes.size == 8192))
      }
      dut.clock.step()
      steps += 1
    }
    assert(bytes.size == 8192)
    dut.io.out.ready.poke(false.B)
    (0 until 65536).filter(p => (bytes(p / 8) >> (p % 8) & 1) == 1).toSet
  }

  def write(dut: HostLink, addr: Long, wlen: Int): Unit = {
    dut.io.written.valid.poke(true.B)
    dut.io.written.bits.addr.poke(BigInt(addr).U)
    dut.io.written.bits.wlen.poke(wlen.U)
    dut.clock.step()
    dut.io.written.valid.poke(false.B)
    dut.clock.step(2)
  }

  /** Bytes into the link, a memory serving its writes meanwhile; then cycles until it is idle. */
  def run(dut: HostLink, bytes: Seq[Int], mem: scala.collection.mutable.Map[Long, Int]): Unit = {
    def serve(): Unit =
      if (dut.io.mem.wr.peek().litToBoolean) {
        val base = dut.io.mem.addr.peek().litValue.toLong
        mem(base) = dut.io.mem.wdata.peek().litValue.toInt
        for (i <- 1 until dut.io.mem.wlen.peek().litValue.toInt) {
          dut.io.mem.waccept.poke(true.B); dut.clock.step()
          mem(base + 2 * i) = dut.io.mem.wdata.peek().litValue.toInt
        }
        dut.io.mem.waccept.poke(false.B); dut.io.mem.ready.poke(true.B); dut.clock.step()
        dut.io.mem.ready.poke(false.B)
      } else dut.clock.step()
    for (b <- bytes) {
      dut.io.in.valid.poke(true.B); dut.io.in.bits.poke(b.U)
      while (!dut.io.in.ready.peek().litToBoolean) serve()
      dut.clock.step()
    }
    dut.io.in.valid.poke(false.B)
    for (_ <- 0 until 200) serve()
  }

  def le(v: Long): Seq[Int] = (0 until 4).map(i => ((v >> (8 * i)) & 0xFF).toInt)

  val tests = Tests {
    test("fill_writes_its_value_in_every_word") {
      simulate(new HostLink) { dut =>
        dut.io.mmio.req.ready.poke(false.B)
        dut.io.mmio.resp.valid.poke(false.B)
        dut.io.mem.ready.poke(false.B)
        dut.io.mem.waccept.poke(false.B)
        dut.io.written.valid.poke(false.B)
        dut.io.in.valid.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val mem = scala.collection.mutable.Map[Long, Int]()
        // 96 bytes from 0x1060: across the 128-byte boundary at 0x1080, so two bursts.
        run(dut, 'F'.toInt +: (le(0x1060) ++ le(96) ++ le(0x12345678L)), mem)
        run(dut, 'F'.toInt +: (le(0x2000) ++ le(0) ++ le(0x9ABCDEF0L)), mem)
        val want = (0x1060L until 0x10C0L by 2).map(a => a -> (if (a % 4 == 0) 0x5678 else 0x1234)).toMap
        assert(mem.toMap == want)
        println(s"  'F' wrote ${mem.size} halfwords, all of them its value, nothing else")
      }
    }
    test("dirty_pages_are_reported_once") {
      simulate(new HostLink) { dut =>
        dut.io.mmio.req.ready.poke(false.B)
        dut.io.mmio.resp.valid.poke(false.B)
        dut.io.mem.ready.poke(false.B)
        dut.io.mem.waccept.poke(false.B)
        dut.io.written.valid.poke(false.B)
        dut.io.in.valid.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dirtyPages(dut)                      // the RAM starts unknown: the first 'D' clears it
        write(dut, 0x8400000L, 2)            // one word in the heap's first page
        write(dut, 0x8400ff0L, 32)           // 64 bytes over pages 0x8400 and 0x8401: one RAM word
        write(dut, 0x841f000L - 8, 8)        // pages 0x841e and 0x841f: one RAM word
        write(dut, 0x8420000L - 8, 8)        // pages 0x841f and 0x8420: two RAM words
        write(dut, 0x0fffff80L, 64)          // the window's last page, its last 128 bytes
        write(dut, 0x18400000L, 2)           // beyond the window: wraps onto 0x8400
        assert(dirtyPages(dut) == Set(0x8400, 0x8401, 0x841e, 0x841f, 0x8420, 0xffff))
        assert(dirtyPages(dut).isEmpty)
        write(dut, 0x1000L, 2)
        assert(dirtyPages(dut) == Set(0x1))
      }
    }
  }
}
