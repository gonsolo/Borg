// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator.{
  toTestableClock, toTestableSInt, toTestableUInt, toTestableBool,
  toTestableReset, toTestableEnum, toTestableRecord, toTestableVec, toTestableData
}
import utest._

/** Whole-Borg render scenarios: attachments, per-tile flushes, occlusion
  * windows, and the MMIO pixel path's early tests and instruction cache.
  * Renders go through the draw front end ([[BorgDrawTests.DrawRig]]).
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
    * them when BorgConfig.Default moved to FP32: uniforms and GPRs are
    * `cfg.totalBits` wide, and BorgDMA stages
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

  /** Several renders sharing one memory, as a GPU's attachments would across
    * render passes: a right-angle corner triangle (or `scene`) drawn through
    * [[BorgDrawTests.DrawRig]] with the attachment registers set, so a later
    * render loads what an earlier one flushed. Colour is RGB565, one flat
    * colour per render; depth is the corner's z. The occlusion counter
    * reports the samples that passed in each render. */
  class AttachmentRig(borg: BorgTestWrapper) {
    private val d = new BorgDrawTests.DrawRig(borg)
    val fbBase = d.fbBase; val zbBase = 0x40000; val sbBase = 0x30000
    /** Everything the GPU wrote, as halfwords. */
    def half: scala.collection.mutable.Map[Int, Int] = d.half

    /** The right-angle corner's legs, in pixels. 4 x 4 puts the hypotenuse
      * exactly through pixel corners; other sizes cut pixels, leaving them
      * partly covered at 4x MSAA. */
    var legs: (Float, Float) = (4.0f, 4.0f)
    /** A multi-triangle, multi-tile render instead of the corner: every entry
      * is three (x, y) screen positions, on a framebuffer `tilesPerRow` tiles
      * square. */
    var scene: Seq[Seq[(Double, Double)]] = Nil
    var tilesPerRow = 1
    /** OCC_TRI_RANGE: count only triangles first <= index < last. */
    var occRange: (Int, Int) = (0, 0xFFFF)

    val DEPTH_LESS = 1 | (1 << 3); val DEPTH_ALWAYS = 7 | (1 << 3)
    val STENCIL_WRITE_7 = 1 | (7 << 1) | (BorgStencil.REPLACE << 7)   // ALWAYS, pass: REPLACE
    val STENCIL_EQUAL_7 = 1 | (2 << 1)                                // EQUAL, keep
    val STENCIL_FACE_7  = 0xFF | (0xFF << 8) | (7 << 16)              // masks, reference 7

    /** Render with clear colour R/G (FP16, SEQ_CLEAR_HI = {R, G}) and the
      * triangle in colour `colour`. Each render clears to its own colour, so
      * an uncovered pixel shows whose clear it got -- unless colour is
      * loaded, in which case it keeps the stored value. The stencil ops
      * apply to both faces, so the winding does not matter. */
    def render(colour: (Float, Float), load: Int, depthCfg: Int, stencilCfg: Int,
               bindStencil: Boolean, clearHi: Long = 0L, d32: Boolean = false,
               attachMs: Boolean = false): BigInt = {
      import BorgDrawTests.at
      val (r, g) = (colour._1.toDouble, colour._2.toDouble)
      val tris = if (scene.nonEmpty) scene
                 else Seq(Seq((0.0, 0.0), (0.0, legs._2.toDouble), (legs._1.toDouble, 0.0)))
      d.window = (0, 0, tilesPerRow, tilesPerRow); d.pitch = tilesPerRow
      val bothFaces = stencilCfg | (((stencilCfg >> 1) & 0xFFF) << 13)
      val regs = BorgGpuRegs
      d.draw(tris.flatten.map { case (x, y) => at(x, y, 1.0, z = 0.1, r = r, g = g) },
             keep = true, depthCfg = depthCfg, occ = occRange,
             extra = Seq(regs.flush_format_offset -> BigInt(FlushFormat.RGB565),
                         regs.seq_clear_hi_offset -> BigInt(clearHi),
                         regs.flush_zb_base_offset -> BigInt(zbBase),
                         regs.depth_format_offset -> BigInt(if (d32) 1 else 0),
                         regs.attach_ms_offset -> BigInt(if (attachMs) 1 else 0),
                         regs.flush_sb_base_offset -> BigInt(if (bindStencil) sbBase else 0),
                         regs.stencil_cfg_offset -> BigInt(bothFaces),
                         regs.stencil_front_offset -> BigInt(STENCIL_FACE_7),
                         regs.stencil_back_offset -> BigInt(STENCIL_FACE_7),
                         regs.tile_load_offset -> BigInt(load)))
    }
    def framebuffer: Seq[Int] = (0 until 16).map(i => half.getOrElse(fbBase + 2 * i, -1))
  }

  val tests = Tests {

    // ONE simulate() call for all scenarios. BorgTestWrapper is the entire
    // Borg design, and chisel3.simulator rebuilds it from scratch on every
    // simulate() call -- far longer than the few seconds each scenario
    // simulates. Every scenario resets the DUT as its first act, and a
    // failure still names the scenario it came from.
    utest.test("sequencer scenarios (one shared BorgTestWrapper build)") {
      simulate(new BorgTestWrapper(suiteCfg)) { borg =>
        val failures = scala.collection.mutable.ArrayBuffer[(String, Throwable)]()
        var scenarios = 0

        def scenario(name: String)(body: => Unit): Unit = {
          scenarios += 1
          println(s"\n=== scenario: $name ===")
          try { body; println(s"  [pass] $name") }
          catch {
            case t: Throwable =>
              failures += ((name, t))
              println(s"  [FAIL] $name: ${t.getMessage}")
          }
        }

        scenario("rgba8_and_depth_advance_per_tile") {
        // A multi-tile draw with a 32-bit colour attachment and a depth
        // attachment bound. Each tile must flush its colour at fbBase + 64*t
        // and its depth at zbBase + 32*t. The depth address used to stay at
        // zbBase for every tile, so all tiles' Z landed on the same 32 bytes.
        import BorgDrawTests.at
        val d = new BorgDrawTests.DrawRig(borg)
        val zbBase = 0x40000
        // 8x8 framebuffer (2x2 tiles); the triangle covers three of them, at
        // a depth that differs per tile.
        d.draw(Seq(at(0, 0, 1, z = 0.2), at(8, 0, 1, z = 0.4), at(0, 8, 1, z = 0.6)),
               extra = Seq(BorgGpuRegs.flush_zb_base_offset -> BigInt(zbBase)))
        def depthTile(t: Int) = (0 until 16).map(i => d.half.getOrElse(zbBase + 32 * t + 2 * i, -1))
        def colourTile(t: Int) = (0 until 32).map(i => d.half.getOrElse(d.fbBase + 64 * t + 2 * i, -1))
        val tiles = (0 until 4).map(t => (depthTile(t), colourTile(t)))
        for (((z, c), t) <- tiles.zipWithIndex)
          println(s"  tile $t: depth ${if (z.contains(-1)) "MISSING" else z.distinct.size + " distinct"}, " +
                  s"colour ${if (c.contains(-1)) "MISSING" else "written"}")
        Predef.assert(tiles.forall { case (z, c) => !z.contains(-1) && !c.contains(-1) },
          "every tile must flush its own colour and depth")
        val covered = tiles.take(3).map(_._1)
        Predef.assert(covered.distinct.size == 3,
          "the covered tiles' depths must differ: a depth address that does not advance repeats one tile")
        println("=== rgba8_and_depth_advance_per_tile PASSED ===\n")
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
        // Two identical triangles in one draw (binned in pass 1, rasterized in
        // pass 2). Depth compare ALWAYS, so both are fully visible and each
        // contributes the same N passing samples. The triangle window must
        // pick out exactly the triangles inside it:
        //   [0,1) -> N, [1,2) -> N, [0,2) -> 2N, counting disabled -> 0.
        import BorgDrawTests.at
        val d = new BorgDrawTests.DrawRig(borg)
        val tri = Seq(at(0, 0, 1), at(0, 4, 1), at(4, 0, 1))
        def render(window: (Int, Int), enable: Boolean = true): BigInt =
          d.draw(tri ++ tri, occ = window,
                 extra = if (enable) Nil else Seq(BorgGpuRegs.occ_ctrl_offset -> BigInt(2)))
        val t0   = render((0, 1))
        val t1   = render((1, 2))
        val both = render((0, 2))
        val off  = render((0, 2), enable = false)
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
        val rig = new AttachmentRig(borg)
        import rig._
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

        if (failures.nonEmpty) {
          val failedNames = failures.map(_._1).mkString(", ")
          println(s"\n${failures.size} of $scenarios scenarios FAILED:")
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
