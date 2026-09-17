// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator.{
  toTestableClock, toTestableSInt, toTestableUInt, toTestableBool,
  toTestableReset, toTestableEnum, toTestableRecord, toTestableVec, toTestableData
}
import utest._
import borg.link.{BorgLinkTestWrapper, LinkParams}

/** Full-width words written through Borg's external `gpuMem` port.
  *
  * Every real memory behind that port stores a write word as ONE 16-bit
  * halfword: `MemoryController` issues GPU writes as `HuttSize.Half` and streams
  * `wdata(15, 0)` per burst beat, and `BorgLinkSlave` sends one 16-bit flit per
  * word. A 32-bit value therefore only survives as a two-word burst, low
  * halfword first, which a word read at the same address reassembles.
  *
  * The memory models these tests previously used stored `wdata` whole, so an
  * FP32 shader STORE (and the geometry sequencer's FP32 setup store) looked
  * correct in simulation while silently dropping the upper half on the ULX3S,
  * in the Verilator SoC and across the wafer.space link. [[HalfwordDram]]
  * models the contract as the hardware implements it, and the scenario runs both
  * directly and over the link.
  */
object BorgGpuMemWordTests extends TestSuite with FastBuildSimulator {

  type Dut = Module with HasLegacyBorgMmio

  val cfg    = BorgConfig.Test
  val lsBase = 0x1000

  /** DRAM with MemoryController's write contract: one 16-bit halfword per write
    * word at a 2-byte stride, `waccept` between burst words, `ready` once at
    * the end, then a cycle of grace before the next request is sampled. Reads
    * return the 32-bit word at `addr` (two halfwords, low first).
    */
  class HalfwordDram {
    val mem = scala.collection.mutable.Map[Int, Int]()
    private var state = 0 // 0 idle, 1 writing, 2 respond, 3 grace
    private var base, len, idx = 0
    private var readData = 0L

    def word(addr: Int): Long =
      (mem.getOrElse(addr + 2, 0).toLong << 16) | mem.getOrElse(addr, 0).toLong

    def step(d: Dut): Unit = {
      val g = d.io.gpuMem
      g.waccept.poke(false.B)
      g.ready.poke(false.B)
      state match {
        case 0 =>
          if (g.wr.peek().litToBoolean) {
            base = g.addr.peek().litValue.toInt
            len = g.wlen.peek().litValue.toInt
            idx = 0
            state = 1
          } else if (g.req.peek().litToBoolean) {
            readData = word(g.addr.peek().litValue.toInt)
            state = 2
          }
        case 1 =>
          mem(base + 2 * idx) = (g.wdata.peek().litValue & 0xffff).toInt
          if (idx < len - 1) { g.waccept.poke(true.B); idx += 1 }
          else state = 2
        case 2 =>
          g.data.poke(readData.U)
          g.ready.poke(true.B)
          state = 3
        case _ =>
          state = 0
      }
      d.clock.step(1)
    }
  }

  def waitReady(d: Dut, dram: HalfwordDram, limit: Int = 20000): Unit = {
    var n = 0
    while (n < limit && !d.io.data_ready.peek().litToBoolean) { dram.step(d); n += 1 }
    Predef.assert(n < limit, "timed out waiting for data_ready")
  }

  def mmioWrite(d: Dut, dram: HalfwordDram, addr: Int, data: BigInt): Unit = {
    waitReady(d, dram)
    d.io.address.poke(addr.U)
    d.io.data_in.poke(data.U)
    d.io.data_write_n.poke(2.U)
    dram.step(d)
    d.io.data_write_n.poke(3.U)
    waitReady(d, dram)
    dram.step(d)
  }

  def mmioRead(d: Dut, dram: HalfwordDram, addr: Int): BigInt = {
    waitReady(d, dram)
    d.io.address.poke(addr.U)
    d.io.data_read_n.poke(2.U)
    dram.step(d)
    dram.step(d)
    waitReady(d, dram)
    val v = d.io.data_out.peek().litValue
    d.io.data_read_n.poke(3.U)
    dram.step(d)
    v
  }

  // -pi first: its low halfword is non-zero, so a dropped upper half reads
  // back as 0x00000FDB -- distinguishable from a write that never happened.
  val values = Seq(BigInt("C0490FDB", 16), BigInt("3F800000", 16)) // -pi, 1.0f

  /** STORE r1 to element r0, LOAD it back into r3, for values whose upper
    * halfword is non-zero. Checks the DRAM word itself and the register.
    */
  def storeLoadScenario(d: Dut): Unit = {
    val dram = new HalfwordDram
    d.io.data_write_n.poke(3.U)
    d.io.data_read_n.poke(3.U)
    d.io.gpuMem.data.poke(0.U)
    dram.step(d)
    mmioWrite(d, dram, BorgGpuRegs.control_offset.litValue.toInt, 2) // reset pipeline
    mmioWrite(d, dram, BorgGpuRegs.ls_base_offset.litValue.toInt, lsBase)

    val imem = BorgGpuRegs.imem_offset.litValue.toInt
    val gpr  = BorgGpuRegs.gpr_offset.litValue.toInt
    for ((v, i) <- values.zipWithIndex) {
      val index = 3 + i
      mmioWrite(d, dram, gpr + 0 * 4, index)
      mmioWrite(d, dram, gpr + 1 * 4, v)
      mmioWrite(d, dram, gpr + 3 * 4, 0)
      mmioWrite(d, dram, imem + 0 * 4, Instructions.STORE(rs1 = 0, rs2 = 1))
      mmioWrite(d, dram, imem + 1 * 4, Instructions.LOAD(rs1 = 0, rd = 3))
      mmioWrite(d, dram, imem + 2 * 4, 0)
      mmioWrite(d, dram, BorgGpuRegs.control_offset.litValue.toInt, 2)
      mmioWrite(d, dram, BorgGpuRegs.control_offset.litValue.toInt, 1)

      var status = BigInt(0)
      var n = 0
      do {
        for (_ <- 0 until 20) dram.step(d)
        status = mmioRead(d, dram, BorgGpuRegs.status_offset.litValue.toInt)
        n += 1
      } while ((status & 2) == 0 && n < 200)
      utest.assert(n < 200)

      val addr   = lsBase + index * 4
      val stored = dram.word(addr)
      val loaded = mmioRead(d, dram, gpr + 3 * 4) & BigInt(0xffffffffL)
      println(f"  value 0x${v.toLong}%08x: DRAM[0x$addr%x] = 0x$stored%08x, r3 = 0x${loaded.toLong}%08x")
      utest.assert(BigInt(stored) == v)
      utest.assert(loaded == v)
    }
  }

  val tests = Tests {
    utest.test("fp32_store_load_direct") {
      simulate(new BorgTestWrapper(cfg)) { d => storeLoadScenario(d) }
    }
    utest.test("fp32_store_load_over_link") {
      simulate(new BorgLinkTestWrapper(cfg, LinkParams())) { d => storeLoadScenario(d) }
    }
  }
}
