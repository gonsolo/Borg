// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator.{
  toTestableClock, toTestableSInt, toTestableUInt, toTestableBool,
  toTestableReset, toTestableEnum, toTestableRecord, toTestableVec, toTestableData
}
import utest._

/** BorgSequencer unit tests — Steps 29.1/29.2/29.3.
  *
  * Instantiates the full BorgTestWrapper (whole Borg, not a submodule), so
  * this uses FastBuildSimulator rather than EphemeralSimulator's default
  * -O3 Verilator build -- see FastBuildSimulator's doc comment.
  */
object BorgSequencerTests extends TestSuite with FastBuildSimulator {

  // --- Float conversion helpers ---

  /** The config this suite builds its DUT with. The float helpers below
    * follow it rather than hardcoding a format, so these tests keep meaning
    * the same thing when the default changes -- which is exactly what broke
    * them when BorgConfig.Default moved to FP32: uniforms, GPRs, clipRegs
    * and covDeltaDebug are all `cfg.totalBits` wide, and BorgDMA stages
    * `io.gpuMem.data(cfg.totalBits - 1, 0)` out of each DRAM word, so a
    * 16-bit pattern written into DRAM and a 16-bit mask on readback were
    * both wrong at FP32 (FP32 -1.0 is 0xBF800000, whose low 16 bits decode
    * as 0.0 -- the "got 0.0, expected 1.5" these tests reported).
    */
  val suiteCfg = BorgConfig.Test

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

  /** Float -> the datapath's own bit pattern, `suiteCfg.totalBits` wide. */
  def floatToBits(f: Float): BigInt = suiteCfg.fp match {
    case FloatConfig.FP32 => BigInt(java.lang.Float.floatToRawIntBits(f) & 0xffffffffL)
    case _                => floatToBits16(f)
  }

  /** The inverse. Masks to the datapath width itself, so call sites do not
    * carry their own `& 0xFFFF` (which is what silently truncated FP32). */
  def bitsToFloat(b: BigInt): Float = suiteCfg.fp match {
    case FloatConfig.FP32 => java.lang.Float.intBitsToFloat((b & BigInt(0xffffffffL)).toInt)
    case _                => bitsToFloat16(b & BigInt(0xffff))
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
    // Writes before reads, as MemoryController and BorgLinkSlave do: the
    // setup store raises req AND wr together, and taking it as a read drops
    // the write. (The map is immutable here, so writes are acknowledged and
    // discarded -- scenarios that need pass 1's bins and setup data back use
    // their own mutable service, see the occlusion-query scenario.)
    if (borg.io.gpuMem.wr.peek().litToBoolean) {
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
    } else if (borg.io.gpuMem.req.peek().litToBoolean) {
      // Read request: provide data and pulse ready.
      val addr = borg.io.gpuMem.addr.peek().litValue.toInt
      val data = dram.getOrElse(addr, BigInt(0)) & BigInt(0xFFFFFFFFL)
      borg.io.gpuMem.data.poke(data.U)
      borg.io.gpuMem.waccept.poke(false.B)
      borg.io.gpuMem.ready.poke(true.B)
      1
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

  /** The setup shader covDelta_diagnostic_real_values uses: unlike
    * setupShader() it negates the area and normalizes the edges, and with it
    * (plus seq_inv_width = 1.0) pass 1 really bins the triangle and pass 2
    * really rasterizes it. setupShader() only exercises the sequencer FSM.
    */
  def binningSetupShader(): Seq[BigInt] = Seq(
    Instructions.ADD(rs1 = 0, rs2 = 31, rd = 8,  funct3 = 1),
    Instructions.ADD(rs1 = 1, rs2 = 31, rd = 9,  funct3 = 1),
    Instructions.ADD(rs1 = 2, rs2 = 31, rd = 10, funct3 = 1),
    Instructions.ADD(rs1 = 3, rs2 = 31, rd = 11, funct3 = 1),
    Instructions.ADD(rs1 = 4, rs2 = 31, rd = 12, funct3 = 1),
    Instructions.ADD(rs1 = 5, rs2 = 31, rd = 13, funct3 = 1),
    Instructions.FNEG(rs1 = 10, rd = 14),
    Instructions.ADD(rs1 = 8,  rs2 = 14, rd = 0),
    Instructions.FNEG(rs1 = 9,  rd = 15),
    Instructions.ADD(rs1 = 11, rs2 = 15, rd = 1),
    Instructions.FNEG(rs1 = 12, rd = 16),
    Instructions.ADD(rs1 = 10, rs2 = 16, rd = 2),
    Instructions.FNEG(rs1 = 11, rd = 22),
    Instructions.ADD(rs1 = 13, rs2 = 22, rd = 3),
    Instructions.FNEG(rs1 = 8,  rd = 23),
    Instructions.ADD(rs1 = 12, rs2 = 23, rd = 4),
    Instructions.FNEG(rs1 = 13, rd = 24),
    Instructions.ADD(rs1 = 9,  rs2 = 24, rd = 5),
    Instructions.MUL(rs1 = 0, rs2 = 5, rd = 20),
    Instructions.FNEG(rs1 = 1, rd = 21),
    Instructions.FMA(rs1 = 4, rs2 = 21, rs3 = 20, rd = 6),
    Instructions.FNEG(rs1 = 6, rd = 6),
    Instructions.MUL(rs1 = 0, rs2 = 6, rd = 0, funct3 = 2),
    Instructions.MUL(rs1 = 1, rs2 = 6, rd = 1, funct3 = 2),
    Instructions.MUL(rs1 = 2, rs2 = 6, rd = 2, funct3 = 2),
    Instructions.MUL(rs1 = 3, rs2 = 6, rd = 3, funct3 = 2),
    Instructions.MUL(rs1 = 4, rs2 = 6, rd = 4, funct3 = 2),
    Instructions.MUL(rs1 = 5, rs2 = 6, rd = 5, funct3 = 2),
    Instructions.MUL(rs1 = 6, rs2 = 6, rd = 6, funct3 = 2),
    Instructions.FRCP(rs1 = 6, rd = 7),
    // Step 50.2b
    Instructions.MUL(rs1 = 0, rs2 = 7, rd = 14, funct3 = 2),
    Instructions.FMA(rs1 = 1, rs2 = 8, rs3 = 14, rd = 8, funct3 = 2),
    Instructions.MUL(rs1 = 0, rs2 = 8, rd = 14, funct3 = 2),
    Instructions.FMA(rs1 = 1, rs2 = 9, rs3 = 14, rd = 9, funct3 = 2),
    Instructions.MUL(rs1 = 2, rs2 = 7, rd = 14, funct3 = 2),
    Instructions.FMA(rs1 = 3, rs2 = 8, rs3 = 14, rd = 10, funct3 = 2),
    Instructions.MUL(rs1 = 2, rs2 = 8, rd = 14, funct3 = 2),
    Instructions.FMA(rs1 = 3, rs2 = 9, rs3 = 14, rd = 11, funct3 = 2),
    Instructions.MUL(rs1 = 4, rs2 = 7, rd = 14, funct3 = 2),
    Instructions.FMA(rs1 = 5, rs2 = 8, rs3 = 14, rd = 12, funct3 = 2),
    Instructions.MUL(rs1 = 4, rs2 = 8, rd = 14, funct3 = 2),
    Instructions.FMA(rs1 = 5, rs2 = 9, rs3 = 14, rd = 13, funct3 = 2),
    BigInt(0) // HALT
  )

  /** Build a stride-32 DRAM descriptor for 3 vertices with a zero-extent bbox.
    * The zero bbox (min=max=0) causes the sequencer's tile loop to exit
    * immediately (0 >= 0), so tests that only verify uniform staging
    * don't need to simulate 16 pixel iterations.
    */
  def buildDescriptor(baseAddr: Int, verts: Seq[Seq[Float]]): Map[Int, BigInt] = {
    val m1 = (for (v <- 0 until 3; c <- 0 until 8) yield
      (baseAddr + v * 32 + c * 4) -> floatToBits(verts(v)(c))
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
      (baseAddr + v * 32 + c * 4) -> floatToBits(verts(v)(c))
    ).toMap
    m1 ++ Map(
      (baseAddr + 96)  -> BigInt(((minY & ~3) << 16) | (minX & ~3)),
      (baseAddr + 100) -> BigInt((maxY << 16) | maxX)
    )
  }

  val tests = Tests {

    // ONE simulate() call for all seven scenarios. BorgTestWrapper is the
    // entire Borg design, and chisel3.simulator rebuilds it from scratch on
    // every simulate() call -- ~900s of single-threaded Verilator
    // translation, dwarfing the handful of seconds each scenario actually
    // simulates. Seven calls meant seven identical builds. They are
    // identical: BorgConfig.Default is FP32+samples=4, which is exactly
    // what `Default.copy(samples = 4)` and the FloatConfig.FP32 auxiliary
    // constructor now resolve to as well.
    //
    // Safe because every scenario already resets the DUT as its first act
    // (resetAndWait, or an inline reset poke). Scenarios are still named and
    // reported individually below, and a failure names the scenario it came
    // from -- what utest's own per-test reporting gave us before.
    utest.test("sequencer scenarios (one shared BorgTestWrapper build)") {
      simulate(new BorgTestWrapper(suiteCfg)) { borg =>
        val failures = scala.collection.mutable.ArrayBuffer[(String, Throwable)]()

        def scenario(name: String)(body: => Unit): Unit = {
          println(s"\n=== scenario: $name ===")
          try { body; println(s"  [pass] $name") }
          catch {
            case t: Throwable =>
              failures += ((name, t))
              println(s"  [FAIL] $name: ${t.getMessage}")
          }
        }

        scenario("vertex_shader_run") {
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

        scenario("triangle_setup") {
        println("\n=== BorgSequencerTests: triangle_setup ===")
        resetAndWait(borg)

        // Pass 2 always visits tile (0,0) at least once (handleStartPass2 issues
        // its bin-count read unconditionally, before the fbHeightTiles bound
        // check). With the rasterizer edge-test shader now a permanent hardware
        // ROM (BorgRasterRom) rather than software-configurable IMEM content,
        // that visit genuinely runs the rasterizer -- with real uniform data --
        // against any tile that has bin entries. This test's own setup shader
        // only writes registers r0-r7 for inspection; it never populates the
        // uniform slots (via the production sStageUniforms/DRAM setup-store
        // round trip) that the real rasterizer needs, so ANY tile actually
        // being binned corrupts r0-r2 here regardless of which tile it is --
        // relocating the triangle doesn't help. The correct fix is to keep
        // this triangle from being binned AT ALL: reverse its winding order
        // (v1/v2 swapped vs. the natural CW-in-screen order) so
        // handleWaitSetup's backface cull (BorgSequencer.scala: "Back-facing
        // -> r6 < 0 (sign 1) -> skip") fires and sLoadBBox/sBinTri never runs,
        // leaving every tile's bin count at 0. Edge deltas (and thus every
        // "expected" value below) are computed purely from these same v0/v1/v2
        // coordinates, so relabeling which physical vertex is "v1" vs "v2"
        // doesn't change what's being tested -- only the winding sign.
        val v0x = 1.0f; val v0y = 0.0f
        val v1x = 1.0f; val v1y = 5.0f
        val v2x = 2.0f; val v2y = 0.0f

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

        val outputs = (0 until 8).map { i => bitsToFloat(rawRead(borg, BorgGpuRegs.gpr_offset.litValue.toInt + i * 4)) }
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

    /** Step 29.3 gate: sequencer_uniform_staging
      * Verifies all 31 physical uniform registers after a full sequencer run.
      */
        scenario("sequencer_uniform_staging") {
        println("\n=== BorgSequencerTests: sequencer_uniform_staging ===")
        resetAndWait(borg)

        // See the comment in triangle_setup for why this triangle's winding is
        // reversed (v1/v2 swapped vs. the natural CW-in-screen order): it
        // forces handleWaitSetup's backface cull to skip sLoadBBox/sBinTri
        // entirely, so no tile is ever binned and Pass 2's mandatory tile-(0,0)
        // visit stays harmless regardless of which tile would otherwise have
        // received this triangle's (never populated) uniform-slot data.
        val v0x = 1.0f; val v0y = 0.0f; val v0z = 0.1f
        val v1x = 1.0f; val v1y = 5.0f; val v1z = 0.3f
        val v2x = 2.0f; val v2y = 0.0f; val v2z = 0.2f
        val c0r = 1.0f; val c0g = 0.0f; val c0b = 0.0f  // v0 = red
        val c1r = 0.0f; val c1g = 0.0f; val c1b = 1.0f  // v1 = blue
        val c2r = 0.0f; val c2g = 1.0f; val c2b = 0.0f  // v2 = green

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
        val e0dxBits = floatToBits(v0x - v1x); val e0dyBits = floatToBits(v1y - v0y)
        val e1dxBits = floatToBits(v1x - v2x); val e1dyBits = floatToBits(v2y - v1y)
        val e2dxBits = floatToBits(v2x - v0x); val e2dyBits = floatToBits(v0y - v2y)
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
        //
        // Memory is serviced throughout, as real hardware always does: CONTROL
        // start does not reset the PC, so the core resumes where the last
        // shader halted. With the instruction cache that line belongs to an
        // older program, and the core fetches the current program's word from
        // DRAM rather than running whatever IMEM happened to hold. A bare
        // clock.step would leave that fetch unanswered and the core stalled.
        for (_ <- 0 until 400) { serviceDram(borg, dram); borg.clock.step(1) }

        val gprs = (0 until 12).map { i => bitsToFloat(rawRead(borg, BorgGpuRegs.gpr_offset.litValue.toInt + i * 4)) }
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

    /** Step 29.4 gate: sequencer_full_triangle
      *
      * Full integration: sequencer stages uniforms from DRAM descriptor,
      * then rasterizer iterates a tile using those uniforms, fragment shader
      * reads staged color values, tile buffer receives correct RGBZ.
      */
        scenario("sequencer_full_triangle") {
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

        scenario("multi_triangle_loop") {
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
                    buildDescriptorWithBbox(descAddr + 256, verts2, 0, 0, 4, 4)

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

    /** Sequencer → Flusher E2E: verify autonomous render produces correct
      * FP16 pixel writes to SDRAM.
      *
      * Setup: 1 triangle covering tile (0,0), with simple shaders that output
      * a known color. After the sequencer completes, verify the captured GPU
      * writes contain the expected FP16 RGBZ values at the correct addresses.
      */
        scenario("sequencer_flusher_e2e") {
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

        scenario("sequencer_rgba8_and_depth_advance_per_tile") {
        // An autonomous multi-tile render with a 32-bit colour attachment and a
        // depth attachment bound. Each flushed tile must write its colour as two
        // 32-byte halves at fbBase + 64*t and its depth at zbBase + 32*t.
        // The depth address used to stay at zbBase for every tile, so all
        // tiles' Z landed on the same 32 bytes -- this scenario pins that.
        println("\n=== BorgSequencerTests: sequencer_rgba8_and_depth_advance_per_tile ===")
        borg.reset.poke(true.B)
        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.io.gpuMem.ready.poke(false.B)
        borg.io.gpuMem.data.poke(0.U)
        borg.clock.step(4)
        borg.reset.poke(false.B)
        borg.clock.step(20)
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)

        val vertAddr  = 0x1000; val setupAddr = 0x3000; val descAddr  = 0x2000
        val rastAddr  = 0x4000; val fragAddr  = 0x5000
        val binBase   = 0x6000; val setupBase = 0x7000
        val fbBase    = 0x10000; val zbBase = 0x20000

        // 8x8 framebuffer (2x2 tiles); the triangle spans three of them.
        val verts = Seq(
          Seq(0.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(8.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(0.0f, 8.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
        )
        val vertShader = vertPassthroughShader()
        val setup      = setupShader()
        val rastShader = Seq(
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 0),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 1),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 2),
          BigInt(0))
        val fragShader = Seq(
          Instructions.ADD(rs1 = 7,  rs2 = 25, rd = 26, funct3 = 1),
          Instructions.ADD(rs1 = 10, rs2 = 25, rd = 27, funct3 = 1),
          Instructions.ADD(rs1 = 13, rs2 = 25, rd = 28, funct3 = 1),
          BigInt(0))
        val dram = scala.collection.mutable.Map[Int, BigInt]() ++= (
          vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
          setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
          rastShader.zipWithIndex.map { case (w,i) => (rastAddr + i*4) -> w }.toMap ++
          fragShader.zipWithIndex.map { case (w,i) => (fragAddr + i*4) -> w }.toMap ++
          buildDescriptorWithBbox(descAddr, verts, 0, 0, 8, 8))

        // Every write burst, as (base address, length).
        val bursts = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
        def service(): Unit = {
          if (borg.io.gpuMem.req.peek().litToBoolean) {
            val addr = borg.io.gpuMem.addr.peek().litValue.toInt
            borg.io.gpuMem.data.poke((dram.getOrElse(addr, BigInt(0)) & BigInt(0xFFFFFFFFL)).U)
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
          } else if (borg.io.gpuMem.wr.peek().litToBoolean) {
            val base = borg.io.gpuMem.addr.peek().litValue.toInt
            val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
            if (wlen == 16) bursts += ((base, wlen))
            for (_ <- 1 until wlen) {
              borg.io.gpuMem.waccept.poke(true.B)
              borg.io.gpuMem.ready.poke(false.B)
              borg.clock.step(1)
            }
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
          } else {
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(false.B)
          }
        }

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
        rawWrite(borg, BorgGpuRegs.seq_tiles_per_row_offset.litValue.toInt, 2)
        rawWrite(borg, BorgGpuRegs.seq_clear_lo_offset.litValue.toInt, 0x7BFF)
        rawWrite(borg, BorgGpuRegs.seq_clear_hi_offset.litValue.toInt, 0)
        rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, 64)
        rawWrite(borg, BorgGpuRegs.flush_width_offset.litValue.toInt, 3)
        rawWrite(borg, BorgGpuRegs.flush_format_offset.litValue.toInt, FlushFormat.RGBA8)
        rawWrite(borg, BorgGpuRegs.flush_zb_base_offset.litValue.toInt, zbBase)
        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

        var seqBusySeen = false
        var seqBusyCleared = false
        for (cycle <- 0 until 60000 if !seqBusyCleared) {
          service()
          borg.clock.step(1)
          if (cycle % 10 == 5) {
            borg.io.address.poke(BorgGpuRegs.status_offset)
            borg.io.data_read_n.poke(2.U)
            borg.io.data_write_n.poke(3.U)
            service(); borg.clock.step(1)
            val st = borg.io.data_out.peek().litValue
            borg.io.data_read_n.poke(3.U)
            service(); borg.clock.step(1)
            val busy = (st >> 5) & 1
            if (busy == 1) seqBusySeen = true
            if (seqBusySeen && busy == 0) seqBusyCleared = true
          }
        }
        // Leave the attachment registers as every other scenario expects them.
        rawWrite(borg, BorgGpuRegs.flush_format_offset.litValue.toInt, FlushFormat.RGB565)
        rawWrite(borg, BorgGpuRegs.flush_zb_base_offset.litValue.toInt, 0)
        Predef.assert(seqBusyCleared, "sequencer never completed")

        val flushBursts = bursts.filter(b => b._1 >= fbBase)
        println("  flush bursts: " + flushBursts.map(b => f"0x${b._1}%x").mkString(" "))
        Predef.assert(flushBursts.size % 3 == 0 && flushBursts.nonEmpty,
          s"expected colour, colour, depth per tile; got ${flushBursts.size} bursts")
        val tiles = flushBursts.grouped(3).map { g =>
          val t = (g(0)._1 - fbBase) / 64
          Predef.assert(g(0)._1 == fbBase + 64 * t, f"colour half 0 at 0x${g(0)._1}%x not 64-aligned")
          Predef.assert(g(1)._1 == fbBase + 64 * t + 32, f"colour half 1 at 0x${g(1)._1}%x")
          Predef.assert(g(2)._1 == zbBase + 32 * t,
            f"tile $t depth at 0x${g(2)._1}%x, expected 0x${zbBase + 32 * t}%x")
          t
        }.toSeq
        println(s"  tiles flushed: ${tiles.mkString(", ")}")
        Predef.assert(tiles.distinct.size >= 2, "need at least two distinct tiles to prove the offset advances")
        println("=== sequencer_rgba8_and_depth_advance_per_tile PASSED ===\n")
        }

        scenario("ztest_suppresses_stores_of_hidden_fragments") {
        // One 4x4 tile shaded twice through the real rasterizer ROM,
        // dispatcher, core and tile buffer (the MMIO pixel path: all-zero edge
        // constants make every pixel inside, each ITER write shades one).
        // Pass 1 is near (depth = colour = 0.25), pass 2 is far (0.75) and
        // therefore hidden everywhere. The fragment shader writes its depth,
        // then (optionally) ZTEST, then STOREs its colour. With the tests late
        // every hidden fragment's STORE lands -- exactly what SPIR-V
        // EarlyFragmentTests forbids. With ZTEST none may.
        println("\n=== BorgSequencerTests: ztest_suppresses_stores_of_hidden_fragments ===")
        val lsBase = 0x30000
        val nearBits = floatToBits(0.25f); val farBits = floatToBits(0.75f)

        def render(useZTest: Boolean): Seq[BigInt] = {
          resetAndWait(borg)
          def uniform(i: Int, v: BigInt): Unit =
            rawWrite(borg, BorgGpuRegs.uniform_offset.litValue.toInt + i * 4, v)
          for (i <- 0 until 12) uniform(i, 0)          // all-zero edges: every pixel inside
          val fragPc = 1
          val frag = Seq(
            Instructions.IXOR(rs1 = 25, rs2 = 25, rd = 25),                // r25 = 0 (index, no kill)
            Instructions.ADD(rs1 = 12, rs2 = 25, rd = 29, funct3 = 1),      // r29 = depth = u12
            Instructions.ADD(rs1 = 12, rs2 = 25, rd = 27, funct3 = 1),      // r27 = colour = u12
            if (useZTest) Instructions.ZTEST()
            else Instructions.IXOR(rs1 = 25, rs2 = 25, rd = 25),            // same length, no test
            Instructions.STORE(rs1 = 25, rs2 = 27),                         // mem[lsBase] = colour
            BigInt(0))
          for ((w, i) <- frag.zipWithIndex)
            rawWrite(borg, BorgGpuRegs.imem_offset.litValue.toInt + (fragPc + i) * 4, w)
          rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, fragPc)
          rawWrite(borg, BorgGpuRegs.ls_base_offset.litValue.toInt, lsBase)

          val stores = scala.collection.mutable.ArrayBuffer[BigInt]()
          def service(): Unit = {
            if (borg.io.gpuMem.req.peek().litToBoolean) {
              borg.io.gpuMem.data.poke(0.U)
              borg.io.gpuMem.waccept.poke(false.B)
              borg.io.gpuMem.ready.poke(true.B)
            } else if (borg.io.gpuMem.wr.peek().litToBoolean) {
              val base = borg.io.gpuMem.addr.peek().litValue.toInt
              val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
              val halves = scala.collection.mutable.ArrayBuffer(borg.io.gpuMem.wdata.peek().litValue & 0xFFFF)
              for (_ <- 1 until wlen) {
                borg.io.gpuMem.waccept.poke(true.B)
                borg.io.gpuMem.ready.poke(false.B)
                borg.clock.step(1)
                halves += borg.io.gpuMem.wdata.peek().litValue & 0xFFFF
              }
              borg.io.gpuMem.waccept.poke(false.B)
              borg.io.gpuMem.ready.poke(true.B)
              // A shader STORE of a 32-bit register is one 2-halfword write.
              if (base == lsBase && wlen == 2) stores += (halves(0) | (halves(1) << 16))
            } else {
              borg.io.gpuMem.waccept.poke(false.B)
              borg.io.gpuMem.ready.poke(false.B)
            }
          }
          def run(cycles: Int): Unit = for (_ <- 0 until cycles) { service(); borg.clock.step(1) }

          for (depth <- Seq(nearBits, farBits)) {
            uniform(12, depth)
            rawWrite(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, 0)   // tile (0,0)
            run(10)
            for (_ <- 0 until 16) {
              rawWrite(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)
              run(300)
            }
          }
          rawWrite(borg, BorgGpuRegs.ls_base_offset.litValue.toInt, 0)
          rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, 0)
          stores.toSeq
        }

        val late  = render(useZTest = false)
        val early = render(useZTest = true)
        def count(xs: Seq[BigInt], v: BigInt) = xs.count(_ == v)
        println(s"  late tests:  ${count(late, nearBits)} near stores, ${count(late, farBits)} hidden stores")
        println(s"  with ZTEST:  ${count(early, nearBits)} near stores, ${count(early, farBits)} hidden stores")
        Predef.assert(count(late, nearBits) == 16 && count(late, farBits) == 16,
          "control: every fragment of both passes should store when the tests are late")
        Predef.assert(count(early, nearBits) == 16,
          "ZTEST must not suppress the visible fragments' stores")
        Predef.assert(count(early, farBits) == 0,
          "hidden fragments executed STOREs despite failing ZTEST")
        println("=== ztest_suppresses_stores_of_hidden_fragments PASSED ===\n")
        }

        scenario("icache_runs_a_fragment_shader_longer_than_imem") {
        // A 106-word fragment shader -- longer than IMEM -- in DRAM, of which
        // the MMIO DMA preloads only 40 words at IMEM offset 1 (which also sets
        // CODE_BASE = source - 4). Each of 16 pixels, shaded through the real
        // rasterizer ROM, dispatcher and core, must run all of it: it adds 1
        // a hundred times and STOREs the sum. Every instruction past the
        // preload arrives through the core's miss path and Borg's gpuMem mux.
        println("\n=== BorgSequencerTests: icache_runs_a_fragment_shader_longer_than_imem ===")
        resetAndWait(borg)
        val lsBase = 0x30000; val fragAddr = 0x5000; val fragPc = 1
        val frag = Seq(
            Instructions.IXOR(rs1 = 25, rs2 = 25, rd = 25),               // r25 = 0 (index, no kill)
            Instructions.IXOR(rs1 = 5, rs2 = 5, rd = 5),                  // r5 = 0
            Instructions.IADD(rs1 = 12, rs2 = 25, rd = 6, funct3 = 1)) ++ // r6 = u12 = 1
          Seq.fill(100)(Instructions.IADD(rs1 = 5, rs2 = 6, rd = 5)) ++   // r5 += 1, x100
          Seq(Instructions.STORE(rs1 = 25, rs2 = 5), BigInt(0))
        Predef.assert(frag.length + fragPc > suiteCfg.maxInstructions)
        val dram = frag.zipWithIndex.map { case (w, i) => (fragAddr + i * 4) -> w }.toMap

        val stores = scala.collection.mutable.ArrayBuffer[BigInt]()
        def service(): Unit = {
          if (borg.io.gpuMem.req.peek().litToBoolean) {
            val a = borg.io.gpuMem.addr.peek().litValue.toInt
            borg.io.gpuMem.data.poke((dram.getOrElse(a, BigInt(0)) & BigInt(0xFFFFFFFFL)).U)
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
          } else if (borg.io.gpuMem.wr.peek().litToBoolean) {
            val base = borg.io.gpuMem.addr.peek().litValue.toInt
            val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
            val halves = scala.collection.mutable.ArrayBuffer(borg.io.gpuMem.wdata.peek().litValue & 0xFFFF)
            for (_ <- 1 until wlen) {
              borg.io.gpuMem.waccept.poke(true.B)
              borg.io.gpuMem.ready.poke(false.B)
              borg.clock.step(1)
              halves += borg.io.gpuMem.wdata.peek().litValue & 0xFFFF
            }
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
            if (base == lsBase && wlen == 2) stores += (halves(0) | (halves(1) << 16))
          } else {
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(false.B)
          }
        }
        def run(cycles: Int): Unit = for (_ <- 0 until cycles) { service(); borg.clock.step(1) }

        // Preload 40 words at IMEM offset 1 through the MMIO DMA.
        val preload = 40
        rawWrite(borg, BorgGpuRegs.dma_dram_offset.litValue.toInt, fragAddr)
        rawWrite(borg, BorgGpuRegs.dma_config_offset.litValue.toInt, 1 | (preload << 1) | (0 << 7) | (fragPc << 9))
        run(400)
        def uniform(i: Int, v: BigInt): Unit =
          rawWrite(borg, BorgGpuRegs.uniform_offset.litValue.toInt + i * 4, v)
        for (i <- 0 until 12) uniform(i, 0)          // all-zero edges: every pixel inside
        uniform(12, 1)
        rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, fragPc)
        rawWrite(borg, BorgGpuRegs.ls_base_offset.litValue.toInt, lsBase)
        rawWrite(borg, BorgGpuRegs.cmd_enqueue_offset.litValue.toInt, 0)
        run(10)
        for (_ <- 0 until 16) {
          rawWrite(borg, BorgGpuRegs.iter_offset.litValue.toInt, 1)
          run(3000)
        }
        rawWrite(borg, BorgGpuRegs.ls_base_offset.litValue.toInt, 0)
        rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, 0)
        println(s"  stores: ${stores.length} (expect 16), values: ${stores.distinct.mkString(",")} (expect 100)")
        Predef.assert(stores.length == 16, "every pixel must finish the long shader")
        Predef.assert(stores.forall(_ == 100), "a store saw the wrong sum: instructions were skipped or wrong")
        println("=== icache_runs_a_fragment_shader_longer_than_imem PASSED ===\n")
        }

        scenario("occlusion_query_counts_the_samples_of_the_triangle_window") {
        // Two identical triangles through a real sequencer render (binned in
        // pass 1, rasterized in pass 2). Depth compare ALWAYS, so both are
        // fully visible and each contributes the same N passing samples. The
        // triangle window must pick out exactly the triangles inside it:
        //   [0,1) -> N, [1,2) -> N, [0,2) -> 2N, counting disabled -> 0.
        println("\n=== BorgSequencerTests: occlusion_query_counts_the_samples_of_the_triangle_window ===")
        // Front-facing winding: binningSetupShader normalizes edges for it,
        // and the reset cull mode (back) then keeps the triangle.
        val verts = Seq(
          Seq(0.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(0.0f, 4.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(4.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f))

        def render(window: Option[(Int, Int)]): BigInt = {
          borg.reset.poke(true.B)
          borg.io.data_write_n.poke(3.U)
          borg.io.data_read_n.poke(3.U)
          borg.io.gpuMem.ready.poke(false.B)
          borg.io.gpuMem.data.poke(0.U)
          borg.clock.step(4)
          borg.reset.poke(false.B)
          borg.clock.step(20)
          rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)

          val vertAddr = 0x1000; val setupAddr = 0x3000; val descAddr = 0x2000
          val rastAddr = 0x4000; val fragAddr = 0x5000
          val binBase = 0x6000; val setupBase = 0x7000; val fbBase = 0x10000
          val vertShader = vertPassthroughShader()
          val setup = binningSetupShader()
          val rastShader = Seq(BigInt(0))
          val fragShader = Seq(
            Instructions.ADD(rs1 = 7,  rs2 = 25, rd = 26, funct3 = 1),
            Instructions.ADD(rs1 = 10, rs2 = 25, rd = 27, funct3 = 1),
            Instructions.ADD(rs1 = 13, rs2 = 25, rd = 28, funct3 = 1),
            Instructions.ADD(rs1 = 16, rs2 = 25, rd = 29, funct3 = 1),
            BigInt(0))
          val dram = scala.collection.mutable.Map[Int, BigInt]() ++= (
            vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
            setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
            rastShader.zipWithIndex.map { case (w,i) => (rastAddr + i*4) -> w }.toMap ++
            fragShader.zipWithIndex.map { case (w,i) => (fragAddr + i*4) -> w }.toMap ++
            buildDescriptorWithBbox(descAddr,       verts, 0, 0, 4, 4) ++
            buildDescriptorWithBbox(descAddr + 256, verts, 0, 0, 4, 4))
          // Writes before reads, as MemoryController and BorgLinkSlave do: the
          // setup store raises req AND wr together, and a service that looks
          // at req first answers it as a read and drops the write -- pass 2
          // then finds no bins and no setup data.
          def service(): Unit = {
            if (borg.io.gpuMem.wr.peek().litToBoolean) {
              val base = borg.io.gpuMem.addr.peek().litValue.toInt
              val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
              val halves = scala.collection.mutable.ArrayBuffer(borg.io.gpuMem.wdata.peek().litValue & 0xFFFF)
              for (_ <- 1 until wlen) {
                borg.io.gpuMem.waccept.poke(true.B)
                borg.io.gpuMem.ready.poke(false.B)
                borg.clock.step(1)
                halves += borg.io.gpuMem.wdata.peek().litValue & 0xFFFF
              }
              // Keep the binner's and setup store's writes: pass 2 reads them back.
              for ((h, i) <- halves.zipWithIndex) dram(base + 2 * i) = h
              if (wlen == 2) dram(base) = halves(0) | (halves(1) << 16)
              if (wlen == 1) dram(base) = halves(0)
              borg.io.gpuMem.waccept.poke(false.B)
              borg.io.gpuMem.ready.poke(true.B)
            } else if (borg.io.gpuMem.req.peek().litToBoolean) {
              val a = borg.io.gpuMem.addr.peek().litValue.toInt
              borg.io.gpuMem.data.poke((dram.getOrElse(a, BigInt(0)) & BigInt(0xFFFFFFFFL)).U)
              borg.io.gpuMem.waccept.poke(false.B)
              borg.io.gpuMem.ready.poke(true.B)
            } else {
              borg.io.gpuMem.waccept.poke(false.B)
              borg.io.gpuMem.ready.poke(false.B)
            }
          }

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
          rawWrite(borg, BorgGpuRegs.seq_bin_row_bytes_offset.litValue.toInt, 4)
          rawWrite(borg, BorgGpuRegs.seq_setup_base_offset.litValue.toInt, setupBase)
          rawWrite(borg, BorgGpuRegs.seq_fb_base_offset.litValue.toInt, fbBase)
          rawWrite(borg, BorgGpuRegs.seq_tiles_per_row_offset.litValue.toInt, 1)
          rawWrite(borg, BorgGpuRegs.seq_clear_lo_offset.litValue.toInt, 0x7BFF)
          rawWrite(borg, BorgGpuRegs.seq_clear_hi_offset.litValue.toInt, 0)
          rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, 1)
          rawWrite(borg, BorgGpuRegs.flush_width_offset.litValue.toInt, 2)
          rawWrite(borg, BorgGpuRegs.seq_inv_width_offset.litValue.toInt, floatToBits(1.0f))
          rawWrite(borg, BorgGpuRegs.depth_cfg_offset.litValue.toInt, 7 | (1 << 3))   // ALWAYS, write on
          window.foreach { case (f, l) =>
            rawWrite(borg, BorgGpuRegs.occ_tri_range_offset.litValue.toInt, f | (l << 16))
          }
          rawWrite(borg, BorgGpuRegs.occ_ctrl_offset.litValue.toInt, (if (window.isDefined) 1 else 0) | 2)
          rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 2)
          rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

          var seqBusySeen = false
          var seqBusyCleared = false
          for (cycle <- 0 until 60000 if !seqBusyCleared) {
            service()
            borg.clock.step(1)
            if (cycle % 10 == 5) {
              borg.io.address.poke(BorgGpuRegs.status_offset)
              borg.io.data_read_n.poke(2.U)
              borg.io.data_write_n.poke(3.U)
              service(); borg.clock.step(1)
              val st = borg.io.data_out.peek().litValue
              borg.io.data_read_n.poke(3.U)
              service(); borg.clock.step(1)
              val busy = (st >> 5) & 1
              if (busy == 1) seqBusySeen = true
              if (seqBusySeen && busy == 0) seqBusyCleared = true
            }
          }
          Predef.assert(seqBusyCleared, "sequencer never completed")
          val count = rawRead(borg, BorgGpuRegs.occ_count_offset.litValue.toInt)
          rawWrite(borg, BorgGpuRegs.occ_ctrl_offset.litValue.toInt, 0)
          rawWrite(borg, BorgGpuRegs.occ_tri_range_offset.litValue.toInt, BigInt(0xFFFF) << 16)
          rawWrite(borg, BorgGpuRegs.depth_cfg_offset.litValue.toInt, 1 | (1 << 3))
          count
        }

        val t0   = render(Some((0, 1)))
        val t1   = render(Some((1, 2)))
        val both = render(Some((0, 2)))
        val off  = render(None)
        println(s"  window [0,1): $t0  [1,2): $t1  [0,2): $both  disabled: $off")
        Predef.assert(t0 > 0, "no samples counted: the triangle was not rasterized")
        Predef.assert(t1 == t0, "identical triangles must count the same")
        Predef.assert(both == 2 * t0, "the window must add exactly the triangles inside it")
        Predef.assert(off == 0, "counting while disabled")
        println("=== occlusion_query_counts_the_samples_of_the_triangle_window PASSED ===\n")
        }

        scenario("attachments_store_and_load_across_renders") {
        // loadOp = LOAD and a stored stencil attachment, end to end: several
        // renders of the same triangle share one memory, and the occlusion
        // counter reports how many samples passed in each.
        //   render 1: store colour (red), depth and stencil (REPLACE 7)
        //   depth:    reload colour+depth, redraw at the same depth under LESS
        //             -> 0 pass, and the framebuffer comes out byte-identical;
        //             the same render cleared instead of loaded -> all pass
        //   stencil:  reload stencil, test EQUAL 7 -> all pass;
        //             cleared instead (0) -> none pass
        println("\n=== BorgSequencerTests: attachments_store_and_load_across_renders ===")
        val vertAddr = 0x1000; val setupAddr = 0x3000; val descAddr = 0x2000
        val rastAddr = 0x4000; val fragAddr = 0x5000
        val binBase = 0x6000; val setupBase = 0x7000
        val fbBase = 0x10000; val zbBase = 0x20000; val sbBase = 0x30000

        // Program and descriptor words (read only), and everything the GPU
        // writes, kept as halfwords the way the memory controller stores them.
        val rom = scala.collection.mutable.Map[Int, BigInt]()
        val half = scala.collection.mutable.Map[Int, Int]()
        def read32(a: Int): BigInt =
          if (half.contains(a) || half.contains(a + 2))
            BigInt(half.getOrElse(a, 0)) | (BigInt(half.getOrElse(a + 2, 0)) << 16)
          else rom.getOrElse(a, BigInt(0)) & BigInt(0xFFFFFFFFL)
        def service(): Unit = {
          if (borg.io.gpuMem.wr.peek().litToBoolean) {
            val base = borg.io.gpuMem.addr.peek().litValue.toInt
            val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
            half(base) = (borg.io.gpuMem.wdata.peek().litValue & 0xFFFF).toInt
            for (i <- 1 until wlen) {
              borg.io.gpuMem.waccept.poke(true.B)
              borg.io.gpuMem.ready.poke(false.B)
              borg.clock.step(1)
              half(base + 2 * i) = (borg.io.gpuMem.wdata.peek().litValue & 0xFFFF).toInt
            }
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
          } else if (borg.io.gpuMem.req.peek().litToBoolean) {
            borg.io.gpuMem.data.poke(read32(borg.io.gpuMem.addr.peek().litValue.toInt).U)
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
          } else {
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(false.B)
          }
        }

        val vertShader = vertPassthroughShader()
        val setup = binningSetupShader()
        val fragShader = Seq(
          Instructions.ADD(rs1 = 7,  rs2 = 25, rd = 26, funct3 = 1),
          Instructions.ADD(rs1 = 10, rs2 = 25, rd = 27, funct3 = 1),
          Instructions.ADD(rs1 = 13, rs2 = 25, rd = 28, funct3 = 1),
          Instructions.ADD(rs1 = 16, rs2 = 25, rd = 29, funct3 = 1),
          BigInt(0))
        rom ++= vertShader.zipWithIndex.map { case (w, i) => (vertAddr + i * 4) -> w }
        rom ++= setup.zipWithIndex.map      { case (w, i) => (setupAddr + i * 4) -> w }
        rom ++= Seq(rastAddr -> BigInt(0))
        rom ++= fragShader.zipWithIndex.map { case (w, i) => (fragAddr + i * 4) -> w }
        def triangle(r: Float, g: Float) = Seq(       // front-facing
          Seq(0.0f, 0.0f, 0.1f, r, g, 0.0f, 0.0f, 0.0f),
          Seq(0.0f, 4.0f, 0.1f, r, g, 0.0f, 0.0f, 0.0f),
          Seq(4.0f, 0.0f, 0.1f, r, g, 0.0f, 0.0f, 0.0f))

        val DEPTH_LESS = 1 | (1 << 3); val DEPTH_ALWAYS = 7 | (1 << 3)
        val STENCIL_WRITE_7 = 1 | (7 << 1) | (BorgStencil.REPLACE << 7)   // ALWAYS, pass: REPLACE
        val STENCIL_EQUAL_7 = 1 | (2 << 1)                                // EQUAL, keep
        val STENCIL_FACE_7  = 0xFF | (0xFF << 8) | (7 << 16)              // masks, reference 7

        // Clear colour R/G as FP16 (seq_clear_hi = {R, G}). Each render clears
        // to its own colour, so an uncovered pixel shows whose clear it got --
        // unless colour is loaded, in which case it keeps the stored value.
        def render(colour: (Float, Float), load: Int, depthCfg: Int, stencilCfg: Int,
                   bindStencil: Boolean, clearHi: Long = 0L, d32: Boolean = false): BigInt = {
          borg.reset.poke(true.B)
          borg.io.data_write_n.poke(3.U); borg.io.data_read_n.poke(3.U)
          borg.io.gpuMem.ready.poke(false.B); borg.io.gpuMem.data.poke(0.U)
          borg.clock.step(4); borg.reset.poke(false.B); borg.clock.step(20)
          rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)
          rom ++= buildDescriptorWithBbox(descAddr, triangle(colour._1, colour._2), 0, 0, 4, 4)
          def reg(r: UInt, v: BigInt): Unit = rawWrite(borg, r.litValue.toInt, v)
          reg(BorgGpuRegs.seq_desc_base_offset, descAddr)
          reg(BorgGpuRegs.seq_vert_addr_offset, vertAddr); reg(BorgGpuRegs.seq_vert_len_offset, vertShader.size)
          reg(BorgGpuRegs.seq_setup_addr_offset, setupAddr); reg(BorgGpuRegs.seq_setup_len_offset, setup.size)
          reg(BorgGpuRegs.seq_rast_addr_offset, rastAddr); reg(BorgGpuRegs.seq_rast_len_offset, 1)
          reg(BorgGpuRegs.seq_frag_addr_offset, fragAddr); reg(BorgGpuRegs.seq_frag_len_offset, fragShader.size)
          reg(BorgGpuRegs.seq_bin_base_offset, binBase); reg(BorgGpuRegs.seq_bin_row_bytes_offset, 4)
          reg(BorgGpuRegs.seq_setup_base_offset, setupBase)
          reg(BorgGpuRegs.seq_fb_base_offset, fbBase); reg(BorgGpuRegs.seq_tiles_per_row_offset, 1)
          reg(BorgGpuRegs.seq_clear_lo_offset, 0x7BFF); reg(BorgGpuRegs.seq_clear_hi_offset, clearHi)
          reg(BorgGpuRegs.frag_pc_offset, 1); reg(BorgGpuRegs.flush_width_offset, 2)
          reg(BorgGpuRegs.seq_inv_width_offset, floatToBits(1.0f))
          reg(BorgGpuRegs.flush_zb_base_offset, zbBase)
          reg(BorgGpuRegs.depth_format_offset, if (d32) 1 else 0)
          reg(BorgGpuRegs.flush_sb_base_offset, if (bindStencil) sbBase else 0)
          reg(BorgGpuRegs.depth_cfg_offset, depthCfg)
          reg(BorgGpuRegs.stencil_cfg_offset, stencilCfg)
          reg(BorgGpuRegs.stencil_front_offset, STENCIL_FACE_7)
          reg(BorgGpuRegs.tile_load_offset, load)
          reg(BorgGpuRegs.occ_ctrl_offset, 3)                     // clear + enable
          reg(BorgGpuRegs.seq_tri_count_offset, 1)
          reg(BorgGpuRegs.seq_trigger_offset, 1)
          var seqBusySeen = false; var seqBusyCleared = false
          for (cycle <- 0 until 60000 if !seqBusyCleared) {
            service(); borg.clock.step(1)
            if (cycle % 10 == 5) {
              borg.io.address.poke(BorgGpuRegs.status_offset)
              borg.io.data_read_n.poke(2.U); borg.io.data_write_n.poke(3.U)
              service(); borg.clock.step(1)
              val st = borg.io.data_out.peek().litValue
              borg.io.data_read_n.poke(3.U)
              service(); borg.clock.step(1)
              if (((st >> 5) & 1) == 1) seqBusySeen = true
              if (seqBusySeen && ((st >> 5) & 1) == 0) seqBusyCleared = true
            }
          }
          Predef.assert(seqBusyCleared, "sequencer never completed")
          val count = rawRead(borg, BorgGpuRegs.occ_count_offset.litValue.toInt)
          for ((r, v) <- Seq(BorgGpuRegs.occ_ctrl_offset -> 0, BorgGpuRegs.tile_load_offset -> 0,
                             BorgGpuRegs.flush_zb_base_offset -> 0, BorgGpuRegs.flush_sb_base_offset -> 0,
                             BorgGpuRegs.depth_cfg_offset -> DEPTH_LESS, BorgGpuRegs.stencil_cfg_offset -> 0))
            reg(r, v)
          count
        }
        def framebuffer: Seq[Int] = (0 until 16).map(i => half.getOrElse(fbBase + 2 * i, -1))

        val CLEAR_1 = 0x3C003800L; val CLEAR_2 = 0x00003C00L   // (1.0, 0.5) and (0, 1.0)
        val stored = render((1.0f, 0.0f), load = 0, DEPTH_LESS, STENCIL_WRITE_7, bindStencil = true, CLEAR_1)
        val fb1 = framebuffer
        println(s"  render 1 (store): $stored samples, framebuffer ${fb1.map(v => f"$v%04x").mkString(" ")}")
        Predef.assert(stored > 0, "the stored render drew nothing")

        // Stencil first: the depth renders below store a cleared stencil.
        val sLoaded  = render((0.0f, 1.0f), load = 4, DEPTH_ALWAYS, STENCIL_EQUAL_7, bindStencil = true)
        val sCleared = render((0.0f, 1.0f), load = 0, DEPTH_ALWAYS, STENCIL_EQUAL_7, bindStencil = false)
        println(s"  stencil EQUAL 7: loaded $sLoaded (expect $stored), cleared $sCleared (expect 0)")

        // Restore render 1's colour and depth, then the depth checks.
        render((1.0f, 0.0f), load = 0, DEPTH_LESS, 0, bindStencil = false, CLEAR_1)
        val zLoaded  = render((0.0f, 1.0f), load = 3, DEPTH_LESS, 0, bindStencil = false, CLEAR_2)
        val fb2 = framebuffer
        val zCleared = render((0.0f, 1.0f), load = 0, DEPTH_LESS, 0, bindStencil = false, CLEAR_2)
        val fb3 = framebuffer
        println(s"  depth LESS at equal depth: loaded $zLoaded (expect 0), cleared $zCleared (expect $stored)")
        println(s"  framebuffer after the loaded render ${if (fb2 == fb1) "unchanged" else "CHANGED: " + fb2.map(v => f"$v%04x").mkString(" ")}")
        Predef.assert(sLoaded == stored, "stencil was not loaded")
        Predef.assert(sCleared == 0, "stencil test passed on a cleared stencil")
        Predef.assert(zLoaded == 0, "depth was not loaded: the equal-depth redraw passed LESS")
        Predef.assert(zCleared == stored, "control: a cleared depth must let the redraw through")
        Predef.assert(fb1.exists(_ != 0) && fb1.distinct.length > 1,
          "render 1's framebuffer must mix clear and drawn pixels for this check to mean anything")
        Predef.assert(fb2 == fb1, "loaded colour did not survive a render that drew nothing")
        Predef.assert(fb3 != fb1, "control: a cleared render must not reproduce render 1's colours")

        // The same depth round trip through a D32_SFLOAT attachment.
        render((1.0f, 0.0f), load = 0, DEPTH_LESS, 0, bindStencil = false, CLEAR_1, d32 = true)
        val z32Loaded  = render((0.0f, 1.0f), load = 2, DEPTH_LESS, 0, bindStencil = false, CLEAR_2, d32 = true)
        val z32Cleared = render((0.0f, 1.0f), load = 0, DEPTH_LESS, 0, bindStencil = false, CLEAR_2, d32 = true)
        println(s"  D32_SFLOAT: loaded $z32Loaded (expect 0), cleared $z32Cleared (expect $stored)")
        Predef.assert(z32Loaded == 0, "D32 depth was not stored and loaded")
        Predef.assert(z32Cleared == stored, "control: a cleared D32 depth must let the redraw through")
        println("=== attachments_store_and_load_across_renders PASSED ===\n")
        }

        scenario("covDelta_diagnostic_real_values") {
        println("\n=== BorgSequencerTests: covDelta_diagnostic_real_values ===")

        borg.reset.poke(true.B)
        borg.io.data_write_n.poke(3.U)
        borg.io.data_read_n.poke(3.U)
        borg.io.gpuMem.ready.poke(false.B)
        borg.io.gpuMem.data.poke(0.U)
        borg.clock.step(4)
        borg.reset.poke(false.B)
        borg.clock.step(20)
        rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)

        val vertAddr  = 0x1000; val setupAddr = 0x3000; val descAddr  = 0x2000
        val rastAddr  = 0x4000; val fragAddr  = 0x5000
        val binBase   = 0x6000; val setupBase = 0x7000
        val fbBase    = 0x10000

        // Triangle: v0=(0,0), v1=(4,0), v2=(0,4) — same shape as sequencer_flusher_e2e.
        // Hand-computed (see investigation notes):
        //   X0=v0.x-v1.x=-4, Y0=v1.y-v0.y=0
        //   X1=v1.x-v2.x=4,  Y1=v2.y-v1.y=4
        //   X2=v2.x-v0.x=0,  Y2=v0.y-v2.y=-4
        // inv_width fed as 1.0 so the normalization step is a no-op.
        //   d0[0]=X0*-0.375+Y0*-0.125=1.5   d1[0]=X0*-0.125+Y0*0.375=0.5
        //   d0[1]=X1*-0.375+Y1*-0.125=-2.0  d1[1]=X1*-0.125+Y1*0.375=1.0
        //   d0[2]=X2*-0.375+Y2*-0.125=0.5   d1[2]=X2*-0.125+Y2*0.375=-1.5
        val verts = Seq(
          Seq(0.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(4.0f, 0.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
          Seq(0.0f, 4.0f, 0.1f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
        )
        val expected = Seq((1.5f, 0.5f), (-2.0f, 1.0f), (0.5f, -1.5f))

        val vertShader = vertPassthroughShader()

        // Ported 1:1 from software/borg/borg_driver.c's seq_setup_shader
        // (base edge computation + Step 50.2b delta block).
        val setup = binningSetupShader()

        val rastShader = Seq(
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 0),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 1),
          Instructions.ADD(rs1 = 7, rs2 = 6, rd = 2),
          BigInt(0)
        )
        val fragShader = Seq(
          Instructions.ADD(rs1 = 14, rs2 = 25, rd = 26, funct3 = 1),
          Instructions.ADD(rs1 = 7,  rs2 = 25, rd = 27, funct3 = 1),
          Instructions.ADD(rs1 = 10, rs2 = 25, rd = 28, funct3 = 1),
          Instructions.ADD(rs1 = 13, rs2 = 25, rd = 29, funct3 = 1),
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0),
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0),
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0),
          Instructions.ADD(rs1 = 0, rs2 = 0, rd = 0),
          BigInt(0)
        )

        val dramInit: Map[Int, BigInt] =
          vertShader.zipWithIndex.map { case (w,i) => (vertAddr + i*4) -> w }.toMap ++
          setup.zipWithIndex.map      { case (w,i) => (setupAddr + i*4) -> w }.toMap ++
          rastShader.zipWithIndex.map { case (w,i) => (rastAddr + i*4) -> w }.toMap ++
          fragShader.zipWithIndex.map { case (w,i) => (fragAddr + i*4) -> w }.toMap ++
          buildDescriptorWithBbox(descAddr, verts, 0, 0, 4, 4)

        val dram = scala.collection.mutable.Map[Int, BigInt]() ++= dramInit

        def serviceDramCapture(): Int = {
          if (borg.io.gpuMem.req.peek().litToBoolean) {
            val addr = borg.io.gpuMem.addr.peek().litValue.toInt
            val data = dram.getOrElse(addr, BigInt(0)) & BigInt(0xFFFFFFFFL)
            borg.io.gpuMem.data.poke(data.U)
            borg.io.gpuMem.waccept.poke(false.B)
            borg.io.gpuMem.ready.poke(true.B)
            1
          } else if (borg.io.gpuMem.wr.peek().litToBoolean) {
            val baseAddr = borg.io.gpuMem.addr.peek().litValue.toInt
            val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
            if (wlen > 1) {
              for (i <- 1 until wlen) {
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
        rawWrite(borg, BorgGpuRegs.seq_tiles_per_row_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_clear_lo_offset.litValue.toInt, 0x7BFF)
        rawWrite(borg, BorgGpuRegs.seq_clear_hi_offset.litValue.toInt, 0)
        rawWrite(borg, BorgGpuRegs.frag_pc_offset.litValue.toInt, 64)
        rawWrite(borg, BorgGpuRegs.flush_width_offset.litValue.toInt, 2)
        rawWrite(borg, BorgGpuRegs.seq_inv_width_offset.litValue.toInt, floatToBits(1.0f))

        rawWrite(borg, BorgGpuRegs.seq_tri_count_offset.litValue.toInt, 1)
        rawWrite(borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt, 1)

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
        Predef.assert(seqBusySeen, "sequencer never went busy")
        Predef.assert(seqBusyCleared, "sequencer never completed")

        val actual = (0 until 3).map { e =>
          val d0 = borg.io.covDeltaDebug.get(e)(0).peek().litValue
          val d1 = borg.io.covDeltaDebug.get(e)(1).peek().litValue
          (bitsToFloat(d0), bitsToFloat(d1))
        }
        for (e <- 0 until 3) {
          println(f"  edge$e: d0=${actual(e)._1}%.4f (expect ${expected(e)._1}%.4f)  " +
                  f"d1=${actual(e)._2}%.4f (expect ${expected(e)._2}%.4f)")
        }
        for (e <- 0 until 3) {
          Predef.assert(math.abs(actual(e)._1 - expected(e)._1) < 0.01f,
            s"edge $e d0 mismatch: got ${actual(e)._1}, expected ${expected(e)._1}")
          Predef.assert(math.abs(actual(e)._2 - expected(e)._2) < 0.01f,
            s"edge $e d1 mismatch: got ${actual(e)._2}, expected ${expected(e)._2}")
        }
        println("=== covDelta_diagnostic_real_values PASSED ===\n")
        }

        if (failures.nonEmpty) {
          val failedNames = failures.map(_._1).mkString(", ")
          println(s"\n${failures.size} of 7 scenarios FAILED:")
          failures.foreach { case (n, t) => println(s"  - $n: ${t.getMessage}") }
          // Chain the first failure as the cause so its original stack trace
          // survives; the message names every scenario that failed.
          // java.lang.AssertionError explicitly: `import utest._` puts
          // utest.AssertionError (msg, Seq[TestValue]) in scope otherwise.
          throw new java.lang.AssertionError(
            s"${failures.size} scenario(s) failed: $failedNames", failures.head._2)
        }
      }
    }
  }
}
