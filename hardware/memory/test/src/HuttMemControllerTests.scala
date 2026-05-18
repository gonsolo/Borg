// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// Integration test: Hutt + MemoryController + SdramBackendSim.
//
// Goal: reproduce the ULX3S bring-up symptom where a minimal infinite-loop
// firmware (`jal x0, 0`) executes only briefly and then Hutt stops fetching.
// The Hutt internal tests pass, the MemoryController CPU adapter compiles,
// and the bitstream synthesises clean — so the bug must be in their
// interaction (most likely the instruction-fetch adapter's restart handshake
// with the existing streaming FSM).

package memory

import chisel3.{assert => _, test => _, _}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import hutt.{Hutt, HuttBus, HuttInstrBus}
import borg.GpuMemIO
import utest._

class HuttMemHarnessIO extends Bundle {
  val gpuReady = Output(Bool())
  val memBusy  = Output(Bool())
  val cpuFetchActive = Output(Bool())  // Hutt is currently demanding a fetch
  val cpuFetchFire   = Output(Bool())  // req.fire pulse
  val cpuFetchAddr   = Output(UInt(23.W))
  val cpuRespValid   = Output(Bool())
  val cpuRespFire    = Output(Bool())
  val cpuDataFire    = Output(Bool())  // data port req.fire
}

class HuttMemHarness extends Module {
  val io = IO(new HuttMemHarnessIO)

  val cpu   = Module(new Hutt())
  val mem   = Module(new MemoryController)
  val sdram = Module(new SdramBackendSim(words = 16384, rdDelay = 4, wrDelay = 2))

  sdram.io.backend <> mem.io.backend
  mem.io.instr <> cpu.io.instr
  mem.io.cpuData <> cpu.io.data
  cpu.io.interrupt := false.B

  // GPU port idle (no GPU in this test)
  mem.io.gpuMem.req   := false.B
  mem.io.gpuMem.wr    := false.B
  mem.io.gpuMem.addr  := 0.U
  mem.io.gpuMem.wdata := 0.U

  io.gpuReady := mem.io.gpuMem.ready
  io.memBusy  := sdram.io.backend.busy
  io.cpuFetchActive := cpu.io.instr.req.valid
  io.cpuFetchFire   := cpu.io.instr.req.fire
  io.cpuFetchAddr   := cpu.io.instr.req.bits
  io.cpuRespValid   := cpu.io.instr.resp.valid
  io.cpuRespFire    := cpu.io.instr.resp.fire
  io.cpuDataFire    := cpu.io.data.req.fire
}

object HuttMemControllerTests extends TestSuite {

  val tests = Tests {

    test("Hutt issues multiple sequential instruction fetches") {
      // We don't preload the SDRAM; SdramBackendSim returns garbage that
      // Hutt will try to execute, but the important thing is to observe
      // that the CPU keeps issuing fetch *requests*.
      simulate(new HuttMemHarness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        // SDRAM init can take many cycles; just step a lot and count how
        // often Hutt asserts req.valid on the instruction port.
        var fetchActive = 0
        var fetchFires  = 0
        var respValid   = 0
        var respFires   = 0
        var memBusyCycles = 0
        var dataFires   = 0
        val maxCycles = 20000
        var firstFireCycle = -1
        var lastFireCycle  = -1
        var lastFireAddr: BigInt = -1
        for (i <- 0 until maxCycles) {
          if (dut.io.cpuFetchActive.peek().litToBoolean) fetchActive += 1
          if (dut.io.cpuFetchFire.peek().litToBoolean) {
            fetchFires += 1
            if (firstFireCycle < 0) firstFireCycle = i
            lastFireCycle = i
            lastFireAddr  = dut.io.cpuFetchAddr.peek().litValue
          }
          if (dut.io.cpuRespValid.peek().litToBoolean) respValid += 1
          if (dut.io.cpuRespFire.peek().litToBoolean)  respFires += 1
          if (dut.io.memBusy.peek().litToBoolean)      memBusyCycles += 1
          if (dut.io.cpuDataFire.peek().litToBoolean)  dataFires += 1
          dut.clock.step(1)
        }

        println(s"  fetch active : $fetchActive / $maxCycles cycles")
        println(s"  fetch fires  : $fetchFires (first at cycle $firstFireCycle, last at $lastFireCycle, last addr=$lastFireAddr)")
        println(s"  resp valid   : $respValid cycles")
        println(s"  resp fires   : $respFires")
        println(s"  data fires   : $dataFires (CPU store/load completions)")
        println(s"  memBusy      : $memBusyCycles / $maxCycles cycles")

        // We want Hutt to keep trying to fetch.  If after init it stops
        // forever, fetchCycles will be low.
        // 37 fires in 755 cycles is what we see with un-preloaded (garbage)
        // SDRAM — Hutt executes random instructions until it takes a bad
        // branch.  The fix this test was written to verify is the
        // instruction-fetch adapter's sAbort state: before the fix Hutt
        // completed exactly 1 fetch then hung forever.
        Predef.assert(fetchFires > 10,
          s"Hutt only completed $fetchFires fetch fires; expected the adapter to allow multiple sequential fetches")
      }
    }
  }
}
