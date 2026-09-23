// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object BorgCoreTestHelpers {

  val config = BorgConfig.Default

  def floatToFp16Bits(f: Float): BigInt = {
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits >>> 31) << 15
    var exp = ((bits >>> 23) & 0xff) - 127 + 15
    var sig = (bits >>> 13) & 0x3ff
    if (exp <= 0) { exp = 0; sig = 0 }
    else if (exp >= 31) { exp = 31; sig = 0x3ff }
    BigInt(sign | (exp << 10) | sig)
  }

  def fp16BitsToFloat(b: BigInt): Float = {
    val bits = b.toInt
    val sign = (bits >>> 15) << 31
    var exp = ((bits >>> 10) & 0x1f)
    var sig = (bits & 0x3ff) << 13
    if (exp == 0) { /* subnormal or zero */ }
    else if (exp == 31) { exp = 255 }
    else { exp = exp - 15 + 127 }
    java.lang.Float.intBitsToFloat(sign | (exp << 23) | sig)
  }

  /** Signed integer -> its two's-complement pattern at the datapath width.
    *
    * The integer ALU is `config.totalBits` wide, so "-5" is 0xFFFB at FP16
    * and 0xFFFFFFFB at FP32 -- a literal 0xFFFB in an FP32 build is simply
    * +65531, which is why i2f_int16 read back 65531.0 instead of -5.0 when
    * the default moved.
    */
  def intBits(v: Int): BigInt = BigInt(v) & ((BigInt(1) << config.totalBits) - 1)

  /** Mask for reading a raw integer result back at the datapath width. */
  val intMask: BigInt = (BigInt(1) << config.totalBits) - 1

  /** Float -> the datapath's own bit pattern, `config.totalBits` wide.
    *
    * BorgCore's register file, pipeWrite and regReadData are all
    * cfg.totalBits wide, so at FP32 a 16-bit FP16 pattern is simply a
    * different (tiny, denormal) number -- not a narrower version of the same
    * one. These tests fed FP16 patterns and compared decoded FP16, which is
    * why ~24 of them failed the moment BorgConfig.Default became FP32.
    *
    * The FP16 primitives above are kept and still used directly by
    * FrcpPrecisionTests, whose subject really is the FP16 rcpLut.
    */
  def floatToBits(f: Float): BigInt = config.fp match {
    case FloatConfig.FP32 => BigInt(java.lang.Float.floatToRawIntBits(f) & 0xffffffffL)
    case _                => floatToFp16Bits(f)
  }

  /** The inverse, masking to the datapath width itself. */
  def bitsToFloat(b: BigInt): Float = config.fp match {
    case FloatConfig.FP32 => java.lang.Float.intBitsToFloat((b & BigInt(0xffffffffL)).toInt)
    case _                => fp16BitsToFloat(b & BigInt(0xffff))
  }

  /** Perform a write to BorgCore (simulating the edge-detected is_writing pulse). */
  def writeCore(core: BorgCore, addr: Int, data: BigInt): Unit = {
    core.io.bus.address.poke(addr.U)
    core.io.bus.data_in.poke(data.U)
    core.io.bus.is_writing.poke(true.B)
    core.io.bus.is_reading.poke(false.B)
    core.clock.step(1)
    core.io.bus.is_writing.poke(false.B)
    core.clock.step(1)
  }

  /** Read a register from BorgCore via the regReadData output. */
  def readReg(core: BorgCore, regIdx: Int): BigInt = {
    val addr = 0 + regIdx * 4
    core.io.bus.address.poke(addr.U)
    core.io.bus.is_reading.poke(true.B)
    core.io.bus.is_writing.poke(false.B)
    core.clock.step(1)
    val result = core.io.regReadData.peek().litValue
    core.io.bus.is_reading.poke(false.B)
    result
  }

  /** Write a register via MMIO. */
  def writeReg(core: BorgCore, regIdx: Int, bits: BigInt): Unit =
    writeCore(core, 0 + regIdx * 4, bits)

  /** Write an instruction to IMEM. */
  def writeImem(core: BorgCore, slot: Int, instr: BigInt): Unit =
    writeCore(core, 128 + slot * 4, instr)

  def resetCore(core: BorgCore): Unit = {
    core.io.control.reset.poke(true.B)
    core.clock.step(1)
    core.io.control.reset.poke(false.B)
    core.clock.step(1)
  }

  /** Start execution and poll status until idle. */
  def startAndWait(core: BorgCore): Unit = {
    core.io.control.start.poke(true.B)
    core.clock.step(1)
    core.io.control.start.poke(false.B)
    // Poll running output
    var idle = false
    var watchdog = 0
    while (!idle && watchdog < 200) {
      core.clock.step(1)
      val running = core.io.status.running.peek().litToBoolean
      idle = !running
      watchdog += 1
    }
    utest.assert(idle)
  }

  def idleInputs(core: BorgCore): Unit = {
    // Ensure module is properly reset (EphemeralSimulator may not auto-reset)
    core.reset.poke(true.B)
    core.clock.step(1)
    core.reset.poke(false.B)

    core.io.bus.address.poke(0.U)
    core.io.bus.data_in.poke(0.U)
    core.io.bus.is_writing.poke(false.B)
    core.io.bus.is_reading.poke(false.B)
    for (i <- 0 until core.cfg.fragLanes) {
      core.io.iter(i).x.poke(0.U)
      core.io.iter(i).y.poke(0.U)
    }
    core.io.coreTrigger.valid.poke(false.B)
    core.io.coreTrigger.pc.poke(0.U)
    core.io.uniformPage.poke(0.U)
    core.io.control.uniformWritePage.poke(0.U)
    core.io.control.start.poke(false.B)
    core.io.control.reset.poke(false.B)
    core.io.control.startPC.poke(0.U)
    // Step 34.4: FTEX texture response inputs — must be driven to avoid X propagation
    core.io.texDone.poke(false.B)
    core.io.texR.poke(0.U)
    core.io.texG.poke(0.U)
    core.io.texB.poke(0.U)
    core.io.texA.poke(0.U)
    core.io.seqBusy.poke(false.B)
    // LOAD/STORE DRAM port -- driven idle so nothing X-propagates for the
    // tests that never execute a memory instruction.
    core.io.gpuMem.get.data.poke(0.U)
    core.io.gpuMem.get.ready.poke(false.B)
    core.io.compute.foreach { c =>
      c.mode.poke(false.B)
      c.laneMask.poke(0.U)
      c.r30.foreach(_.poke(0.U))
      c.r31.foreach(_.poke(0.U))
    }
    core.io.gpuMem.get.waccept.poke(false.B)
    core.io.lsBase.get.poke(0.U)
    core.clock.step(1)
  }

  /** Run the shader with a model DRAM attached to the core's gpuMem port.
    *
    * Acks every request in the cycle it is made, which is the fastest a
    * controller could possibly be -- deliberately, so the test exercises the
    * FSM's same-cycle-ready path. `mem` is keyed by byte address and is
    * read/written in place, so a test can seed it, run, and inspect it.
    */
  def startAndWaitWithMem(core: BorgCore,
                          mem: scala.collection.mutable.Map[BigInt, BigInt]): Unit = {
    core.io.control.start.poke(true.B)
    core.clock.step(1)
    core.io.control.start.poke(false.B)
    var idle = false
    var watchdog = 0
    while (!idle && watchdog < 500) {
      val rd = core.io.gpuMem.get.req.peek().litToBoolean
      val wr = core.io.gpuMem.get.wr.peek().litToBoolean
      if (rd || wr) {
        val addr = core.io.gpuMem.get.addr.peek().litValue
        if (wr) mem(addr) = core.io.gpuMem.get.wdata.peek().litValue
        core.io.gpuMem.get.data.poke((mem.getOrElse(addr, BigInt(0)) & BigInt("ffffffff", 16)).U)
        core.io.gpuMem.get.ready.poke(true.B)
      } else {
        core.io.gpuMem.get.ready.poke(false.B)
      }
      core.clock.step(1)
      idle = !core.io.status.running.peek().litToBoolean
      watchdog += 1
    }
    core.io.gpuMem.get.ready.poke(false.B)
    utest.assert(idle)
  }

  // Linear→sRGB encode (used by the fsrgb test to compute expected values).
  def linearToSrgb(x: Float): Float = {
    val c = math.max(0.0, math.min(1.0, x.toDouble))
    (if (c <= 0.0031308) c * 12.92 else 1.055 * math.pow(c, 1.0 / 2.4) - 0.055).toFloat
  }


    // FP32 datapath plan item 3: the shift amount was hardcoded to 4 bits

    // (shamt = recB_raw(3,0), correct only for a 16-bit width) and is now

    // log2Ceil(w) -- 5 bits at w=32. Shift by 20 is the discriminating value:

    // a still-4-bit shamt truncates 20 to 20 mod 16 = 4, so this fails

    // loudly (1048576 vs the broken 16) if the width fix ever regresses.

    // --- Uniform Buffer Tests (Step 10.6.4.1) ---

    /** Write a uniform entry via MMIO. */

    def writeUniform(core: BorgCore, idx: Int, bits: BigInt): Unit =
      writeCore(core, 432 + idx * 4, bits)

    // --- FRCP via BorgCore (with rcpLut BRAM initialization) ---

    // End-to-end validation of the borgc-compiled cube.c vertex shader: load the

    // EXACT 25-word program borgc emits (BORGC_DUMP_ISA) and confirm it computes a

    // column-major MVP·position + perspective divide, landing screen coords in

    // r0/r1/r2. This catches data-flow bugs that structural checks miss.

    // =========================================================================

    // LOAD / STORE -- the first instructions that touch an address the shader

    // computes itself. Everything else reaches memory only through a

    // fixed-function path (FTEX, the uniform bank, the tile-buffer ABI).

    // =========================================================================

    val LS_BASE = 0x1000

    // =========================================================================

    // Control flow -- until BRZ/BRNZ every shader was straight-line, the

    // program counter only ever advancing by one.

    // =========================================================================

    // =========================================================================

    // Execution mask -- divergent control flow for the 2x2 quad.

    //

    // BRZ/BRNZ redirect one shared program counter and so can only express

    // control flow whose condition is uniform. EXPUSH/EXELSE/EXPOP express the

    // other case by predication: both arms run, masked lanes write nothing.

    //

    // These need genuinely different per-lane values, which the MMIO register

    // path cannot produce (the bus is broadcast to every lane). They come from

    // the only real source of per-lane difference: the pixel coordinate

    // pseudo-register r30, fanned out per lane by the iterator -- which is

    // where divergence comes from in a real shader too.

    // =========================================================================

    val SIMT = BorgConfig.Simt

    /** Give each lane of the quad its own pixel coordinate. */

    def pokeQuad(core: BorgCore): Unit =
      for ((x, y, i) <- Seq((0, 0, 0), (1, 0, 1), (0, 1, 2), (1, 1, 3))) {
        core.io.iter(i).x.poke(x.U)
        core.io.iter(i).y.poke(y.U)
      }

    /** Read one lane's register through its own pipeWrite snoop is not
      * possible after the fact, so tests below check lane 0 via regReadData
      * and infer the others from the masked/unmasked contrast. */

}
