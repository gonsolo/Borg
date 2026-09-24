// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** BorgSetupRom run on a real BorgCore against a double-precision reference:
  * the inverse of the homogeneous vertex matrix, its planes, the depth plane,
  * the MSAA sample deltas and the bounding-box corners, for random triangles
  * -- including ones with corners behind the eye, which this setup exists to
  * handle without clipping. */
object BorgSetupRomTests extends TestSuite {
  import BorgCoreTestHelpers._
  import BorgSetupRom.{Record, uX, uY, uZ, uW}

  val W = 256.0                      // viewport: (0, 0) .. (256, 256)
  val recordBase = 0x8000

  case class Tri(c: Seq[Seq[Double]])   // corners: X, Y, Z, W

  /** Reference: the edge planes the ROM stores -- rows of adj(M), i.e. the
    * cross products, times sign(det): M^-1's rows unscaled -- the Zn plane
    * (scaled), det, and the screen corners. */
  def reference(t: Tri) = {
    val (sx, sy, ox, oy) = (W / 2, W / 2, W / 2, W / 2)
    val v = t.c.map { case Seq(x, y, _, w) => Seq(x * sx + w * ox, y * sy + w * oy, w) }
    def cross(a: Seq[Double], b: Seq[Double]) =
      Seq(a(1) * b(2) - a(2) * b(1), a(2) * b(0) - a(0) * b(2), a(0) * b(1) - a(1) * b(0))
    val rows0 = Seq(cross(v(1), v(2)), cross(v(2), v(0)), cross(v(0), v(1)))
    val det = (0 until 3).map(i => v(0)(i) * rows0(0)(i)).sum
    val rows = rows0.map(_.map(_ / det))
    val zn = (0 until 3).map(i => (0 until 3).map(k => t.c(k)(2) * rows(k)(i)).sum)
    val screen = v.map(p => (p(0) / p(2), p(1) / p(2)))
    (rows0.map(_.map(_ * math.signum(det))) :+ zn, det, screen)
  }

  def runSetup(core: BorgCore, t: Tri, mem: scala.collection.mutable.Map[BigInt, BigInt],
               bias: (Double, Double, Double) = (0, 0, 0)): Unit = {
    def u(i: Int, f: Double): Unit = writeCore(core, 432 + 4 * i, floatToBits(f.toFloat))
    u(BorgSetupRom.uBiasSlope, bias._1); u(BorgSetupRom.uBiasConst, bias._2); u(BorgSetupRom.uRFloor, bias._3)
    for (k <- 0 until 3) {
      u(uX(k), t.c(k)(0)); u(uY(k), t.c(k)(1)); u(uZ(k), t.c(k)(2)); u(uW(k), t.c(k)(3))
    }
    Seq(W / 2, W / 2, W / 2, W / 2, 0.5, 0.25, 1.0, -0.125, -0.375, 0.375)
      .zipWithIndex.foreach { case (f, i) => u(BorgSetupRom.uSx + i, f) }
    core.io.seqBusy.poke(true.B)
    val rec = core.io.record.get
    rec.outBase.poke(recordBase.U); rec.outInterleave.poke(false.B)
    core.io.coreTrigger.valid.poke(true.B); core.io.coreTrigger.isSetup.poke(true.B)
    core.io.coreTrigger.pc.poke(0.U)
    core.clock.step(1)
    core.io.coreTrigger.valid.poke(false.B); core.io.coreTrigger.isSetup.poke(false.B)
    var started = false; var done = false; var cycles = 0
    while (!done && cycles < 20000) {
      if (core.io.gpuMem.get.wr.peek().litToBoolean) {
        mem(core.io.gpuMem.get.addr.peek().litValue) = core.io.gpuMem.get.wdata.peek().litValue
        core.io.gpuMem.get.ready.poke(true.B)
      } else core.io.gpuMem.get.ready.poke(false.B)
      core.clock.step(1); cycles += 1
      val running = core.io.status.running.peek().litToBoolean
      if (running) started = true
      if (started && !running) done = true
    }
    core.io.gpuMem.get.ready.poke(false.B)
    Predef.assert(done, "setup ROM never finished")
  }

  /** Run the draw-mode raster ROM for one pixel; returns r0..r8 and r29. */
  def runRaster(core: BorgCore, x: Int, y: Int): Seq[Double] = {
    core.io.seqBusy.poke(false.B)             // r30/r31 = the pixel centre
    core.io.drawMode.get.poke(true.B)
    core.io.iter(0).x.poke(x.U); core.io.iter(0).y.poke(y.U)
    core.io.coreTrigger.valid.poke(true.B); core.io.coreTrigger.isRast.poke(true.B)
    core.io.coreTrigger.pc.poke(0.U)
    core.clock.step(1)
    core.io.coreTrigger.valid.poke(false.B); core.io.coreTrigger.isRast.poke(false.B)
    var started = false; var done = false; var cycles = 0
    while (!done && cycles < 5000) {
      core.clock.step(1); cycles += 1
      val running = core.io.status.running.peek().litToBoolean
      if (running) started = true
      if (started && !running) done = true
    }
    Predef.assert(done, "raster ROM never finished")
    core.io.drawMode.get.poke(false.B)
    ((0 to 8) :+ 29).map(r => bitsToFloat(readReg(core, r)).toDouble)
  }

  val tests = Tests {
    utest.test("raster_rom_gives_planes_perspective_barycentrics_and_depth") {
      simulate(new BorgCore(config)) { core =>
        println(s"\n--- Draw-mode raster ROM: ${BorgRasterRom.drawInstructions.size} words ---")
        idleInputs(core)
        resetCore(core)
        // A triangle leaning away from the eye (w 1 -> 4), so perspective
        // correction is large; and one with a corner behind the eye.
        val tris = Seq(
          Tri(Seq(Seq(-0.8, -0.8, 0.2, 1.0), Seq(3.2, -2.0, 3.0, 4.0), Seq(-0.8, 2.5, 1.5, 2.5))),
          Tri(Seq(Seq(-0.5, -0.5, 0.3, 1.0), Seq(0.6, -0.4, 0.4, 1.2), Seq(0.2, 1.0, -0.2, -0.5))))
        for ((t, n) <- tris.zipWithIndex) {
          val mem = scala.collection.mutable.Map[BigInt, BigInt]()
          runSetup(core, t, mem)
          // Pass 2 DMAs the record's first words into the uniform bank.
          for (i <- 0 until Record.Image)
            writeCore(core, 432 + 4 * i, mem.getOrElse(BigInt(recordBase + 4 * i), BigInt(0)))
          val (planes, _, _) = reference(t)
          for ((x, y) <- Seq((10, 20), (60, 128), (200, 50), (128, 128), (255, 255))) {
            val (px, py) = (x + 0.5, y + 0.5)
            val e = planes.map { case Seq(a, b, c) => a * px + b * py + c }
            val q = e(0) + e(1) + e(2)
            val lam = (0 until 3).map(k => e(k) / q)
            val z = e(3)
            val got = runRaster(core, x, y)
            val (_, det, _) = reference(t)
            val expect = Seq(e(0), e(1), e(2), z, 1 - z) ++ lam ++ Seq(q / math.abs(det), z * 0.5 + 0.25)
            val scale = planes.map { case Seq(a, b, c) => math.abs(a) * W + math.abs(b) * W + math.abs(c) }
            val tol = Seq(scale(0), scale(1), scale(2), scale(3), scale(3)).map(_ * 3e-5) ++
                      lam.map(l => 1e-4 * math.max(1.0, math.abs(l))) ++ Seq(3e-5 * scale.take(3).sum / math.abs(reference(t)._2), 3e-5 * scale(3))
            for (((g, ex), i) <- got.zip(expect).zipWithIndex) {
              Predef.assert(math.abs(g - ex) <= tol(i), f"tri $n pixel ($x,$y) value $i: $g%.6g vs $ex%.6g")
            }
            if (x == 128 && y == 128)
              println(f"  tri $n at (128,128): lambda = (${got(5)}%.4f, ${got(6)}%.4f, ${got(7)}%.4f), 1/w = ${got(8)}%.4f, z = ${got(9)}%.4f")
          }
        }
        println("  PASSED")
      }
    }

    utest.test("setup_rom_adds_depth_bias") {
      // offset + m * slope + r * constant, m = max(|dz/dx|, |dz/dy|) of
      // FragCoord.z (scale 0.5), r = max(floor, ulp(largest corner |depth|)),
      // or ulp(1.0) with a corner behind the eye.
      simulate(new BorgCore(config)) { core =>
        println("\n--- BorgSetupRom: depth bias ---")
        idleInputs(core)
        resetCore(core)
        val rnd = new scala.util.Random(11)
        def ulp(f: Float) = java.lang.Math.ulp(f).toDouble
        for (n <- 0 until 12) {
          val behind = n >= 9
          val t = Tri((0 until 3).map { k =>
            val w = if (behind && k == 0) -0.4 else 0.5 + 2.5 * rnd.nextDouble()
            Seq((rnd.nextDouble() * 2 - 1) * math.abs(w), (rnd.nextDouble() * 2 - 1) * math.abs(w),
                rnd.nextDouble() * math.abs(w), w)
          })
          val (slope, const, floor) = (if (n % 3 == 0) 0.0 else -1.5 + n, if (n % 2 == 0) 3.0 else -2.0,
                                       if (n % 4 == 1) math.pow(2, -15) else 0.0)
          val mem = scala.collection.mutable.Map[BigInt, BigInt]()
          runSetup(core, t, mem, (slope, const, floor))
          val (planes, _, _) = reference(t)
          val m = 0.5 * math.max(math.abs(planes(3)(0)), math.abs(planes(3)(1)))
          val zmax = if (behind) 1.0 else t.c.map(c => math.abs(0.5 * c(2) / c(3) + 0.25)).max.min(1.0)
          val r = math.max(floor, ulp(zmax.toFloat))
          val expect = 0.25 + m * slope + r * const
          val got = bitsToFloat(mem.getOrElse(BigInt(recordBase + 4 * Record.DepthOffset), BigInt(0x7fc00000))).toDouble
          println(f"  tri $n: m $m%.4g r $r%.3g slope $slope const $const -> $got%.9g (expect $expect%.9g)")
          // The slope term to FP32 rounding of m; the constant term exactly
          // (r is a power of two), to half an ulp of the result.
          Predef.assert(math.abs(got - expect) <= 1e-5 * math.abs(m * slope) + ulp(got.toFloat) / 2,
                        f"tri $n bias: $got vs $expect")
        }
        println("  PASSED")
      }
    }

    utest.test("setup_rom_inverts_the_homogeneous_vertex_matrix") {
      simulate(new BorgCore(config)) { core =>
        println(s"\n--- BorgSetupRom: ${BorgSetupRom.instructions.size} words ---")
        idleInputs(core)
        resetCore(core)
        val rnd = new scala.util.Random(7)
        def corner(behind: Boolean) = {
          val w = if (behind) -(0.2 + rnd.nextDouble()) else 0.5 + 2.5 * rnd.nextDouble()
          val a = math.abs(w)
          Seq((rnd.nextDouble() * 2 - 1) * a * 1.3, (rnd.nextDouble() * 2 - 1) * a * 1.3,
              rnd.nextDouble() * a, w)
        }
        // 16 in front of the eye, 8 with one corner behind it, 4 with two.
        val tris = (0 until 28).map { n =>
          val behind = if (n < 16) 0 else if (n < 24) 1 else 2
          Tri((0 until 3).map(k => corner(k < behind)))
        }
        var worst = 0.0
        for ((t, n) <- tris.zipWithIndex) {
          val mem = scala.collection.mutable.Map[BigInt, BigInt]()
          runSetup(core, t, mem)
          val (planes, det, screen) = reference(t)
          def word(i: Int): Double =
            bitsToFloat(mem.getOrElse(BigInt(recordBase + 4 * i), BigInt(0x7fc00000))).toDouble
          // Plane error measured where it matters: its value over the
          // viewport, relative to the plane's own scale there.
          for (k <- 0 until 4) {
            val Seq(a, b, c) = planes(k)
            val (ha, hb, hc) = (word(3 * k), word(3 * k + 1), word(3 * k + 2))
            val scale = math.abs(a) * W + math.abs(b) * W + math.abs(c)
            for (px <- Seq(0.5, 100.5, 255.5); py <- Seq(0.5, 170.5, 255.5)) {
              val err = math.abs((ha * px + hb * py + hc) - (a * px + b * py + c)) / scale
              worst = math.max(worst, err)
              Predef.assert(err < 2e-5, f"tri $n plane $k at ($px,$py): rel err $err%.2e")
            }
            // Sample deltas: -0.125a - 0.375b and 0.375a - 0.125b.
            val d0 = -0.125 * a - 0.375 * b; val d1 = 0.375 * a - 0.125 * b
            Predef.assert(math.abs(word(Record.covDelta(k)) - d0) <= 2e-5 * scale, s"tri $n covDelta0 $k")
            Predef.assert(math.abs(word(Record.covDelta(k) + 1) - d1) <= 2e-5 * scale, s"tri $n covDelta1 $k")
          }
          Predef.assert(word(Record.DepthScale) == 0.5 && word(Record.DepthOffset) == 0.25 && word(Record.One) == 1.0)
          Predef.assert(math.abs(word(Record.InvDet) - 1 / math.abs(det)) <= 1e-5 / math.abs(det), s"tri $n |1/det|")
          val hwDet = bitsToFloat(readReg(core, 6)).toDouble
          Predef.assert(math.signum(hwDet) == math.signum(det), s"tri $n det sign")
          Predef.assert(math.abs(hwDet - det) <= 1e-5 * math.abs(det), s"tri $n det $hwDet vs $det")
          if (t.c.forall(_(3) > 0)) for (k <- 0 until 3) {
            val (x, y) = (bitsToFloat(readReg(core, 2 * k)), bitsToFloat(readReg(core, 2 * k + 1)))
            Predef.assert(math.abs(x - screen(k)._1) < 1e-3 && math.abs(y - screen(k)._2) < 1e-3,
                          s"tri $n corner $k at ($x, $y), expected ${screen(k)}")
          }
        }
        println(f"  ${tris.size} triangles (12 with corners behind the eye): worst plane error $worst%.2e")
        println("  PASSED")
      }
    }
  }
}
