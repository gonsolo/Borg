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

  class DrawRig(borg: BorgTestWrapper) {
    val vsAddr = 0x1000; val fsAddr = 0x5000; val binBase = 0x6000
    val vb = 0x9000; val ib = 0xA000; val vsConst = 0xB000; val fsConst = 0xB100
    val setupBase = 0x20000; val fbBase = 0x10000
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
    // u22 + 6*i; u23 = 6 and u24 = 1 are integers. Varyings r and g are
    // output components 0 and 1.
    val vs = Seq(
      IMUL(rs1 = 30, rs2 = 23, rd = 9, funct3 = 2),
      IADD(rs1 = 9, rs2 = 22, rd = 9, funct3 = 2)) ++
      (0 until 6).flatMap { c =>
        val dst = if (c < 4) c else 10 + c - 4
        Seq(LOAD(rs1 = 9, rd = dst)) ++ (if (c < 5) Seq(IADD(rs1 = 9, rs2 = 24, rd = 9, funct3 = 2)) else Nil)
      } ++ Seq(SOUT(rs2 = 10, index = 0), SOUT(rs2 = 11, index = 1), BigInt(0))
    // v = l0*a0 + l1*a1 + l2*a2 for r and g; blue is 0 (u16 = 0.0).
    val fs = Seq(
      FATTR(rd = 10, index = 0),
      MUL(rs1 = 5, rs2 = 10, rd = 26), FMA(rs1 = 6, rs2 = 11, rs3 = 26, rd = 26), FMA(rs1 = 7, rs2 = 12, rs3 = 26, rd = 26),
      FATTR(rd = 10, index = 1),
      MUL(rs1 = 5, rs2 = 10, rd = 27), FMA(rs1 = 6, rs2 = 11, rs3 = 27, rd = 27), FMA(rs1 = 7, rs2 = 12, rs3 = 27, rd = 27),
      MUL(rs1 = 5, rs2 = 16, rd = 28, funct3 = 2),
      BigInt(0))
    rom ++= vs.zipWithIndex.map { case (w, i) => (vsAddr + 4 * i) -> w }
    rom ++= fs.zipWithIndex.map { case (w, i) => (fsAddr + 4 * i) -> w }
    rom ++= Seq(vsConst -> BigInt(vb / 4), (vsConst + 4) -> BigInt(6), (vsConst + 8) -> BigInt(1))
    rom ++= Seq(fsConst -> BigInt(0))

    /** Render; returns the occlusion count. topology 0/1/2, indexType 0/1/2. */
    def draw(verts: Seq[V], topology: Int = 0, indices: Seq[Int] = Nil, restart: Boolean = false,
             instances: Int = 1, count: Int = -1): BigInt = {
      borg.reset.poke(true.B)
      borg.io.data_write_n.poke(3.U); borg.io.data_read_n.poke(3.U)
      borg.io.gpuMem.ready.poke(false.B); borg.io.gpuMem.data.poke(0.U)
      borg.clock.step(4); borg.reset.poke(false.B); borg.clock.step(20)
      half.clear()
      for ((v, i) <- verts.zipWithIndex; (c, j) <- Seq(v.x, v.y, v.z, v.w, v.r, v.g).zipWithIndex)
        rom(vb + 4 * (6 * i + j)) = f32(c)
      for ((ix, i) <- indices.zipWithIndex) {               // 16-bit indices
        val w = ib + 4 * (i / 2); val old = rom.getOrElse(w, BigInt(0))
        rom(w) = if (i % 2 == 0) (old & BigInt(0xFFFF0000L)) | ix else (old & 0xFFFF) | (BigInt(ix) << 16)
      }
      def reg(r: UInt, v: BigInt): Unit = rawWrite(borg, r.litValue.toInt, v)
      rawWrite(borg, BorgGpuRegs.control_offset.litValue.toInt, 2)
      reg(BorgGpuRegs.seq_vert_addr_offset, vsAddr); reg(BorgGpuRegs.seq_vert_len_offset, vs.size)
      reg(BorgGpuRegs.seq_frag_addr_offset, fsAddr); reg(BorgGpuRegs.seq_frag_len_offset, fs.size)
      reg(BorgGpuRegs.seq_bin_base_offset, binBase); reg(BorgGpuRegs.seq_bin_row_bytes_offset, 32)
      reg(BorgGpuRegs.seq_setup_base_offset, setupBase)
      reg(BorgGpuRegs.seq_fb_base_offset, fbBase); reg(BorgGpuRegs.seq_tiles_per_row_offset, Size / 4)
      reg(BorgGpuRegs.seq_clear_lo_offset, 0x7BFF); reg(BorgGpuRegs.seq_clear_hi_offset, 0)
      reg(BorgGpuRegs.frag_pc_offset, 1)
      reg(BorgGpuRegs.flush_format_offset, FlushFormat.RGBA8)
      reg(BorgGpuRegs.depth_cfg_offset, 7 | (1 << 3))      // ALWAYS
      reg(BorgGpuRegs.cull_cfg_offset, 0)                  // cull nothing
      reg(BorgGpuRegs.occ_ctrl_offset, 3)
      val indexType = if (indices.isEmpty) 0 else 1
      reg(BorgGpuRegs.draw_cfg_offset, 1 | (topology << 1) | (indexType << 3) |
                                        ((if (restart) 1 else 0) << 5) | (8 << 6))
      reg(BorgGpuRegs.draw_vertex_count_offset, if (count >= 0) count else if (indices.nonEmpty) indices.size else verts.size)
      reg(BorgGpuRegs.draw_instance_count_offset, instances)
      reg(BorgGpuRegs.draw_first_vertex_offset, 0); reg(BorgGpuRegs.draw_first_instance_offset, 0)
      reg(BorgGpuRegs.draw_vertex_offset_offset, 0); reg(BorgGpuRegs.draw_index_base_offset, ib)
      for ((r, v) <- Seq(BorgGpuRegs.viewport_sx_offset -> Size / 2.0, BorgGpuRegs.viewport_sy_offset -> Size / 2.0,
                         BorgGpuRegs.viewport_ox_offset -> Size / 2.0, BorgGpuRegs.viewport_oy_offset -> Size / 2.0,
                         BorgGpuRegs.depth_scale_offset -> 1.0, BorgGpuRegs.depth_offset_offset -> 0.0))
        reg(r, f32(v))
      reg(BorgGpuRegs.draw_vs_const_offset, vsConst); reg(BorgGpuRegs.draw_fs_const_offset, fsConst)
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
      val tile = (y / 4) * (Size / 4) + x / 4
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

  val tests = Tests {
    utest.test("varyings_are_perspective_correct") {
      run("perspective-correct varyings")(perspective)
    }
    utest.test("near_plane_and_corners_behind_the_eye_clip_without_clipping") {
      run("near plane, w <= 0")(nearPlane)
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
