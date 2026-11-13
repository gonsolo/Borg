// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{Bundle, Flipped, IO, Module, UInt, fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth}
import chisel3.util.Decoupled
import chisel3.simulator.EphemeralSimulator._
import utest._

class AsyncFifoRigIO extends Bundle {
  val enq = Flipped(Decoupled(UInt(8.W)))
  val deq = Decoupled(UInt(8.W))
}

/** Both sides on one clock: the order and the full and empty flags. */
class AsyncFifoRig extends Module {
  val io = IO(new AsyncFifoRigIO)
  val f  = Module(new AsyncFifo(8, 3))
  f.enqClock := clock; f.deqClock := clock
  f.enqReset := reset.asBool; f.deqReset := reset.asBool
  f.enq <> io.enq
  io.deq <> f.deq
}

object UsbFifoTests extends TestSuite {
  val tests = Tests {
    // The FT2232H's side as its data sheet has it: a byte moves at a rising edge with RD# (WR#)
    // and RXF# (TXE#) low; the bus must never be driven from both ends.
    test("bytes_cross_both_ways_with_the_chip_stalling") {
      simulate(new UsbFifo) { dut =>
        val rnd = new scala.util.Random(7)
        val down = Seq.fill(300)(rnd.nextInt(256))
        val up   = Seq.fill(300)(rnd.nextInt(256))
        var host = down
        var toSend = up
        val got, sent = scala.collection.mutable.ArrayBuffer[Int]()
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        for (_ <- 0 until 6000) {
          val p = dut.io.pins
          val rxf = host.nonEmpty && rnd.nextInt(4) != 0
          val txe = rnd.nextInt(5) != 0
          p.rxfN.poke((!rxf).B)
          p.txeN.poke((!txe).B)
          p.dIn.poke(host.headOption.getOrElse(0).U)
          dut.io.tx.valid.poke(toSend.nonEmpty.B)
          dut.io.tx.bits.poke(toSend.headOption.getOrElse(0).U)
          dut.io.txEnd.poke(false.B)
          val rxReady = rnd.nextInt(3) != 0
          dut.io.rx.ready.poke(rxReady.B)
          val oe = !p.oeN.peek().litToBoolean
          assert(!(oe && p.dOe.peek().litToBoolean))
          if (!p.rdN.peek().litToBoolean && rxf) { assert(oe); host = host.tail }
          if (!p.wrN.peek().litToBoolean && txe) { assert(p.dOe.peek().litToBoolean); sent += p.dOut.peek().litValue.toInt }
          if (dut.io.tx.ready.peek().litToBoolean && toSend.nonEmpty) toSend = toSend.tail
          if (rxReady && dut.io.rx.valid.peek().litToBoolean) got += dut.io.rx.bits.peek().litValue.toInt
          dut.clock.step()
        }
        assert(got.toSeq == down)
        assert(sent.toSeq == up)
      }
    }
    // SIWU# sends what the chip holds at once: only after a reply's last byte, not at every gap.
    test("a_reply_goes_out_at_once_only_at_its_end") {
      simulate(new UsbFifo) { dut =>
        val p = dut.io.pins
        var left = (1 to 10).toList
        var sent = 0
        var nextAt = 0
        var siwuAfter = Seq[Int]()
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        p.rxfN.poke(true.B); p.txeN.poke(false.B); dut.io.rx.ready.poke(true.B)
        for (c <- 0 until 200) {
          val give = left.nonEmpty && c >= nextAt      // a gap after every byte
          dut.io.tx.valid.poke(give.B)
          dut.io.tx.bits.poke(left.headOption.getOrElse(0).U)
          dut.io.txEnd.poke((left.size == 1).B)
          if (!p.siwuN.peek().litToBoolean) siwuAfter :+= sent
          if (!p.wrN.peek().litToBoolean) sent += 1
          if (give && dut.io.tx.ready.peek().litToBoolean) { left = left.tail; nextAt = c + 7 }
          dut.clock.step()
        }
        assert(sent == 10)
        assert(siwuAfter == Seq(10))
      }
    }
    test("async_fifo_keeps_order") {
      simulate(new AsyncFifoRig) { dut =>
        val rnd  = new scala.util.Random(3)
        val data = Seq.fill(200)(rnd.nextInt(256))
        var left = data
        val got  = scala.collection.mutable.ArrayBuffer[Int]()
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        for (_ <- 0 until 2000) {
          val push = left.nonEmpty && rnd.nextBoolean()
          dut.io.enq.valid.poke(push.B)
          dut.io.enq.bits.poke(left.headOption.getOrElse(0).U)
          val pop = rnd.nextBoolean()
          dut.io.deq.ready.poke(pop.B)
          if (push && dut.io.enq.ready.peek().litToBoolean) left = left.tail
          if (pop && dut.io.deq.valid.peek().litToBoolean) got += dut.io.deq.bits.peek().litValue.toInt
          dut.clock.step()
        }
        assert(got.toSeq == data)
      }
    }
  }
}
