// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** BorgSampler against a reference of Vulkan's sampling rules, with the
  * hardware's declared precision (8 sub-texel bits, 8 LOD fraction bits):
  * every format's decode, non-square and non-power-of-two sizes, the
  * address modes and border colours, bilinear, mipmaps (explicit and from
  * the quad's derivatives, trilinear), 3D, arrays, depth compare, gather,
  * texelFetch and offsets, tiled and linear layouts. */
object BorgSamplerTests extends TestSuite {
  import SamplerCtl._
  val cfg = BorgConfig.Test.copy(fragLanes = 4)
  val TexBase = 0x1000; val SampBase = 0x1800; val Data = 0x4000

  def fbits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  def bitsf(b: Long): Float = java.lang.Float.intBitsToFloat(b.toInt)

  // --- Reference decode (exact math, independent of the RTL) --------------
  def decode(fm: TexFormat.Fmt, raw: BigInt): Seq[Either[Long, Double]] = {
    import TexFormat._
    val exp5 = ((raw >> 27) & 31).toInt
    (0 until 4).map { c =>
      val (off, w) = fm.ch(c)
      if (w == 0) { if (c == 3) (if (fm.isInt) Left(1L) else Right(1.0)) else (if (fm.isInt) Left(0L) else Right(0.0)) }
      else {
        val v = ((raw >> off) & ((BigInt(1) << w) - 1)).toLong
        val s = if (v >= (1L << (w - 1))) v - (1L << w) else v
        fm.kind match {
          case UNORM => Right(v / ((1L << w) - 1).toDouble)
          case SNORM => Right(math.max(s / ((1L << (w - 1)) - 1).toDouble, -1.0))
          case UINT  => Left(v)
          case SINT  => Left(s & 0xFFFFFFFFL)
          case SFLOAT => Right(if (w == 16) halfToFloat(v.toInt).toDouble else bitsf(v).toDouble)
          case SRGB => Right(if (c == 3) v / 255.0 else {
            val x = v / 255.0; if (x <= 0.04045) x / 12.92 else math.pow((x + 0.055) / 1.055, 2.4) })
          case UFLOAT => val mb = w - 5; val e = (v >> mb).toInt; val m = v & ((1L << mb) - 1)
            Right(if (e == 0) m * math.pow(2, -14 - mb) else (1 + m / math.pow(2, mb)) * math.pow(2, e - 15))
          case SHAREDEXP => Right(v * math.pow(2, exp5 - 15 - 9))
        }
      }
    }
  }
  def halfToFloat(h: Int): Float = {
    val e = (h >> 10) & 31; val m = h & 1023; val sgn = if ((h >> 15) != 0) -1 else 1
    if (e == 0) sgn * m * math.pow(2, -24).toFloat
    else if (e == 31) (if (m == 0) sgn * Float.PositiveInfinity else Float.NaN)
    else sgn * (1 + m / 1024f) * math.pow(2, e - 15).toFloat
  }

  // --- Textures in memory -------------------------------------------------
  case class Tex(fm: TexFormat.Fmt, w: Int, h: Int, d: Int = 1, levels: Int = 1,
                 layout: Int = Tiled, ttype: Int = T2D, base: Int = Data) {
    def lw(l: Int) = math.max(1, w >> l)
    def lh(l: Int) = if (ttype == T1D) 1 else math.max(1, h >> l)
    def ld(l: Int) = if (ttype == T3D) math.max(1, d >> l) else 1
    def layers = if (ttype == T3D) 1 else d
    def tpr(l: Int) = (lw(l) + 3) / 4
    def slice(l: Int) = tpr(l) * ((lh(l) + 3) / 4) * 16
    def levelBytes(l: Int) = slice(l) * ld(l) * fm.bytes
    def levelOff(l: Int) = (0 until l).map(levelBytes).sum
    def layerStride = if (layout == Linear) w * fm.bytes else (0 until levels).map(levelBytes).sum
    def addr(x: Int, y: Int, z: Int, layer: Int, l: Int): Int =
      if (layout == Linear) base + y * w * fm.bytes + x * fm.bytes
      else base + levelOff(l) + layer * layerStride +
        (z * slice(l) + ((y >> 2) * tpr(l) + (x >> 2)) * 16 + (y & 3) * 4 + (x & 3)) * fm.bytes
    def desc: Seq[Long] = Seq(base.toLong, ((h - 1).toLong << 16) | (w - 1),
      ((d - 1).toLong) | ((levels - 1).toLong << 12) | (fm.code.toLong << 16) | (layout.toLong << 24) | (ttype.toLong << 26),
      layerStride.toLong) ++ (1 to 12).map(l => if (l < levels) levelOff(l).toLong else 0L)
  }
  case class Samp(magLin: Boolean = false, minLin: Boolean = false, mipLin: Boolean = false,
                  mode: (Int, Int, Int) = (ClampEdge, ClampEdge, ClampEdge), border: Int = 0,
                  cmp: Option[Int] = None, unnorm: Boolean = false,
                  bias: Float = 0, minLod: Float = 0, maxLod: Float = 1000) {
    def desc: Seq[Long] = Seq(
      (if (magLin) 1L else 0) | (if (minLin) 2L else 0) | (if (mipLin) 4L else 0) |
      (mode._1.toLong << 3) | (mode._2.toLong << 6) | (mode._3.toLong << 9) | (border.toLong << 12) |
      (if (cmp.isDefined) 1L << 15 else 0) | (cmp.getOrElse(0).toLong << 16) | (if (unnorm) 1L << 19 else 0),
      fbits(bias), fbits(minLod), fbits(maxLod))
  }

  class Mem {
    val bytes = scala.collection.mutable.Map[Int, Int]()
    def word(a: Int): Long = (0 until 4).map(i => bytes.getOrElse(a + i, 0).toLong << (8 * i)).sum
    def putWord(a: Int, v: Long): Unit = for (i <- 0 until 4) bytes(a + i) = ((v >> (8 * i)) & 0xFF).toInt
    def putTexel(a: Int, raw: BigInt, n: Int): Unit = for (i <- 0 until n) bytes(a + i) = ((raw >> (8 * i)) & 0xFF).toInt
  }
  /** Fill a texture with random texels; returns texel(x, y, z, layer, level). */
  def fill(mem: Mem, t: Tex, rnd: scala.util.Random): (Int, Int, Int, Int, Int) => BigInt = {
    val data = scala.collection.mutable.Map[(Int, Int, Int, Int, Int), BigInt]()
    for (l <- 0 until t.levels; layer <- 0 until t.layers; z <- 0 until t.ld(l); y <- 0 until t.lh(l); x <- 0 until t.lw(l)) {
      var raw = BigInt(8 * t.fm.bytes, rnd)
      if (t.fm.kind == TexFormat.SFLOAT) {   // finite values in [-2, 2)
        raw = (0 until 4).foldLeft(BigInt(0)) { (acc, c) =>
          val (off, w) = t.fm.ch(c)
          if (w == 0) acc else {
            val f = (rnd.nextDouble() * 4 - 2).toFloat
            val b = if (w == 16) BigInt(Fp16Bits(f)) else BigInt(fbits(f))
            acc | (b << off)
          }
        }
      }
      // No infinities or NaNs: clear each small float's top exponent bit.
      if (t.fm.kind == TexFormat.UFLOAT) raw = raw & ~((BigInt(1) << 10) | (BigInt(1) << 21) | (BigInt(1) << 31))
      data((x, y, z, layer, l)) = raw
      mem.putTexel(t.addr(x, y, z, layer, l), raw, t.fm.bytes)
    }
    (x, y, z, la, l) => data((x, y, z, la, l))
  }
  def Fp16Bits(f: Float): Int = {
    val b = java.lang.Float.floatToRawIntBits(f); val s = (b >>> 16) & 0x8000
    val e = ((b >>> 23) & 0xFF) - 127 + 15; val m = b & 0x7FFFFF
    if (f == 0) s else if (e <= 0) s else s | (e << 10) | (m >> 13)
  }

  // --- Reference sampler (the hardware's fixed-point conventions) ----------
  def fixedMod(u: Float, intBits: Int): BigInt = {
    val w = intBits + 24; val m = (BigDecimal(math.abs(u.toDouble)) * BigDecimal(1 << 24)).toBigInt
    val r = if (u < 0) (-m).mod(BigInt(1) << w) else m.mod(BigInt(1) << w); r
  }
  def fixed(u: Float, frac: Int, width: Int): BigInt = {
    val m = (BigDecimal(math.abs(u.toDouble)) * BigDecimal(BigInt(1) << frac)).toBigInt
    val lim = (BigInt(1) << (width - 1)) - 1
    val c = m.min(lim); if (u < 0) -c else c
  }
  def log2q(x: Float): Int = {
    val b = fbits(x); val e = ((b >> 23) & 0xFF).toInt
    if (e == 0) -(1 << 20) else ((e - 127) << 8) + math.round(math.log(1 + ((b >> 17) & 63) / 64.0) / math.log(2) * 256).toInt
  }
  case class Lane(u: Float, v: Float = 0, w: Float = 0, lod: Float = 0, dref: Float = 0, fetch: (Int, Int, Int, Int) = (0, 0, 0, 0))

  def reference(t: Tex, s: Samp, texel: (Int, Int, Int, Int, Int) => BigInt, ctl: Long, lanes: Seq[Lane]): Seq[Seq[Either[Long, Double]]] = {
    val opc = ((ctl >> 16) & 3).toInt; val isFetch = opc == OpFetch; val isGather = opc == OpGather
    val lodMode = ((ctl >> 21) & 3).toInt
    def sx4(v: Long) = { val x = (v & 15).toInt; if (x >= 8) x - 16 else x }
    val offs = Seq(sx4(ctl >> 23), sx4(ctl >> 27), 0)
    val dims = t.ttype match { case T1D => 1; case T3D => 3; case _ => 2 }
    val quadLod = if (isFetch || isGather || lodMode == LodExplicit || s.unnorm) 0 else {
      def fma(a: Float, b: Float, c: Float) = Math.fma(a, b, c)
      val (dux, dvx) = (lanes(1).u - lanes(0).u, lanes(1).v - lanes(0).v)
      val (duy, dvy) = (lanes(2).u - lanes(0).u, lanes(2).v - lanes(0).v)
      val rx = fma(math.abs(dvx), t.h.toFloat, math.abs(dux) * t.w.toFloat)
      val ry = fma(math.abs(dvy), t.h.toFloat, math.abs(duy) * t.w.toFloat)
      log2q(math.max(rx, ry))
    }
    lanes.map { ln =>
      val q = (t.levels - 1) << 8
      val base0 = lodMode match {
        case LodBias => quadLod + fixed(ln.lod, 8, 24).toInt
        case LodExplicit => fixed(ln.lod, 8, 24).toInt
        case _ => quadLod
      }
      val biased = base0 + fixed(s.bias, 8, 24).toInt
      val lam = biased.max(fixed(s.minLod, 8, 24).toInt).min(fixed(s.maxLod, 8, 24).toInt)
      val mag = lam <= 0
      val linear = if (isFetch) false else if (isGather) true else if (mag) s.magLin else s.minLin
      val lp = if (mag) 0 else lam
      val lv: Seq[(Int, Int)] =
        if (isFetch) Seq((math.min(ln.fetch._4, t.levels - 1), 256))
        else if (isGather || mag || t.levels == 1) Seq((0, 256))
        else if (lp >= q) Seq((t.levels - 1, 256))
        else if (!s.mipLin) Seq((math.min((lp + 127) >> 8, t.levels - 1), 256))
        else { val fl = lp & 255; if (fl == 0) Seq((lp >> 8, 256)) else Seq((lp >> 8, 256 - fl), ((lp >> 8) + 1, fl)) }
      val lsrc = if (t.ttype == T1D) ln.v else ln.w
      val layer = if (t.ttype == T3D) 0 else if (isFetch) math.min(math.max(ln.fetch._3, 0), t.d - 1)
                  else (((fixed(lsrc, 1, 18) + 1) >> 1).toInt).max(0).min(t.d - 1)
      val coords = Seq(ln.u, ln.v, ln.w)
      var acc = Seq.fill(4)(0.0f); var gather = Seq.fill(4)(0L); var singleVal: Seq[Either[Long, Double]] = null
      val nLev = lv.size
      for ((l, lw) <- lv) {
        val size = Seq(t.lw(l), t.lh(l), t.ld(l))
        val mode = Seq(s.mode._1, s.mode._2, s.mode._3)
        val axes = (0 until dims).map { a =>
          val c = coords(a)
          val x8: BigInt =
            if (isFetch) BigInt(Seq(ln.fetch._1, ln.fetch._2, ln.fetch._3)(a)) << 8
            else if (s.unnorm) fixed(c, 8, 28)
            else {
              val f = mode(a) match { case Repeat => fixedMod(c, 0); case Mirror => fixedMod(c, 1); case _ => fixed(c, 24, 28) }
              (f * size(a)) >> 16
            }
          val xs = if (linear) x8 - 128 else x8
          val first = (xs >> 8).toInt + (if (isFetch) 0 else offs(a))
          val frac = if (linear) (xs & 255).toInt else 0
          def wrap(i0: Int): (Int, Boolean) = {
            val sz = size(a); var i = i0
            if (isFetch) (i.max(0).min(sz - 1), false)
            else mode(a) match {
              case Repeat => i = ((i % sz) + sz) % sz; (i, false)
              case Mirror => i = ((i % (2 * sz)) + 2 * sz) % (2 * sz); (if (i >= sz) 2 * sz - 1 - i else i, false)
              case MirrorClampEdge => val m = if (i < 0) -1 - i else i; (math.min(m, sz - 1), false)
              case ClampBorder => (i, i < 0 || i >= sz)
              case _ => (i.max(0).min(sz - 1), false)
            }
          }
          (wrap(first), wrap(first + 1), frac)
        }
        val nTaps = if (isGather) 4 else if (linear && !isFetch) 1 << dims else 1
        for (tp <- 0 until nTaps) {
          val tt = if (isGather) Seq(2, 3, 1, 0)(tp) else tp
          val picked = (0 until dims).map(a => if (((tt >> a) & 1) == 1 && linear) axes(a)._2 else axes(a)._1)
          val border = picked.exists(_._2)
          val pc = picked.map(_._1) ++ Seq.fill(3 - dims)(0)
          val z = if (t.ttype == T3D) pc(2) else 0
          val y = if (dims >= 2) pc(1) else 0
          var vals: Seq[Either[Long, Double]] =
            if (border) {
              val one: Either[Long, Double] = if (t.fm.isInt) Left(1L) else Right(1.0)
              val zero: Either[Long, Double] = if (t.fm.isInt) Left(0L) else Right(0.0)
              Seq(if (s.border >= 4) one else zero, if (s.border >= 4) one else zero,
                  if (s.border >= 4) one else zero, if (s.border >= 2) one else zero)
            } else decode(t.fm, texel(pc(0), y, z, layer, l))
          s.cmp.foreach { op =>
            val dt = vals(0).fold(_.toDouble, identity); val dr = ln.dref.toDouble
            val pass = op match { case 0 => false; case 1 => dr < dt; case 2 => dr == dt; case 3 => dr <= dt
                                  case 4 => dr > dt; case 5 => dr != dt; case 6 => dr >= dt; case _ => true }
            vals = Seq(Right(if (pass) 1.0 else 0.0), Right(0.0), Right(0.0), Right(1.0))
          }
          if (isGather) gather = gather.updated(tp, vals(((ctl >> 18) & 3).toInt).fold(identity, d => fbits(d.toFloat)))
          else if ((nTaps == 1 && nLev == 1) || t.fm.isInt) singleVal = vals
          else {
            def aw(a: Int) = if (a >= dims || !linear) 256 else if (((tt >> a) & 1) == 1) axes(a)._3 else 256 - axes(a)._3
            val wInt = BigInt(aw(0)) * aw(1) * aw(2) * lw
            // uintToFp32 truncates to 24 significant bits
            val wt = { val bl = wInt.bitLength; val sh = math.max(0, bl - 24); ((wInt >> sh) << sh).toDouble / math.pow(2, 32) }.toFloat
            acc = acc.indices.map(c => Math.fma(wt, vals(c).fold(_.toFloat, _.toFloat), acc(c)))
          }
        }
      }
      if (isGather) gather.map(Left(_))
      else if (singleVal != null) singleVal
      else acc.map(a => Right(a.toDouble))
    }
  }

  // --- Driving the unit ------------------------------------------------------
  def run(d: BorgSampler, mem: Mem, ctl: Long, lanes: Seq[Lane], active: Seq[Boolean] = Seq.fill(4)(true)): Seq[Seq[Long]] = {
    d.io.req.bits.ctl.poke(ctl.U)
    for (i <- 0 until 4) {
      val l = lanes(i); val p = d.io.req.bits.lane(i)
      if (((ctl >> 16) & 3) == OpFetch) {
        p.u.poke(l.fetch._1.U); p.v.poke(l.fetch._2.U); p.w.poke(l.fetch._3.U); p.lod.poke(l.fetch._4.U)
      } else {
        p.u.poke(fbits(l.u).U); p.v.poke(fbits(l.v).U); p.w.poke(fbits(l.w).U); p.lod.poke(fbits(l.lod).U)
      }
      p.dref.poke(fbits(l.dref).U)
      d.io.req.bits.active(i).poke(active(i).B)
    }
    d.io.req.valid.poke(true.B); d.clock.step(1); d.io.req.valid.poke(false.B)
    val out = Array.fill(4)(Seq.fill(4)(0L))
    var done = false; var cycles = 0
    d.io.respReady.poke(true.B)
    while (!done && cycles < 200000) {
      if (d.io.gpuMem.req.peek().litToBoolean) {
        d.io.gpuMem.data.poke(mem.word(d.io.gpuMem.addr.peek().litValue.toInt).U)
        d.io.gpuMem.ready.poke(true.B)
      } else d.io.gpuMem.ready.poke(false.B)
      if (d.io.resp.valid.peek().litToBoolean) {
        val ln = d.io.resp.bits.lane.peek().litValue.toInt
        out(ln) = (0 until 4).map(c => d.io.resp.bits.data(c).peek().litValue.toLong)
      }
      if (d.io.done.peek().litToBoolean) done = true
      d.clock.step(1); cycles += 1
    }
    d.io.gpuMem.ready.poke(false.B)
    Predef.assert(done, "sampler never finished")
    out.toSeq
  }

  def check(name: String, got: Seq[Seq[Long]], exp: Seq[Seq[Either[Long, Double]]], active: Seq[Boolean] = Seq.fill(4)(true)): Unit =
    for (i <- 0 until 4 if active(i); c <- 0 until 4) {
      val g = got(i)(c)
      exp(i)(c) match {
        case Left(v) => Predef.assert(g == v, f"$name lane $i ch $c: got 0x$g%x, expected 0x$v%x")
        case Right(v) =>
          val gf = bitsf(g).toDouble
          val ok = (gf.isNaN && v.isNaN) || math.abs(gf - v) <= 2e-5 * math.max(1.0, math.abs(v)) + 1e-6
          Predef.assert(ok, f"$name lane $i ch $c: got $gf%.7g, expected $v%.7g")
      }
    }

  def setup(mem: Mem, texs: Seq[Tex], samps: Seq[Samp]): Unit = {
    for ((t, i) <- texs.zipWithIndex; (w, k) <- t.desc.zipWithIndex) mem.putWord(TexBase + 64 * i + 4 * k, w)
    for ((s, i) <- samps.zipWithIndex; (w, k) <- s.desc.zipWithIndex) mem.putWord(SampBase + 16 * i + 4 * k, w)
  }
  def withSampler(body: BorgSampler => Unit): Unit = simulate(new BorgSampler(cfg)) { d =>
    d.io.req.valid.poke(false.B); d.io.respReady.poke(false.B); d.io.invalidate.poke(false.B)
    d.io.gpuMem.ready.poke(false.B); d.io.gpuMem.data.poke(0.U); d.io.gpuMem.waccept.poke(false.B)
    d.io.texBase.poke(TexBase.U); d.io.sampBase.poke(SampBase.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(1)
    body(d)
  }
  /** One sample request for four lanes: set up, run, compare. */
  def sample(d: BorgSampler, name: String, t: Tex, s: Samp, ctl: Long, lanes: Seq[Lane], seed: Int = 1): Unit = {
    val mem = new Mem
    setup(mem, Seq(t), Seq(s))
    val texel = fill(mem, t, new scala.util.Random(seed))
    d.io.invalidate.poke(true.B); d.clock.step(1); d.io.invalidate.poke(false.B)
    check(name, run(d, mem, ctl, lanes), reference(t, s, texel, ctl, lanes))
  }

  val tests = Tests {
    utest.test("every_format_decodes") {
      withSampler { d =>
        for (fm <- TexFormat.all) {
          val t = Tex(fm, 4, 4)
          // Four texel centres, nearest.
          val lanes = Seq((0, 0), (3, 1), (1, 2), (2, 3)).map { case (x, y) => Lane((x + 0.5f) / 4, (y + 0.5f) / 4) }
          sample(d, fm.name, t, Samp(), LodExplicit.toLong << 21, lanes, seed = fm.code)
        }
        println(s"  ${TexFormat.all.size} formats decode like the reference")
      }
    }

    utest.test("address_modes_borders_and_npot_sizes") {
      withSampler { d =>
        val fm = TexFormat.byName("R8G8B8A8_UNORM")
        var n = 0
        for (mode <- Seq(Repeat, Mirror, ClampEdge, ClampBorder, MirrorClampEdge); lin <- Seq(false, true);
             border <- if (mode == ClampBorder) 0 until 6 else Seq(0)) {
          val t = Tex(fm, 5, 3)                             // not square, not powers of two
          val s = Samp(magLin = lin, minLin = lin, mode = (mode, mode, mode), border = border)
          val lanes = Seq(Lane(-0.3f, 1.7f), Lane(2.61f, -1.05f), Lane(0.99f, 0.01f), Lane(-7.4f, 13.2f))
          sample(d, s"mode $mode lin $lin border $border", t, s, LodExplicit.toLong << 21, lanes, seed = 11 + n)
          n += 1
        }
        println(s"  $n address mode / filter / border combinations on a 5x3 texture")
      }
    }

    utest.test("mipmaps_explicit_implicit_and_trilinear") {
      withSampler { d =>
        val fm = TexFormat.byName("R8G8B8A8_UNORM")
        val t = Tex(fm, 16, 8, levels = 5)                  // 16x8 .. 1x1, non-square chain
        for (mipLin <- Seq(false, true); lod <- Seq(0.0f, 0.6f, 1.5f, 2.25f, 3.9f, 7.0f)) {
          val s = Samp(magLin = true, minLin = true, mipLin = mipLin, mode = (Repeat, Mirror, ClampEdge))
          val lanes = Seq(Lane(0.31f, 0.47f, lod = lod), Lane(0.8f, 0.1f, lod = lod), Lane(0.05f, 0.9f, lod = lod), Lane(0.5f, 0.5f, lod = lod))
          sample(d, s"explicit lod $lod mipLin $mipLin", t, s, LodExplicit.toLong << 21, lanes)
        }
        // Implicit: the quad spans 3 texels in x and 5 in y -> lod ~ log2(8/..).
        for ((du, dv, bias) <- Seq((3.0f / 16, 0f, 0f), (0f, 5.0f / 8, 0f), (1.0f / 64, 1.0f / 64, 0f), (2.0f / 16, 1.0f / 8, 1.25f))) {
          val s = Samp(magLin = true, minLin = true, mipLin = true, mode = (Repeat, Repeat, Repeat), bias = bias, maxLod = 3.5f)
          val (u0, v0) = (0.3f, 0.6f)
          val lanes = Seq(Lane(u0, v0), Lane(u0 + du, v0 + dv), Lane(u0 + dv, v0 + du), Lane(u0 + du + dv, v0 + du + dv))
          sample(d, s"implicit du $du dv $dv bias $bias", t, s, 0L, lanes)
        }
        println("  explicit and implicit LOD, nearest and linear mip selection, bias and clamp")
      }
    }

    utest.test("3d_arrays_linear_layout_and_float_formats") {
      withSampler { d =>
        val lin = Samp(magLin = true, minLin = true, mode = (Repeat, ClampEdge, Mirror))
        val lanes3 = Seq(Lane(0.2f, 0.7f, 0.45f), Lane(0.9f, 0.1f, 0.05f), Lane(0.51f, 0.49f, 0.99f), Lane(-0.1f, 0.3f, 0.6f))
        sample(d, "3D trilinear", Tex(TexFormat.byName("R16G16B16A16_SFLOAT"), 4, 6, d = 3, ttype = T3D), lin,
               LodExplicit.toLong << 21, lanes3)
        val lanesA = Seq(Lane(0.2f, 0.7f, 0f), Lane(0.9f, 0.1f, 1.4f), Lane(0.51f, 0.49f, 2.6f), Lane(0.3f, 0.3f, 9f))
        sample(d, "2D array", Tex(TexFormat.byName("R32G32_SFLOAT"), 7, 5, d = 3), lin, LodExplicit.toLong << 21, lanesA)
        sample(d, "1D array", Tex(TexFormat.byName("R8_UNORM"), 9, 1, d = 4, ttype = T1D), lin, LodExplicit.toLong << 21,
               Seq(Lane(0.1f, 0f), Lane(0.55f, 1f), Lane(0.9f, 3f), Lane(0.33f, 2.4f)))
        sample(d, "linear layout", Tex(TexFormat.byName("B8G8R8A8_UNORM"), 6, 5, layout = Linear), lin,
               LodExplicit.toLong << 21, lanesA)
        sample(d, "B10G11R11", Tex(TexFormat.byName("B10G11R11_UFLOAT_PACK32"), 4, 4), lin, LodExplicit.toLong << 21, lanesA)
        println("  3D, 1D and 2D arrays, linear layout, float formats filtered")
      }
    }

    utest.test("compare_gather_fetch_and_offsets") {
      withSampler { d =>
        val depth = Tex(TexFormat.byName("D16_UNORM"), 8, 8)
        for (op <- Seq(3, 1, 6); lin <- Seq(false, true)) {
          val s = Samp(magLin = lin, minLin = lin, cmp = Some(op))
          val lanes = Seq(0.1f, 0.4f, 0.6f, 0.9f).zipWithIndex.map { case (r, i) => Lane(0.2f + 0.2f * i, 0.37f, dref = r) }
          sample(d, s"compare op $op lin $lin", depth, s, (LodExplicit.toLong << 21) | (1L << 20), lanes)
        }
        val rgba = Tex(TexFormat.byName("R8G8B8A8_UINT"), 6, 6)
        for (comp <- 0 until 4)
          sample(d, s"gather $comp", rgba, Samp(mode = (Repeat, Repeat, Repeat)), (OpGather.toLong << 16) | (comp.toLong << 18),
                 Seq(Lane(0.0f, 0.0f), Lane(0.5f, 0.5f), Lane(0.99f, 0.2f), Lane(0.3f, 0.95f)))
        val mip = Tex(TexFormat.byName("R32_UINT"), 8, 8, levels = 3)
        sample(d, "texelFetch", mip, Samp(), OpFetch.toLong << 16,
               Seq(Lane(0, fetch = (1, 2, 0, 0)), Lane(0, fetch = (3, 3, 0, 1)), Lane(0, fetch = (0, 1, 0, 2)), Lane(0, fetch = (7, 0, 0, 0))))
        for ((ou, ov) <- Seq((1, 0), (-2, 3), (7, -8)))
          sample(d, s"offset ($ou,$ov)", rgba, Samp(mode = (Repeat, ClampEdge, Repeat)),
                 (LodExplicit.toLong << 21) | ((ou & 15).toLong << 23) | ((ov & 15).toLong << 27),
                 Seq(Lane(0.1f, 0.1f), Lane(0.5f, 0.5f), Lane(0.9f, 0.2f), Lane(0.3f, 0.95f)))
        println("  depth compare (PCF), gather, texelFetch across levels, offsets")
      }
    }
  }
}
