// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** The draw front end end to end (docs/B1_geometry_front_end.md): draws
  * walked by hardware, a vertex shader that pulls its own vertices, the setup
  * and raster ROMs, and a fragment shader interpolating varyings with FATTR
  * and the perspective-correct barycentrics -- rendered by the whole Borg and
  * checked against a double-precision reference. */
object BorgDrawTests extends TestSuite {
  import BorgSequencerTests.{rawWrite, rawRead, suiteCfg}

  val Size = 8                                   // 8x8 framebuffer, 2x2 tiles
  val offsets = Seq((-0.125, -0.375), (0.375, -0.125), (-0.375, 0.125), (0.125, 0.375))

  /** A vertex: clip-space position and two varyings. */
  case class V(x: Double, y: Double, z: Double, w: Double, r: Double, g: Double)
  /** A vertex from its screen position (pixels), w, depth z_ndc and varyings. */
  def at(sx: Double, sy: Double, w: Double, z: Double = 0.5, r: Double = 0, g: Double = 0) =
    V((sx / (Size / 2.0) - 1) * w, (sy / (Size / 2.0) - 1) * w, z * w, w, r, g)

  /** Reference coverage and interpolation: planes E = M^-1 (px, py, 1). */
  def planes(t: Seq[V]): (Seq[Seq[Double]], Double) = {
    val h = Size / 2.0
    val v = t.map(c => Seq(c.x * h + c.w * h, c.y * h + c.w * h, c.w))
    def cross(a: Seq[Double], b: Seq[Double]) =
      Seq(a(1) * b(2) - a(2) * b(1), a(2) * b(0) - a(0) * b(2), a(0) * b(1) - a(1) * b(0))
    val rows = Seq(cross(v(1), v(2)), cross(v(2), v(0)), cross(v(0), v(1)))
    val det = (0 until 3).map(i => v(0)(i) * rows(0)(i)).sum
    (rows.map(_.map(_ / det)), det)
  }
  def eval(p: Seq[Double], x: Double, y: Double) = p(0) * x + p(1) * y + p(2)
  /** Is sample (x, y) inside: three edges and the near/far planes. */
  def inside(t: Seq[V], x: Double, y: Double): Boolean = {
    val (e, _) = planes(t)
    val ev = e.map(eval(_, x, y))
    val z = (0 until 3).map(k => ev(k) * t(k).z).sum
    ev.forall(_ >= 0) && z >= 0 && z <= 1
  }
  def samplesCovered(t: Seq[V]): Int =
    (for (py <- 0 until Size; px <- 0 until Size; (ox, oy) <- offsets
          if inside(t, px + 0.5 + ox, py + 0.5 + oy)) yield 1).sum

  class DrawRig(val borg: BorgTestWrapper) {
    val vsAddr = 0x1000; val fsAddr = 0x5000; val binBase = 0x6000
    val vb = 0x9000; val ib = 0xA000; val vsConst = 0xB000; val fsConst = 0xB100
    val setupBase = 0x20000; var fbBase = 0x10000
    val texDesc = 0xB200; val sampDesc = 0xB400
    // Render window (tiles) and viewport; the defaults are the 8x8 screen.
    var window = (0, 0, Size / 4, Size / 4)          // origin x, y; width, height
    var pitch = Size / 4                             // framebuffer tiles per row
    var viewport = (Size / 2.0, Size / 2.0, Size / 2.0, Size / 2.0)
    val rom = scala.collection.mutable.Map[Int, BigInt]()
    val half = scala.collection.mutable.Map[Int, Int]()
    def f32(d: Double): BigInt = BigInt(java.lang.Float.floatToRawIntBits(d.toFloat)) & BigInt(0xFFFFFFFFL)
    def read32(a: Int): BigInt =
      if (half.contains(a) || half.contains(a + 2))
        BigInt(half.getOrElse(a, 0)) | (BigInt(half.getOrElse(a + 2, 0)) << 16)
      else rom.getOrElse(a, BigInt(0))
    def service(): Unit = {
      if (borg.io.gpuMem.wr.peek().litToBoolean) {
        val base = borg.io.gpuMem.addr.peek().litValue.toInt
        val wlen = borg.io.gpuMem.wlen.peek().litValue.toInt
        // One halfword per beat: a 32-bit word (SOUT, STORE, the walker's
        // meta store) is a two-beat burst, low half first.
        half(base) = (borg.io.gpuMem.wdata.peek().litValue & 0xFFFF).toInt
        for (i <- 1 until wlen) {
          borg.io.gpuMem.waccept.poke(true.B); borg.io.gpuMem.ready.poke(false.B)
          borg.clock.step(1)
          half(base + 2 * i) = (borg.io.gpuMem.wdata.peek().litValue & 0xFFFF).toInt
        }
        borg.io.gpuMem.waccept.poke(false.B); borg.io.gpuMem.ready.poke(true.B)
      } else if (borg.io.gpuMem.req.peek().litToBoolean) {
        borg.io.gpuMem.data.poke((read32(borg.io.gpuMem.addr.peek().litValue.toInt) & BigInt(0xFFFFFFFFL)).U)
        borg.io.gpuMem.waccept.poke(false.B); borg.io.gpuMem.ready.poke(true.B)
      } else {
        borg.io.gpuMem.waccept.poke(false.B); borg.io.gpuMem.ready.poke(false.B)
      }
    }

    import Instructions._
    // Vertex pulling: vertex i is six words (X, Y, Z, W, r, g) at
    // u25 + 6*i; u26 = 6 and u27 = 1 are integers. Varyings r and g are
    // output components 0 and 1.
    val vs = Seq(
      IMUL(rs1 = 30, rs2 = 26, rd = 9, funct3 = 2),
      IADD(rs1 = 9, rs2 = 25, rd = 9, funct3 = 2)) ++
      (0 until 6).flatMap { c =>
        val dst = if (c < 4) c else 10 + c - 4
        Seq(LOAD(rs1 = 9, rd = dst)) ++ (if (c < 5) Seq(IADD(rs1 = 9, rs2 = 27, rd = 9, funct3 = 2)) else Nil)
      } ++ Seq(SOUT(rs2 = 10, index = 0), SOUT(rs2 = 11, index = 1), BigInt(0))
    // v = l0*a0 + l1*a1 + l2*a2 for r and g; blue is 0 (u20 = 0.0, the
    // constant window's first word).
    val fs = Seq(
      FATTR(rd = 10, index = 0),
      MUL(rs1 = 5, rs2 = 10, rd = 26), FMA(rs1 = 6, rs2 = 11, rs3 = 26, rd = 26), FMA(rs1 = 7, rs2 = 12, rs3 = 26, rd = 26),
      FATTR(rd = 10, index = 1),
      MUL(rs1 = 5, rs2 = 10, rd = 27), FMA(rs1 = 6, rs2 = 11, rs3 = 27, rd = 27), FMA(rs1 = 7, rs2 = 12, rs3 = 27, rd = 27),
      MUL(rs1 = 5, rs2 = 20, rd = 28, funct3 = 2),
      BigInt(0))
    rom ++= vs.zipWithIndex.map { case (w, i) => (vsAddr + 4 * i) -> w }
    rom ++= fs.zipWithIndex.map { case (w, i) => (fsAddr + 4 * i) -> w }
    rom ++= Seq(vsConst -> BigInt(vb / 4), (vsConst + 4) -> BigInt(6), (vsConst + 8) -> BigInt(1))
    rom ++= Seq(fsConst -> BigInt(0))

    /** Render; returns the occlusion count. topology 0/1/2, indexType 0/1/2.
      * `frag` replaces the fragment shader; `keep` keeps what earlier
      * renders wrote to memory (a render that samples a previous one). */
    def draw(verts: Seq[V], topology: Int = 0, indices: Seq[Int] = Nil, restart: Boolean = false,
             instances: Int = 1, count: Int = -1, frag: Seq[BigInt] = fs, keep: Boolean = false,
             sampleMaskCfg: Int = 0xF, depthCfg: Int = 7 | (1 << 3), occ: (Int, Int) = (0, 0xFFFF),
             extra: Seq[(UInt, BigInt)] = Nil, recordShift: Int = 8): BigInt = {
      rom ++= frag.zipWithIndex.map { case (w, i) => (fsAddr + 4 * i) -> w }
      borg.reset.poke(true.B)
      borg.io.data_write_n.poke(3.U); borg.io.data_read_n.poke(3.U)
      borg.io.gpuMem.ready.poke(false.B); borg.io.gpuMem.data.poke(0.U)
      borg.clock.step(4); borg.reset.poke(false.B); borg.clock.step(20)
      if (!keep) half.clear()
      for ((v, i) <- verts.zipWithIndex; (c, j) <- Seq(v.x, v.y, v.z, v.w, v.r, v.g).zipWithIndex)
        rom(vb + 4 * (6 * i + j)) = f32(c)
      for ((ix, i) <- indices.zipWithIndex) {               // 16-bit indices
        val w = ib + 4 * (i / 2); val old = rom.getOrElse(w, BigInt(0))
        rom(w) = if (i % 2 == 0) (old & BigInt(0xFFFF0000L)) | ix else (old & 0xFFFF) | (BigInt(ix) << 16)
      }
      def reg(r: UInt, v: BigInt): Unit = rawWrite(borg, r.litValue.toInt, v)
      rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)
      reg(BorgGpuRegs.seq_vert_addr_offset, vsAddr); reg(BorgGpuRegs.seq_vert_len_offset, vs.size)
      reg(BorgGpuRegs.seq_frag_addr_offset, fsAddr); reg(BorgGpuRegs.seq_frag_len_offset, math.min(frag.size, 60))
      reg(BorgGpuRegs.seq_bin_base_offset, binBase); reg(BorgGpuRegs.seq_bin_row_bytes_offset, 32)
      reg(BorgGpuRegs.seq_setup_base_offset, setupBase)
      reg(BorgGpuRegs.seq_fb_base_offset, fbBase); reg(BorgGpuRegs.seq_tiles_per_row_offset, window._3)
      reg(BorgGpuRegs.seq_tile_rows_offset, window._4); reg(BorgGpuRegs.fb_pitch_offset, pitch)
      reg(BorgGpuRegs.fb_origin_offset, BigInt(window._1) | (BigInt(window._2) << 16))
      reg(BorgGpuRegs.seq_clear_lo_offset, 0x7BFF); reg(BorgGpuRegs.seq_clear_hi_offset, 0)
      reg(BorgGpuRegs.frag_pc_offset, 1)
      reg(BorgGpuRegs.flush_format_offset, FlushFormat.RGBA8)
      reg(BorgGpuRegs.depth_cfg_offset, depthCfg)          // default ALWAYS, writes on
      reg(BorgGpuRegs.occ_tri_range_offset, BigInt(occ._1) | (BigInt(occ._2) << 16))
      reg(BorgGpuRegs.cull_cfg_offset, 0)                  // cull nothing
      reg(BorgGpuRegs.occ_ctrl_offset, 3)
      val indexType = if (indices.isEmpty) 0 else 1
      reg(BorgGpuRegs.draw_cfg_offset, 1 | (topology << 1) | (indexType << 3) |
                                        ((if (restart) 1 else 0) << 5) | (recordShift << 6))
      reg(BorgGpuRegs.draw_vertex_count_offset, if (count >= 0) count else if (indices.nonEmpty) indices.size else verts.size)
      reg(BorgGpuRegs.draw_instance_count_offset, instances)
      reg(BorgGpuRegs.draw_first_vertex_offset, 0); reg(BorgGpuRegs.draw_first_instance_offset, 0)
      reg(BorgGpuRegs.draw_vertex_offset_offset, 0); reg(BorgGpuRegs.draw_index_base_offset, ib)
      for ((r, v) <- Seq(BorgGpuRegs.viewport_sx_offset -> viewport._1, BorgGpuRegs.viewport_sy_offset -> viewport._2,
                         BorgGpuRegs.viewport_ox_offset -> viewport._3, BorgGpuRegs.viewport_oy_offset -> viewport._4,
                         BorgGpuRegs.depth_scale_offset -> 1.0, BorgGpuRegs.depth_offset_offset -> 0.0))
        reg(r, f32(v))
      reg(BorgGpuRegs.draw_vs_const_offset, vsConst); reg(BorgGpuRegs.draw_fs_const_offset, fsConst)
      reg(BorgGpuRegs.tex_desc_base_offset, texDesc); reg(BorgGpuRegs.sampler_desc_base_offset, sampDesc)
      reg(BorgGpuRegs.sample_mask_cfg_offset, sampleMaskCfg)
      for ((r, v) <- extra) reg(r, v)
      reg(BorgGpuRegs.seq_trigger_offset, 1)
      var seen = false; var cleared = false
      for (cycle <- 0 until 400000 if !cleared) {
        service(); borg.clock.step(1)
        if (cycle % 10 == 5) {
          borg.io.address.poke(BorgGpuRegs.status_offset)
          borg.io.data_read_n.poke(2.U); borg.io.data_write_n.poke(3.U)
          service(); borg.clock.step(1)
          val st = borg.io.data_out.peek().litValue
          borg.io.data_read_n.poke(3.U)
          service(); borg.clock.step(1)
          if (((st >> 5) & 1) == 1) seen = true
          if (seen && ((st >> 5) & 1) == 0) cleared = true
        }
      }
      Predef.assert(cleared, "render never completed")
      rawRead(borg, BorgGpuRegs.occ_count_offset.litValue.toInt)
    }
    /** Pixel (x, y)'s RGBA8 colour. */
    def pixel(x: Int, y: Int): (Int, Int, Int) = {
      val tile = (y / 4) * pitch + x / 4
      val a = fbBase + 64 * tile + 4 * ((y % 4) * 4 + x % 4)
      val lo = half.getOrElse(a, -1); val hi = half.getOrElse(a + 2, -1)
      (lo & 0xFF, (lo >> 8) & 0xFF, hi & 0xFF)
    }
  }

  /** A 2x2 quad build, whose vertex shader shades the three corners at once,
    * and the Wafer sizing: one uniform page, multi-pass MSAA, split FMA. */
  val quadCfg  = suiteCfg.copy(fragLanes = 4)
  val waferCfg = suiteCfg.copy(fragLanes = 4, maxUniforms = 32, tileColorBits = 8,
                               msaaMultiPass = true, fmaStages = 4)

  def run(name: String, cfg: BorgConfig = suiteCfg)(body: DrawRig => Unit): Unit =
    simulate(new BorgTestWrapper(cfg)) { borg =>
      println(s"\n--- BorgDrawTests: $name ---")
      body(new DrawRig(borg))
      println("  PASSED")
    }

  /** Varyings that vary with w: screen-linear interpolation is far off. */
  def perspective(rig: DrawRig): Unit = {
    val t = Seq(at(0, 0, 1.0, r = 1), at(8, 0, 4.0, g = 1), at(0, 8, 2.0))
    val count = rig.draw(t)
    val expect = samplesCovered(t)
    println(s"  $count samples covered (expect $expect)")
    utest.assert(count == expect)
    val (e, _) = planes(t)
    var checked = 0; var worst = 0
    for (y <- 0 until Size; x <- 0 until Size
         if offsets.forall { case (ox, oy) => inside(t, x + 0.5 + ox, y + 0.5 + oy) }) {
      val ev = e.map(eval(_, x + 0.5, y + 0.5)); val q = ev.sum
      val (r, g) = (ev(0) / q, ev(1) / q)
      val (hr, hg, hb) = rig.pixel(x, y)
      val err = Seq(math.abs(hr - r * 255), math.abs(hg - g * 255)).max
      worst = math.max(worst, err.ceil.toInt)
      Predef.assert(err <= 2.0 && hb == 0, f"pixel ($x,$y): ($hr,$hg,$hb) vs (${r * 255}%.1f, ${g * 255}%.1f, 0)")
      checked += 1
    }
    println(s"  $checked fully covered pixels match the perspective-correct reference (worst ${worst}/255)")
    utest.assert(checked >= 8)
  }

  /** Both depth planes cut a triangle; corners behind the eye. */
  def nearPlane(rig: DrawRig): Unit = {
    // z_ndc from -0.6 to 1.2 across the triangle.
    val zCut = Seq(at(0, 0, 1.0, z = -0.6), at(8, 0, 1.0, z = 0.3), at(0, 8, 1.5, z = 1.2))
    val c1 = rig.draw(zCut); val e1 = samplesCovered(zCut)
    println(s"  depth-clipped: $c1 samples (expect $e1 of ${samplesCovered(zCut.map(_.copy(z = 0.5)))})")
    utest.assert(c1 == e1)
    // One corner behind the eye (w < 0).
    val behind = Seq(V(-0.5, -0.5, 0.2, 1.0, 0, 0), V(0.6, -0.4, 0.3, 1.2, 0, 0), V(0.2, 1.0, -0.2, -0.5, 0, 0))
    val c2 = rig.draw(behind); val e2 = samplesCovered(behind)
    println(s"  corner behind the eye: $c2 samples (expect $e2)")
    utest.assert(c2 == e2 && e2 > 0)
    // Every corner behind the eye: nothing.
    val allBehind = behind.map(v => v.copy(w = -math.abs(v.w) - 0.1))
    utest.assert(rig.draw(allBehind) == 0)
  }

  /** The 8x8 quad, split on its (0,0)-(8,8) diagonal, which no sample lies
    * on: every pixel's four samples exactly once, whatever the topology. */
  def topologies(rig: DrawRig): Unit = {
    val quad = Seq(at(8, 0, 1), at(0, 0, 1), at(8, 8, 1), at(0, 8, 1))
    val full = Size * Size * 4
    val list  = rig.draw(Seq(quad(0), quad(1), quad(2), quad(2), quad(1), quad(3)))
    val strip = rig.draw(quad, topology = 1)
    val fan   = rig.draw(Seq(quad(1), quad(0), quad(2), quad(3)), topology = 2)
    val restartStrip = rig.draw(quad, topology = 1, indices = Seq(0, 1, 2, 0xFFFF, 2, 1, 3), restart = true)
    val instanced = rig.draw(quad, topology = 1, instances = 2)
    println(s"  list $list, strip $strip, fan $fan, restart $restartStrip, 2 instances $instanced (expect $full, x2 for instances)")
    utest.assert(list == full && strip == full && fan == full && restartStrip == full)
    utest.assert(instanced == 2 * full)
  }

  /** Render to a texture, then sample it: the second draw's fragment shader
    * reads the first draw's framebuffer through TEX (R8G8B8A8_UNORM in the
    * flusher's 4x4-tiled layout, nearest, at texel centres), so the second
    * framebuffer must equal the first exactly. */
  def renderToTexture(rig: DrawRig): Unit = {
    import Instructions._
    val fbA = 0x10000; val fbB = 0x14000
    rig.fbBase = fbA
    val t = Seq(at(0, 0, 1.0, r = 1), at(8, 0, 4.0, g = 1), at(0, 8, 2.0))
    rig.draw(t)
    val a = for (y <- 0 until Size; x <- 0 until Size) yield rig.pixel(x, y)
    // The texture descriptor: fbA, 8x8, R8G8B8A8_UNORM, tiled, 2D; nearest sampler.
    val fm = TexFormat.byName("R8G8B8A8_UNORM")
    val desc = SamplerCtl.descHeader(fbA, Size, Size, 1, 1, fm.code, SamplerCtl.Tiled, SamplerCtl.T2D, 0).map(BigInt(_)) ++ Seq.fill(12)(BigInt(0))
    for ((w, i) <- desc.zipWithIndex) rig.rom(rig.texDesc + 4 * i) = w
    for ((w, i) <- Seq[BigInt](2 << 3 | 2 << 6, 0, 0, 0).zipWithIndex) rig.rom(rig.sampDesc + 4 * i) = w
    // u = varying 0, v = varying 1, both screen / 8: texel centres at pixel centres.
    rig.rom(rig.fsConst + 4) = BigInt(SamplerCtl.LodExplicit) << 21         // u21: TEX control word
    val fs2 = Seq(
      FATTR(rd = 10, index = 0),
      MUL(rs1 = 5, rs2 = 10, rd = 13), FMA(rs1 = 6, rs2 = 11, rs3 = 13, rd = 13), FMA(rs1 = 7, rs2 = 12, rs3 = 13, rd = 13),
      FATTR(rd = 10, index = 1),
      MUL(rs1 = 5, rs2 = 10, rd = 14), FMA(rs1 = 6, rs2 = 11, rs3 = 14, rd = 14), FMA(rs1 = 7, rs2 = 12, rs3 = 14, rd = 14),
      TEX(rd = 20, rs1 = 13, rs2 = 14, rs3 = 21, funct3 = 3),                 // rs3 from the constant window
      ADD(rs1 = 20, rs2 = 20, rd = 26, funct3 = 2), ADD(rs1 = 21, rs2 = 20, rd = 27, funct3 = 2),
      ADD(rs1 = 22, rs2 = 20, rd = 28, funct3 = 2), ADD(rs1 = 23, rs2 = 20, rd = 24, funct3 = 2),
      BigInt(0))
    rig.fbBase = fbB
    val quad = Seq(at(8, 0, 1, r = 1, g = 0), at(0, 0, 1, r = 0, g = 0), at(8, 8, 1, r = 1, g = 1), at(0, 8, 1, r = 0, g = 1))
    rig.draw(quad, topology = 1, frag = fs2, keep = true)
    val b = for (y <- 0 until Size; x <- 0 until Size) yield rig.pixel(x, y)
    val diff = a.zip(b).count { case (p, q) => p != q }
    for (((p, q), i) <- a.zip(b).zipWithIndex if p != q) println(s"  pixel (${i % Size},${i / Size}): rendered $p, sampled $q")
    println(s"  sampled render target: ${Size * Size - diff} of ${Size * Size} pixels identical")
    utest.assert(diff == 0)
    rig.fbBase = 0x10000
  }

  /** Shared edges are covered exactly once: every sample of the 8x8
    * screen counted once, not twice, never zero times. */
  def watertight(rig: DrawRig): Unit = {
    val full = Size * Size * 4
    // An edge exactly through a sample: x - y = 0.5 holds the standard
    // offset (0.375, -0.125) of every pixel on the diagonal.
    val p0 = at(-8, -8.5, 1); val p1 = at(16, 15.5, 1)
    val split = rig.draw(Seq(p0, p1, at(16, -8.5, 1), p0, at(-8, 16, 1), p1))
    // A fan of random perspective triangles around a random centre, whose
    // rim encloses the screen: every sample lies in exactly one.
    val rnd = new scala.util.Random(3)
    val centre = at(1 + 6 * rnd.nextFloat(), 1 + 6 * rnd.nextFloat(), 0.5 + rnd.nextFloat())
    val angles = (0 until 12).map(i => (i + 0.2 + 0.6 * rnd.nextDouble()) * 2 * math.Pi / 12)
    val rim = angles.map(a => at(4 + 16 * math.cos(a), 4 + 16 * math.sin(a), 0.5 + 2 * rnd.nextDouble()))
    val fan = rig.draw(centre +: rim :+ rim.head, topology = 2)
    println(s"  edge through samples: $split, 12-triangle perspective fan: $fan (each expect $full)")
    utest.assert(split == full && fan == full)
  }

  /** The sample mask test, alpha to coverage, the shader's sample mask and
    * SMASK, counted with the occlusion query. */
  def masks(rig: DrawRig): Unit = {
    import Instructions._
    val quad = Seq(at(8, 0, 1), at(0, 0, 1), at(8, 8, 1), at(0, 8, 1))
    val tri = Seq(at(0, 0, 1.0, r = 1), at(8, 0, 4.0, g = 1), at(0, 8, 2.0))
    def samplesIn(t: Seq[V], mask: Int) = (for (py <- 0 until Size; px <- 0 until Size; ((ox, oy), s) <- offsets.zipWithIndex
      if ((mask >> s) & 1) == 1 && inside(t, px + 0.5 + ox, py + 0.5 + oy)) yield 1).sum
    // u22 = alpha (float), u23 = a mask (integer); r24 = alpha, r19 = mask.
    val zero = MUL(rs1 = 5, rs2 = 20, rd = 24, funct3 = 2)
    def withAlpha(a: Float) = { rig.rom(rig.fsConst + 8) = BigInt(java.lang.Float.floatToRawIntBits(a)) & 0xFFFFFFFFL
      rig.fs.init ++ Seq(zero, ADD(rs1 = 24, rs2 = 22, rd = 24, funct3 = 2), BigInt(0)) }
    val shaderMask = rig.fs.init ++ Seq(MUL(rs1 = 5, rs2 = 20, rd = 19, funct3 = 2), IADD(rs1 = 19, rs2 = 23, rd = 19, funct3 = 2), BigInt(0))
    val smaskId  = rig.fs.init ++ Seq(SMASK(rd = 19), BigInt(0))
    val smaskNot = rig.fs.init ++ Seq(SMASK(rd = 19), MUL(rs1 = 5, rs2 = 20, rd = 18, funct3 = 2),
                                      IADD(rs1 = 18, rs2 = 23, rd = 18, funct3 = 2), IXOR(rs1 = 19, rs2 = 18, rd = 19), BigInt(0))
    val static = rig.draw(quad, topology = 1, sampleMaskCfg = 0x5)
    val a50 = rig.draw(quad, topology = 1, frag = withAlpha(0.5f), sampleMaskCfg = 0xF | 0x10)
    val a30 = rig.draw(quad, topology = 1, frag = withAlpha(0.3f), sampleMaskCfg = 0xF | 0x10)
    rig.rom(rig.fsConst + 12) = BigInt(0x9)
    val sm = rig.draw(quad, topology = 1, frag = shaderMask, sampleMaskCfg = 0xF | 0x20)
    val id = rig.draw(tri, frag = smaskId, sampleMaskCfg = 0x7 | 0x20)
    rig.rom(rig.fsConst + 12) = BigInt(0xF)
    val none = rig.draw(tri, frag = smaskNot, sampleMaskCfg = 0xF | 0x20)
    println(s"  static 0101: $static, alpha 0.5: $a50, alpha 0.3: $a30, shader 1001: $sm, " +
            s"SMASK as mask: $id (expect ${samplesIn(tri, 0x7)}), ~SMASK: $none")
    utest.assert(static == 128 && a50 == 128 && a30 == 64 && sm == 128)
    utest.assert(id == samplesIn(tri, 0x7) && none == 0)
  }

  /** Depth is tested at each sample's own position: a flat quad at z 0.5,
    * then a steep triangle crossing z 0.5 at x = 4.25 -- inside pixel 4,
    * whose centre (4.5) is behind but whose sample at x 4.125 is in front. */
  def perSampleDepth(rig: DrawRig): Unit = {
    val quad = Seq(at(0, 0, 1, z = 0.5), at(8, 0, 1, z = 0.5), at(0, 8, 1, z = 0.5),
                   at(8, 0, 1, z = 0.5), at(8, 8, 1, z = 0.5), at(0, 8, 1, z = 0.5))
    val z1 = 0.1 + 0.4 * 8 / 4.25
    val steep = Seq(at(0, 0, 1, z = 0.1), at(0, 8, 1, z = 0.1), at(8, 0, 1, z = z1))
    val got = rig.draw(quad ++ steep, depthCfg = 1 | (1 << 3), occ = (2, 3))   // LESS, writes; count the steep one
    def zAt(x: Double) = 0.1 + (z1 - 0.1) * x / 8
    val perSample = (for (py <- 0 until Size; px <- 0 until Size; (ox, oy) <- offsets
      if inside(steep, px + 0.5 + ox, py + 0.5 + oy) && zAt(px + 0.5 + ox) < 0.5) yield 1).sum
    val atCentre = (for (py <- 0 until Size; px <- 0 until Size; (ox, oy) <- offsets
      if inside(steep, px + 0.5 + ox, py + 0.5 + oy) && zAt(px + 0.5) < 0.5) yield 1).sum
    println(s"  steep triangle in front: $got samples (per-sample depth $perSample, depth at the centre would give $atCentre)")
    utest.assert(got == perSample && perSample != atCentre)
  }

  /** Render windows: a 16x8 framebuffer drawn as one non-square window, and
    * again as two 8x8 windows at different origins. Both must give the same
    * image, and the coverage the reference predicts. */
  def windows(rig: DrawRig): Unit = {
    val (fw, fh) = (16, 8)
    def at16(sx: Double, sy: Double, w: Double, r: Double = 0, g: Double = 0) =
      V((sx / (fw / 2.0) - 1) * w, (sy / (fh / 2.0) - 1) * w, 0.5 * w, w, r, g)
    val t = Seq(at16(0, 0, 1.0, r = 1), at16(16, 0, 3.0, g = 1), at16(0, 8, 2.0))
    rig.viewport = (fw / 2.0, fh / 2.0, fw / 2.0, fh / 2.0); rig.pitch = fw / 4
    rig.fbBase = 0x10000; rig.window = (0, 0, 4, 2)
    val one = rig.draw(t)
    val a = for (y <- 0 until fh; x <- 0 until fw) yield rig.pixel(x, y)
    rig.fbBase = 0x14000; rig.window = (0, 0, 2, 2)
    val left = rig.draw(t)
    rig.window = (2, 0, 2, 2)
    val right = rig.draw(t, keep = true)
    val b = for (y <- 0 until fh; x <- 0 until fw) yield rig.pixel(x, y)
    // Screen-linear edges (x/16 + y/8 <= 1): no sample lies exactly on one.
    val expect = (for (py <- 0 until fh; px <- 0 until fw; (ox, oy) <- offsets
      if (px + 0.5 + ox) / 16 + (py + 0.5 + oy) / 8 <= 1) yield 1).sum
    val diff = a.zip(b).count { case (p, q) => p != q }
    println(s"  16x8 as one 4x2-tile window: $one samples; as two windows: $left + $right (expect $expect); " +
            s"${a.size - diff} of ${a.size} pixels identical")
    utest.assert(one == expect && left + right == expect && diff == 0)
    rig.viewport = (Size / 2.0, Size / 2.0, Size / 2.0, Size / 2.0); rig.pitch = Size / 4
    rig.window = (0, 0, Size / 4, Size / 4); rig.fbBase = 0x10000
  }

  /** Several colour attachments: the tile is rendered once per attachment,
    * the fragment shader writing the one ATTIDX names. Attachment 0 RGBA8
    * cleared, 1 BGRA8 cleared to its own colour, 2 RGBA8 loaded; depth LESS,
    * which only works if every pass starts from the same depth. */
  def attachments(rig: DrawRig): Unit = {
    import Instructions._
    val bases = Seq(0x10000, 0x14000, 0x18000)
    val t = Seq(at(0, 0, 1.0, r = 1), at(8, 0, 4.0, g = 1), at(0, 8, 2.0))
    // Attachment 2's previous content: every pixel (10, 20, 30, 40).
    for (i <- 0 until Size * Size) { rig.rom(bases(2) + 4 * i) = BigInt(0x281E140AL) }
    rig.rom(rig.fsConst + 8) = BigInt(java.lang.Float.floatToRawIntBits(0.25f))   // u22
    val frag = rig.fs.init ++ Seq(ATTIDX(rd = 10), I2F(rs1 = 10, rd = 10), MUL(rs1 = 10, rs2 = 22, rd = 26, funct3 = 2), BigInt(0))
    def fp16(f: Float) = java.lang.Float.floatToFloat16(f).toInt & 0xFFFF
    rig.fbBase = bases(0)
    val count = rig.draw(t, frag = frag, depthCfg = 1 | (1 << 3), extra = Seq(
      BorgGpuRegs.att_cfg_offset -> BigInt(2 | (1 << 3)),                 // 3 attachments, 2 loads
      BorgGpuRegs.att_format_offset -> BigInt(FlushFormat.BGRA8 | (FlushFormat.RGBA8 << 3)),
      BorgGpuRegs.att_base1_offset -> BigInt(bases(1)), BorgGpuRegs.att_base2_offset -> BigInt(bases(2)),
      BorgGpuRegs.att_clear_rg1_offset -> BigInt((fp16(1.0f) << 16) | fp16(0.5f)),  // R 1.0, G 0.5
      BorgGpuRegs.att_clear_ba1_offset -> BigInt((fp16(0.25f) << 16) | 200)))     // B 0.25, A 200
    def px(k: Int, x: Int, y: Int): Seq[Int] = {
      val a = bases(k) + 64 * ((y / 4) * (Size / 4) + x / 4) + 4 * ((y % 4) * 4 + x % 4)
      val w = rig.read32(a); val b = (0 until 4).map(i => ((w >> (8 * i)) & 0xFF).toInt)
      if (k == 1) Seq(b(2), b(1), b(0), b(3)) else b                      // BGRA8 -> RGBA
    }
    val (e, _) = planes(t)
    var checked = 0
    for (y <- 0 until Size; x <- 0 until Size) {
      val cov = offsets.count { case (ox, oy) => inside(t, x + 0.5 + ox, y + 0.5 + oy) }
      for (k <- 0 until 3) {
        val p = px(k, x, y)
        if (cov == 4) {
          val ev = e.map(eval(_, x + 0.5, y + 0.5)); val g = ev(1) / ev.sum * 255
          val r = math.round(k * 0.25 * 255 + 0.001).toInt
          Predef.assert(math.abs(p(0) - r) <= 1 && math.abs(p(1) - g) <= 2, s"attachment $k pixel ($x,$y): $p, red $r green $g")
          checked += 1
        } else if (cov == 0) {
          val clear = Seq(Seq(0, 0, 0), Seq(255, 128, 64), Seq(10, 20, 30))(k)
          Predef.assert(p.take(3) == clear, s"attachment $k uncovered pixel ($x,$y): $p, expected $clear")
        }
      }
    }
    println(s"  3 attachments (RGBA8 cleared, BGRA8 cleared, RGBA8 loaded), depth LESS: $count samples " +
            s"(expect ${samplesCovered(t)}), $checked covered pixels and every uncovered one right")
    utest.assert(count == samplesCovered(t))
  }

  /** A bin that overflows is never written past its row: the render is
    * abandoned whole -- nothing flushed, nothing counted -- and SEQ_TRIGGER's
    * bit 1 says so. With room for both triangles the same draw renders. */
  def binOverflow(rig: DrawRig): Unit = {
    val quad = Seq(at(8, 0, 1), at(0, 0, 1), at(8, 8, 1), at(0, 8, 1))
    val tris = Seq(quad(0), quad(1), quad(2), quad(2), quad(1), quad(3))
    def status = rawRead(rig.borg, BorgGpuRegs.seq_trigger_offset.litValue.toInt)
    val fb = rig.fbBase until rig.fbBase + 64 * (Size / 4) * (Size / 4)
    // Every tile is in both triangles' bounding boxes; a row of 2 bytes
    // holds one entry.
    val lost = rig.draw(tris, extra = Seq(BorgGpuRegs.seq_bin_row_bytes_offset -> BigInt(2)))
    val st = status
    val written = rig.half.keys.count(fb.contains)
    println(s"  one entry per bin: status $st, $lost samples, $written framebuffer halfwords written")
    utest.assert(st == 3 && lost == 0 && written == 0)
    val full = rig.draw(tris, extra = Seq(BorgGpuRegs.seq_bin_row_bytes_offset -> BigInt(4)))
    println(s"  two entries per bin: status $status, $full samples (expect ${Size * Size * 4})")
    utest.assert(status == 1 && full == Size * Size * 4)
  }

  /** rasterizationSamples = 1 (SAMPLE_MASK_CFG bit 6): coverage and depth
    * at the pixel centre, one count per pixel, every mask on sample 0, and a
    * pixel that is either wholly the triangle's or untouched -- no partial
    * edge pixels, so the four identical copies resolve exactly. */
  def singleSample(rig: DrawRig): Unit = {
    import Instructions._
    val one = 0x40
    val quad = Seq(at(8, 0, 1), at(0, 0, 1), at(8, 8, 1), at(0, 8, 1))
    // No edge through a pixel centre (x + y = 8.5 on the long one), so the
    // reference needs no tie rule.
    val tri = Seq(at(0.25, 0.25, 1.0, r = 1), at(8, 0.5, 4.0, g = 1), at(0.5, 8, 2.0))
    def centres(t: Seq[V]) = for (py <- 0 until Size; px <- 0 until Size if inside(t, px + 0.5, py + 0.5)) yield (px, py)
    val count = rig.draw(tri, sampleMaskCfg = 0xF | one)
    val (e, _) = planes(tri)
    val in = centres(tri).toSet
    for (y <- 0 until Size; x <- 0 until Size) {
      val (hr, hg, hb) = rig.pixel(x, y)
      if (in((x, y))) {
        val ev = e.map(eval(_, x + 0.5, y + 0.5)); val q = ev.sum
        Predef.assert(math.abs(hr - ev(0) / q * 255) <= 2 && math.abs(hg - ev(1) / q * 255) <= 2 && hb == 0,
                      s"pixel ($x,$y) centre inside: ($hr,$hg,$hb)")
      } else Predef.assert((hr, hg, hb) == (0, 0, 0), s"pixel ($x,$y) centre outside: ($hr,$hg,$hb)")
    }
    println(s"  one sample: $count pixels counted (expect ${in.size}), each wholly shaded or untouched")
    utest.assert(count == in.size && in.size > 8)
    // Depth at the centre: the steep triangle of perSampleDepth.
    val flat = Seq(at(0, 0, 1, z = 0.5), at(8, 0, 1, z = 0.5), at(0, 8, 1, z = 0.5),
                   at(8, 0, 1, z = 0.5), at(8, 8, 1, z = 0.5), at(0, 8, 1, z = 0.5))
    val z1 = 0.1 + 0.4 * 8 / 4.25
    val steep = Seq(at(0, 0, 1, z = 0.1), at(0, 8, 1, z = 0.1), at(8, 0, 1, z = z1))
    val front = rig.draw(flat ++ steep, depthCfg = 1 | (1 << 3), occ = (2, 3), sampleMaskCfg = 0xF | one)
    // Its long edge x + y = 8 runs through pixel centres; it is a bottom-right
    // edge, so the tie rule leaves those out: strictly inside.
    val (es, _) = planes(steep)
    val expFront = centres(steep).count { case (px, py) =>
      es.forall(eval(_, px + 0.5, py + 0.5) > 0) && 0.1 + (z1 - 0.1) * (px + 0.5) / 8 < 0.5 }
    // Masks act on the one sample.
    val zero = MUL(rs1 = 5, rs2 = 20, rd = 24, funct3 = 2)
    def withAlpha(a: Float) = { rig.rom(rig.fsConst + 8) = BigInt(java.lang.Float.floatToRawIntBits(a)) & 0xFFFFFFFFL
      rig.fs.init ++ Seq(zero, ADD(rs1 = 24, rs2 = 22, rd = 24, funct3 = 2), BigInt(0)) }
    val staticOff = rig.draw(quad, topology = 1, sampleMaskCfg = 0xE | one)
    val staticOn  = rig.draw(quad, topology = 1, sampleMaskCfg = 0x1 | one)
    val a60 = rig.draw(quad, topology = 1, frag = withAlpha(0.6f), sampleMaskCfg = 0xF | 0x10 | one)
    val a40 = rig.draw(quad, topology = 1, frag = withAlpha(0.4f), sampleMaskCfg = 0xF | 0x10 | one)
    val smaskId = rig.fs.init ++ Seq(SMASK(rd = 19), BigInt(0))
    val smaskNot = rig.fs.init ++ Seq(SMASK(rd = 19), MUL(rs1 = 5, rs2 = 20, rd = 18, funct3 = 2),
                                      IADD(rs1 = 18, rs2 = 23, rd = 18, funct3 = 2), IXOR(rs1 = 19, rs2 = 18, rd = 19), BigInt(0))
    rig.rom(rig.fsConst + 12) = BigInt(0x1)
    val id = rig.draw(quad, topology = 1, frag = smaskId, sampleMaskCfg = 0xF | 0x20 | one)
    val not = rig.draw(quad, topology = 1, frag = smaskNot, sampleMaskCfg = 0xF | 0x20 | one)
    println(s"  depth at the centre: $front (expect $expFront); static mask bit 0 off $staticOff, on $staticOn; " +
            s"alpha 0.6 $a60, 0.4 $a40; SMASK as mask $id, ~SMASK $not")
    utest.assert(front == expFront && staticOff == 0 && staticOn == 64 && a60 == 64 && a40 == 0 && id == 64 && not == 0)
  }

  /** Depth bias, o = m * slope + r * constant added to the triangle's depth.
    * Constant: against a full-precision clear depth half an r below the
    * triangle's 0.5, a bias of -1 passes LESS and -0.4 does not -- r = 2^-15
    * for D16, ulp(0.5) = 2^-24 for D32_SFLOAT. Slope: a ramp of 0.1 per
    * pixel over a flat 0.5 quad; slope factor -2.5 moves the crossing 2.5
    * pixels, sample for sample. */
  def depthBias(rig: DrawRig): Unit = {
    val quad = Seq(at(0, 0, 1, z = 0.5), at(8, 0, 1, z = 0.5), at(0, 8, 1, z = 0.5),
                   at(8, 0, 1, z = 0.5), at(8, 8, 1, z = 0.5), at(0, 8, 1, z = 0.5))
    val less = 1 | (1 << 3)
    def run(clear: Double, const: Double, d32: Boolean) = rig.draw(quad, depthCfg = less, extra = Seq(
      BorgGpuRegs.depth_format_offset -> BigInt(if (d32) 1 else 0),
      BorgGpuRegs.clear_depth_offset -> rig.f32(clear),
      BorgGpuRegs.depth_bias_const_offset -> rig.f32(const)))
    val full = Size * Size * 4
    val results = for ((d32, r) <- Seq(false -> math.pow(2, -15), true -> math.pow(2, -24))) yield {
      val none = run(0.5, 0, d32); val below = run(0.5 - r / 2, -1, d32)
      val short = run(0.5 - r / 2, -0.4, d32); val up = run(0.5, 1, d32)
      println(f"  ${if (d32) "D32_SFLOAT" else "D16_UNORM "} (r = $r%.3g): no bias $none, -1 $below, -0.4 $short, +1 $up")
      (none, below, short, up)
    }
    utest.assert(results.forall(_ == ((0, full, 0, 0))))
    val ramp = Seq(at(0, 0, 1, z = 0.1), at(8, 0, 1, z = 0.9), at(0, 8, 1, z = 0.1),
                   at(8, 0, 1, z = 0.9), at(8, 8, 1, z = 0.9), at(0, 8, 1, z = 0.1))
    def front(bias: Double) = (for (py <- 0 until Size; px <- 0 until Size; (ox, _) <- offsets
      if 0.1 + 0.1 * (px + 0.5 + ox) + bias < 0.5) yield 1).sum
    // Bias -0.25 takes the ramp's left end below 0: D16 clamps it to 0,
    // D32_SFLOAT keeps it, and both must still count it in front.
    val flat0 = rig.draw(quad ++ ramp, depthCfg = less, occ = (2, 4))
    val biased = for (d32 <- Seq(false, true)) yield
      rig.draw(quad ++ ramp, depthCfg = less, occ = (2, 4), extra = Seq(
        BorgGpuRegs.depth_format_offset -> BigInt(if (d32) 1 else 0),
        BorgGpuRegs.depth_bias_slope_offset -> rig.f32(-2.5)))
    println(s"  ramp in front of the flat quad: no bias $flat0 (expect ${front(0)}), " +
            s"slope -2.5: D16 ${biased(0)}, D32_SFLOAT ${biased(1)} (expect ${front(-0.25)})")
    utest.assert(flat0 == front(0) && biased.forall(_ == front(-0.25)) && front(-0.25) > flat0)
  }

  /** RAW colour formats: the fragment shader's r26 as raw bytes (1, 2 or 4
    * per pixel), loaded back, read in the shader with TLD and written again
    * -- the path every format the compiler packs takes. Single-sample, so
    * nothing is resolved. Then R32_SFLOAT blending done in the shader: two
    * overlapping triangles each add 0.375 to the destination, in FP32. */
  def rawFormats(rig: DrawRig): Unit = {
    import Instructions._
    val one = 0x40
    val tri = Seq(at(0.25, 0.25, 1.0), at(8, 0.5, 4.0), at(0.5, 8, 2.0))
    val in = (for (py <- 0 until Size; px <- 0 until Size if inside(tri, px + 0.5, py + 0.5)) yield (px, py)).toSet
    def u(k: Int, v: BigInt): Unit = rig.rom(rig.fsConst + 4 * k) = v
    def f32(f: Float) = BigInt(java.lang.Float.floatToRawIntBits(f)) & 0xFFFFFFFFL
    // u21 = word 0, u22 = 1, u23 = word 1, u24 = 2.
    u(1, BigInt(0xA1B2C3D4L)); u(2, BigInt(1)); u(3, BigInt(0x11223344L)); u(4, BigInt(2))
    val store = Seq(IXOR(rs1 = 9, rs2 = 9, rd = 26), IOR(rs1 = 26, rs2 = 21, rd = 26, funct3 = 2),
                    IXOR(rs1 = 9, rs2 = 9, rd = 27), IOR(rs1 = 27, rs2 = 23, rd = 27, funct3 = 2), BigInt(0))
    val addOne = Seq(TLD(rd = 20), IADD(rs1 = 20, rs2 = 22, rd = 26, funct3 = 2),
                     TLD(rd = 21, word = 1), IADD(rs1 = 21, rs2 = 24, rd = 27, funct3 = 2), BigInt(0))
    val bytes = Map(FlushFormat.RAW32 -> 4, FlushFormat.RAW16 -> 2, FlushFormat.RAW8 -> 1, FlushFormat.RAW64 -> 8)
    def bytesAt(fmt: Int, x: Int, y: Int): BigInt = {
      val n = bytes(fmt)
      val a = rig.fbBase + 16 * n * ((y / 4) * rig.pitch + x / 4) + n * ((y % 4) * 4 + x % 4)
      (0 until n).map { i =>
        val h = rig.half.getOrElse((a + i) & ~1, -1)
        BigInt(if (((a + i) & 1) == 1) (h >> 8) & 0xFF else h & 0xFF) << (8 * i)
      }.sum
    }
    val w1Clear = BigInt(0x0BADF00DL)
    val clear = Seq(BorgGpuRegs.plane_clear_offset -> BigInt(0x5A << 8),      // byte 3 of the clear word
                    BorgGpuRegs.att_clear_ext0_offset -> w1Clear)              // word 1 (RAW64)
    val stored = (BigInt(0x11223344L) << 32) | 0xA1B2C3D4L
    val added  = (BigInt(0x11223346L) << 32) | 0xA1B2C3D5L
    val cleared = (w1Clear << 32) | 0x5A000000L
    for (fmt <- Seq(FlushFormat.RAW32, FlushFormat.RAW16, FlushFormat.RAW8, FlushFormat.RAW64)) {
      val mask = (BigInt(1) << (8 * bytes(fmt))) - 1
      val cfg = Seq(BorgGpuRegs.flush_format_offset -> BigInt(fmt)) ++ clear
      val c1 = rig.draw(tri, frag = store, sampleMaskCfg = 0xF | one, extra = cfg)
      for (y <- 0 until Size; x <- 0 until Size)
        Predef.assert(bytesAt(fmt, x, y) == ((if (in((x, y))) stored else cleared) & mask),
                      f"format $fmt pixel ($x,$y): 0x${bytesAt(fmt, x, y)}%x")
      val c2 = rig.draw(tri, frag = addOne, sampleMaskCfg = 0xF | one, keep = true,
                        extra = cfg :+ (BorgGpuRegs.tile_load_offset -> BigInt(1)))
      // Word 0 + 1 in whatever bytes the format keeps; RAW64's word 1 + 2.
      val exp1 = if (fmt == FlushFormat.RAW64) added else ((stored & mask) + 1) & mask
      for (y <- 0 until Size; x <- 0 until Size)
        Predef.assert(bytesAt(fmt, x, y) == (if (in((x, y))) exp1 else cleared & mask),
                      f"format $fmt loaded pixel ($x,$y): 0x${bytesAt(fmt, x, y)}%x")
      println(f"  format $fmt: $c1 pixels stored as 0x${stored & mask}%x, loaded, TLD + 1 -> 0x$exp1%x; ${Size * Size - in.size} untouched")
      utest.assert(c1 == in.size && c2 == in.size)
    }
    // R32_SFLOAT, blend ONE/ONE in the shader: dst + 0.375, twice.
    u(2, f32(0.375f))
    val blend = Seq(TLD(rd = 20), ADD(rs1 = 20, rs2 = 22, rd = 26, funct3 = 2), BigInt(0))
    rig.draw(tri ++ tri, frag = blend, sampleMaskCfg = 0xF | one, extra = Seq(
      BorgGpuRegs.flush_format_offset -> BigInt(FlushFormat.RAW32), BorgGpuRegs.plane_clear_offset -> BigInt(0)))
    for (y <- 0 until Size; x <- 0 until Size) {
      val f = java.lang.Float.intBitsToFloat(bytesAt(FlushFormat.RAW32, x, y).toInt)
      Predef.assert(f == (if (in((x, y))) 0.75f else 0f), s"R32_SFLOAT pixel ($x,$y): $f")
    }
    println("  R32_SFLOAT: 0 + 0.375 + 0.375 = 0.75 exactly on every covered pixel, blended in the shader")
    // R32G32_SFLOAT, a blend that needs the whole pixel: x' = 0.5 + x*y,
    // y' = y + 1, from (0, 2) twice -> (0.5, 3) -> (2, 4).
    u(2, f32(0.5f)); u(3, f32(1.0f))
    val blend64 = Seq(TLD(rd = 20), TLD(rd = 21, word = 1), MUL(rs1 = 20, rs2 = 21, rd = 18),
                      ADD(rs1 = 18, rs2 = 22, rd = 26, funct3 = 2), ADD(rs1 = 21, rs2 = 23, rd = 27, funct3 = 2), BigInt(0))
    rig.draw(tri ++ tri, frag = blend64, sampleMaskCfg = 0xF | one, extra = Seq(
      BorgGpuRegs.flush_format_offset -> BigInt(FlushFormat.RAW64), BorgGpuRegs.plane_clear_offset -> BigInt(0),
      BorgGpuRegs.att_clear_ext0_offset -> f32(2.0f)))
    for (y <- 0 until Size; x <- 0 until Size) {
      val v = bytesAt(FlushFormat.RAW64, x, y)
      val (fx, fy) = (java.lang.Float.intBitsToFloat((v & 0xFFFFFFFFL).toInt), java.lang.Float.intBitsToFloat((v >> 32).toInt))
      Predef.assert((fx, fy) == (if (in((x, y))) (2f, 4f) else (0f, 2f)), s"R32G32_SFLOAT pixel ($x,$y): ($fx, $fy)")
    }
    println("  R32G32_SFLOAT: (0, 2) -> x + x*y ... -> (2, 4) exactly: both words of the pixel resident")
    // RAW128: two slices, ATTIDX = 4 * slice. u21, u23 = words 0, 1 and
    // u24, u25 = words 2, 3; u26 = 2 (the slice's shift).
    u(3, BigInt(0x11223344L)); u(4, BigInt(0x55667788L)); u(5, BigInt(0x99AABBCCL)); u(6, BigInt(2))
    def pick(dst: Int, lo: Int, hi: Int) = Seq(
      IXOR(rs1 = 9, rs2 = 9, rd = dst), IOR(rs1 = dst, rs2 = lo, rd = dst, funct3 = 2),
      IXOR(rs1 = 9, rs2 = 9, rd = 19), IOR(rs1 = 19, rs2 = hi, rd = 19, funct3 = 2),
      IXOR(rs1 = dst, rs2 = 19, rd = 19), IAND(rs1 = 19, rs2 = 20, rd = 19), IXOR(rs1 = dst, rs2 = 19, rd = dst))
    val store128 = Seq(ATTIDX(rd = 20), ISRL(rs1 = 20, rs2 = 26, rd = 20, funct3 = 2),
                       IXOR(rs1 = 9, rs2 = 9, rd = 18), ISUB(rs1 = 18, rs2 = 20, rd = 20)) ++
                   pick(26, 21, 24) ++ pick(27, 23, 25) :+ BigInt(0)
    val w23Clear = Seq(BorgGpuRegs.att_clear_w2_0_offset -> BigInt(0x0C0FFEE0L), BorgGpuRegs.att_clear_w3_0_offset -> BigInt(0x0FACADE0L))
    def at128(x: Int, y: Int): BigInt = {
      val t = rig.fbBase + 256 * ((y / 4) * rig.pitch + x / 4) + 8 * ((y % 4) * 4 + x % 4)
      def w(a: Int) = BigInt(rig.half.getOrElse(a, -1) & 0xFFFF) | (BigInt(rig.half.getOrElse(a + 2, -1) & 0xFFFF) << 16)
      w(t) | (w(t + 4) << 32) | (w(t + 128) << 64) | (w(t + 132) << 96)
    }
    val words128 = Seq(BigInt(0xA1B2C3D4L), BigInt(0x11223344L), BigInt(0x55667788L), BigInt(0x99AABBCCL))
    def join(ws: Seq[BigInt]) = ws.zipWithIndex.map { case (v, i) => v << (32 * i) }.sum
    val cfg128 = Seq(BorgGpuRegs.flush_format_offset -> BigInt(FlushFormat.RAW128)) ++ clear ++ w23Clear
    val c128 = rig.draw(tri, frag = store128, sampleMaskCfg = 0xF | one, extra = cfg128)
    val clear128 = join(Seq(BigInt(0x5A000000L), w1Clear, BigInt(0x0C0FFEE0L), BigInt(0x0FACADE0L)))
    for (y <- 0 until Size; x <- 0 until Size)
      Predef.assert(at128(x, y) == (if (in((x, y))) join(words128) else clear128), f"RAW128 pixel ($x,$y): 0x${at128(x, y)}%x")
    u(2, BigInt(1)); u(4, BigInt(2))                                   // addOne's u22, u24
    rig.draw(tri, frag = addOne, sampleMaskCfg = 0xF | one, keep = true, extra = cfg128 :+ (BorgGpuRegs.tile_load_offset -> BigInt(1)))
    val added128 = join(words128.zip(Seq(1, 2, 1, 2)).map { case (v, d) => v + d })
    for (y <- 0 until Size; x <- 0 until Size)
      Predef.assert(at128(x, y) == (if (in((x, y))) added128 else clear128), f"RAW128 loaded pixel ($x,$y): 0x${at128(x, y)}%x")
    println(f"  RAW128: $c128 pixels in two slices (0x${join(words128)}%x), cleared per slice, loaded, TLD + 1/2 per word")
    // 4x, stored per sample (ATTACH_MS): each sample's own tile region holds
    // the word where that sample is covered and the clear value elsewhere.
    u(1, BigInt(0xA1B2C3D4L)); u(3, BigInt(0x11223344L))
    val triMs = Seq(at(0.3, 0.2, 1.0), at(7.9, 0.45, 4.0), at(0.35, 7.7, 2.0))   // no sample on an edge
    rig.draw(triMs, frag = store, extra = Seq(BorgGpuRegs.flush_format_offset -> BigInt(FlushFormat.RAW64),
      BorgGpuRegs.attach_ms_offset -> BigInt(1)) ++ clear)
    var hits = 0
    for (y <- 0 until Size; x <- 0 until Size; ((ox, oy), smp) <- offsets.zipWithIndex) {
      val a = rig.fbBase + 128 * (4 * ((y / 4) * rig.pitch + x / 4) + smp) + 8 * ((y % 4) * 4 + x % 4)
      val v = (0 until 8).map { i => val h = rig.half.getOrElse((a + i) & ~1, -1)
        BigInt(if (((a + i) & 1) == 1) (h >> 8) & 0xFF else h & 0xFF) << (8 * i) }.sum
      val cov = inside(triMs, x + 0.5 + ox, y + 0.5 + oy)
      if (cov) hits += 1
      Predef.assert(v == (if (cov) stored else cleared), f"4x RAW64 pixel ($x,$y) sample $smp: 0x$v%x")
    }
    println(s"  4x RAW64 per sample: $hits covered samples hold the word, the rest the clear value")
  }

  /** D16 invariance at 4x: depth stored per sample, reloaded, and the same
    * triangle drawn again with EQUAL passes at every covered sample -- the
    * fragment depth is rounded to D16 before the test, as the stored one is. */
  def depthInvariance(rig: DrawRig): Unit = {
    val tri = Seq(at(0.3, 0.2, 1.0, z = 0.1), at(7.9, 0.45, 2.0, z = 0.8), at(0.35, 7.7, 1.5, z = 0.37))
    val zb = BigInt(0x30000)
    val store = Seq(BorgGpuRegs.flush_zb_base_offset -> zb, BorgGpuRegs.attach_ms_offset -> BigInt(1))
    val first = rig.draw(tri, depthCfg = 1 | (1 << 3), extra = store)                  // LESS, write
    val again = rig.draw(tri, depthCfg = 2, keep = true,                               // EQUAL, no write
                         extra = store :+ (BorgGpuRegs.tile_load_offset -> BigInt(2)))  // load depth
    val exp = samplesCovered(tri)
    println(s"  4x D16: $first samples stored (expect $exp), $again pass EQUAL after the reload (expect $exp)")
    utest.assert(first == exp && again == exp)
  }

  /** A primitive exactly on the far plane (z = w, the skybox) is inside the
    * clip volume and loses no sample -- with w = 1 and with a different w
    * at each corner, at 4x and at one sample. */
  def farPlane(rig: DrawRig): Unit = {
    for ((ws, name) <- Seq((Seq(1.0, 1.0, 1.0, 1.0), "w = 1"), (Seq(1.0, 2.5, 0.7, 1.9), "w varies"))) {
      val q = Seq(at(8, 0, ws(0), z = 1.0), at(0, 0, ws(1), z = 1.0), at(8, 8, ws(2), z = 1.0), at(0, 8, ws(3), z = 1.0))
      val tris = Seq(q(0), q(1), q(2), q(2), q(1), q(3))
      val four = rig.draw(tris)
      val one = rig.draw(tris, sampleMaskCfg = 0xF | 0x40)
      val z0 = rig.draw(tris.map(v => v.copy(z = 0.0)))                        // and the near plane
      println(s"  z = w, $name: $four of ${Size * Size * 4} samples at 4x, $one of ${Size * Size} pixels at 1x; z = 0: $z0")
      utest.assert(four == Size * Size * 4 && one == Size * Size && z0 == Size * Size * 4)
    }
  }

  /** Flat shading takes the provoking vertex, corner 0: v_p for strip
    * primitive p, odd ones included (Vulkan's {v_p, v_p+2, v_p+1}). */
  def flatStrip(rig: DrawRig): Unit = {
    import Instructions._
    val q = Seq(at(8, 0, 1, r = 0.2), at(0, 0, 1, r = 0.6), at(8, 8, 1, r = 0.4), at(0, 8, 1, r = 0.8))
    val flat = Seq(FATTR(rd = 10, index = 0), IXOR(rs1 = 9, rs2 = 9, rd = 26), IOR(rs1 = 26, rs2 = 10, rd = 26),
                   IXOR(rs1 = 9, rs2 = 9, rd = 27), IXOR(rs1 = 9, rs2 = 9, rd = 28), BigInt(0))
    val tris = Seq(Seq(q(0), q(1), q(2)), Seq(q(1), q(3), q(2)))              // the strip's primitives
    for (indexed <- Seq(false, true)) {
      rig.draw(q, topology = 1, frag = flat, sampleMaskCfg = 0xF | 0x40, indices = if (indexed) Seq(0, 1, 2, 3) else Nil)
      var checked = 0
      for (y <- 0 until Size; x <- 0 until Size; (t, p) <- tris.zipWithIndex) {
        val (e, _) = planes(t)
        if (e.forall(eval(_, x + 0.5, y + 0.5) > 1e-9)) {                        // strictly inside
          val want = math.round(q(p).r * 255).toInt
          Predef.assert(math.abs(rig.pixel(x, y)._1 - want) <= 1, s"indexed $indexed pixel ($x,$y) of primitive $p: red ${rig.pixel(x, y)._1}, provoking $want")
          checked += 1
        }
      }
      println(s"  strip${if (indexed) ", indexed" else ""}: $checked pixels, each primitive flat in its provoking vertex's colour")
      utest.assert(checked > 40)
    }
  }

  val tests = Tests {
    utest.test("several_colour_attachments") {
      run("colour attachments")(attachments)
    }
    utest.test("render_windows_and_non_square_framebuffers") {
      run("render windows")(windows)
    }
    utest.test("depth_is_tested_per_sample") {
      run("per-sample depth")(perSampleDepth)
    }
    utest.test("sample_mask_alpha_to_coverage_and_smask") {
      run("sample masks")(masks)
    }
    utest.test("shared_edges_are_covered_exactly_once") {
      run("watertight shared edges")(watertight)
    }
    utest.test("render_to_texture_and_sample_it") {
      run("render to texture", quadCfg)(renderToTexture)
    }
    utest.test("varyings_are_perspective_correct") {
      run("perspective-correct varyings")(perspective)
    }
    utest.test("near_plane_and_corners_behind_the_eye_clip_without_clipping") {
      run("near plane, w <= 0")(nearPlane)
    }
    utest.test("single_sample_rasterization") {
      run("one sample")(singleSample)
      run("one sample, Wafer sizing", waferCfg)(singleSample)
    }
    utest.test("depth_bias_constant_and_slope") {
      run("depth bias")(depthBias)
    }
    utest.test("raw_colour_formats_and_tld") {
      run("RAW colour formats", quadCfg)(rawFormats)
      run("RAW colour formats, Wafer sizing", waferCfg)(rawFormats)
    }
    utest.test("d16_depth_is_invariant_across_store_and_reload") {
      run("D16 invariance", quadCfg)(depthInvariance)
      run("D16 invariance, Wafer sizing", waferCfg)(depthInvariance)
    }
    utest.test("far_and_near_plane_primitives_are_inside") {
      run("far plane")(farPlane)
      run("far plane, Wafer sizing", waferCfg)(farPlane)
    }
    utest.test("flat_shading_uses_the_provoking_vertex") {
      run("flat strip")(flatStrip)
    }
    utest.test("bin_overflow_renders_nothing_and_reports") {
      run("bin overflow")(binOverflow)
    }
    utest.test("strips_fans_indices_restart_and_instances") {
      run("topologies")(topologies)
    }
    utest.test("simt_corners_one_per_lane") {
      run("4 lanes", quadCfg) { rig => perspective(rig); nearPlane(rig); topologies(rig) }
    }
    utest.test("wafer_sizing") {
      run("Wafer sizing", waferCfg) { rig => perspective(rig); nearPlane(rig); topologies(rig) }
    }
  }
}
