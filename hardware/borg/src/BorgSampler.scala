// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The descriptor-based texture unit (docs/B2_texture_unit.md).
  *
  * TEX rd, u, v, ctl samples the texture and sampler that `ctl` names, out of
  * descriptor tables in memory, for every lane of the quad at once, and
  * writes RGBA to rd..rd+3 (TEXA supplies a lane's layer/w, LOD or bias, and
  * depth-compare reference first). What a Vulkan 1.0 sampled image needs, in
  * hardware: any size up to 4096 in each dimension, not just square powers
  * of two; mip chains with nearest or linear level selection and the LOD
  * from the quad's derivatives, bias and clamps; per-axis address modes and
  * the six border colours; nearest, bilinear and trilinear filtering (and 3D);
  * depth compare; gather; texelFetch; texel offsets; and every format in
  * [[TexFormat]].
  *
  * Deliberately sequential: one multiplier, one FP32 FMA (the lanes' own
  * BorgFma) and one field extractor serve every step. A sample costs tens
  * to hundreds of cycles; conformance, not throughput, is the goal, and the
  * area stays that of a small state machine plus two datapath units.
  */
object SamplerCtl {
  // TEX's ctl operand (lane 0's value of rs3; a compile-time constant).
  def texIdx(c: UInt)  = c(7, 0)
  def sampIdx(c: UInt) = c(15, 8)
  def op(c: UInt)      = c(17, 16)   // 0 sample, 1 fetch (texelFetch), 2 gather
  def comp(c: UInt)    = c(19, 18)   // gather component
  def compare(c: UInt) = c(20)       // depth compare against TEXA's dref
  def lodMode(c: UInt) = c(22, 21)   // 0 implicit, 1 implicit + TEXA bias, 2 TEXA lod
  def offU(c: UInt)    = c(26, 23).asSInt
  def offV(c: UInt)    = c(30, 27).asSInt
  // A 3D image's w offset in the bits gather and compare use, which a 3D
  // image cannot: sign in 31, low bits in 20:18.
  def offW(c: UInt)    = Cat(c(31), c(20, 18)).asSInt
  val OpSample = 0; val OpFetch = 1; val OpGather = 2
  val LodImplicit = 0; val LodBias = 1; val LodExplicit = 2

  // Texture descriptor: 16 words at TEX_DESC_BASE + 64*index.
  //   0  base (byte address)
  //   1  width-1 [15:0], height-1 [27:16], type [29:28] (0 1D, 1 2D, 2 3D,
  //      3 cube), layout [30] (0 tiled 4x4, 1 linear)
  //   2  depth or layers-1 [9:0], levels-1 [13:10], format [19:14],
  //      swizzle R [22:20], G [25:23], B [28:26], A [31:29] (VkComponentSwizzle:
  //      0 identity, 1 zero, 2 one, 3-6 R-A)
  //   3  tiled: bytes per array layer (all levels); linear: bytes per row
  //   4.. byte offset of level 1, 2, ... 12 from the base
  // Sampler descriptor: 4 words at SAMPLER_DESC_BASE + 16*index.
  //   0  [0] mag linear, [1] min linear, [2] mip linear, [5:3] [8:6] [11:9]
  //      address mode U V W (VkSamplerAddressMode), [14:12] border colour
  //      (VkBorderColor), [15] compare, [18:16] compare op (VkCompareOp),
  //      [19] unnormalized coordinates
  //   1  mip LOD bias, 2 min LOD, 3 max LOD (FP32)
  val Tiled = 0; val Linear = 1
  val T1D = 0; val T2D = 1; val T3D = 2; val TCube = 3
  val Repeat = 0; val Mirror = 1; val ClampEdge = 2; val ClampBorder = 3; val MirrorClampEdge = 4
  /** maxSamplerLodBias: the shader's and the sampler's bias together are
    * clamped to +-this before they reach the LOD. */
  val MaxLodBias = 15

  /** A texture descriptor's words 0-3 (the driver's layout, for tests). */
  def descHeader(base: Long, w: Int, h: Int, d: Int, levels: Int, format: Int, layout: Int, ttype: Int,
                 stride: Long, swizzle: Seq[Int] = Seq(0, 0, 0, 0)): Seq[Long] = Seq(
    base & 0xFFFFFFFFL,
    ((w - 1).toLong) | ((h - 1).toLong << 16) | (ttype.toLong << 28) | (layout.toLong << 30),
    ((d - 1).toLong) | ((levels - 1).toLong << 10) | (format.toLong << 14) |
      swizzle.zipWithIndex.map { case (s, i) => s.toLong << (20 + 3 * i) }.sum,
    stride & 0xFFFFFFFFL)
}

class SamplerLaneArgs extends Bundle {
  val u = UInt(32.W); val v = UInt(32.W)
  val w = UInt(32.W); val lod = UInt(32.W); val dref = UInt(32.W)
}

class SamplerReq(val lanes: Int) extends Bundle {
  val ctl    = UInt(32.W)
  val active = Vec(lanes, Bool())
  val lane   = Vec(lanes, new SamplerLaneArgs)
}

class SamplerResp(val lanes: Int) extends Bundle {
  val lane = UInt(log2Up(lanes).W)
  val data = Vec(4, UInt(32.W))
}

class BorgSamplerIO(val cfg: BorgConfig) extends Bundle {
  val req        = Flipped(Valid(new SamplerReq(cfg.fragLanes)))  // one-cycle pulse
  val busy       = Output(Bool())
  val resp       = Valid(new SamplerResp(cfg.fragLanes))         // held until respReady
  val respReady  = Input(Bool())
  val done       = Output(Bool())                                // pulse after the last lane
  val texBase    = Input(UInt(GpuMemIO.AddrBits.W))
  val sampBase   = Input(UInt(GpuMemIO.AddrBits.W))
  val invalidate = Input(Bool())                                 // a descriptor base was written
  val gpuMem     = new GpuMemIO
}

/** Seamless cube filtering: where a tap that falls off face f across edge e
  * (0: i = -1, 1: i = n, 2: j = -1, 3: j = n) lands on the neighbouring face.
  * Derived from the face definitions (Vulkan's major-axis table), not
  * written out by hand: the along-edge coordinate k maps to k or n-1-k, the
  * other to 0 or n-1. */
private[borg] object CubeEdges {
  // Major axis M, and the axes s (U) and t (V) run along, per face +X -X +Y -Y +Z -Z.
  private val M = Seq((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
  private val U = Seq((0, 0, -1), (0, 0, 1), (1, 0, 0), (1, 0, 0), (1, 0, 0), (-1, 0, 0))
  private val V = Seq((0, -1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0), (0, -1, 0))
  private def dot(a: (Int, Int, Int), d: (Double, Double, Double)) = a._1 * d._1 + a._2 * d._2 + a._3 * d._3
  /** Direction -> (face, s, t) in [0, 1]. */
  private def project(d: (Double, Double, Double)): (Int, Double, Double) = {
    val f = (0 until 6).maxBy(i => dot(M(i), d))
    val ma = dot(M(f), d)
    (f, (dot(U(f), d) / ma + 1) / 2, (dot(V(f), d) / ma + 1) / 2)
  }
  /** Selectors: 0 -> 0, 1 -> n-1, 2 -> k, 3 -> n-1-k. */
  case class Remap(face: Int, iSel: Int, jSel: Int)
  val table: Seq[Seq[Remap]] = for (f <- 0 until 6) yield for (e <- 0 until 4) yield {
    val n = 64
    def at(k: Int): (Int, Double, Double) = {
      val (i, j) = if (e < 2) (if (e == 0) -1 else n, k) else (k, if (e == 2) -1 else n)
      val sc = 2 * (i + 0.5) / n - 1; val tc = 2 * (j + 0.5) / n - 1
      def c(a: Int) = Seq(M(f), U(f), V(f)).zip(Seq(1.0, sc, tc)).map { case (v, w) => Seq(v._1, v._2, v._3)(a) * w }.sum
      project((c(0), c(1), c(2)))
    }
    val (f0, s0, t0) = at(n / 2 - 8); val (f1, s1, t1) = at(n / 2 + 8)
    require(f0 == f1, s"cube face $f edge $e: the neighbour changes along the edge")
    def sel(a: Double, b: Double): Int =
      if (math.abs(b - a) > 0.1) { if (b > a) 2 else 3 } else if (a < 0.5) 0 else 1
    Remap(f0, sel(s0, s1), sel(t0, t1))
  }
}

class BorgSampler(val cfg: BorgConfig) extends Module {
  require(cfg.totalBits == 32, "the sampler returns FP32")
  import SamplerCtl._
  val io = IO(new BorgSamplerIO(cfg))
  private val N = cfg.fragLanes

  // ===================================================================
  // Fixed-point and float helpers (combinational)
  // ===================================================================

  /** FP32 -> signed fixed point with `frac` fraction bits, saturating to
    * `width` bits. Denormals read as zero. */
  private def fpToFixed(x: UInt, frac: Int, width: Int): SInt = {
    val e = x(30, 23); val sig = Cat(e =/= 0.U, x(22, 0))
    val sh = e.zext - (150 - frac).S                   // value * 2^frac = sig * 2^sh
    val maxMag = ((BigInt(1) << (width - 1)) - 1).U
    val left = sh >= 0.S
    val shl = sh.asUInt(5, 0)
    val big = left && (sh >= (width - 1).S || (sig << shl)(width + 24, width - 1) =/= 0.U)
    val mag = Mux(left, (sig << shl)(width - 2, 0),
                Mux((-sh) >= 32.S, 0.U, (sig >> (-sh).asUInt(5, 0)).pad(width)(width - 2, 0)))
    val m = Mux(big || e === 255.U, maxMag, mag)
    Mux(x(31), -(m.zext), m.zext)(width - 1, 0).asSInt
  }

  /** FP32 mod 2^intBits as unsigned fixed point with 24 fraction bits: the
    * wrap of REPEAT (intBits 0) and MIRRORED_REPEAT (1), done exactly on the
    * float before any scaling, so a coordinate of 1000.25 is as good as 0.25. */
  private def fpToFixedMod(x: UInt, intBits: Int): UInt = {
    val width = intBits + 24
    val e = x(30, 23); val sig = Cat(e =/= 0.U, x(22, 0))
    val sh = e.zext - 126.S                            // value * 2^24 = sig * 2^(e-126)
    val left = sh >= 0.S
    val mag = Mux(left, Mux(sh >= width.S, 0.U, (sig << sh.asUInt(5, 0))(width - 1, 0)),
                Mux((-sh) >= 32.S, 0.U, (sig >> (-sh).asUInt(5, 0)).pad(width)(width - 1, 0)))
    Mux(x(31), (0.U - mag)(width - 1, 0), mag)
  }

  /** A positive float's log2 in signed fixed point, 8 fraction bits: a
    * 65-entry table of log2(1 + i/64), interpolated linearly on the next 8
    * mantissa bits. Without the interpolation it was ~5 bits (up to 0.023
    * low), where Vulkan asks mipmapPrecisionBits of the LOD and the CTS
    * mipmap tests assume 8. */
  // Four extra fraction bits through the interpolation, rounded once at the
  // end: worst error 0.54/256 (the rounding itself), against 1.5/256 at 8
  // bits and 5.9/256 for the bare table.
  private val log2Rom = VecInit((0 to 64).map(i => math.round(math.log(1 + i / 64.0) / math.log(2) * 4096).U(13.W)))
  private def fpLog2(x: UInt): SInt = {
    val i = x(22, 17); val f = x(16, 9)
    val lo = log2Rom(i); val hi = log2Rom(i +& 1.U)
    val frac = (lo +& (((hi - lo) * f) >> 8) +& 8.U) >> 4
    Mux(x(30, 23) === 0.U, (-(1 << 20)).S(24.W),          // 0 -> "minus infinity"
      ((x(30, 23).zext - 127.S) << 8) + frac.zext)
  }

  /** Unsigned int (up to 34 bits) times 2^scale, as FP32 (truncated). */
  private def uintToFp32(v0: UInt, scale: SInt): UInt = {
    val v = v0.pad(34); val w = 34
    val lz = PriorityEncoder(Reverse(v))
    val norm = (v << lz)(w - 1, 0)
    val exp = (127 + w - 1).S - lz.zext + scale
    Mux(v === 0.U, 0.U(32.W), Cat(0.U(1.W), exp.asUInt(7, 0), (norm << 1)(w - 1, w - 23)))
  }

  /** Signed ordered compare key for floats. */
  private def ordered(x: UInt): UInt = Mux(x(31), ~x, x | (1.U << 31))

  // ===================================================================
  // State
  // ===================================================================

  private val states = Enum(24)                            // (two patterns: tuples stop at 22)
  private val (sIdle :: sTexDesc :: sSampDesc :: sLod :: sLodWait :: sLane :: sLaneSetup ::
               sLevel :: sLevelDims :: sLayer :: sAxis :: sAxisMul :: Nil) = states.take(12)
  private val (sWrap :: sTap :: sTapLayer :: sTapMul :: sTapAddr :: sFetch :: sDecode :: sCompare :: sAcc ::
               sAccWait :: sTapNext :: sResp :: Nil) = states.drop(12)
  private val state = RegInit(sIdle)

  private val ctl   = Reg(UInt(32.W))
  private val act   = Reg(Vec(N, Bool()))
  private val args  = Reg(Vec(N, new SamplerLaneArgs))

  // Descriptor caches: one texture and one sampler, tagged by index; any
  // write to either table's base forgets both.
  private val td = Reg(Vec(4, UInt(32.W))); private val tdTag = Reg(UInt(8.W)); private val tdOk = RegInit(false.B)
  private val sd = Reg(Vec(4, UInt(32.W))); private val sdTag = Reg(UInt(8.W)); private val sdOk = RegInit(false.B)
  when(io.invalidate) { tdOk := false.B; sdOk := false.B }
  private val k = RegInit(0.U(4.W))                       // word / step / channel counter

  // Descriptor fields.
  private val base    = td(0)
  private val dimW    = td(1)(15, 0) +& 1.U
  private val dimH    = td(1)(27, 16) +& 1.U
  private val ttype   = td(1)(29, 28)
  private val layout  = td(1)(30)
  private val dimD    = td(2)(9, 0) +& 1.U
  private val levels  = td(2)(13, 10) +& 1.U
  private val fmtCode = td(2)(19, 14)
  private def swz(i: Int): UInt = td(2)(22 + 3 * i, 20 + 3 * i)
  private val stride  = td(3)
  private val fmt     = TexFormat.info(fmtCode)
  private val split16 = layout =/= Linear.U && fmt.bytes === 16.U   // see sTapAddr
  private val magLin  = sd(0)(0); private val minLin = sd(0)(1); private val mipLin = sd(0)(2)
  private def addrMode(a: UInt) = MuxLookup(a, sd(0)(5, 3))(Seq(1.U -> sd(0)(8, 6), 2.U -> sd(0)(11, 9)))
  private val border  = sd(0)(14, 12)
  private val cmpOp   = sd(0)(18, 16)
  private val unnorm  = sd(0)(19)
  private val isFetch  = op(ctl) === OpFetch.U
  private val isGather = op(ctl) === OpGather.U
  private val doCmp    = compare(ctl) && !isFetch && ttype =/= T3D.U     // bit 20 is w's offset on 3D
  private val isInt    = fmt.kind === TexFormat.UINT.U || fmt.kind === TexFormat.SINT.U
  /** Filtered dimensions: 1D 1, 2D and cube 2 (a cube face is 2D), 3D 3. */
  private val dims = Mux(ttype === T3D.U, 3.U, Mux(ttype === T1D.U, 1.U, 2.U))

  private val srgbRom = VecInit(TexFormat.srgbToLinear.map(_.U(32.W)))

  // --- The one FMA: implicit LOD and the filter's weighted sum ---------
  private val fma = Module(new BorgFma(cfg))
  private val fmaA = RegInit(0.U(32.W)); private val fmaB = RegInit(0.U(32.W)); private val fmaC = RegInit(0.U(32.W))
  fma.io.a := fmaA; fma.io.b := fmaB; fma.io.c := fmaC
  fma.io.negate := false.B; fma.io.pipeEn1 := true.B; fma.io.pipeEn2 := true.B
  private val fmaWait = RegInit(0.U(3.W))
  private val fmaLatency = cfg.fmaStages + 1

  // --- The one multiplier ------------------------------------------------
  private val mulA = WireDefault(0.S(30.W)); private val mulB = WireDefault(0.U(GpuMemIO.AddrBits.W))
  private val product = mulA * mulB.zext                 // SInt(63)

  // --- LOD --------------------------------------------------------------
  private val lodTmp = Reg(Vec(6, UInt(32.W)))            // d{u,v,w}/dx, d{u,v,w}/dy
  private val lodSum = Reg(Vec(2, UInt(32.W)))            // the scaled sums in x and y
  private val quadLod = RegInit(0.S(24.W))                // implicit LOD, Q.8

  // --- Per lane ---------------------------------------------------------
  private val lane   = RegInit(0.U(log2Up(N).W))
  private val nLev   = RegInit(1.U(2.W)); private val lvIdx = RegInit(0.U(1.W))
  private val lvl    = Reg(Vec(2, UInt(4.W)))
  private val lvlW   = Reg(Vec(2, UInt(9.W)))             // level weights, 256 = 1
  private val linear = RegInit(false.B)
  private val layerN = RegInit(0.U(12.W))
  private val levelOff = RegInit(0.U(GpuMemIO.AddrBits.W))
  private val lw = RegInit(0.U(16.W)); private val lh = RegInit(0.U(16.W)); private val ld = RegInit(0.U(16.W))
  private val tpr = RegInit(0.U(14.W))                    // tiles per row at this level
  private val sliceTexels = RegInit(0.U(GpuMemIO.AddrBits.W))            // tiled texels per 3D slice
  private val layerOff = RegInit(0.U(GpuMemIO.AddrBits.W))
  // Per axis: first tap, second tap, border flags, fraction.
  private val ax  = RegInit(0.U(2.W))
  private val i0  = Reg(Vec(3, SInt(20.W))); private val i1 = Reg(Vec(3, SInt(20.W)))
  private val b0  = Reg(Vec(3, Bool()));     private val b1 = Reg(Vec(3, Bool()))
  private val fr  = Reg(Vec(3, UInt(8.W)))
  private val wrapEnd = RegInit(0.U(1.W))
  // Per tap.
  private val tap = RegInit(0.U(3.W))
  private val tx = Reg(SInt(20.W)); private val ty = Reg(SInt(20.W)); private val tz = Reg(SInt(20.W))
  private val tBorder = RegInit(false.B)
  private val tLayer = RegInit(0.U(12.W)); private val layerOffTap = RegInit(0.U(GpuMemIO.AddrBits.W))
  // Cube corners: three sub-taps (own face, and across each edge), a third each.
  private val subTap = RegInit(0.U(2.W)); private val tCorner = RegInit(false.B)
  private val tWeight = RegInit(0.U(32.W))
  private val rowTiles = RegInit(0.U(GpuMemIO.AddrBits.W))
  private val addr = RegInit(0.U(GpuMemIO.AddrBits.W))
  private val raw = Reg(Vec(4, UInt(32.W)))
  private val vals = Reg(Vec(4, UInt(32.W)))
  private val acc = Reg(Vec(4, UInt(32.W)))

  io.busy := state =/= sIdle
  io.done := false.B
  io.resp.valid := state === sResp
  io.resp.bits.lane := lane
  // The image view's component swizzle (VkComponentMapping), on the result
  // (gather applied it to its component instead).
  private def oneOf: UInt = Mux(isInt, 1.U(32.W), "h3F800000".U(32.W))
  io.resp.bits.data := Mux(isGather, acc, VecInit((0 until 4).map { i =>
    MuxLookup(swz(i), acc(i))(Seq(1.U -> 0.U, 2.U -> oneOf, 3.U -> acc(0), 4.U -> acc(1), 5.U -> acc(2), 6.U -> acc(3)))
  }))
  io.gpuMem.req := false.B; io.gpuMem.addr := 0.U; io.gpuMem.wr := false.B
  io.gpuMem.wdata := 0.U; io.gpuMem.wlen := 1.U

  private def read(a: UInt)(onData: UInt => Unit): Unit = {
    io.gpuMem.req := true.B; io.gpuMem.addr := a
    when(io.gpuMem.ready) { onData(io.gpuMem.data) }
  }

  // ===================================================================
  // FSM
  // ===================================================================
  switch(state) {
    is(sIdle) {
      when(io.req.valid) {
        ctl := io.req.bits.ctl; act := io.req.bits.active; args := io.req.bits.lane
        k := 0.U
        state := sTexDesc
      }
    }

    // --- Descriptors ---------------------------------------------------
    is(sTexDesc) {
      when(tdOk && tdTag === texIdx(ctl)) { k := 0.U; state := sSampDesc }
        .otherwise {
          read(io.texBase + (texIdx(ctl) << 6) + (k << 2)) { d =>
            td(k(1, 0)) := d
            when(k === 3.U) { tdOk := true.B; tdTag := texIdx(ctl); k := 0.U; state := sSampDesc }
              .otherwise { k := k + 1.U }
          }
        }
    }
    is(sSampDesc) {
      when(isFetch || (sdOk && sdTag === sampIdx(ctl))) { k := 0.U; state := sLod }
        .otherwise {
          read(io.sampBase + (sampIdx(ctl) << 4) + (k << 2)) { d =>
            sd(k(1, 0)) := d
            when(k === 3.U) { sdOk := true.B; sdTag := sampIdx(ctl); k := 0.U; state := sLod }
              .otherwise { k := k + 1.U }
          }
        }
    }

    // --- Implicit LOD: the quad's derivatives, in texels -------------------
    // lane 1 - lane 0 is d/dx, lane 2 - lane 0 d/dy (the DDX/DDY convention).
    // rho = max(|du/dx|*W + |dv/dx|*H + |dw/dx|*D, the same in y), the sum
    // form of the approximation Vulkan allows (w only for 3D); LOD = log2(rho).
    is(sLod) {
      val needQuad = !isFetch && !isGather && lodMode(ctl) =/= LodExplicit.U && (N >= 3).B && !unnorm
      when(!needQuad) {
        quadLod := 0.S; lane := 0.U; state := sLane
      }.otherwise {
        val a = args
        def neg(x: UInt) = Cat(~x(31), x(30, 0))
        def abs(x: UInt) = Cat(0.U(1.W), x(30, 0))
        val one = "h3F800000".U
        val is3D = ttype === T3D.U
        val size = Seq(uintToFp32(dimW, 0.S), uintToFp32(dimH, 0.S), Mux(is3D, uintToFp32(dimD, 0.S), 0.U(32.W)))
        if (N >= 3) {
          // k 0..5: the differences d{u,v,w}/dx, d{u,v,w}/dy into lodTmp;
          // k 6..8 and 9..11: the scaled sums in x and in y.
          val coords = (l: Int) => Seq(a(l).u, a(l).v, a(l).w)
          val step = WireDefault(0.U(4.W)); step := k
          val diffs = (for (l <- Seq(1, 2); c <- 0 until 3) yield (coords(l)(c), neg(coords(0)(c))))
          when(k < 6.U) {
            fmaA := VecInit(diffs.map(_._1))(k); fmaB := one; fmaC := VecInit(diffs.map(_._2))(k)
          }.otherwise {
            val j = Mux(k < 9.U, k - 6.U, k - 9.U)                     // axis 0..2
            val src = Mux(k < 9.U, lodTmp(j), lodTmp(j +& 3.U))
            // 1D arrays carry the layer in v: only u counts (dt = 0 for 1D).
            fmaA := Mux((j === 2.U && !is3D) || (j === 1.U && ttype === T1D.U), 0.U, abs(src))
            fmaB := VecInit(size)(j)
            fmaC := Mux(j === 0.U, 0.U, Mux(k < 9.U, lodSum(0), lodSum(1)))
          }
        }
        fmaWait := fmaLatency.U
        state := sLodWait
      }
    }
    is(sLodWait) {
      when(fmaWait =/= 0.U) { fmaWait := fmaWait - 1.U }
        .otherwise {
          val r = fma.io.out
          when(k < 6.U) { lodTmp(k) := r }
            .elsewhen(k < 9.U) { lodSum(0) := r }
            .otherwise { lodSum(1) := r }
          when(k === 11.U) {
            val rx = lodSum(0); val ry = r
            val rho = Mux(ordered(rx) >= ordered(ry), rx, ry)
            quadLod := fpLog2(rho)
            lane := 0.U
            state := sLane
          }.otherwise {
            k := k + 1.U
            state := sLod
          }
        }
    }

    // --- Lanes ----------------------------------------------------------
    is(sLane) {
      when(act(lane)) { state := sLaneSetup }
        .otherwise {
          acc.foreach(_ := 0.U)
          state := sResp                                   // inactive: answer zeros
        }
    }
    is(sLaneSetup) {
      val a = args(lane)
      // LOD: base, bias, clamps -- all Q.8.
      val q = (levels - 1.U).zext << 8
      // lambda = base + clamp(sampler bias + shader bias, +-maxSamplerLodBias).
      val base0 = Mux(lodMode(ctl) === LodExplicit.U, fpToFixed(a.lod, 8, 24), quadLod)
      val biasSum = fpToFixed(sd(1), 8, 24) +& Mux(lodMode(ctl) === LodBias.U, fpToFixed(a.lod, 8, 24), 0.S)
      val maxB = (MaxLodBias << 8).S
      val biased = base0 + Mux(biasSum > maxB, maxB, Mux(biasSum < -maxB, -maxB, biasSum))
      val minL = fpToFixed(sd(2), 8, 24); val maxL = fpToFixed(sd(3), 8, 24)
      val lam = Mux(biased < minL, minL, Mux(biased > maxL, maxL, biased))
      val mag = lam <= 0.S
      val filt = Mux(isFetch, false.B, Mux(isGather, true.B, Mux(mag, magLin, minLin)))
      val lamPos = Mux(mag, 0.S, lam)
      // Nearest level: ceil(lambda + 0.5) - 1, i.e. round half down.
      val dNear = ((lamPos + 127.S) >> 8).asUInt
      val dFloor = (lamPos >> 8).asUInt
      val lastLvl = levels - 1.U
      when(isFetch) {
        val lv = a.lod(3, 0)                                // texelFetch: an integer level
        lvl(0) := Mux(lv > lastLvl, lastLvl, lv); nLev := 1.U; lvlW(0) := 256.U
      }.elsewhen(isGather || mag || levels === 1.U) {
        lvl(0) := 0.U; nLev := 1.U; lvlW(0) := 256.U
      }.elsewhen(lamPos >= q) {
        lvl(0) := lastLvl; nLev := 1.U; lvlW(0) := 256.U
      }.elsewhen(!mipLin) {
        lvl(0) := Mux(dNear > lastLvl, lastLvl, dNear); nLev := 1.U; lvlW(0) := 256.U
      }.otherwise {
        val fl = lamPos.asUInt(7, 0)
        lvl(0) := dFloor; lvl(1) := dFloor + 1.U
        lvlW(0) := 256.U - fl; lvlW(1) := fl
        nLev := Mux(fl === 0.U, 1.U, 2.U)
      }
      linear := filt
      // Array layer: 1D arrays take it from v, 2D arrays and cubes from w.
      val lsrc = Mux(ttype === T1D.U, a.v, a.w)
      val lr = Mux(isFetch, lsrc(15, 0).asSInt, fpToFixed(lsrc, 1, 18) + 1.S >> 1)  // round
      val lc = Mux(lr < 0.S, 0.U, Mux(lr.asUInt >= dimD, dimD - 1.U, lr.asUInt))
      layerN := Mux(ttype === T3D.U, 0.U, lc)
      acc.foreach(_ := 0.U)
      lvIdx := 0.U
      state := sLevel
    }

    // --- Levels ---------------------------------------------------------
    is(sLevel) {
      val l = lvl(lvIdx)
      when(l === 0.U) { levelOff := 0.U; state := sLevelDims }
        .otherwise {
          read(io.texBase + (texIdx(ctl) << 6) + ((l +& 3.U) << 2)) { d =>
            levelOff := d; state := sLevelDims
          }
        }
    }
    is(sLevelDims) {
      val l = lvl(lvIdx)
      def shr(x: UInt) = { val s = x >> l; Mux(s === 0.U, 1.U, s) }
      val w = shr(dimW); val h = Mux(ttype === T1D.U, 1.U, shr(dimH))
      val d = Mux(ttype === T3D.U, shr(dimD), 1.U)
      lw := w; lh := h; ld := d
      tpr := (w +& 3.U) >> 2
      // texels per 3D slice: tiles per row * tile rows * 16
      mulA := ((w +& 3.U) >> 2).zext; mulB := ((h +& 3.U) >> 2) << 4
      sliceTexels := product.asUInt(GpuMemIO.AddrBits - 1, 0)
      state := sLayer
    }
    is(sLayer) {
      mulA := layerN.zext; mulB := stride
      layerOff := product.asUInt(GpuMemIO.AddrBits - 1, 0)
      ax := 0.U; wrapEnd := 0.U
      state := sAxis
    }

    // --- Axes: coordinate -> texel -----------------------------------------
    is(sAxis) {
      when(ax >= dims) {
        tap := 0.U; state := sTap
      }.otherwise {
        state := sAxisMul
      }
    }
    is(sAxisMul) {
      val a = args(lane)
      val c = MuxLookup(ax, a.u)(Seq(1.U -> a.v, 2.U -> a.w))
      val size = MuxLookup(ax, lw)(Seq(1.U -> lh, 2.U -> ld))
      val mode = addrMode(ax)
      // x = coordinate * size, Q.8: the wrap of REPEAT/MIRRORED_REPEAT happens
      // on the float first (exact), clamping modes clamp it to [-4, 4).
      val repeatF = fpToFixedMod(c, 0)                    // Q0.24
      val mirrorF = fpToFixedMod(c, 1)                    // Q1.24
      val clampF  = fpToFixed(c, 24, 28)                  // Q3.24
      mulA := Mux(mode === Repeat.U, repeatF.zext, Mux(mode === Mirror.U, mirrorF.zext, clampF))
      mulB := size
      val scaled = (product >> 16).asSInt                 // Q.8
      val x8 = Mux(isFetch, (c(19, 0).asSInt << 8), Mux(unnorm, fpToFixed(c, 8, 28), scaled))
      val off = MuxLookup(ax, 0.S(4.W))(Seq(0.U -> offU(ctl), 1.U -> offV(ctl),
                                            2.U -> Mux(ttype === T3D.U, offW(ctl), 0.S)))
      val xs = Mux(linear, x8 - 128.S, x8)                // texel centres at +0.5
      val first = (xs >> 8) + Mux(isFetch, 0.S, off)
      i0(ax) := first; i1(ax) := first + 1.S
      fr(ax) := Mux(linear, xs(7, 0).asUInt, 0.U)
      wrapEnd := 0.U
      state := sWrap
    }
    is(sWrap) {
      // Wrap one end per cycle; REPEAT and MIRRORED_REPEAT may need several
      // steps when an offset pushes a small texture's coordinate far out.
      val size = MuxLookup(ax, lw)(Seq(1.U -> lh, 2.U -> ld)).zext
      val mode = addrMode(ax)
      val i = Mux(wrapEnd === 0.U, i0(ax), i1(ax))
      def set(v: SInt, bord: Bool): Unit = when(wrapEnd === 0.U) { i0(ax) := v; b0(ax) := bord }
                                                .otherwise        { i1(ax) := v; b1(ax) := bord }
      val period = Mux(mode === Mirror.U, size << 1, size)
      val done = WireDefault(true.B)
      when(isFetch || (ttype === TCube.U && !linear)) {
        set(Mux(i < 0.S, 0.S, Mux(i >= size, size - 1.S, i)), false.B)
      }.elsewhen(ttype === TCube.U) {
        set(i, false.B)                                    // resolved per tap: seamless
      }.elsewhen(mode === Repeat.U || mode === Mirror.U) {
        when(i < 0.S) { set(i + period, false.B); done := false.B }
          .elsewhen(i >= period) { set(i - period, false.B); done := false.B }
          .otherwise {
            set(Mux(mode === Mirror.U && i >= size, period - 1.S - i, i), false.B)
          }
      }.elsewhen(mode === MirrorClampEdge.U) {
        val m = Mux(i < 0.S, -1.S - i, i)
        set(Mux(m >= size, size - 1.S, m), false.B)
      }.elsewhen(mode === ClampBorder.U) {
        set(i, i < 0.S || i >= size)
      }.otherwise {                                        // CLAMP_TO_EDGE
        set(Mux(i < 0.S, 0.S, Mux(i >= size, size - 1.S, i)), false.B)
      }
      when(done) {
        when(wrapEnd === 0.U && linear) { wrapEnd := 1.U }
          .otherwise { ax := ax + 1.U; state := sAxis }
      }
    }

    // --- Taps -------------------------------------------------------------
    is(sTap) {
      // Tap bit a picks axis a's second texel. Gather's order is Vulkan's:
      // (i0,j1), (i1,j1), (i1,j0), (i0,j0).
      val t = Mux(isGather, VecInit(2.U, 3.U, 1.U, 0.U)(tap(1, 0)), tap)
      def pick(a: Int) = Mux(t(a) && linear, i1(a), i0(a))
      def bord(a: Int) = Mux(t(a) && linear, b1(a), b0(a))
      val used = (0 until 3).map(a => a.U < dims)
      tx := pick(0); ty := Mux(used(1), pick(1), 0.S); tz := Mux(used(2), pick(2), 0.S)
      tBorder := (0 until 3).map(a => used(a) && bord(a)).reduce(_ || _)
      tLayer := layerN
      // Weight: the product of each used axis's (1 - f) or f, and the level's.
      def aw(a: Int) = Mux(!linear || !used(a), 256.U(9.W), Mux(t(a), fr(a).pad(9), 256.U - fr(a)))
      val wInt = aw(0) * aw(1) * aw(2) * lvlW(lvIdx)                 // 36 bits, 2^32 = 1
      tWeight := uintToFp32(wInt(33, 0), (-32).S)
      tCorner := false.B
      when(ttype === TCube.U) {
        // Seamless: a tap off the face reads the neighbouring face; past a
        // corner, the average of the three texels meeting there.
        val n = lw.zext; val x = pick(0); val y = pick(1)
        val iOut = x < 0.S || x >= n; val jOut = y < 0.S || y >= n
        def clampC(v: SInt) = Mux(v < 0.S, 0.S, Mux(v >= n, n - 1.S, v))
        def across(e: UInt, k: SInt): Unit = {
          val tbl = VecInit(CubeEdges.table.map(row => VecInit(row.map(r => (r.face * 16 + r.iSel * 4 + r.jSel).U(7.W)))))
          val r = tbl(layerN(2, 0))(e)
          def pos(sel: UInt) = MuxLookup(sel, 0.S)(Seq(1.U -> (n - 1.S), 2.U -> k, 3.U -> (n - 1.S - k)))
          tLayer := r(6, 4); tx := pos(r(3, 2)); ty := pos(r(1, 0))
        }
        val eI = Mux(x < 0.S, 0.U, 1.U); val eJ = Mux(y < 0.S, 2.U, 3.U)
        when(iOut && jOut) {
          tCorner := true.B
          tWeight := uintToFp32(((wInt * 21845.U) >> 16)(33, 0), (-32).S)   // a third
          switch(subTap) {
            is(0.U) { tx := clampC(x); ty := clampC(y) }
            is(1.U) { across(eI, clampC(y)) }
            is(2.U) { across(eJ, clampC(x)) }
          }
        }.elsewhen(iOut) { across(eI, y) }
          .elsewhen(jOut) { across(eJ, x) }
      }
      state := sTapLayer
    }
    is(sTapLayer) {
      mulA := tLayer.zext; mulB := stride
      layerOffTap := product.asUInt(GpuMemIO.AddrBits - 1, 0)
      state := sTapMul
    }
    is(sTapMul) {
      // Tile row: (y / 4) * tiles per row. Linear layout: y * row pitch.
      mulA := Mux(layout === Linear.U, ty, ty >> 2); mulB := Mux(layout === Linear.U, stride, tpr)
      rowTiles := product.asUInt(GpuMemIO.AddrBits - 1, 0)
      state := sTapAddr
    }
    is(sTapAddr) {
      val x = tx.asUInt; val y = ty.asUInt
      val shift = Log2(fmt.bytes)
      mulA := tz; mulB := sliceTexels
      val texel = ((rowTiles + (x >> 2)) << 4) + (y(1, 0) << 2) + x(1, 0) + product.asUInt(GpuMemIO.AddrBits - 1, 0)
      val tiled = base + levelOff + layerOffTap + (texel << shift)
      // A 16-byte tiled texel is two 8-byte halves, 128 bytes apart in its
      // 256-byte tile (bytes 0-7 of the tile's texels, then 8-15): what a
      // RAW128 colour attachment renders in its two slices. Word k is then
      // at +128*(k >> 1) + 4*(k & 1) from the first half.
      val tiledSplit = base + levelOff + layerOffTap + ((texel >> 4) << 8) + (texel(3, 0) << 3)
      val lin   = base + rowTiles + (x << shift)
      addr := Mux(layout === Linear.U, lin, Mux(split16, tiledSplit, tiled))(GpuMemIO.AddrBits - 1, 0)
      k := 0.U
      state := Mux(tBorder, sDecode, sFetch)
    }
    is(sFetch) {
      val words = Mux(fmt.bytes >= 4.U, fmt.bytes >> 2, 1.U)
      val wordOff = Mux(split16, Cat(k(1), 0.U(4.W), k(0), 0.U(2.W)), k << 2)   // 128*(k>>1) + 4*(k&1)
      read(Cat(addr(GpuMemIO.AddrBits - 1, 2), 0.U(2.W)) + wordOff) { d =>
        raw(k(1, 0)) := d
        when(k === words - 1.U) { k := 0.U; state := sDecode }.otherwise { k := k + 1.U }
      }
    }
    is(sDecode) {
      // One channel per cycle, k: one field extractor and one sRGB ROM.
      // Sub-word texels are shifted down by their byte offset first.
      val all = Cat(raw(3), raw(2), raw(1), raw(0)) >> Mux(fmt.bytes < 4.U, addr(1, 0) << 3, 0.U)
      val w = fmt.bits(k(1, 0))
      val field = ((all >> fmt.off(k(1, 0))) & ((1.U(33.W) << w) - 1.U))(31, 0)
      val sext = ((field << (32.U - w))(31, 0).asSInt >> (32.U - w)).asUInt
      val oneF = "h3F800000".U(32.W)
      val isAlpha = k === 3.U
      val v = MuxLookup(fmt.kind, field)(Seq(
        TexFormat.UNORM.U  -> TexFormat.unormToFp32(field, w),
        TexFormat.SNORM.U  -> TexFormat.snormToFp32(field, w),
        TexFormat.UINT.U   -> field,
        TexFormat.SINT.U   -> sext,
        TexFormat.SFLOAT.U -> Mux(w === 16.U, Fp16Fp32.widen(field(15, 0)), field),
        TexFormat.SRGB.U   -> Mux(isAlpha, TexFormat.unormToFp32(field, w), srgbRom(field(7, 0))),
        TexFormat.UFLOAT.U -> Mux(w === 11.U, TexFormat.ufloatToFp32(field(10, 0), 6),
                                  TexFormat.ufloatToFp32(field(9, 0), 5)),
        TexFormat.SHAREDEXP.U -> TexFormat.uintScaledToFp32(field, all(31, 27).zext - 24.S)))
      // Absent channels: 0, alpha 1. Border colours: VkBorderColor 0..5 --
      // float/int transparent black, opaque black, opaque white.
      val dflt = Mux(isAlpha, Mux(isInt, 1.U(32.W), oneF), 0.U(32.W))
      // The float and int variants differ only in type; Vulkan requires the
      // one matching the format, so the format decides.
      val bOne = Mux(isInt, 1.U(32.W), oneF)
      val bv = Mux(isAlpha, Mux(border >= 2.U, bOne, 0.U), Mux(border >= 4.U, bOne, 0.U))
      // The border colour replaces only the channels the format has; the
      // missing ones read (0, 0, 0, 1) as for any texel.
      vals(k(1, 0)) := Mux(w === 0.U, dflt, Mux(tBorder, bv, v))
      when(k === 3.U) { k := 0.U; state := Mux(doCmp, sCompare, sAcc) }.otherwise { k := k + 1.U }
    }
    is(sCompare) {
      // Depth compare, per tap before filtering: dref OP texel as 1.0 or 0.0.
      // A UNORM depth format clamps the reference to [0, 1] first.
      val d0 = args(lane).dref
      val dClamped = Mux(d0(31), 0.U, Mux(d0 > "h3F800000".U, "h3F800000".U, d0))
      val dref = ordered(Mux(fmt.kind === TexFormat.UNORM.U, dClamped, d0)); val dtex = ordered(vals(0))
      val pass = MuxLookup(cmpOp, false.B)(Seq(
        1.U -> (dref < dtex), 2.U -> (dref === dtex), 3.U -> (dref <= dtex),
        4.U -> (dref > dtex), 5.U -> (dref =/= dtex), 6.U -> (dref >= dtex), 7.U -> true.B))
      vals(0) := Mux(pass, "h3F800000".U, 0.U)
      vals(1) := 0.U; vals(2) := 0.U; vals(3) := "h3F800000".U
      state := sAcc
    }
    is(sAcc) {
      val single = (!linear || isFetch) && nLev === 1.U
      // Gather reads the component the view's swizzle puts at `comp`.
      val gsw = VecInit((0 until 4).map(swz))(comp(ctl))
      val gathered = MuxLookup(gsw, vals(comp(ctl)))(Seq(1.U -> 0.U, 2.U -> oneOf,
        3.U -> vals(0), 4.U -> vals(1), 5.U -> vals(2), 6.U -> vals(3)))
      when(isGather && tCorner && !isInt) {
        // A gathered corner texel is the average of the three, as filtered.
        fmaA := "h3EAAAAAB".U; fmaB := gathered                         // 1/3
        fmaC := Mux(subTap === 0.U, 0.U, acc(tap(1, 0)))
        k := tap(1, 0); fmaWait := fmaLatency.U
        state := sAccWait
      }.elsewhen(isGather) {
        // An integer corner keeps its own face's texel, one of the three:
        // the spec's "may", which only requires equal texels to stay equal.
        when(!tCorner || subTap === 0.U) { acc(tap(1, 0)) := gathered }
        state := sTapNext
      }.elsewhen(single || isInt) {
        acc := vals
        state := sTapNext
      }.otherwise {
        fmaA := tWeight; fmaB := vals(k(1, 0)); fmaC := acc(k(1, 0))
        fmaWait := fmaLatency.U
        state := sAccWait
      }
    }
    is(sAccWait) {
      when(fmaWait =/= 0.U) { fmaWait := fmaWait - 1.U }
        .otherwise {
          acc(k(1, 0)) := fma.io.out
          when(k === 3.U || isGather) { state := sTapNext }.otherwise { k := k + 1.U; state := sAcc }
        }
    }
    is(sTapNext) {
      val nTaps = Mux(isGather, 4.U, Mux(linear && !isFetch, 1.U << dims, 1.U))
      when(tCorner && subTap =/= 2.U) {
        subTap := subTap + 1.U; state := sTap                // the corner's next texel
      }.elsewhen(tap === nTaps - 1.U) {
        subTap := 0.U
        when(lvIdx === nLev - 1.U) { state := sResp }
          .otherwise { lvIdx := lvIdx + 1.U; state := sLevel }
      }.otherwise {
        subTap := 0.U
        tap := tap + 1.U; state := sTap
      }
    }

    // --- Answer the lane ----------------------------------------------------
    is(sResp) {
      if (BorgDebug.trace) when(io.respReady) {
        printf("[TEX] lane %d u=0x%x v=0x%x lvl=%d taps i0=(%d,%d) addr=0x%x -> %x %x %x %x\n", lane,
          args(lane).u, args(lane).v, lvl(0), i0(0), i0(1), addr, acc(0), acc(1), acc(2), acc(3))
      }
      when(io.respReady) {
        when(lane === (N - 1).U) { io.done := true.B; state := sIdle }
          .otherwise { lane := lane + 1.U; state := sLane }
      }
    }
  }
}
