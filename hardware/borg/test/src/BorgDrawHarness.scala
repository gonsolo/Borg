// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._

/** The test's door into [[BorgDrawHarness]]'s memory. */
class HarnessBackdoor extends Bundle {
  val addr    = Input(UInt(20.W))     // byte address, even
  val wdata   = Input(UInt(32.W))
  val we      = Input(Bool())         // write the 32-bit word at addr (the "ROM": what the test put there)
  val gen     = Input(UInt(32.W))     // GPU writes of another generation are forgotten
  val rom     = Output(UInt(32.W))
  val half    = Output(UInt(16.W))    // the halfword the GPU wrote at addr ...
  val written = Output(Bool())        // ... if it did, this generation
  // 32-bit writes to `watch` (a shader's STOREs), in order: `logCount` of them, `logData` the
  // one at `logIndex`. The design's reset empties the log.
  val watch    = Input(UInt(32.W))
  val logIndex = Input(UInt(6.W))
  val logCount = Output(UInt(7.W))
  val logData  = Output(UInt(32.W))
  // The read being answered this cycle (BORG_DRAW_TRACE).
  val rdValid = Output(Bool())
  val rdAddr  = Output(UInt(32.W))
  val rdData  = Output(UInt(32.W))
}

/** [[BorgTestWrapper]] with the GPU's memory inside the simulation.
  *
  * A draw test used to answer every GPU memory request from Scala, several
  * simulator round trips per clock cycle. Here the memory is part of the
  * design under test, with that Scala model's contents and the direct simulator's handshake:
  *
  *  - a read returns the halfwords the GPU wrote at `addr` and `addr + 2` if
  *    it wrote either, else the test's word;
  *  - a write stores 16 bits per beat, low half first, pulling the next beat
  *    with `waccept` and answering `ready` after the last one.
  *
  * The test reaches it through the backdoor. Raising `gen` forgets every GPU
  * write at once (a tag per halfword), which is how a test starts from clean
  * memory without touching a megabyte of it.
  */
class BorgDrawHarness(cfg: BorgConfig) extends BorgTestWrapper(cfg) {
  val bd = IO(new HarnessBackdoor)

  private val Halves = 1 << 19                       // 1 MiB
  private val romMem  = Mem(Halves, UInt(32.W))      // keyed by the even byte address, as the tests' map was
  private val halfMem = Mem(Halves, UInt(16.W))
  private val tagMem  = Mem(Halves, UInt(32.W))
  // A word the test never wrote reads 0, as it did from the tests' map: the simulator starts
  // its memories with arbitrary contents, so a written word carries a mark.
  private val romMark = Mem(Halves, UInt(32.W))
  private val Marked  = "hC0DE600D".U(32.W)
  private def romAt(i: UInt): UInt = Mux(romMark(i) === Marked, romMem(i), 0.U)
  private def idx(a: UInt): UInt = a(19, 1)

  // The same slave as simulation/direct/direct_sim.h (DirectSim::tick) with no extra latency:
  // a request is taken at one clock edge and answered in the next cycle, so the answer never
  // depends on what the GPU does in the cycle it gets it.
  private val g = borg.io.gpuMem
  private val sIdle :: sSample :: sDelay :: Nil = Enum(3)
  private val state = RegInit(sIdle)
  private val gaddr = Reg(UInt(19.W))
  private val grem  = Reg(UInt(7.W))
  private val gdata = Reg(UInt(32.W))
  private val gread = RegInit(false.B)
  private val logMem   = Mem(64, UInt(32.W))
  private val logCount = RegInit(0.U(7.W))
  private val watched  = RegInit(false.B)         // the write under way is a 32-bit one to `watch`
  private val lowHalf  = Reg(UInt(16.W))
  bd.logCount := logCount
  bd.logData  := logMem(bd.logIndex)
  private val gfull = Reg(UInt(32.W))
  bd.rdValid := state === sDelay && gread
  bd.rdAddr  := gfull
  bd.rdData  := gdata
  private def store(i: UInt): Unit = { halfMem.write(i, g.wdata(15, 0)); tagMem.write(i, bd.gen) }

  private val ra = idx(g.addr)
  private val w0 = tagMem(ra) === bd.gen; private val w1 = tagMem(ra + 1.U) === bd.gen
  g.data    := gdata
  g.ready   := false.B
  g.waccept := false.B
  switch(state) {
    is(sIdle) {
      when(g.wr) {
        gread := false.B
        watched := g.addr === bd.watch && g.wlen === 2.U
        lowHalf := g.wdata(15, 0)
        store(ra)                                   // the first halfword
        when(g.wlen > 1.U) { grem := g.wlen - 1.U; gaddr := ra + 1.U; g.waccept := true.B; state := sSample }
          .otherwise { state := sDelay }
      }.elsewhen(g.req) {
        // The halfwords the GPU wrote at addr and addr + 2 if it wrote either, else the test's word.
        gdata := Mux(w0 || w1, Cat(Mux(w1, halfMem(ra + 1.U), 0.U(16.W)), Mux(w0, halfMem(ra), 0.U(16.W))), romAt(ra))
        gread := true.B; gfull := g.addr
        state := sDelay
      }
    }
    is(sSample) {                                   // wdata now holds the beat the master advanced to
      store(gaddr)
      when(watched && !logCount(6)) { logMem.write(logCount(5, 0), Cat(g.wdata(15, 0), lowHalf)); logCount := logCount + 1.U }
      gaddr := gaddr + 1.U
      grem  := grem - 1.U
      when(grem > 1.U) { g.waccept := true.B }.otherwise { state := sDelay }
    }
    is(sDelay) {
      g.ready := true.B
      state := sIdle
    }
  }

  when(bd.we) { romMem.write(idx(bd.addr), bd.wdata); romMark.write(idx(bd.addr), Marked) }
  bd.rom     := romAt(idx(bd.addr))
  bd.half    := halfMem(idx(bd.addr))
  bd.written := tagMem(idx(bd.addr)) === bd.gen
}

/** One running simulation per configuration, shared by every scene of a test
  * JVM: elaborating, verilating and compiling the design costs far more than
  * simulating a scene. A scene runs on the simulation's own thread (ChiselSim
  * ties a simulation to the thread that started it); every draw resets the
  * design, so scenes do not see each other.
  */
object BorgDrawSim {
  /** What the simulation's memory holds, kept across scenes. */
  class Mirror {
    val rom = scala.collection.mutable.Map[Int, BigInt]()
    var gen = BigInt(0x5EED0000L)
  }
  private class Worker(cfg: BorgConfig) {
    private val jobs = new java.util.concurrent.LinkedBlockingQueue[Option[(BorgDrawHarness, Mirror) => Unit]]()
    private val done = new java.util.concurrent.SynchronousQueue[Option[Throwable]]()
    private val thread = new Thread(() => {
      try simulate(new BorgDrawHarness(cfg)) { h =>
        val mirror = new Mirror
        var go = true
        while (go) jobs.take() match {
          case None => go = false
          case Some(job) => done.put(try { job(h, mirror); None } catch { case t: Throwable => Some(t) })
        }
      } catch { case t: Throwable => done.put(Some(t)) }
    }, "borg-draw-sim")
    thread.setDaemon(true)
    thread.start()
    def run(job: (BorgDrawHarness, Mirror) => Unit): Unit = synchronized {
      jobs.put(Some(job))
      done.take().foreach(t => throw t)
    }
    def stop(): Unit = { jobs.put(None); thread.join(10000) }
  }
  private val workers = scala.collection.mutable.Map[BorgConfig, Worker]()
  Runtime.getRuntime.addShutdownHook(new Thread(() => workers.synchronized { workers.values.foreach(_.stop()) }))

  /** The test's word at byte address `a`. */
  def put(h: BorgDrawHarness, m: Mirror, a: Int, v: BigInt): Unit = if (!m.rom.get(a).contains(v)) {
    Predef.assert((a & 1) == 0 && a >= 0 && a < (1 << 20), f"address 0x$a%x is outside the harness memory")
    h.bd.addr.poke(a.U); h.bd.wdata.poke((v & BigInt(0xFFFFFFFFL)).U); h.bd.we.poke(true.B)
    h.clock.step(1)
    h.bd.we.poke(false.B)
    m.rom(a) = v
  }
  /** Forget what the GPU wrote. */
  def forgetWrites(h: BorgDrawHarness, m: Mirror): Unit = { m.gen += 1; h.bd.gen.poke(m.gen.U) }
  /** Empty memory: every word reads 0. */
  def clear(h: BorgDrawHarness, m: Mirror): Unit = {
    for (a <- m.rom.keys.toSeq) put(h, m, a, 0)
    m.rom.clear()
    forgetWrites(h, m)
  }
  /** The 32-bit values written to the watched address since the last reset. */
  def stores(h: BorgDrawHarness): Seq[BigInt] =
    (0 until h.bd.logCount.peek().litValue.toInt).map { i => h.bd.logIndex.poke(i.U); h.bd.logData.peek().litValue }

  def run(cfg: BorgConfig)(job: (BorgDrawHarness, Mirror) => Unit): Unit =
    workers.synchronized { workers.getOrElseUpdate(cfg, new Worker(cfg)) }.run(job)
}
