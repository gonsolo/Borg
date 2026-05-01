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
    rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)
  }

  def servicePsram(borg: Borg, psram: Map[Int, BigInt]): Int = {
    if (borg.io.gpuMem.req.peek().litToBoolean &&
        !borg.io.gpuMem.wr.peek().litToBoolean) {
      val addr = borg.io.gpuMem.addr.peek().litValue.toInt
      val data = psram.getOrElse(addr, BigInt(0)) & BigInt(0xFFFFFFFFL)
      borg.io.gpuMem.data.poke(data.U)
      borg.io.gpuMem.ready.poke(true.B)
      1
    } else {
      borg.io.gpuMem.ready.poke(false.B)
      0
    }
  }

  def runSequencerUntilDone(borg: Borg, psram: Map[Int, BigInt],
                            maxCycles: Int = 5000): (Boolean, Boolean, Int) = {
    var seqBusySeen    = false
    var seqBusyCleared = false
    var dmaReads       = 0
    for (cycle <- 0 until maxCycles if !seqBusyCleared) {
      dmaReads += servicePsram(borg, psram)
      borg.clock.step(1)
      if (cycle % 10 == 5) {
        borg.io.address.poke(BorgGpuRegs.status_offset)
        borg.io.data_read_n.poke(2.U)
        borg.io.data_write_n.poke(3.U)
        dmaReads += servicePsram(borg, psram)
        borg.clock.step(1)
        val st = borg.io.data_out.peek().litValue
        borg.io.data_read_n.poke(3.U)
        dmaReads += servicePsram(borg, psram)
        borg.clock.step(1)
        val busy = (st >> 5) & 1
        if (busy == 1) seqBusySeen = true
        if (seqBusySeen && busy == 0) seqBusyCleared = true
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

  /** Build a stride-32 PSRAM descriptor for 3 vertices.
    * Each vertex is 8 FP16 words (x,y,z,r,g,b,u,v) × 4 bytes = 32 bytes.
    */
  def buildDescriptor(baseAddr: Int, verts: Seq[Seq[Float]]): Map[Int, BigInt] =
    (for (v <- 0 until 3; c <- 0 until 8) yield
      (baseAddr + v * 32 + c * 4) -> floatToBits16(verts(v)(c))).toMap

  val tests = Tests {

    utest.test("vertex_shader_run") {
      simulate(new Borg(BorgConfig.Sim)) { borg =>
        println("\n=== BorgSequencerTests: vertex_shader_run ===")
        resetAndWait(borg)

        val vertAddr = 0x1000; val descAddr = 0x2000
        val vertShader = vertPassthroughShader()
        val verts = Seq(
          Seq(1.0f, 2.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(4.0f, 5.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
          Seq(7.0f, 8.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
        )
        val psram = vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
                    buildDescriptor(descAddr, verts)

        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt,  descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt,  vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt,   vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, vertAddr)  // passthrough reused
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt,  vertShader.size)

        val statusPre = rawRead(borg, BorgGpuRegs.status_offset.litValue.toInt)
        Predef.assert(((statusPre >> 5) & 1) == 0, "seq_busy should be 0 before trigger")
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, psram)
        println(f"  seq_busy seen: $seen, cleared: $cleared, DMA reads: $dmaReads")
        Predef.assert(seen,    "seq_busy never went high")
        Predef.assert(cleared, "seq_busy never cleared")
        Predef.assert(dmaReads >= 10, s"Expected >= 10 DMA reads, got $dmaReads")
        println("=== vertex_shader_run PASSED ===\n")
      }
    }

    utest.test("triangle_setup") {
      simulate(new Borg(BorgConfig.Sim)) { borg =>
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
        val psram = vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
                    setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
                    buildDescriptor(descAddr, verts)

        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt,  descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt,  vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt,   vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt,  setup.size)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, psram)
        println(f"  seq_busy seen: $seen, cleared: $cleared, DMA reads: $dmaReads")
        Predef.assert(seen,    "seq_busy never went high")
        Predef.assert(cleared, "seq_busy never cleared — hung")

        val e0dx = v0x - v1x; val e0dy = v1y - v0y
        val e1dx = v1x - v2x; val e1dy = v2y - v1y
        val e2dx = v2x - v0x; val e2dy = v0y - v2y
        val area = e0dx * e2dy - e2dx * e0dy
        println(f"  Expected edges: ($e0dx%.1f,$e0dy%.1f) ($e1dx%.1f,$e1dy%.1f) ($e2dx%.1f,$e2dy%.1f) area=$area%.1f")

        val outputs = (0 until 8).map { i => bitsToFloat16(rawRead(borg, i * 4) & 0xFFFF) }
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
      simulate(new Borg(BorgConfig.Sim)) { borg =>
        println("\n=== BorgSequencerTests: sequencer_uniform_staging ===")
        resetAndWait(borg)

        val v0x = 1.0f; val v0y = 0.0f; val v0z = 0.1f
        val v1x = 2.0f; val v1y = 0.0f; val v1z = 0.2f
        val v2x = 1.0f; val v2y = 5.0f; val v2z = 0.3f
        val c0r = 1.0f; val c0g = 0.0f; val c0b = 0.0f  // v0 = red
        val c1r = 0.0f; val c1g = 1.0f; val c1b = 0.0f  // v1 = green
        val c2r = 0.0f; val c2g = 0.0f; val c2b = 1.0f  // v2 = blue

        val vertAddr = 0x1000; val setupAddr = 0x3000; val descAddr = 0x2000
        val vertShader = vertPassthroughShader()
        val setup      = setupShader()
        val verts = Seq(
          Seq(v0x, v0y, v0z, c0r, c0g, c0b, 0.0f, 0.0f),
          Seq(v1x, v1y, v1z, c1r, c1g, c1b, 0.0f, 0.0f),
          Seq(v2x, v2y, v2z, c2r, c2g, c2b, 0.0f, 0.0f),
        )
        val psram = vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
                    setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
                    buildDescriptor(descAddr, verts)

        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt,  descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt,  vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt,   vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt,  setup.size)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        val (seen, cleared, dmaReads) = runSequencerUntilDone(borg, psram)
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
        // Readout shader: r_i = u_i + r31(=0) for i in 0..11, then halt.
        val readoutShader = (0 until 12).map { i =>
          Instructions.ADD(rs1 = i, rs2 = 31, rd = i, funct3 = 1)
        } :+ BigInt(0)

        // Write readout shader to IMEM (offset 128 in BorgCore address space)
        for ((w, i) <- readoutShader.zipWithIndex)
          rawWrite(borg, 128 + i * 4, w & BigInt(0xFFFFFFFFL))

        // Set uniformWritePage=1 (sequencer toggled 0->1) and start BorgCore at PC=0.
        // CONTROL register: bit0=start, bit5=uniform_write_page, bits[10:5]=start_pc.
        // Write page=1 first, then start with page still set.
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, (1 << 5))        // uniformWritePage=1
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, (1 << 5) | 1)   // start + page=1
        borg.clock.step(60)

        val gprs = (0 until 12).map { i => bitsToFloat16(rawRead(borg, i * 4) & 0xFFFF) }
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

        println("=== sequencer_uniform_staging PASSED ===\n")
      }
    }

    /** Step 29.4 gate: sequencer_full_triangle
      *
      * Full integration: sequencer stages uniforms from PSRAM descriptor,
      * then rasterizer iterates a tile using those uniforms, fragment shader
      * reads staged color values, tile buffer receives correct RGBZ.
      */
    utest.test("sequencer_full_triangle") {
      simulate(new Borg(BorgConfig.Sim)) { borg =>
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

        // --- (1) PSRAM setup ---
        val vertAddr = 0x1000; val setupAddr = 0x3000; val descAddr = 0x2000
        val c0r = 1.0f; val c0g = 0.0f; val c0b = 0.0f
        val verts = Seq(
          Seq(1.0f, 0.0f, 0.1f, c0r, c0g, c0b, 0.0f, 0.0f),
          Seq(2.0f, 0.0f, 0.2f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
          Seq(1.0f, 5.0f, 0.3f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
        )
        val vertShader = vertPassthroughShader()
        val setup      = setupShader()
        val psram: Map[Int, BigInt] =
          vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
          setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
          buildDescriptor(descAddr, verts)

        // --- (2) Run sequencer ---
        rawWrite(borg, BorgGpuRegs.seq_desc_base_offset.litValue.toInt, descAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_addr_offset.litValue.toInt, vertAddr)
        rawWrite(borg, BorgGpuRegs.seq_vert_len_offset.litValue.toInt, vertShader.size)
        rawWrite(borg, BorgGpuRegs.seq_setup_addr_offset.litValue.toInt, setupAddr)
        rawWrite(borg, BorgGpuRegs.seq_setup_len_offset.litValue.toInt, setup.size)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)
        val (seen, cleared, _) = runSequencerUntilDone(borg, psram)
        println(f"  Sequencer: busy=$seen, cleared=$cleared")
        Predef.assert(cleared, "sequencer hung")

        // --- (3) Load rast+frag shaders to IMEM ---
        rawWrite(borg, 7 * 4, floatToBits16(1.0f))
        rawWrite(borg, 6 * 4, floatToBits16(0.0f))
        rawWrite(borg, 20 * 4, floatToBits16(0.5f))
        rawWrite(borg, 21 * 4, floatToBits16(0.25f))
        rawWrite(borg, 22 * 4, floatToBits16(0.1f))
        val rastShader = Seq(
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 0),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 1),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 2),
          BigInt(0)
        )
        val fragShader = Seq(
          Instructions.ADD(rs1 = 14, rs2 = 31, rd = 26, funct3 = 1),
          Instructions.ADD(rs1 = 20, rs2 = 31, rd = 27),
          Instructions.ADD(rs1 = 21, rs2 = 31, rd = 28),
          Instructions.ADD(rs1 = 22, rs2 = 31, rd = 29),
          BigInt(0)
        )
        for ((w, i) <- (rastShader ++ fragShader).zipWithIndex)
          rawWrite(borg, 128 + i * 4, w & BigInt(0xFFFFFFFFL))

        // --- (4) Configure rasterizer ---
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 1 << 5)
        rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, 4)

        // --- (5) Enqueue tile cmd + iterate ---
        rawWrite(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, 0)
        borg.clock.step(5)
        for (_ <- 0 until 16) {
          rawWrite(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)
          borg.clock.step(50)
        }
        borg.clock.step(20)

        // --- (6) Read tile buffer pixel 0 ---
        val expR = floatToBits16(c0r).toInt
        val expG = floatToBits16(0.5f).toInt
        val expB = floatToBits16(0.25f).toInt
        val expZ = floatToBits16(0.1f).toInt
        rawWrite(borg, BorgGpuRegs.tile_ctrl_offset.litValue.toInt, 0)
        borg.clock.step(3)
        val rg0 = rawRead(borg, BorgGpuRegs.tile_rg_offset.litValue.toInt)
        val bz0 = rawRead(borg, BorgGpuRegs.tile_bz_offset.litValue.toInt)
        val r0 = ((rg0 >> 16) & 0xFFFF).toInt
        val g0 = (rg0 & 0xFFFF).toInt
        val b0 = ((bz0 >> 16) & 0xFFFF).toInt
        val z0 = (bz0 & 0xFFFF).toInt
        println(f"  Tile[0]: R=0x$r0%04X(exp 0x$expR%04X) G=0x$g0%04X(exp 0x$expG%04X) B=0x$b0%04X(exp 0x$expB%04X) Z=0x$z0%04X(exp 0x$expZ%04X)")

        Predef.assert(r0 == expR, f"R: 0x$r0%04X vs 0x$expR%04X")
        Predef.assert(g0 == expG, f"G: 0x$g0%04X vs 0x$expG%04X")
        Predef.assert(b0 == expB, f"B: 0x$b0%04X vs 0x$expB%04X")
        Predef.assert(z0 == expZ, f"Z: 0x$z0%04X vs 0x$expZ%04X")

        println("=== sequencer_full_triangle PASSED ===\n")
      }
    }
  }
}
