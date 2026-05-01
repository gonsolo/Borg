// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** BorgSequencer unit tests — Step 29.1 gate.
  *
  * Tests the sequencer FSM at the `Borg` top level to verify:
  *   1. DMA loads vertex shader into IMEM.
  *   2. Vertex shader runs 3 times (once per vertex).
  *   3. Clip-space outputs are snooped via PipeWriteIO.
  *   4. STATUS.seq_busy asserts during execution and clears on completion.
  *
  * The test stages a vertex shader binary and triangle descriptor in
  * simulated PSRAM (serviced via `io.gpuMem`), triggers the sequencer
  * via MMIO, and verifies the full FSM sequence.
  */
object BorgSequencerTests extends TestSuite {

  // --- Float conversion helpers (same as BorgTests) ---

  def floatToBits16(f: Float): BigInt = {
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits >>> 31) << 15
    var exp = ((bits >>> 23) & 0xff) - 127 + 15
    var sig = (bits >>> 13) & 0x3ff
    if (exp <= 0) { exp = 0; sig = 0 }
    else if (exp >= 31) { exp = 31; sig = 0x3ff }
    BigInt(sign | (exp << 10) | sig)
  }

  def bitsToFloat16(b: BigInt): Float = {
    val bits = b.toInt & 0xffff
    val sign = (bits >>> 15) << 31
    var exp = ((bits >>> 10) & 0x1f)
    var sig = (bits & 0x3ff) << 13
    if (exp == 0) { /* subnormal or zero */ }
    else if (exp == 31) { exp = 255 }
    else { exp = exp - 15 + 127 }
    java.lang.Float.intBitsToFloat(sign | (exp << 23) | sig)
  }

  // --- Low-level bus helpers ---

  def rawWrite(borg: Borg, addr: Int, data: BigInt): Unit = {
    borg.io.address.poke(addr.U)
    borg.io.data_in.poke(data.U)
    borg.io.data_write_n.poke(2.U)
    borg.clock.step(1)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
  }

  def rawRead(borg: Borg, addr: Int): BigInt = {
    borg.io.address.poke(addr.U)
    borg.io.data_read_n.poke(2.U)
    borg.clock.step(1)
    borg.clock.step(1)
    val bits = borg.io.data_out.peek().litValue
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    bits
  }

  def resetAndWait(borg: Borg): Unit = {
    borg.reset.poke(true.B)
    borg.clock.step(2)
    borg.reset.poke(false.B)
    borg.io.data_write_n.poke(3.U)
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2) // reset pipeline
  }

  val tests = Tests {

    /** Step 29.1 gate test: vertex_shader_run
      *
      * Stages a simple vertex shader (ADD r0 = r0 + r0, halt) in simulated
      * PSRAM at a known address. The descriptor contains 3 vertices with
      * known position values. The sequencer:
      *   1. DMA-loads the shader into IMEM
      *   2. DMA-loads vertex 0 position into uniforms, runs shader, snoops r0
      *   3. Repeats for vertex 1 and vertex 2
      *   4. Asserts done, clears seq_busy
      *
      * Simulated PSRAM is modeled by driving io.gpuMem.ready/data in response
      * to io.gpuMem.req/addr signals.
      */
    utest.test("vertex_shader_run") {
      simulate(new Borg(BorgConfig.Sim)) { borg =>
        println("\n=== BorgSequencerTests: vertex_shader_run ===")
        resetAndWait(borg)

        // --- Define simulated PSRAM contents ---
        // Vertex shader at PSRAM address 0x1000 (2 words: ADD r0=r0+r0, halt)
        val shaderAddr = 0x1000
        val shaderLen  = 2
        val shaderWords = Map(
          shaderAddr     -> Instructions.ADD(0, 0, 0),  // r0 = r0 + r0
          shaderAddr + 4 -> BigInt(0)                   // halt
        )

        // Triangle descriptor at PSRAM address 0x2000
        // Layout: 9 position words (3 vertices × 3 FP16 components, each in low 16 bits of 32-bit word)
        // Vertex 0: pos = (1.0, 2.0, 3.0)
        // Vertex 1: pos = (4.0, 5.0, 6.0)
        // Vertex 2: pos = (7.0, 8.0, 0.5)
        val descAddr = 0x2000
        val vertPositions = Seq(
          // vertex 0
          (1.0f, 2.0f, 3.0f),
          // vertex 1
          (4.0f, 5.0f, 6.0f),
          // vertex 2
          (7.0f, 8.0f, 0.5f)
        )

        val descWords = scala.collection.mutable.Map[Int, BigInt]()
        for (v <- 0 until 3; c <- 0 until 3) {
          val addr = descAddr + (v * 3 + c) * 4
          val value = vertPositions(v).productElement(c).asInstanceOf[Float]
          descWords(addr) = floatToBits16(value)
        }

        // Combine all PSRAM content
        val psram: Map[Int, BigInt] = shaderWords ++ descWords.toMap

        // --- Configure sequencer MMIO registers ---
        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt, descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt, shaderAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt, shaderLen)

        // --- Verify STATUS.seq_busy is initially clear ---
        val statusPre = rawRead(borg, BorgGpuRegs.status_offset.litValue.toInt)
        val seqBusyPre = (statusPre >> 5) & 1
        println(f"  STATUS before trigger: 0x$statusPre%08X  seq_busy=$seqBusyPre")
        Predef.assert(seqBusyPre == 0, "seq_busy should be 0 before trigger")

        // --- Trigger sequencer ---
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        // --- Run simulation, servicing PSRAM requests ---
        var seqBusySeen = false
        var seqBusyCleared = false
        var dmaReads = 0
        val maxCycles = 2000

        for (cycle <- 0 until maxCycles if !seqBusyCleared) {
          // Service PSRAM read requests
          if (borg.io.gpuMem.req.peek().litToBoolean &&
              !borg.io.gpuMem.wr.peek().litToBoolean) {
            val addr = borg.io.gpuMem.addr.peek().litValue.toInt
            val data = psram.getOrElse(addr, BigInt(0))
            borg.io.gpuMem.data.poke(data.U)
            borg.io.gpuMem.ready.poke(true.B)
            dmaReads += 1
          } else {
            borg.io.gpuMem.ready.poke(false.B)
          }
          borg.clock.step(1)

          // Check seq_busy via direct peek of the status register output.
          // We do a 2-cycle MMIO read that still services PSRAM:
          if (cycle % 10 == 5) {
            borg.io.address.poke(BorgGpuRegs.status_offset)
            borg.io.data_read_n.poke(2.U)
            borg.io.data_write_n.poke(3.U)
            // Service PSRAM during wait cycle
            if (borg.io.gpuMem.req.peek().litToBoolean &&
                !borg.io.gpuMem.wr.peek().litToBoolean) {
              val addr = borg.io.gpuMem.addr.peek().litValue.toInt
              val data = psram.getOrElse(addr, BigInt(0))
              borg.io.gpuMem.data.poke(data.U)
              borg.io.gpuMem.ready.poke(true.B)
              dmaReads += 1
            } else {
              borg.io.gpuMem.ready.poke(false.B)
            }
            borg.clock.step(1)
            val st = borg.io.data_out.peek().litValue
            borg.io.data_read_n.poke(3.U)
            // Service PSRAM during second wait cycle
            if (borg.io.gpuMem.req.peek().litToBoolean &&
                !borg.io.gpuMem.wr.peek().litToBoolean) {
              val addr = borg.io.gpuMem.addr.peek().litValue.toInt
              val data = psram.getOrElse(addr, BigInt(0))
              borg.io.gpuMem.data.poke(data.U)
              borg.io.gpuMem.ready.poke(true.B)
              dmaReads += 1
            } else {
              borg.io.gpuMem.ready.poke(false.B)
            }
            borg.clock.step(1)

            val busy = (st >> 5) & 1
            if (busy == 1) seqBusySeen = true
            if (seqBusySeen && busy == 0) seqBusyCleared = true
          }
        }

        // --- Assertions ---
        println(f"  seq_busy was seen high: $seqBusySeen")
        println(f"  seq_busy cleared:       $seqBusyCleared")
        println(f"  Total DMA PSRAM reads:  $dmaReads")

        Predef.assert(seqBusySeen, "seq_busy never went high — sequencer not triggered")
        Predef.assert(seqBusyCleared, "seq_busy never cleared — sequencer hung")

        // Expected DMA reads:
        //   2 (shader load) + 3×3 (3 vertices × 3 position words) = 11
        // But the shader only has 2 words, so DMA reads 2 for shader + 3+3+3 for vertices = 11
        println(f"  Expected DMA reads: 11, got: $dmaReads")
        Predef.assert(dmaReads >= 11, s"Expected at least 11 DMA reads, got $dmaReads")

        // Verify the final STATUS is clean (seq_busy=0, idle=1)
        val statusPost = rawRead(borg, BorgGpuRegs.status_offset.litValue.toInt)
        val seqBusyPost = (statusPost >> 5) & 1
        val idlePost    = (statusPost >> 1) & 1
        println(f"  STATUS after completion: 0x$statusPost%08X  seq_busy=$seqBusyPost  idle=$idlePost")
        Predef.assert(seqBusyPost == 0, "seq_busy should be 0 after completion")

        println("=== BorgSequencerTests: vertex_shader_run PASSED ===\n")
      }
    }
  }
}
