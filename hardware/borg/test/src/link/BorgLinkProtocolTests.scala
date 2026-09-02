// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** End-to-end protocol tests across [[BorgLinkMaster]] + [[BorgLinkSlave]].
  *
  * The test impersonates all four endpoints -- SoC, Borg's mmio port, Borg's gpuMem
  * master, and the SDRAM behind MemoryController -- and reproduces the interface
  * hazards exactly, including the ones a naive stub would paper over:
  *
  *   - withdrawing `mmio.req.ready` mid-flight (Borg does this via `autoRunStall`)
  *   - '''withdrawing an already-asserted `mmio.resp.valid`''', which is the case
  *     that motivated holding `resp.ready` unconditionally high in the adapter
  *   - `gpuMem.ready` as a one-cycle pulse with no backpressure
  *   - a burst write pulled one word at a time by `waccept`
  */
object BorgLinkProtocolTests extends TestSuite {

  val TIMEOUT = 4000

  def init(dut: LinkLoopbackHarness): Unit = {
    dut.io.linkFast.poke(false.B)
    dut.io.socMmio.req.valid.poke(false.B)
    dut.io.socMmio.resp.ready.poke(true.B)
    dut.io.borgMmio.req.ready.poke(false.B)
    dut.io.borgMmio.resp.valid.poke(false.B)
    dut.io.borgMmio.resp.bits.poke(0.U)
    dut.io.borgGpu.req.poke(false.B)
    dut.io.borgGpu.wr.poke(false.B)
    dut.io.borgGpu.addr.poke(0.U)
    dut.io.borgGpu.wlen.poke(1.U)
    dut.io.borgGpu.wdata.poke(0.U)
    dut.io.memGpu.data.poke(0.U)
    dut.io.memGpu.ready.poke(false.B)
    dut.io.memGpu.waccept.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(6)
    dut.reset.poke(false.B)
  }

  def waitLinkUp(dut: LinkLoopbackHarness): Unit = {
    var c = 0
    while (c < 500 && !dut.io.linkUp.peek().litToBoolean) { dut.clock.step(1); c += 1 }
    utest.assert(dut.io.linkUp.peek().litToBoolean)
  }

  /** Step until `cond`, failing the test on timeout rather than hanging. */
  def stepUntil(dut: LinkLoopbackHarness, what: String)(cond: => Boolean): Int = {
    var c = 0
    while (c < TIMEOUT && !cond) { dut.clock.step(1); c += 1 }
    utest.assert(c < TIMEOUT)
    c
  }

  // -- Endpoint impersonation ------------------------------------------------

  /** Act as Borg's mmio port for one transaction.
    *
    * @param readyDelay cycles to withhold `req.ready` (models `autoRunStall`)
    * @param glitchValid assert `resp.valid` for one cycle, withdraw it, then
    *                    reassert -- the hazard that `resp.ready` held high absorbs
    */
  def serveBorgMmio(
      dut: LinkLoopbackHarness,
      respData: Long,
      readyDelay: Int = 0,
      glitchValid: Boolean = false
  ): (Int, Boolean, Long) = {
    stepUntil(dut, "borg req.valid") { dut.io.borgMmio.req.valid.peek().litToBoolean }

    val addr  = dut.io.borgMmio.req.bits.addr.peek().litValue.toInt
    val write = dut.io.borgMmio.req.bits.write.peek().litToBoolean
    val data  = dut.io.borgMmio.req.bits.data.peek().litValue.toLong

    // Withhold ready, as Borg does while the rasterizer is auto-running.
    for (_ <- 0 until readyDelay) dut.clock.step(1)

    if (glitchValid) {
      // Assert resp.valid on the *same* cycle req.ready is granted.  The adapter
      // is still in sMReq then, so its resp.ready is low and nothing can fire;
      // Borg then withdraws valid and reasserts later.  A correct adapter simply
      // waits for a fire and needs no state to recover.
      //
      // (Asserting the glitch any later would not be a hazard at all: resp.ready
      // is held unconditionally high in sMResp, so a "glitch" there is just a
      // legitimate response that fires immediately.)
      dut.io.borgMmio.resp.valid.poke(true.B)
      dut.io.borgMmio.resp.bits.poke(0xbadbad.U)
    }

    dut.io.borgMmio.req.ready.poke(true.B)
    dut.clock.step(1)
    dut.io.borgMmio.req.ready.poke(false.B)

    if (glitchValid) {
      dut.io.borgMmio.resp.valid.poke(false.B)
      dut.clock.step(3)
    }

    dut.io.borgMmio.resp.valid.poke(true.B)
    dut.io.borgMmio.resp.bits.poke(respData.U)
    stepUntil(dut, "borg resp fire") { dut.io.borgMmio.resp.ready.peek().litToBoolean }
    dut.clock.step(1)
    dut.io.borgMmio.resp.valid.poke(false.B)

    (addr, write, data)
  }

  /** Issue one MMIO transaction from the SoC side and return the response. */
  def socMmio(dut: LinkLoopbackHarness, addr: Int, write: Boolean, data: Long): Long = {
    dut.io.socMmio.req.valid.poke(true.B)
    dut.io.socMmio.req.bits.addr.poke(addr.U)
    dut.io.socMmio.req.bits.write.poke(write.B)
    dut.io.socMmio.req.bits.data.poke(data.U)
    dut.io.socMmio.req.bits.size.poke(2.U)
    stepUntil(dut, "soc req.ready") { dut.io.socMmio.req.ready.peek().litToBoolean }
    dut.clock.step(1)
    dut.io.socMmio.req.valid.poke(false.B)

    stepUntil(dut, "soc resp.valid") { dut.io.socMmio.resp.valid.peek().litToBoolean }
    val r = dut.io.socMmio.resp.bits.peek().litValue.toLong
    dut.clock.step(1)
    r
  }

  val tests = Tests {

    utest.test("link_comes_up") {
      simulate(new LinkLoopbackHarness(LinkParams(trainBeats = 8))) { dut =>
        init(dut)
        waitLinkUp(dut)
        utest.assert(!dut.io.linkErr.peek().litToBoolean)
      }
    }

    utest.test("mmio_write_round_trip") {
      simulate(new LinkLoopbackHarness(LinkParams(trainBeats = 8))) { dut =>
        init(dut)
        waitLinkUp(dut)

        // Drive the SoC request, then service it from the Borg side.
        dut.io.socMmio.req.valid.poke(true.B)
        dut.io.socMmio.req.bits.addr.poke(0x155.U)
        dut.io.socMmio.req.bits.write.poke(true.B)
        dut.io.socMmio.req.bits.data.poke(0xdeadbeefL.U)
        dut.io.socMmio.req.bits.size.poke(2.U)
        stepUntil(dut, "soc req.ready") { dut.io.socMmio.req.ready.peek().litToBoolean }
        dut.clock.step(1)
        dut.io.socMmio.req.valid.poke(false.B)

        val seen = serveBorgMmio(dut, 0L)
        utest.assert(seen._1 == 0x155)
        utest.assert(seen._2)
        utest.assert(seen._3 == 0xdeadbeefL)

        stepUntil(dut, "soc resp.valid") { dut.io.socMmio.resp.valid.peek().litToBoolean }
        dut.clock.step(1)
        utest.assert(!dut.io.linkErr.peek().litToBoolean)
      }
    }

    utest.test("mmio_read_returns_data") {
      simulate(new LinkLoopbackHarness(LinkParams(trainBeats = 8))) { dut =>
        init(dut)
        waitLinkUp(dut)

        dut.io.socMmio.req.valid.poke(true.B)
        dut.io.socMmio.req.bits.addr.poke(0x2ac.U)
        dut.io.socMmio.req.bits.write.poke(false.B)
        dut.io.socMmio.req.bits.data.poke(0.U)
        dut.io.socMmio.req.bits.size.poke(2.U)
        stepUntil(dut, "soc req.ready") { dut.io.socMmio.req.ready.peek().litToBoolean }
        dut.clock.step(1)
        dut.io.socMmio.req.valid.poke(false.B)

        val seen = serveBorgMmio(dut, 0x12345678L)
        utest.assert(seen._1 == 0x2ac)
        utest.assert(!seen._2)

        stepUntil(dut, "soc resp.valid") { dut.io.socMmio.resp.valid.peek().litToBoolean }
        utest.assert(dut.io.socMmio.resp.bits.peek().litValue.toLong == 0x12345678L)
        dut.clock.step(1)
        utest.assert(!dut.io.linkErr.peek().litToBoolean)
      }
    }

    utest.test("mmio_survives_stalled_ready_and_withdrawn_valid") {
      // Hazard 3, in full: Borg withholds req.ready for a while (autoRunStall),
      // then asserts resp.valid, withdraws it, and reasserts.
      simulate(new LinkLoopbackHarness(LinkParams(trainBeats = 8))) { dut =>
        init(dut)
        waitLinkUp(dut)

        dut.io.socMmio.req.valid.poke(true.B)
        dut.io.socMmio.req.bits.addr.poke(0x0aa.U)
        dut.io.socMmio.req.bits.write.poke(false.B)
        dut.io.socMmio.req.bits.data.poke(0.U)
        dut.io.socMmio.req.bits.size.poke(2.U)
        stepUntil(dut, "soc req.ready") { dut.io.socMmio.req.ready.peek().litToBoolean }
        dut.clock.step(1)
        dut.io.socMmio.req.valid.poke(false.B)

        serveBorgMmio(dut, 0xcafebabeL, readyDelay = 7, glitchValid = true)

        stepUntil(dut, "soc resp.valid") { dut.io.socMmio.resp.valid.peek().litToBoolean }
        utest.assert(dut.io.socMmio.resp.bits.peek().litValue.toLong == 0xcafebabeL)
        dut.clock.step(1)
        utest.assert(!dut.io.linkErr.peek().litToBoolean)
      }
    }

    utest.test("gpumem_read_round_trip") {
      simulate(new LinkLoopbackHarness(LinkParams(trainBeats = 8))) { dut =>
        init(dut)
        waitLinkUp(dut)

        // Borg raises req and holds it: level-held, no accept handshake.
        dut.io.borgGpu.addr.poke(0x1a2b3c.U)
        dut.io.borgGpu.req.poke(true.B)

        // The memory side sees the replayed request.
        stepUntil(dut, "mem req") { dut.io.memGpu.req.peek().litToBoolean }
        utest.assert(dut.io.memGpu.addr.peek().litValue.toInt == 0x1a2b3c)

        dut.io.memGpu.data.poke(0x89abcdefL.U)
        dut.io.memGpu.ready.poke(true.B)
        dut.clock.step(1)
        dut.io.memGpu.ready.poke(false.B)
        dut.io.memGpu.data.poke(0.U)

        // Hazard 1: ready comes back as a locally generated one-cycle pulse.
        stepUntil(dut, "borg gpuMem.ready") { dut.io.borgGpu.ready.peek().litToBoolean }
        utest.assert(dut.io.borgGpu.data.peek().litValue.toLong == 0x89abcdefL)
        dut.io.borgGpu.req.poke(false.B)
        dut.clock.step(1)
        utest.assert(!dut.io.borgGpu.ready.peek().litToBoolean) // exactly one cycle
        utest.assert(!dut.io.linkErr.peek().litToBoolean)
      }
    }

    utest.test("gpumem_sequential_reads_are_not_duplicated") {
      // The hazard the round-trip test above papers over by dropping `req` in
      // the very cycle it observes `ready`.  BorgDMA (and BorgBinner, and the
      // rasterizer) do NOT do that: `req` is a pure function of the FSM state,
      // so it stays asserted *through* the ready cycle -- with the old address
      // still on the bus -- and only changes on the following cycle.
      //
      // MemoryController tolerates that because it pulses `ready` from
      // `sRespond` and only re-arbitrates in `sIdle`, i.e. one cycle later.
      // The link slave must give the same one-cycle grace, otherwise it
      // re-samples the still-asserted request and issues a duplicate read of
      // the previous address -- which then answers the *next* request with
      // stale data and shifts every subsequent word by one.
      simulate(new LinkLoopbackHarness(LinkParams(trainBeats = 8))) { dut =>
        init(dut)
        waitLinkUp(dut)

        val addrs = Seq(0x001000, 0x001004, 0x001008, 0x00100c)

        val seenReqs = scala.collection.mutable.ArrayBuffer.empty[Int]
        val gotData  = scala.collection.mutable.ArrayBuffer.empty[Long]

        // Borg side: level-held req, address advanced one cycle after ready
        // (exactly BorgDMA's `addrReg := addrReg + 4.U` under `when(ready)`).
        var idx = 0
        dut.io.borgGpu.addr.poke(addrs(0).U)
        dut.io.borgGpu.req.poke(true.B)

        // Memory side: MemoryController's shape -- latch on req, respond a few
        // cycles later with a single-cycle `ready`, never while responding.
        var memBusy    = 0
        var memAddr    = 0
        var c          = 0
        while (idx < addrs.length && c < TIMEOUT) {
          if (memBusy == 0 && dut.io.memGpu.req.peek().litToBoolean) {
            memAddr = dut.io.memGpu.addr.peek().litValue.toInt
            seenReqs += memAddr
            memBusy = 4
          } else if (memBusy > 1) {
            memBusy -= 1
          } else if (memBusy == 1) {
            dut.io.memGpu.data.poke(memAddr.U)
            dut.io.memGpu.ready.poke(true.B)
            memBusy = 0
          }

          val borgReady = dut.io.borgGpu.ready.peek().litToBoolean
          val borgData  = dut.io.borgGpu.data.peek().litValue.toLong
          dut.clock.step(1)
          dut.io.memGpu.ready.poke(false.B)

          if (borgReady) {
            gotData += borgData
            idx += 1
            if (idx < addrs.length) dut.io.borgGpu.addr.poke(addrs(idx).U)
            else dut.io.borgGpu.req.poke(false.B)
          }
          c += 1
        }
        dut.io.borgGpu.req.poke(false.B)
        utest.assert(idx == addrs.length)

        // Let any spurious extra request the slave may have launched reach the
        // memory side before we judge the count.
        for (_ <- 0 until 200) dut.clock.step(1)
        if (dut.io.memGpu.req.peek().litToBoolean)
          seenReqs += dut.io.memGpu.addr.peek().litValue.toInt

        utest.assert(gotData.toSeq == addrs.map(_.toLong))
        utest.assert(seenReqs.toSeq == addrs)
        utest.assert(!dut.io.linkErr.peek().litToBoolean)
      }
    }

    utest.test("gpumem_burst_write_round_trip") {
      // Hazard 4: the slave drains all 16 words locally via waccept before it
      // transmits anything, which is also what keeps the packet atomic on the wire.
      simulate(new LinkLoopbackHarness(LinkParams(trainBeats = 8))) { dut =>
        init(dut)
        waitLinkUp(dut)

        val words = (0 until 16).map(i => 0xb000 + i)

        dut.io.borgGpu.addr.poke(0x004000.U)
        dut.io.borgGpu.wlen.poke(16.U)
        dut.io.borgGpu.wdata.poke(words(0).U)
        dut.io.borgGpu.wr.poke(true.B)

        // Feed words as the slave pulls them.  Note the step *precedes* presenting
        // the next word: Borg advances its burst counter on the clock edge, so
        // poking a new value in the same cycle waccept is observed would have it
        // latched by that very edge and silently drop word 0.
        var idx = 0
        var c = 0
        while (idx < 16 && c < TIMEOUT) {
          val accepted = dut.io.borgGpu.waccept.peek().litToBoolean
          dut.clock.step(1)
          if (accepted) {
            idx += 1
            if (idx < 16) dut.io.borgGpu.wdata.poke(words(idx).U)
          }
          c += 1
        }
        utest.assert(idx == 16)

        // The memory side must see the same burst, in order.
        stepUntil(dut, "mem wr") { dut.io.memGpu.wr.peek().litToBoolean }
        utest.assert(dut.io.memGpu.addr.peek().litValue.toInt == 0x004000)
        utest.assert(dut.io.memGpu.wlen.peek().litValue.toInt == 16)

        val got = scala.collection.mutable.ArrayBuffer.empty[Int]
        c = 0
        while (got.length < 16 && c < TIMEOUT) {
          got += dut.io.memGpu.wdata.peek().litValue.toInt
          dut.io.memGpu.waccept.poke(true.B)
          dut.clock.step(1)
          c += 1
        }
        dut.io.memGpu.waccept.poke(false.B)
        utest.assert(got.toSeq == words)

        dut.io.memGpu.ready.poke(true.B)
        dut.clock.step(1)
        dut.io.memGpu.ready.poke(false.B)

        stepUntil(dut, "borg gpuMem.ready") { dut.io.borgGpu.ready.peek().litToBoolean }
        dut.io.borgGpu.wr.poke(false.B)
        dut.clock.step(1)
        utest.assert(!dut.io.linkErr.peek().litToBoolean)
      }
    }
  }
}
