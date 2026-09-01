// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Physical-layer tests: [[LinkTx]] → pins → [[LinkRx]].
  *
  * Covers serialization, packet framing, the header-driven length decode, odd
  * parity, arbitration, and recovery from both error classes -- at both beat
  * rates (N=1 and N=2) and both bus widths (w=16 and the w=8 `link_narrow`
  * recovery mode).
  */
object BorgLinkPhyTests extends TestSuite {

  case class RxFlit(flit: Int, first: Boolean, last: Boolean, idx: Int)

  // -- Wire-format helpers, mirroring LinkHeader's encoding -------------------

  def hdrBits(chan: Int, opcode: Int, payload: Int): Int =
    ((chan & 1) << 15) | ((opcode & 7) << 12) | (payload & 0xfff)

  def mmioPayload(size: Int, addr: Int): Int = ((size & 3) << 10) | (addr & 0x3ff)
  def vramPayload(wlenLog2: Int, addrHi: Int): Int = ((wlenLog2 & 7) << 9) | (addrHi & 0x1ff)

  val chanM = 0
  val chanV = 1
  val opPutFullData = 0
  val opGet = 4
  val opAccessAck = 0
  val opAccessAckData = 1

  /** M.A read: header only. */
  def mAGet(addr: Int): Seq[Int] = Seq(hdrBits(chanM, opGet, mmioPayload(2, addr)))

  /** M.A write: header + two data flits, low half first. */
  def mAPut(addr: Int, data: Long): Seq[Int] = Seq(
    hdrBits(chanM, opPutFullData, mmioPayload(2, addr)),
    (data & 0xffff).toInt,
    ((data >> 16) & 0xffff).toInt
  )

  /** V.A read: header + addr[15:0]. */
  def vAGet(addr: Int): Seq[Int] = Seq(
    hdrBits(chanV, opGet, vramPayload(0, (addr >> 16) & 0x1ff)),
    addr & 0xffff
  )

  /** V.A burst write: header + addr[15:0] + 2^wlenLog2 data words. */
  def vAPut(addr: Int, wlenLog2: Int, words: Seq[Int]): Seq[Int] = {
    require(words.length == (1 << wlenLog2))
    Seq(
      hdrBits(chanV, opPutFullData, vramPayload(wlenLog2, (addr >> 16) & 0x1ff)),
      addr & 0xffff
    ) ++ words
  }

  /** M.D read response: header + two data flits. */
  def mDData(data: Long): Seq[Int] = Seq(
    hdrBits(chanM, opAccessAckData, 0),
    (data & 0xffff).toInt,
    ((data >> 16) & 0xffff).toInt
  )

  // -- Driver ----------------------------------------------------------------

  /** Drive `packets` through the harness, collecting everything the Rx emits.
    *
    * `inject` is consulted once per beat and returns `(flipMask, forceV0)` so a
    * test can corrupt a chosen beat.  Returns the received flits and the number
    * of error pulses observed.
    */
  def sendAndCollect(
      dut: LinkPhyHarness,
      packets: Seq[(Boolean, Seq[Int])],
      divCycles: Int = 1,
      tailBeats: Int = 24,
      inject: Int => (Int, Boolean) = _ => (0, false)
  ): (Seq[RxFlit], Int) = {

    var received = Vector.empty[RxFlit]
    var errors   = 0

    var pktIdx  = 0
    var flitIdx = 0
    var beat    = 0
    var phase   = 0
    var tail    = 0

    // Generous bound: the driver must terminate even if the DUT wedges.
    val maxCycles = (packets.map(_._2.length).sum + packets.length * 4 + tailBeats + 16) * divCycles * 4
    var cycle = 0

    while (cycle < maxCycles && (pktIdx < packets.length || tail < tailBeats)) {
      val beatEn = phase == 0
      dut.io.beatEn.poke(beatEn.B)

      val (flip, forceV0) = if (beatEn) inject(beat) else (0, false)
      dut.io.flipD.poke(flip.U)
      dut.io.forceV0.poke(forceV0.B)

      val active = pktIdx < packets.length
      val (useD, flits) = if (active) packets(pktIdx) else (false, Seq.empty[Int])

      // Hold valid for the whole packet: LinkTx requires atomicity.
      dut.io.a.valid.poke((active && !useD).B)
      dut.io.d.valid.poke((active && useD).B)
      if (active) {
        val f    = flits(flitIdx)
        val last = flitIdx == flits.length - 1
        dut.io.a.bits.flit.poke(f.U)
        dut.io.a.bits.last.poke(last.B)
        dut.io.d.bits.flit.poke(f.U)
        dut.io.d.bits.last.poke(last.B)
      }

      // Sample combinational outputs before stepping.
      val ready = if (!active) false
                  else if (useD) dut.io.d.ready.peek().litToBoolean
                  else dut.io.a.ready.peek().litToBoolean

      if (dut.io.out.valid.peek().litToBoolean) {
        received = received :+ RxFlit(
          dut.io.out.bits.flit.peek().litValue.toInt,
          dut.io.out.bits.first.peek().litToBoolean,
          dut.io.out.bits.last.peek().litToBoolean,
          dut.io.out.bits.idx.peek().litValue.toInt
        )
      }
      if (dut.io.err.peek().litToBoolean) errors += 1

      dut.clock.step(1)

      if (ready) {
        flitIdx += 1
        if (flitIdx == flits.length) { flitIdx = 0; pktIdx += 1 }
      }
      if (beatEn) {
        beat += 1
        if (!active) tail += 1
      }
      phase = (phase + 1) % divCycles
      cycle += 1
    }

    dut.io.a.valid.poke(false.B)
    dut.io.d.valid.poke(false.B)
    (received, errors)
  }

  def init(dut: LinkPhyHarness): Unit = {
    dut.io.a.valid.poke(false.B)
    dut.io.d.valid.poke(false.B)
    dut.io.beatEn.poke(false.B)
    dut.io.flipD.poke(0.U)
    dut.io.forceV0.poke(false.B)
    dut.io.forceDead.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(4)
    dut.reset.poke(false.B)
    dut.clock.step(2)
  }

  /** Split a received flit stream back into packets on the `last` marker. */
  def packetize(flits: Seq[RxFlit]): Seq[Seq[Int]] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Seq[Int]]
    var cur = scala.collection.mutable.ArrayBuffer.empty[Int]
    for (f <- flits) {
      cur += f.flit
      if (f.last) { out += cur.toSeq; cur = scala.collection.mutable.ArrayBuffer.empty[Int] }
    }
    out.toSeq
  }

  val tests = Tests {

    utest.test("single_flit_round_trip") {
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val pkt = mAGet(0x123)
        val (rx, errs) = sendAndCollect(dut, Seq((false, pkt)))
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == Seq(pkt))
        utest.assert(rx.head.first && rx.head.last && rx.head.idx == 0)
      }
    }

    utest.test("multi_flit_round_trip") {
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val pkt = mAPut(0x2ac, 0xdeadbeefL)
        val (rx, errs) = sendAndCollect(dut, Seq((false, pkt)))
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == Seq(pkt))
        // Index and framing markers must be exact -- adapters rely on them.
        utest.assert(rx.map(_.idx) == Seq(0, 1, 2))
        utest.assert(rx.map(_.first) == Seq(true, false, false))
        utest.assert(rx.map(_.last) == Seq(false, false, true))
      }
    }

    utest.test("burst_write_length_from_header") {
      // The whole point of the log2 burst encoding: an 18-flit packet whose
      // length the receiver knows from flit 0 alone.
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = false)) { dut =>
        init(dut)
        val words = (0 until 16).map(i => 0x1000 + i)
        val pkt = vAPut(0x1a2b3c, 4, words)
        utest.assert(pkt.length == 18)
        val (rx, errs) = sendAndCollect(dut, Seq((false, pkt)))
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == Seq(pkt))
      }
    }

    utest.test("back_to_back_packets") {
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val pkts = Seq(mAGet(0x11), mAPut(0x22, 0x01020304L), mAGet(0x33))
        val (rx, errs) = sendAndCollect(dut, pkts.map(pk => (false, pk)))
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == pkts)
      }
    }

    utest.test("arbitration_d_beats_a") {
      // Fixed priority D over A, non-preemptible. With both valid from the same
      // cycle, D's packet must come out first and intact.
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val dPkt = Seq(hdrBits(chanV, opAccessAckData, 0), 0xcafe, 0xbabe)
        val aPkt = mAGet(0x0ff)

        // Present D first so it wins arbitration, then A.
        val (rx, errs) = sendAndCollect(dut, Seq((true, dPkt), (false, aPkt)))
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == Seq(dPkt, aPkt))
      }
    }

    utest.test("parity_error_detected") {
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val pkt = mAPut(0x2ac, 0xdeadbeefL)
        // Corrupt the second beat of the packet, leaving parity stale.
        val (_, errs) = sendAndCollect(dut, Seq((false, pkt)),
          inject = b => if (b == 1) (0x0001, false) else (0, false))
        utest.assert(errs >= 1)
        utest.assert(dut.io.errParity.peek().litToBoolean)
      }
    }

    utest.test("framing_error_detected") {
      // The corrupted beat must land while the receiver is in sPayload, so use a
      // long burst.  (The pins are registered and the Rx has capture flops, so a
      // beat index is two stages ahead of what the Rx is acting on -- dropping
      // `v` on an early beat is just a longer gap, correctly not an error.)
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = false)) { dut =>
        init(dut)
        val words = (0 until 16).map(i => 0x2000 + i)
        val pkt = vAPut(0x000100, 4, words) // 18 flits
        // Drop valid mid-packet with parity recomputed: pure framing error.
        val (_, errs) = sendAndCollect(dut, Seq((false, pkt)),
          inject = b => if (b == 8) (0, true) else (0, false))
        utest.assert(errs >= 1)
        utest.assert(!dut.io.errParity.peek().litToBoolean)
      }
    }

    utest.test("resynchronizes_after_error") {
      // The self-resynchronizing property: after corruption the receiver may emit
      // garbage for the rest of that packet, but the inter-packet gap must restore
      // alignment so a later packet arrives intact.
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val bad  = mAPut(0x2ac, 0xdeadbeefL)
        val good = mAPut(0x155, 0x12345678L)
        val (rx, errs) = sendAndCollect(dut, Seq((false, bad), (false, good)),
          inject = b => if (b == 1) (0x0040, false) else (0, false))
        utest.assert(errs >= 1)
        val pkts = packetize(rx)
        utest.assert(pkts.nonEmpty)
        utest.assert(pkts.last == good)
      }
    }

    utest.test("idle_line_is_not_an_error") {
      // A genuine idle line carries d=0, v=0 and *odd* parity p=1, and must be
      // silent -- otherwise link_err would be meaningless between packets.
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        var errs = 0
        dut.io.beatEn.poke(true.B)
        for (_ <- 0 until 16) {
          if (dut.io.err.peek().litToBoolean) errs += 1
          dut.clock.step(1)
        }
        utest.assert(errs == 0)
      }
    }

    utest.test("dead_cable_fails_parity_every_beat") {
      // An unplugged cable reads all-zero, which is *even* parity, so it must
      // fail continuously rather than looking like a quiet idle line.  This is
      // the mechanism that keeps link_up from ever asserting on a dead link.
      val p = LinkParams()
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        dut.io.beatEn.poke(true.B)
        dut.io.forceDead.poke(true.B)
        // Let the capture flops fill with the dead-line value first.
        dut.clock.step(3)
        var errs = 0
        for (_ <- 0 until 16) {
          if (dut.io.err.peek().litToBoolean) errs += 1
          dut.clock.step(1)
        }
        utest.assert(errs == 16)
      }
    }

    utest.test("div2_beat_rate") {
      // N=2 -- the reset default, 12.5 MHz beats from the 25 MHz core clock.
      val p = LinkParams(divLog2 = 1)
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val pkts = Seq(mAGet(0x11), mAPut(0x22, 0x89abcdefL))
        val (rx, errs) = sendAndCollect(dut, pkts.map(pk => (false, pk)), divCycles = 2)
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == pkts)
      }
    }

    utest.test("div4_beat_rate") {
      val p = LinkParams(divLog2 = 2)
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val pkts = Seq(mAPut(0x22, 0x89abcdefL))
        val (rx, errs) = sendAndCollect(dut, pkts.map(pk => (false, pk)), divCycles = 4)
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == pkts)
      }
    }

    utest.test("narrow_w8_round_trip") {
      // link_narrow: half the pins, two beats per flit, identical flit format.
      val p = LinkParams(w = 8)
      utest.assert(p.beatsPerFlit == 2)
      simulate(new LinkPhyHarness(p, isDn = true)) { dut =>
        init(dut)
        val pkts = Seq(mAGet(0x3ff), mAPut(0x155, 0xfeedfaceL))
        val (rx, errs) = sendAndCollect(dut, pkts.map(pk => (false, pk)))
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == pkts)
      }
    }

    utest.test("narrow_w8_burst") {
      val p = LinkParams(w = 8)
      simulate(new LinkPhyHarness(p, isDn = false)) { dut =>
        init(dut)
        val words = (0 until 16).map(i => 0xbe00 + i)
        val pkt = vAPut(0x0f1e2d, 4, words)
        val (rx, errs) = sendAndCollect(dut, Seq((false, pkt)))
        utest.assert(errs == 0)
        utest.assert(packetize(rx) == Seq(pkt))
      }
    }
  }
}
