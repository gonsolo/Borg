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

/** Bare harness — MemoryController + SdramBackendSim, with cpuData exposed.
  * Bypasses Hutt so we can drive writes/reads directly and verify the
  * controller path in isolation.
  */
class CpuDataHarnessIO extends Bundle {
  val cpuData = Flipped(new HuttBus(25))
  val memBusy = Output(Bool())
  val beState = Output(UInt(4.W))
}

class CpuDataHarness extends Module {
  val io = IO(new CpuDataHarnessIO)

  val mem   = Module(new MemoryController)
  val sdram = Module(new SdramBackendSim(words = 16384, rdDelay = 4, wrDelay = 2))

  sdram.io.backend <> mem.io.backend
  mem.io.cpuData <> io.cpuData

  // Tie off other ports
  mem.io.instr.req.valid := false.B
  mem.io.instr.req.bits  := 0.U
  mem.io.instr.resp.ready := true.B
  mem.io.gpuMem.req   := false.B
  mem.io.gpuMem.wr    := false.B
  mem.io.gpuMem.addr  := 0.U
  mem.io.gpuMem.wdata := 0.U
  mem.io.gpuMem.wlen  := 1.U

  io.memBusy := sdram.io.backend.busy
  io.beState := sdram.io.debug_be_state
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
  mem.io.gpuMem.wlen  := 1.U

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

    test("CPU word write to SDRAM then read back") {
      // Bypass Hutt — drive mem.io.cpuData directly. Issue a SW (size=2),
      // wait for resp, then issue a LW and verify the data.
      simulate(new CpuDataHarness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        val addr = 0x100
        val data = BigInt("12345678", 16)

        // --- Issue write ---
        dut.io.cpuData.req.valid.poke(true.B)
        dut.io.cpuData.req.bits.write.poke(true.B)
        dut.io.cpuData.req.bits.size.poke(2.U)
        dut.io.cpuData.req.bits.addr.poke(addr.U)
        dut.io.cpuData.req.bits.data.poke(data.U)
        dut.io.cpuData.resp.ready.poke(true.B)

        var writeAcked = false
        var writeRespFired = false
        // Track when req.fire happened so we deassert valid AFTER the step
        // that latched it (not before — that would clear it at the same edge).
        var deassertNext = false
        for (i <- 0 until 200 if !writeRespFired) {
          val reqReady = dut.io.cpuData.req.ready.peek().litToBoolean
          val reqValid = dut.io.cpuData.req.valid.peek().litToBoolean
          val respValid = dut.io.cpuData.resp.valid.peek().litToBoolean
          val busy = dut.io.memBusy.peek().litToBoolean
          val be = dut.io.beState.peek().litValue
          if (i < 15) println(s"  cy$i: reqRdy=$reqReady reqVld=$reqValid respVld=$respValid busy=$busy be=$be")
          val reqFire = reqReady && reqValid
          if (reqFire && !writeAcked) {
            writeAcked = true
            deassertNext = true
            println(s"  write req.fire at cycle $i")
          }

          val respFire = respValid &&
                         dut.io.cpuData.resp.ready.peek().litToBoolean
          if (respFire) {
            writeRespFired = true
            println(s"  write resp fired at cycle $i")
          }
          dut.clock.step(1)
          // Deassert AFTER the step that latched the req.fire
          if (deassertNext) {
            dut.io.cpuData.req.valid.poke(false.B)
            deassertNext = false
          }
        }
        Predef.assert(writeAcked, "CPU write req never fired (req.ready never asserted)")
        Predef.assert(writeRespFired, "CPU write resp never fired (write hung)")

        // Allow the SDRAM write to fully complete behind the early ack.
        dut.clock.step(20)

        // --- Issue read of the same address ---
        dut.io.cpuData.req.valid.poke(true.B)
        dut.io.cpuData.req.bits.write.poke(false.B)
        dut.io.cpuData.req.bits.size.poke(2.U)
        dut.io.cpuData.req.bits.addr.poke(addr.U)
        dut.io.cpuData.req.bits.data.poke(0.U)

        var readAcked = false
        var readData: BigInt = -1
        var deassertNextR = false
        for (i <- 0 until 200 if readData < 0) {
          val reqReady = dut.io.cpuData.req.ready.peek().litToBoolean
          val reqValid = dut.io.cpuData.req.valid.peek().litToBoolean
          val respValid = dut.io.cpuData.resp.valid.peek().litToBoolean
          if (i < 20) println(s"  R cy$i: reqRdy=$reqReady reqVld=$reqValid respVld=$respValid")
          val reqFire = reqReady && reqValid
          if (reqFire && !readAcked) {
            readAcked = true
            deassertNextR = true
            println(s"  read req.fire at cycle $i")
          }

          val respFire = respValid &&
                         dut.io.cpuData.resp.ready.peek().litToBoolean
          if (respFire) {
            readData = dut.io.cpuData.resp.bits.peek().litValue
            println(s"  read resp at cycle $i: 0x${readData.toString(16)}")
          }
          dut.clock.step(1)
          if (deassertNextR) {
            dut.io.cpuData.req.valid.poke(false.B)
            deassertNextR = false
          }
        }
        Predef.assert(readAcked, "CPU read req never fired")
        Predef.assert(readData >= 0, "CPU read resp never fired")
        Predef.assert(readData == data,
          s"Read mismatch: got 0x${readData.toString(16)}, expected 0x${data.toString(16)}")
      }
    }

    test("CPU byte write does not clobber neighbour byte (RMW)") {
      // Regression for the ULX3S garbled-render bug: dqm-less backends (the real
      // SdramController, QSPI DRAM, and SdramBackendSim) write the full 16-bit
      // lane regardless of byteEnIn.  The MemoryController must therefore turn
      // each byte write into a read-modify-write so a `sb` only changes its byte.
      // Before the fix, a byte store clobbered the adjacent byte (replicated the
      // value into both lanes), corrupting firmware data → 0 draw calls.
      simulate(new CpuDataHarness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.cpuData.resp.ready.poke(true.B)

        def doWrite(addr: Int, size: Int, data: BigInt): Unit = {
          dut.io.cpuData.req.valid.poke(true.B)
          dut.io.cpuData.req.bits.write.poke(true.B)
          dut.io.cpuData.req.bits.size.poke(size.U)
          dut.io.cpuData.req.bits.addr.poke(addr.U)
          dut.io.cpuData.req.bits.data.poke(data.U)
          var fired, done, deassert = false
          var i = 0
          while (!done && i < 300) {
            val reqFire = dut.io.cpuData.req.ready.peek().litToBoolean &&
                          dut.io.cpuData.req.valid.peek().litToBoolean
            if (reqFire && !fired) { fired = true; deassert = true }
            if (dut.io.cpuData.resp.valid.peek().litToBoolean) done = true
            dut.clock.step(1)
            if (deassert) { dut.io.cpuData.req.valid.poke(false.B); deassert = false }
            i += 1
          }
          Predef.assert(done, s"write to 0x${addr.toHexString} (size $size) never completed")
          dut.clock.step(20) // let the SDRAM write settle behind the early ack
        }

        def doReadWord(addr: Int): BigInt = {
          dut.io.cpuData.req.valid.poke(true.B)
          dut.io.cpuData.req.bits.write.poke(false.B)
          dut.io.cpuData.req.bits.size.poke(2.U)
          dut.io.cpuData.req.bits.addr.poke(addr.U)
          dut.io.cpuData.req.bits.data.poke(0.U)
          var data: BigInt = -1
          var deassert = false
          var i = 0
          while (data < 0 && i < 300) {
            val reqFire = dut.io.cpuData.req.ready.peek().litToBoolean &&
                          dut.io.cpuData.req.valid.peek().litToBoolean
            if (reqFire) deassert = true
            if (dut.io.cpuData.resp.valid.peek().litToBoolean)
              data = dut.io.cpuData.resp.bits.peek().litValue
            dut.clock.step(1)
            if (deassert) { dut.io.cpuData.req.valid.poke(false.B); deassert = false }
            i += 1
          }
          Predef.assert(data >= 0, s"read of 0x${addr.toHexString} never completed")
          data
        }

        // Seed a known word: bytes [0x100..0x103] = FF AB? no — EE FF DD CC →
        // little-endian word 0xCCDDEEFF.
        doWrite(0x100, 2, BigInt("CCDDEEFF", 16))

        // Overwrite the odd-lane byte at 0x101 (was 0xEE) with 0xAB.
        doWrite(0x101, 0, BigInt("AB", 16))
        val w1 = doReadWord(0x100)
        Predef.assert(w1 == BigInt("CCDDABFF", 16),
          s"odd-lane byte write clobbered a neighbour: got 0x${w1.toString(16)}, expected 0xccddabff")

        // Overwrite the even-lane byte at 0x102 (was 0xDD) with 0x12.
        doWrite(0x102, 0, BigInt("12", 16))
        val w2 = doReadWord(0x100)
        Predef.assert(w2 == BigInt("CC12ABFF", 16),
          s"even-lane byte write clobbered a neighbour: got 0x${w2.toString(16)}, expected 0xcc12abff")
      }
    }
  }
}
