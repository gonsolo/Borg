// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** BorgSequencer unit tests — Steps 29.1/29.2/29.3. */
object BorgSequencerTests extends TestSuite {

  // --- Float conversion helpers ---

  def floatToBits16(f: Float): BigInt = {
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits >>> 31) << 15
    var exp  = ((bits >>> 23) & 0xff) - 127 + 15
    var sig  = (bits >>> 13) & 0x3ff
    if (exp <= 0) { exp = 0; sig = 0 }
    else if (exp >= 31) { exp = 31; sig = 0x3ff }
    BigInt(sign | (exp << 10) | sig)
  }

  def bitsToFloat16(b: BigInt): Float = {
    val bits = b.toInt & 0xffff
    val sign = (bits >>> 15) << 31
    var exp  = (bits >>> 10) & 0x1f
    var sig  = (bits & 0x3ff) << 13
    if (exp == 0) { /* zero/subnormal */ }
    else if (exp == 31) { exp = 255 }
    else { exp = exp - 15 + 127 }
    java.lang.Float.intBitsToFloat(sign | (exp << 23) | sig)
  }

  // --- Bus helpers ---

  def rawWrite(borg: BorgTestWrapper, addr: Int, data: BigInt): Unit = {
    borg.io.address.poke(addr.U)
    borg.io.data_in.poke(data.U)
    borg.io.data_write_n.poke(2.U)
    borg.clock.step(1)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
  }

  def rawRead(borg: BorgTestWrapper, addr: Int): BigInt = {
    borg.io.address.poke(addr.U)
    borg.io.data_read_n.poke(2.U)
    borg.clock.step(1)
    borg.clock.step(1)
    val bits = borg.io.data_out.peek().litValue
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    bits
  }

  def resetAndWait(borg: BorgTestWrapper): Unit = {
    borg.reset.poke(true.B)
    borg.clock.step(2)
    borg.reset.poke(false.B)
    borg.io.data_write_n.poke(3.U)
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    rawWrite(borg, BorgGpuRegs.gpr_offset.litValue.toInt + 31 * 4, 0)
    rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)
  }

  def serviceDram(borg: BorgTestWrapper, dram: Map[Int, BigInt]): Int = {
    if (borg.io.gpuMem.req.peek().litToBoolean) {
      // Read request: provide data and pulse ready.
      val addr = borg.io.gpuMem.addr.peek().litValue.toInt
      val data = dram.getOrElse(addr, BigInt(0)) & BigInt(0xFFFFFFFFL)
      borg.io.gpuMem.data.poke(data.U)
      borg.io.gpuMem.waccept.poke(false.B)
      borg.io.gpuMem.ready.poke(true.B)
      1
    } else if (borg.io.gpuMem.wr.peek().litToBoolean) {
      // Write request.  For a burst (wlen>1) consume words via waccept until
      // all are transferred, then pulse ready.  For a single write just pulse ready.
      val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
      if (wlen > 1) {
        // Consume words 1..wlen-1 via waccept (word 0 already presented).
        for (_ <- 0 until wlen - 1) {
          borg.io.gpuMem.waccept.poke(true.B)
          borg.io.gpuMem.ready.poke(false.B)
          borg.clock.step(1)
        }
        borg.io.gpuMem.waccept.poke(false.B)
      }
      borg.io.gpuMem.ready.poke(true.B)
      wlen
    } else {
      borg.io.gpuMem.waccept.poke(false.B)
      borg.io.gpuMem.ready.poke(false.B)
      0
    }
  }

  def runSequencerUntilDone(borg: BorgTestWrapper, dram: Map[Int, BigInt],
                            maxCycles: Int = 5000): (Boolean, Boolean, Int) = {
    var seqBusySeen    = false
    var seqBusyCleared = false
    var dmaReads       = 0
    var prevDmaReads   = 0
    var dmaStallCycle  = -1
    for (cycle <- 0 until maxCycles if !seqBusyCleared) {
      dmaReads += serviceDram(borg, dram)
      borg.clock.step(1)
      // Detect when DMA ops stop increasing (possible hang)
      if (dmaReads > prevDmaReads) {
        prevDmaReads = dmaReads
        dmaStallCycle = cycle
      }
      if (cycle % 10 == 5) {
        borg.io.address.poke(BorgGpuRegs.status_offset)
        borg.io.data_read_n.poke(2.U)
        borg.io.data_write_n.poke(3.U)
        dmaReads += serviceDram(borg, dram)
        borg.clock.step(1)
        val st = borg.io.data_out.peek().litValue
        borg.io.data_read_n.poke(3.U)
        dmaReads += serviceDram(borg, dram)
        borg.clock.step(1)
        val busy = (st >> 5) & 1
        if (busy == 1) seqBusySeen = true
        if (seqBusySeen && busy == 0) seqBusyCleared = true
        // Debug: print STATUS every 2000 cycles when DMA has been stuck for >1000 cycles
        if (cycle > dmaStallCycle + 1000 && cycle % 2000 == 5) {
          println(f"  [debug cycle=$cycle] status=0x${st}%08X busy=$busy dmaReads=$dmaReads lastDMAat=$dmaStallCycle")
        }
      }
    }
    (seqBusySeen, seqBusyCleared, dmaReads)
  }


  /** Vertex passthrough shader: r0=u0, r1=u1, halt. */
  def vertPassthroughShader(): Seq[BigInt] = Seq(
    Instructions.ADD(rs1 = 0, rs2 = 31, rd = 0, funct3 = 1),
    Instructions.ADD(rs1 = 1, rs2 = 31, rd = 1, funct3 = 1),
    BigInt(0)
  )

  /** Triangle setup shader.
    * Inputs: u0=v0.x, u1=v0.y, u2=v1.x, u3=v1.y, u4=v2.x, u5=v2.y
    * Outputs: r0-r5=edge components, r6=area, r7=inv_area
    */
  def setupShader(): Seq[BigInt] = Seq(
    Instructions.ADD(rs1 = 0, rs2 = 31, rd = 8,  funct3 = 1),  // r8  = v0.x
    Instructions.ADD(rs1 = 1, rs2 = 31, rd = 9,  funct3 = 1),  // r9  = v0.y
    Instructions.ADD(rs1 = 2, rs2 = 31, rd = 10, funct3 = 1),  // r10 = v1.x
    Instructions.ADD(rs1 = 3, rs2 = 31, rd = 11, funct3 = 1),  // r11 = v1.y
    Instructions.ADD(rs1 = 4, rs2 = 31, rd = 12, funct3 = 1),  // r12 = v2.x
    Instructions.ADD(rs1 = 5, rs2 = 31, rd = 13, funct3 = 1),  // r13 = v2.y
    Instructions.FNEG(rs1 = 10, rd = 14),
    Instructions.ADD(rs1 = 8,  rs2 = 14, rd = 0),              // r0 = v0.x - v1.x
    Instructions.FNEG(rs1 = 9,  rd = 15),
    Instructions.ADD(rs1 = 11, rs2 = 15, rd = 1),              // r1 = v1.y - v0.y
    Instructions.FNEG(rs1 = 12, rd = 16),
    Instructions.ADD(rs1 = 10, rs2 = 16, rd = 2),              // r2 = v1.x - v2.x
    Instructions.FNEG(rs1 = 11, rd = 17),
    Instructions.ADD(rs1 = 13, rs2 = 17, rd = 3),              // r3 = v2.y - v1.y
    Instructions.FNEG(rs1 = 8,  rd = 18),
    Instructions.ADD(rs1 = 12, rs2 = 18, rd = 4),              // r4 = v2.x - v0.x
    Instructions.FNEG(rs1 = 13, rd = 19),
    Instructions.ADD(rs1 = 9,  rs2 = 19, rd = 5),              // r5 = v0.y - v2.y
    Instructions.MUL(rs1 = 0, rs2 = 5, rd = 20),               // r20 = e0dx * e2dy
    Instructions.FNEG(rs1 = 1, rd = 21),
    Instructions.FMA(rs1 = 4, rs2 = 21, rs3 = 20, rd = 6),     // r6 = area
    Instructions.FRCP(rs1 = 6, rd = 7),                         // r7 = inv_area
    BigInt(0)
  )

  /** Build a stride-32 DRAM descriptor for 3 vertices with a zero-extent bbox.
    * The zero bbox (min=max=0) causes the sequencer's tile loop to exit
    * immediately (0 >= 0), so tests that only verify uniform staging
    * don't need to simulate 16 pixel iterations.
    */
  def buildDescriptor(baseAddr: Int, verts: Seq[Seq[Float]]): Map[Int, BigInt] = {
    val m1 = (for (v <- 0 until 3; c <- 0 until 8) yield
      (baseAddr + v * 32 + c * 4) -> floatToBits16(verts(v)(c))
    ).toMap
    // Zero-extent bbox: minX=minY=maxX=maxY=0.
    // With >= comparison in sNextTile: nextX(4) >= maxX(0) → immediately goes
    // to sNextTriangle. Tile loop runs sClearTile → sEnqueueTile → sIteratePixels
    // → sWaitRast (no advances since autoRunStall+tileCompleteLatch aren't set)
    // → sWaitFlush → sWaitFlushSync → sNextTile → sNextTriangle.
    m1 ++ Map(
      (baseAddr + 96)  -> BigInt(0),   // packed {y0<<16 | x0} = (0,0)
      (baseAddr + 100) -> BigInt(0)    // packed {y1<<16 | x1} = (0,0) — zero extent
    )
  }

  /** Build a descriptor with an explicit tile-aligned bounding box.
    * minX/Y and maxX/Y are in pixel coordinates; maxX/Y are exclusive ends.
    * Use this when the test needs to exercise the full tile iteration loop.
    */
  def buildDescriptorWithBbox(baseAddr: Int, verts: Seq[Seq[Float]],
                              minX: Int, minY: Int, maxX: Int, maxY: Int): Map[Int, BigInt] = {
    val m1 = (for (v <- 0 until 3; c <- 0 until 8) yield
      (baseAddr + v * 32 + c * 4) -> floatToBits16(verts(v)(c))
    ).toMap
    m1 ++ Map(
      (baseAddr + 96)  -> BigInt(((minY & ~3) << 16) | (minX & ~3)),
      (baseAddr + 100) -> BigInt((maxY << 16) | maxX)
    )
  }

  val tests = Tests {

    utest.test("vertex_shader_run") {
      simulate(new BorgTestWrapper(BorgConfig.Default)) { borg =>
        println("\n=== BorgSequencerTests: vertex_shader_run ===")
        resetAndWait(borg)

        val vertAddr = 0x1000; val descAddr = 0x2000
        val vertShader = vertPassthroughShader()
        val verts = Seq(
          Seq(1.0f, 2.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(4.0f, 5.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
          Seq(7.0f, 8.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
        )
        val dram = vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
                    buildDescriptor(descAddr, verts)

        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt,  descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt,  vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt,   vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, vertAddr)  // passthrough reused
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt,  vertShader.size)

        val statusPre = rawRead(borg, BorgGpuRegs.status_offset.litValue.toInt)
        Predef.assert(((statusPre >> 5) & 1) == 0, "seq_busy should be 0 before trigger")
        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, dram)
        println(f"  seq_busy seen: $seen, cleared: $cleared, DMA reads: $dmaReads")
        Predef.assert(seen,    "seq_busy never went high")
        Predef.assert(cleared, "seq_busy never cleared")
        Predef.assert(dmaReads >= 10, s"Expected >= 10 DMA reads, got $dmaReads")
        println("=== vertex_shader_run PASSED ===\n")
      }
    }

    utest.test("triangle_setup") {
      simulate(new BorgTestWrapper(BorgConfig.Default)) { borg =>
        println("\n=== BorgSequencerTests: triangle_setup ===")
        resetAndWait(borg)

        val v0x = 1.0f; val v0y = 0.0f
        val v1x = 2.0f; val v1y = 0.0f
        val v2x = 1.0f; val v2y = 5.0f

        val vertAddr = 0x1000; val setupAddr = 0x3000; val descAddr = 0x2000
        val vertShader = vertPassthroughShader()
        val setup      = setupShader()
        val verts = Seq(
          Seq(v0x, v0y, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(v1x, v1y, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
          Seq(v2x, v2y, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
        )
        val dram = vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
                    setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
                    buildDescriptor(descAddr, verts)

        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt,  descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt,  vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt,   vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt,  setup.size)
        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, dram)
        println(f"  seq_busy seen: $seen, cleared: $cleared, DMA reads: $dmaReads")
        Predef.assert(seen,    "seq_busy never went high")
        Predef.assert(cleared, "seq_busy never cleared — hung")

        val e0dx = v0x - v1x; val e0dy = v1y - v0y
        val e1dx = v1x - v2x; val e1dy = v2y - v1y
        val e2dx = v2x - v0x; val e2dy = v0y - v2y
        val area = e0dx * e2dy - e2dx * e0dy
        println(f"  Expected edges: ($e0dx%.1f,$e0dy%.1f) ($e1dx%.1f,$e1dy%.1f) ($e2dx%.1f,$e2dy%.1f) area=$area%.1f")

        val outputs = (0 until 8).map { i => bitsToFloat16(rawRead(borg, BorgGpuRegs.gpr_offset.litValue.toInt + i * 4) & 0xFFFF) }
        println(f"  r0-r5: ${outputs.take(6).map(v => f"$v%.3f").mkString(" ")}  r6=${outputs(6)}%.3f  r7=${outputs(7)}%.6f")

        val tol = 0.1f
        def assertClose(name: String, got: Float, exp: Float): Unit =
          Predef.assert(math.abs(got - exp) < math.max(tol, math.abs(exp)*0.01f),
            f"$name: got $got%.4f, expected $exp%.4f")

        assertClose("e0.dx", outputs(0), e0dx)
        assertClose("e0.dy", outputs(1), e0dy)
        assertClose("e1.dx", outputs(2), e1dx)
        assertClose("e1.dy", outputs(3), e1dy)
        assertClose("e2.dx", outputs(4), e2dx)
        assertClose("e2.dy", outputs(5), e2dy)
        assertClose("area",  outputs(6), area)
        Predef.assert(outputs(7) != 0.0f, "inv_area should be non-zero")
        println("=== triangle_setup PASSED ===\n")
      }
    }

    /** Step 29.3 gate: sequencer_uniform_staging
      * Verifies all 31 physical uniform registers after a full sequencer run.
      */
    utest.test("sequencer_uniform_staging") {
      simulate(new BorgTestWrapper(BorgConfig.Default)) { borg =>
        println("\n=== BorgSequencerTests: sequencer_uniform_staging ===")
        resetAndWait(borg)

        val v0x = 1.0f; val v0y = 0.0f; val v0z = 0.1f
        val v1x = 2.0f; val v1y = 0.0f; val v1z = 0.2f
        val v2x = 1.0f; val v2y = 5.0f; val v2z = 0.3f
        val c0r = 1.0f; val c0g = 0.0f; val c0b = 0.0f  // v0 = red
        val c1r = 0.0f; val c1g = 1.0f; val c1b = 0.0f  // v1 = green
        val c2r = 0.0f; val c2g = 0.0f; val c2b = 1.0f  // v2 = blue

        val vertAddr = 0x1000; val setupAddr = 0x3000; val descAddr = 0x2000
        val rastAddr = 0x4000; val fragAddr = 0x5000; val setupBase = 0x7000; val binBase = 0x6000
        val vertShader = vertPassthroughShader()
        val setup      = setupShader()
        val rast       = Seq(BigInt(0))  // HALT
        val frag       = Seq(BigInt(0))  // HALT
        val verts = Seq(
          Seq(v0x, v0y, v0z, c0r, c0g, c0b, 0.0f, 0.0f),
          Seq(v1x, v1y, v1z, c1r, c1g, c1b, 0.0f, 0.0f),
          Seq(v2x, v2y, v2z, c2r, c2g, c2b, 0.0f, 0.0f),
        )
        // Pre-computed edge uniforms stored by sStoreSetup at setupBase.
        // sLoadTriSetup (pass-2) reads these back — must match sStageUniforms output.
        val e0dxBits = floatToBits16(v0x - v1x); val e0dyBits = floatToBits16(v1y - v0y)
        val e1dxBits = floatToBits16(v1x - v2x); val e1dyBits = floatToBits16(v2y - v1y)
        val e2dxBits = floatToBits16(v2x - v0x); val e2dyBits = floatToBits16(v0y - v2y)
        val setupData: Map[Int, BigInt] = Map(
          (setupBase + 0*4) -> e0dxBits, (setupBase + 1*4) -> e0dyBits,
          (setupBase + 2*4) -> e1dxBits, (setupBase + 3*4) -> e1dyBits,
          (setupBase + 4*4) -> e2dxBits, (setupBase + 5*4) -> e2dyBits,
        )
        val dram = vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
                    setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
                    rast.zipWithIndex.map       { case (w,i) => (rastAddr + i*4) -> w }.toMap ++
                    frag.zipWithIndex.map       { case (w,i) => (fragAddr + i*4) -> w }.toMap ++
                    buildDescriptor(descAddr, verts) ++
                    setupData

        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt,  descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt,  vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt,   vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt,  setup.size)
        rawWrite(borg, BorgGpuRegs.seq_rast_addr_offset.litValue.toInt,  rastAddr)
        rawWrite(borg, BorgGpuRegs.seq_rast_len_offset.litValue.toInt,   rast.size)
        rawWrite(borg, BorgGpuRegs.seq_frag_addr_offset.litValue.toInt,  fragAddr)
        rawWrite(borg, BorgGpuRegs.seq_frag_len_offset.litValue.toInt,   frag.size)
        rawWrite(borg, BorgGpuRegs.seq_bin_base_offset.litValue.toInt,   binBase)
        rawWrite(borg, BorgGpuRegs.seq_bin_row_bytes_offset.litValue.toInt, 2)
        rawWrite(borg, BorgGpuRegs.seq_setup_base_offset.litValue.toInt, setupBase)
        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, dram)
        println(f"  seq_busy seen: $seen, cleared: $cleared, DMA reads: $dmaReads")
        Predef.assert(seen,    "seq_busy never went high")
        Predef.assert(cleared, "seq_busy never cleared — hung")

        // Software reference
        val e0dx = v0x - v1x; val e0dy = v1y - v0y
        val e1dx = v1x - v2x; val e1dy = v2y - v1y
        val e2dx = v2x - v0x; val e2dy = v0y - v2y

        // Uniform memory is write-only from MMIO.  Verify staged values by running
        // a readout shader that reads u0-u11 into r0-r11, then reading the GPRs.
        // The sequencer toggled uniformPage from 0 to 1 at the start of sStageUniforms,
        // so we set uniformPage=1 so BorgCore reads from the correct page.
        //
        // Readout shader: r_i = u_i + r25(=0) for i in 0..11, then halt.
        val readoutShader = (0 until 12).map { i =>
          Instructions.ADD(rs1 = i, rs2 = 25, rd = i, funct3 = 1)
        } :+ BigInt(0)

        // Initialize r25 to 0.0f
        rawWrite(borg, BorgGpuRegs.gpr_offset.litValue.toInt + 25 * 4, 0)

        // Write readout shader to IMEM (offset 128 in BorgCore address space)
        for ((w, i) <- readoutShader.zipWithIndex)
          rawWrite(borg, 128 + i * 4, w & BigInt(0xFFFFFFFFL))

        // Set uniformWritePage=0 (sequencer's uniformPage is 0, it never toggles)
        // and start BorgCore at PC=0.
        // CONTROL register: bit0=start, bit5=uniform_write_page, bits[10:5]=start_pc.
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 0)              // uniformWritePage=0
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 1)              // start + page=0
        // 12 ADD instructions x 8 cycles/instruction (BorgCore's busy_counter
        // pipeline, widened 4->7 by the register-file read serialization --
        // see BorgLane's `regFile` doc comment) = 96 cycles, + start/halt
        // detection margin.
        borg.clock.step(160)

        val gprs = (0 until 12).map { i => bitsToFloat16(rawRead(borg, BorgGpuRegs.gpr_offset.litValue.toInt + i * 4) & 0xFFFF) }
        println(f"  r0-r5  (u0-u5 edges):   ${gprs.take(6).map(v => f"$v%.3f").mkString(" ")}")
        println(f"  r6-r11 (u6-u11 negpos): ${gprs.slice(6,12).map(v => f"$v%.3f").mkString(" ")}")
        // Note: u6-u11 (negated positions) are in the uniform buffer but the readout
        // shader runs while the sequencer's previous setup-shader GPR values are still
        // in r6-r11, masking the uniform read.  We verify u0-u5 (edges) which are not
        // contaminated by prior shader output.

        val tol = 0.1f
        def assertClose(name: String, got: Float, expV: Float): Unit =
          Predef.assert(math.abs(got - expV) < math.max(tol, math.abs(expV)*0.02f),
            f"$name: got $got%.4f, expected $expV%.4f")

        assertClose("u0(e0dx)", gprs(0), e0dx)
        assertClose("u1(e0dy)", gprs(1), e0dy)
        assertClose("u2(e1dx)", gprs(2), e1dx)
        assertClose("u3(e1dy)", gprs(3), e1dy)
        assertClose("u4(e2dx)", gprs(4), e2dx)
        assertClose("u5(e2dy)", gprs(5), e2dy)

        // NOTE: u19-27 now stage per-vertex MODEL position (frag_pos for the borgc
        // cube.frag) instead of vertex colour (M6).  They cannot be observed via
        // this post-run readout (Pass 2 re-stages only u0-5; u6+ read back stale —
        // the same reason this test only asserts u0-5).  The frag_pos staging is
        // validated on hardware at M7 (lit cube on HDMI).

        println("=== sequencer_uniform_staging PASSED ===\n")
      }
    }

    /** Step 29.4 gate: sequencer_full_triangle
      *
      * Full integration: sequencer stages uniforms from DRAM descriptor,
      * then rasterizer iterates a tile using those uniforms, fragment shader
      * reads staged color values, tile buffer receives correct RGBZ.
      */
    utest.test("sequencer_full_triangle") {
      simulate(new BorgTestWrapper(BorgConfig.Default)) { borg =>
        println("\n=== BorgSequencerTests: sequencer_full_triangle ===")

        // --- (0) Reset ---
        borg.reset.poke(true.B)
        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.io.gpuMem.ready.poke(false.B)
        borg.io.gpuMem.data.poke(0.U)
        borg.clock.step(4)
        borg.reset.poke(false.B)
        borg.clock.step(20)
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)

        // --- (1) DRAM setup ---
        val vertAddr = 0x1000; val setupAddr = 0x3000; val descAddr = 0x2000
        val c0r = 1.0f; val c0g = 0.0f; val c0b = 0.0f
        val verts = Seq(
          Seq(1.0f, 0.0f, 0.1f, c0r, c0g, c0b, 0.0f, 0.0f),
          Seq(2.0f, 0.0f, 0.2f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
          Seq(1.0f, 5.0f, 0.3f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
        )
        val vertShader = vertPassthroughShader()
        val setup      = setupShader()
        val rastAddr = 0x4000; val fragAddr = 0x5000
        val rastShader = Seq(
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 0),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 1),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 2),
          BigInt(0)
        )
        val fragShader = Seq(
          Instructions.ADD(rs1 = 14, rs2 = 25, rd = 26, funct3 = 1),
          Instructions.ADD(rs1 = 20, rs2 = 25, rd = 27),
          Instructions.ADD(rs1 = 21, rs2 = 25, rd = 28),
          Instructions.ADD(rs1 = 22, rs2 = 25, rd = 29),
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP 1
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP 2
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP 3
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP 4
          BigInt(0)
        )
        val dram: Map[Int, BigInt] =
          vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
          setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
          rastShader.zipWithIndex.map { case (w,i) => (rastAddr + i*4) -> w }.toMap ++
          fragShader.zipWithIndex.map { case (w,i) => (fragAddr + i*4) -> w }.toMap ++
          buildDescriptor(descAddr, verts)

        // --- (2) Run sequencer ---
        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt, descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt, vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt, vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt, setup.size)
        rawWrite(borg, BorgGpuRegs.seq_rast_addr_offset.litValue.toInt, rastAddr)
        rawWrite(borg, BorgGpuRegs.seq_rast_len_offset.litValue.toInt, rastShader.size)
        rawWrite(borg, BorgGpuRegs.seq_frag_addr_offset.litValue.toInt, fragAddr)
        rawWrite(borg, BorgGpuRegs.seq_frag_len_offset.litValue.toInt, fragShader.size)
        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)
        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, dram)
        println(f"  seq_busy seen: $seen, cleared: $cleared, DMA reads: $dmaReads")
        Predef.assert(seen,    "sequencer never went busy")
        Predef.assert(cleared, "sequencer never completed (hung in tile loop)")
        // DMA should include: vert shader, 3 vertices, setup shader, rast shader, frag shader, bbox
        Predef.assert(dmaReads >= 30, s"Expected >= 30 DMA reads, got $dmaReads")
        println("=== sequencer_full_triangle PASSED ===\n")
      }
    }

    utest.test("multi_triangle_loop") {
      simulate(new BorgTestWrapper(BorgConfig.Default)) { borg =>
        println("\n=== BorgSequencerTests: multi_triangle_loop ===")
        resetAndWait(borg)

        val vertAddr = 0x1000; val descAddr = 0x2000; val setupAddr = 0x3000
        val rastAddr = 0x4000; val fragAddr = 0x5000
        val vertShader = vertPassthroughShader()
        val setup      = setupShader()
        val rast       = Seq(BigInt(0)) // just HALT
        val frag       = Seq(BigInt(0)) // just HALT

        val verts1 = Seq(
          Seq(1.0f, 2.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(4.0f, 5.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
          Seq(7.0f, 8.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
        )
        val verts2 = Seq(
          Seq(2.0f, 3.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(5.0f, 6.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
          Seq(8.0f, 9.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
        )

        // Now that the coreTrigger mux is fixed, use real bbox descriptors
        // to exercise the full tile iteration loop for each triangle.
        val dram = vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
                    setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
                    rast.zipWithIndex.map       { case (w,i) => (rastAddr + i*4) -> w }.toMap ++
                    frag.zipWithIndex.map       { case (w,i) => (fragAddr + i*4) -> w }.toMap ++
                    buildDescriptorWithBbox(descAddr,       verts1, 0, 0, 4, 4) ++
                    buildDescriptorWithBbox(descAddr + 128, verts2, 0, 0, 4, 4)

        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt,  descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt,  vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt,   vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt,  setup.size)
        rawWrite(borg, BorgGpuRegs.seq_rast_addr_offset.litValue.toInt,  rastAddr)
        rawWrite(borg, BorgGpuRegs.seq_rast_len_offset.litValue.toInt,   rast.size)
        rawWrite(borg, BorgGpuRegs.seq_frag_addr_offset.litValue.toInt,  fragAddr)
        rawWrite(borg, BorgGpuRegs.seq_frag_len_offset.litValue.toInt,   frag.size)
        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt,  2)

        val statusPre = rawRead(borg, BorgGpuRegs.status_offset.litValue.toInt)
        Predef.assert(((statusPre >> 5) & 1) == 0, "seq_busy should be 0 before trigger")
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, dram, maxCycles = 10000)
        println(f"  seq_busy seen: $seen, cleared: $cleared, DMA reads: $dmaReads")
        Predef.assert(seen,    "seq_busy never went high")
        Predef.assert(cleared, "seq_busy never cleared")
        // Minimum DMA reads:
        // For each of 2 triangles:
        // - Load vert shader: 3 words
        // - Load 3 vertices: 3 * 8 = 24 words
        // - Load setup shader: 23 words
        // - Load rast shader: 1 word
        // - Load frag shader: 1 word
        // Total per tri ~ 52. For 2 triangles ~ 104.
        Predef.assert(dmaReads > 80, s"Expected > 80 DMA reads for 2 triangles, got $dmaReads")
        println("=== multi_triangle_loop PASSED ===\n")
      }
    }

    /** Sequencer → Flusher E2E: verify autonomous render produces correct
      * FP16 pixel writes to SDRAM.
      *
      * Setup: 1 triangle covering tile (0,0), with simple shaders that output
      * a known color. After the sequencer completes, verify the captured GPU
      * writes contain the expected FP16 RGBZ values at the correct addresses.
      */
    utest.test("sequencer_flusher_e2e") {
      simulate(new BorgTestWrapper(BorgConfig.Default)) { borg =>
        println("\n=== BorgSequencerTests: sequencer_flusher_e2e ===")

        // --- Reset ---
        borg.reset.poke(true.B)
        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.io.gpuMem.ready.poke(false.B)
        borg.io.gpuMem.data.poke(0.U)
        borg.clock.step(4)
        borg.reset.poke(false.B)
        borg.clock.step(20)
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2) // reset pipeline

        // --- DRAM layout ---
        val vertAddr  = 0x1000; val setupAddr = 0x3000; val descAddr  = 0x2000
        val rastAddr  = 0x4000; val fragAddr  = 0x5000
        val binBase   = 0x6000; val setupBase = 0x7000
        val fbBase    = 0x10000  // framebuffer base for flusher

        // Triangle: fully covers tile (0,0), i.e., pixels (0,0)-(3,3).
        // Vertices at (0, 0), (4, 0), (0, 4) — covers the 4×4 tile.
        // Colors: v0=red, v1=red, v2=red → all pixels should be pure red.
        val verts = Seq(
          Seq(0.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f), // v0 (red)
          Seq(4.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f), // v1 (red)
          Seq(0.0f, 4.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f), // v2 (red)
        )

        val vertShader = vertPassthroughShader()
        val setup      = setupShader()

        // Rast shader: simple stub that passes edge results through.
        // r0-r5 are edge values from setup uniforms.
        // For test simplicity, just output 0 for all weights — the frag shader
        // will output the clear color or the uniform color directly.
        val rastShader = Seq(
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 0),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 1),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 2),
          BigInt(0) // HALT
        )

        // Frag shader: output u7 (color R for w2 vertex) → r26, u10 (G) → r27,
        // u13 (B) → r28, u16 (Z) → r29.
        // These are the sequencer-staged uniforms from sStageUniforms.
        // Since all 3 vertices have r=1.0, the staged u7/u8/u9 (R for w2/w1/w0)
        // are all 1.0. Similarly u10-u12 (G)=0, u13-u15 (B)=0.
        // The frag shader outputs from the "w2" color slot (u7, u10, u13, u16).
        val fragShader = Seq(
          Instructions.ADD(rs1 = 14, rs2 = 25, rd = 26, funct3 = 1), // r26 = u14 (inv_area from seq)
          Instructions.ADD(rs1 = 7,  rs2 = 25, rd = 27, funct3 = 1), // r27 = u7  (color R, w2→v1)
          Instructions.ADD(rs1 = 10, rs2 = 25, rd = 28, funct3 = 1), // r28 = u10 (color G, w2→v1)
          Instructions.ADD(rs1 = 13, rs2 = 25, rd = 29, funct3 = 1), // r29 = u13 (color B, w2→v1)
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0), // NOP
          BigInt(0) // HALT
        )

        val dramInit: Map[Int, BigInt] =
          vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
          setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
          rastShader.zipWithIndex.map { case (w,i) => (rastAddr + i*4) -> w }.toMap ++
          fragShader.zipWithIndex.map { case (w,i) => (fragAddr + i*4) -> w }.toMap ++
          buildDescriptorWithBbox(descAddr, verts, 0, 0, 4, 4)

        // Mutable DRAM that captures writes
        val dram = scala.collection.mutable.Map[Int, BigInt]() ++= dramInit
        val gpuWrites = scala.collection.mutable.ArrayBuffer[(Int, BigInt)]()

        def serviceDramCapture(): Int = {
          if (borg.io.gpuMem.req.peek().litToBoolean) {
            // Read: provide data from dram
            val addr = borg.io.gpuMem.addr.peek().litValue.toInt
            val data = dram.getOrElse(addr, BigInt(0)) & BigInt(0xFFFFFFFFL)
            borg.io.gpuMem.data.poke(data.U)
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
            1
          } else if (borg.io.gpuMem.wr.peek().litToBoolean) {
            // Write (burst or single): capture each word at its byte address.
            // The flusher holds addr=baseAddr fixed; MemoryController increments
            // by 2 bytes per halfword.  We simulate that increment here.
            val baseAddr = borg.io.gpuMem.addr.peek().litValue.toInt
            val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
            val d0 = borg.io.gpuMem.wdata.peek().litValue & BigInt(0xFFFF)
            gpuWrites += ((baseAddr, d0))
            dram(baseAddr) = d0
            if (wlen > 1) {
              for (i <- 1 until wlen) {
                borg.io.gpuMem.waccept.poke(true.B)
                borg.io.gpuMem.ready.poke(false.B)
                borg.clock.step(1)
                val wordAddr = baseAddr + i * 2
                val d = borg.io.gpuMem.wdata.peek().litValue & BigInt(0xFFFF)
                gpuWrites += ((wordAddr, d))
                dram(wordAddr) = d
              }
              borg.io.gpuMem.waccept.poke(false.B)
            }
            borg.io.gpuMem.ready.poke(true.B)
            wlen
          } else {
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(false.B)
            0
          }
        }

        // --- Configure sequencer registers ---
        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt, descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt, vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt, vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt, setup.size)
        rawWrite(borg, BorgGpuRegs.seq_rast_addr_offset.litValue.toInt, rastAddr)
        rawWrite(borg, BorgGpuRegs.seq_rast_len_offset.litValue.toInt, rastShader.size)
        rawWrite(borg, BorgGpuRegs.seq_frag_addr_offset.litValue.toInt, fragAddr)
        rawWrite(borg, BorgGpuRegs.seq_frag_len_offset.litValue.toInt, fragShader.size)
        rawWrite(borg, BorgGpuRegs.seq_bin_base_offset.litValue.toInt, binBase)
        rawWrite(borg, BorgGpuRegs.seq_bin_row_bytes_offset.litValue.toInt, 2)
        rawWrite(borg, BorgGpuRegs.seq_setup_base_offset.litValue.toInt, setupBase)
        rawWrite(borg, BorgGpuRegs.seq_fb_base_offset.litValue.toInt, fbBase)
        rawWrite(borg, BorgGpuRegs.seq_tiles_per_row_offset.litValue.toInt, 1) // 4-pixel wide FB
        rawWrite(borg, BorgGpuRegs.seq_clear_lo_offset.litValue.toInt, 0x7BFF) // clear Z=max
        rawWrite(borg, BorgGpuRegs.seq_clear_hi_offset.litValue.toInt, 0)      // clear R=0, G=0
        // Set frag_pc so dispatcher chains to fragment shader
        rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, 64) // IMEM frag offset
        // Set flush_width (log2 of fb width in pixels)
        rawWrite(borg, BorgGpuRegs.flush_width_offset.litValue.toInt, 2) // log2(4) = 2

        // --- Trigger sequencer ---
        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        // --- Run until done (with write capture) ---
        var seqBusySeen = false
        var seqBusyCleared = false
        val maxCycles = 20000
        for (cycle <- 0 until maxCycles if !seqBusyCleared) {
          serviceDramCapture()
          borg.clock.step(1)
          if (cycle % 10 == 5) {
            borg.io.address.poke(BorgGpuRegs.status_offset)
            borg.io.data_read_n.poke(2.U)
            borg.io.data_write_n.poke(3.U)
            serviceDramCapture()
            borg.clock.step(1)
            val st = borg.io.data_out.peek().litValue
            borg.io.data_read_n.poke(3.U)
            serviceDramCapture()
            borg.clock.step(1)
            val busy = (st >> 5) & 1
            if (busy == 1) seqBusySeen = true
            if (seqBusySeen && busy == 0) seqBusyCleared = true
          }
        }
        println(f"  seq_busy seen: $seqBusySeen, cleared: $seqBusyCleared")
        Predef.assert(seqBusySeen, "sequencer never went busy")
        Predef.assert(seqBusyCleared, "sequencer never completed")

        // --- Verify flusher writes ---
        println(f"  Total GPU writes captured: ${gpuWrites.size}")

        // The flusher writes 16 pixels × 1 RGB565 word = 16 writes for tile (0,0).
        // Each write: 16-bit RGB565 value at addr = fbBase + tile_index*32 + pixel*2.
        // For tile (0,0): tile_index=0, so addr starts at fbBase.
        val flusherWrites = gpuWrites.filter { case (addr, _) =>
          addr >= fbBase && addr < fbBase + 32
        }
        println(f"  Flusher writes in tile (0,0) range: ${flusherWrites.size}")

        if (flusherWrites.nonEmpty) {
          // Print first few writes for debug
          println("  First 8 flusher writes:")
          for ((addr, data) <- flusherWrites.take(8)) {
            println(f"    addr=0x$addr%06X data=0x${data.toInt}%04X")
          }

          // Verify 16 writes (16 pixels × 1 RGB565 word)
          Predef.assert(flusherWrites.size >= 16,
            s"Expected >= 16 flusher writes for 16 pixels, got ${flusherWrites.size}")

          // Check pixel 0 (RGB565): red cube color → R5=31 → 0xF800 high bits set.
          val pixel0 = flusherWrites.find(_._1 == fbBase).map(_._2.toInt & 0xFFFF)
          println(f"  Pixel 0 RGB565 = 0x${pixel0.getOrElse(0)}%04X")
          Predef.assert(pixel0.isDefined, "No write at fbBase (pixel 0)")
        }

        println("=== sequencer_flusher_e2e PASSED ===\n")
      }
    }
  }
}
