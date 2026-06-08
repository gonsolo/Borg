// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

class CacheTestHarnessIO extends Bundle {
  val fetch = Input(Bool())
  val addr  = Input(UInt(23.W))
  val data  = Output(UInt(32.W))
  val valid = Output(Bool())
}

// InstrCache wired to a 1-cycle-latency backing memory.
// Backing memory returns data = zero-extended addr(14,0).
class CacheTestHarness extends Module {
  val io = IO(new CacheTestHarnessIO)

  val cache = Module(new InstrCache(23))

  cache.io.cpu.req.valid  := io.fetch
  cache.io.cpu.req.bits   := io.addr
  cache.io.cpu.resp.ready := true.B
  io.data  := cache.io.cpu.resp.bits
  io.valid := cache.io.cpu.resp.valid

  // 1-cycle-latency backing memory (latches req, responds next cycle).
  val memAddrReg   = RegInit(0.U(23.W))
  val memRespValid = RegInit(false.B)

  cache.io.mem.req.ready  := !memRespValid
  cache.io.mem.resp.valid := memRespValid
  cache.io.mem.resp.bits  := Cat(0.U(9.W), memAddrReg)  // data = addr for correctness check

  when(cache.io.mem.req.fire) {
    memAddrReg   := cache.io.mem.req.bits
    memRespValid := true.B
  }.elsewhen(cache.io.mem.resp.fire) {
    memRespValid := false.B
  }
}

object InstrCacheTests extends TestSuite {
  // InstrCache starts in sFlush and invalidates all 512 entries before
  // accepting requests.  EphemeralSimulator does not assert reset on its own,
  // so each test must drive reset explicitly (RegInit only takes effect while
  // reset is asserted) and then wait for the flush to finish before fetching.
  val FLUSH_WAIT = 514  // 512 flush cycles + 2 setup cycles

  val tests = Tests {

    // Assert reset (so RegInit puts the FSM into sFlush) then let the cache
    // run through its 512-cycle invalidation before any fetch is issued.
    def resetAndFlush(dut: CacheTestHarness): Unit = {
      dut.io.fetch.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(FLUSH_WAIT)
    }

    // Issue one fetch and count clock steps until valid is asserted.
    // Returns (steps, data).  Leaves the cache in sIdle (consumes resp).
    // Call only after resetAndFlush has run (cache in sIdle).
    def fetchCycles(dut: CacheTestHarness, addr: Int): (Int, Int) = {
      dut.io.fetch.poke(true.B)
      dut.io.addr.poke(addr.U)
      var count = 0
      var done  = false
      while (!done) {
        dut.clock.step(1)
        count += 1
        if (dut.io.valid.peek().litToBoolean) done = true
        else dut.io.fetch.poke(false.B)  // drop fetch after the first step
        if (count > 2000)
          throw new RuntimeException(s"fetchCycles never saw valid after $count steps")
      }
      dut.io.fetch.poke(false.B)
      val data = dut.io.data.peek().litValue.toInt
      dut.clock.step(1)   // let resp.fire → sIdle
      (count, data)
    }

    test("cold miss is slower than hit") {
      simulate(new CacheTestHarness) { dut =>
        resetAndFlush(dut)

        val (miss, _) = fetchCycles(dut, 0x42)
        val (hit, _)  = fetchCycles(dut, 0x42)

        assert(miss > hit)   // miss must take more steps than hit
        assert(hit == 1)     // hit = 1 step: req(sIdle) → resp(sCheck), BRAM latency absorbed
      }
    }

    test("different indices don't evict each other") {
      simulate(new CacheTestHarness) { dut =>
        resetAndFlush(dut)

        fetchCycles(dut, 0x00)
        fetchCycles(dut, 0x01)
        val (h0, _) = fetchCycles(dut, 0x00)
        val (h1, _) = fetchCycles(dut, 0x01)
        assert(h0 == 1)
        assert(h1 == 1)
      }
    }

    test("same index different tag causes eviction") {
      simulate(new CacheTestHarness) { dut =>
        resetAndFlush(dut)

        val addr0 = 0          // tag=0, index=0
        val addr1 = 1 << 9     // tag=1, index=0 (same index, different tag)

        fetchCycles(dut, addr0)           // cold miss, fills index 0 with tag=0
        fetchCycles(dut, addr1)           // miss, overwrites index 0 with tag=1
        val (evicted, _) = fetchCycles(dut, addr0)  // must miss (tag=0 was evicted)
        assert(evicted > 1)
      }
    }

    test("data is correct on hit") {
      simulate(new CacheTestHarness) { dut =>
        resetAndFlush(dut)

        val addr = 0x1F
        fetchCycles(dut, addr)                // cold miss, fill cache
        val (_, data) = fetchCycles(dut, addr) // hit
        assert(data == (addr & 0x7FFF))        // backing memory returns addr(14,0)
      }
    }
  }
}
