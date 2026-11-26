// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The descriptor-based texture unit (docs/B2_texture_unit.md): TEX rd, u, v, ctl samples
  * the texture and sampler `ctl` names for every lane of the quad and writes RGBA to rd..rd+3.
  * Deliberately sequential (one multiplier, one FMA, one field extractor): conformance over speed.
  */
object SamplerCtl {
  // TEX's ctl operand (the lowest active lane's value of rs3).
  def texIdx(c: UInt)  = c(7, 0)
  def sampIdx(c: UInt) = c(15, 8)
  def op(c: UInt)      = c(17, 16)   // 0 sample, 1 fetch (texelFetch), 2 gather, 3 size (textureSize)
  def comp(c: UInt)    = c(19, 18)   // gather component
  def compare(c: UInt) = c(20)       // depth compare against TEXA's dref
  def lodMode(c: UInt) = c(22, 21)   // 0 implicit, 1 implicit + TEXA bias, 2 TEXA lod, 3 query
  def offU(c: UInt)    = c(26, 23).asSInt
  def offV(c: UInt)    = c(30, 27).asSInt
  // A 3D image's w offset in the bits gather and compare use, which a 3D
  // image cannot: sign in 31, low bits in 20:18.
  def offW(c: UInt)    = Cat(c(31), c(20, 18)).asSInt
  val OpSample = 0; val OpFetch = 1; val OpGather = 2; val OpSize = 3
  val LodImplicit = 0; val LodBias = 1; val LodExplicit = 2; val LodQuery = 3

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
  // maxSamplerLodBias: the shader's and sampler's bias together clamp to +-this.
  val MaxLodBias = 15

  /** A texture descriptor's words 0-3, for tests. */
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
  val texAddr  = UInt(GpuMemIO.AddrBits.W)   // the texture descriptor (64 bytes)
  val sampAddr = UInt(GpuMemIO.AddrBits.W)   // the sampler descriptor (16 bytes)
  val active = Vec(lanes, Bool())
  val lane   = Vec(lanes, new SamplerLaneArgs)
}

class SamplerResp(val lanes: Int) extends Bundle {
  val lane = UInt(log2Up(lanes).W)
  val data = Vec(4, UInt(32.W))
}

/** One level of one lane, from the sampler's setup to its taps. */
class SamplerJob(val lanes: Int) extends Bundle {
  val lane = UInt(log2Up(lanes).W)
  val last = Bool()                                    // the lane's last level: answer after it
  val single = Bool()                                  // one texel of one level: no filter
  val linear = Bool()
  val lvlW = UInt(9.W)                                 // level weight, 256 = 1
  val layerN = UInt(12.W)
  val levelOff = UInt(GpuMemIO.AddrBits.W)
  val lw = UInt(17.W)                                  // a texel buffer reaches 65536
  val tpr = UInt(14.W)                                 // tiles per row at this level
  val sliceTexels = UInt(GpuMemIO.AddrBits.W)          // tiled texels per 3D slice
  // Per axis: first tap, second tap, border flags, fraction.
  val i0 = Vec(3, SInt(20.W)); val i1 = Vec(3, SInt(20.W))
  val b0 = Vec(3, Bool()); val b1 = Vec(3, Bool())
  val fr = Vec(3, UInt(8.W))
}

class BorgSamplerIO(val cfg: BorgConfig) extends Bundle {
  val req        = Flipped(Valid(new SamplerReq(cfg.fragLanes)))  // one-cycle pulse
  val busy       = Output(Bool())
  val resp       = Valid(new SamplerResp(cfg.fragLanes))         // held until respReady
  val respReady  = Input(Bool())
  val done       = Output(Bool())                                // pulse with the last answer
  val invalidate = Input(Bool())                                 // descriptors may have changed
  val gpuMem     = new GpuMemIO
  val prof       = Output(UInt(5.W))                             // perf: bit 4 a request, 3:0 the phase
}

/** Seamless cube filtering: where a tap off face f across edge e (0: i = -1, 1: i = n,
  * 2: j = -1, 3: j = n) lands on the neighbour, derived from Vulkan's major-axis table. */
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
  import SamplerCtl._
  val io = IO(new BorgSamplerIO(cfg))
  private val N = cfg.fragLanes

  /** FP32 -> signed fixed point, `frac` fraction bits, saturating to `width`; denormals are 0. */
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

  /** FP32 mod 2^intBits, unsigned Q.24: REPEAT (0) and MIRRORED_REPEAT (1) wrapped exactly
    * on the float before scaling, so 1000.25 is as precise as 0.25. */
  private def fpToFixedMod(x: UInt, intBits: Int): UInt = {
    val width = intBits + 24
    val e = x(30, 23); val sig = Cat(e =/= 0.U, x(22, 0))
    val sh = e.zext - 126.S                            // value * 2^24 = sig * 2^(e-126)
    val left = sh >= 0.S
    val mag = Mux(left, Mux(sh >= width.S, 0.U, (sig << sh.asUInt(5, 0))(width - 1, 0)),
                Mux((-sh) >= 32.S, 0.U, (sig >> (-sh).asUInt(5, 0)).pad(width)(width - 1, 0)))
    Mux(x(31), (0.U - mag)(width - 1, 0), mag)
  }

  /** A positive float's log2, Q.8: log2(1 + i/64) interpolated on the next 8 mantissa bits,
    * with 4 extra fraction bits rounded once. The CTS mipmap tests assume 8 LOD bits. */
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

  private def q8ToFp32(x: SInt): UInt = {
    val neg = x < 0.S
    uintToFp32(Mux(neg, (0.S - x).asUInt, x.asUInt), -8.S) | Cat(neg, 0.U(31.W))
  }

  /** Signed ordered compare key for floats. */
  private def ordered(x: UInt): UInt = Mux(x(31), ~x, x | (1.U << 31))

  // Two stages: the setup (descriptors, LOD, a lane's level, coordinates and wrap) hands a job per
  // lane and level to the taps (address, fetch, decode, filter, answer) and goes on with the next.
  private val (sIdle :: sTexDesc :: sSampDesc :: sLod :: sLane :: sLodLam :: sLaneSetup :: sLevel ::
               sLevelDims :: sLayer :: sAxisMul :: sAxisFix :: sWrap :: sMul :: sHand :: sEnd :: Nil) = Enum(16)
  private val state = RegInit(sIdle)
  private val mulRet = Reg(chiselTypeOf(sIdle))
  private val (tIdle :: sTap :: sTapLayer :: sTapMul :: sTapSlice :: sTapAddr :: sFetch :: sDecode :: sCompare ::
               sAcc :: sTapNext :: sResp :: tMul :: Nil) = Enum(13)
  private val tst = RegInit(tIdle)
  private val tMulRet = Reg(chiselTypeOf(tIdle))
  locally {
    // The taps' phase while they work; the setup's only while it holds them up.
    def in(r: UInt, ss: Seq[UInt]) = ss.map(r === _).reduce(_ || _)
    val tPhases = Seq(5 -> Seq(sTap, sTapLayer, sTapMul, sTapSlice, sTapAddr, tMul), 6 -> Seq(sFetch),
      7 -> Seq(sDecode, sCompare, sAcc, sTapNext), 8 -> Seq(sResp))
    val sPhases = Seq(1 -> Seq(sTexDesc, sSampDesc), 2 -> Seq(sLod),
      3 -> Seq(sLane, sLodLam, sLaneSetup, sLevelDims, sLayer, sAxisMul, sAxisFix, sWrap, sMul, sHand), 4 -> Seq(sLevel))
    val phase = PriorityMux(tPhases.map { case (p, ss) => in(tst, ss) -> p.U(4.W) } ++
      sPhases.map { case (p, ss) => in(state, ss) -> p.U(4.W) } :+ (true.B -> 0.U(4.W)))
    io.prof := Cat(state === sIdle && io.req.valid, phase)
  }

  private val ctl   = Reg(UInt(32.W))
  private val act   = Reg(Vec(N, Bool()))
  private val args  = Reg(Vec(N, new SamplerLaneArgs))

  // Descriptor caches: 2^texDescCacheLog2 texture descriptors (direct-mapped, td holds
  // the current one) and one sampler, tagged by index; a write to either table's base forgets all.
  private val nTd = 1 << cfg.texDescCacheLog2
  private val td = Reg(Vec(4, UInt(32.W))); private val tdTag = Reg(Vec(nTd, UInt((GpuMemIO.AddrBits - 6).W)))
  private val tdOk = RegInit(VecInit(Seq.fill(nTd)(false.B)))
  private val tdMem = Option.when(nTd > 1)(Mem(nTd, Vec(4, UInt(32.W))))
  private val sd = Reg(Vec(4, UInt(32.W))); private val sdTag = Reg(UInt((GpuMemIO.AddrBits - 4).W)); private val sdOk = RegInit(false.B)
  private val tAddr = Reg(UInt(GpuMemIO.AddrBits.W)); private val sAddr = Reg(UInt(GpuMemIO.AddrBits.W))
  // Texel cache: 64 words, direct-mapped on a fold of the word address so a tile's vertical
  // neighbours differ. Bilinear taps and magnified textures reread the same words.
  private val tcIdx = (a: UInt) => a(7, 2) ^ a(13, 8)
  private val tcData = Mem(64, UInt(32.W)); private val tcTag = Mem(64, UInt((GpuMemIO.AddrBits - 2).W))
  private val tcOk = RegInit(VecInit(Seq.fill(64)(false.B)))
  when(io.invalidate) { tdOk.foreach(_ := false.B); sdOk := false.B; tcOk.foreach(_ := false.B) }
  private val ks = RegInit(0.U(5.W))                      // descriptor word / LOD step
  private val k = RegInit(0.U(3.W))                       // texel word / channel

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
  // Not registered: as a function of the 6-bit code its uses collapse to constant cases.
  private val fmt     = TexFormat.info(fmtCode)
  private val split16 = layout =/= Linear.U && fmt.bytes === 16.U   // see sTapAddr
  private val magLin  = sd(0)(0); private val minLin = sd(0)(1); private val mipLin = sd(0)(2)
  private def addrMode(a: UInt) = MuxLookup(a, sd(0)(5, 3))(Seq(1.U -> sd(0)(8, 6), 2.U -> sd(0)(11, 9)))
  private val border  = sd(0)(14, 12)
  private val cmpOp   = sd(0)(18, 16)
  private val unnorm  = sd(0)(19)
  private val isFetch  = op(ctl) === OpFetch.U
  private val isGather = op(ctl) === OpGather.U
  private val isSize   = op(ctl) === OpSize.U
  private val isQuery  = op(ctl) === OpSample.U && lodMode(ctl) === LodQuery.U
  private val doCmp    = compare(ctl) && !isFetch && ttype =/= T3D.U     // bit 20 is w's offset on 3D
  private val isInt    = fmt.kind === TexFormat.UINT.U || fmt.kind === TexFormat.SINT.U
  /** Filtered dimensions: 1D 1, 2D and cube 2 (a cube face is 2D), 3D 3. */
  private val dims = Mux(ttype === T3D.U, 3.U, Mux(ttype === T1D.U, 1.U, 2.U))

  private val srgbRom = VecInit(TexFormat.srgbToLinear.map(_.U(32.W)))
  private val decoding = RegInit(false.B)                  // sDecode's second stage holds channel decK
  private val decK     = Reg(UInt(2.W))
  private val decField = Reg(UInt(32.W))

  // The one FMA: implicit LOD and the filter's weighted sum.
  private val fma = Module(new BorgFma(cfg))
  private val fmaA = RegInit(0.U(32.W)); private val fmaB = RegInit(0.U(32.W)); private val fmaC = RegInit(0.U(32.W))
  fma.io.a := fmaA; fma.io.b := fmaB; fma.io.c := fmaC
  fma.io.negate := false.B; fma.io.pipeEn1 := true.B; fma.io.pipeEn2 := true.B
  private val fmaLatency = 4

  // A multiplier per stage, registered on both sides: issue with `mul`, read `product` on return.
  private val mulA = WireDefault(0.S(30.W)); private val mulB = WireDefault(0.U(GpuMemIO.AddrBits.W))
  private val mulAR = RegNext(mulA); private val mulBR = RegNext(mulB)
  private val product = Reg(chiselTypeOf(mulAR * mulBR.zext))
  private val tMulA = WireDefault(0.S(30.W)); private val tMulB = WireDefault(0.U(GpuMemIO.AddrBits.W))
  private val tMulAR = RegNext(tMulA); private val tMulBR = RegNext(tMulB)
  private val tProduct = Reg(chiselTypeOf(tMulAR * tMulBR.zext))

  private val lodTmp = Reg(Vec(6, UInt(32.W)))            // d{u,v,w}/dx, d{u,v,w}/dy
  private val lodSum = Reg(Vec(2, UInt(32.W)))            // the scaled sums in x and y
  private val quadLod = RegInit(0.S(24.W))                // implicit LOD, Q.8
  private val lodIssue = WireDefault(false.B); private val lodDst = WireDefault(0.U(3.W))   // 6, 7: lodSum
  when(ShiftRegister(lodIssue, fmaLatency)) {
    val d = ShiftRegister(lodDst, fmaLatency)
    when(d < 6.U) { lodTmp(d) := fma.io.out }.otherwise { lodSum(d(0)) := fma.io.out }
  }

  private val lane   = RegInit(0.U(log2Up(N).W))
  private val nLev   = RegInit(1.U(2.W)); private val lvIdx = RegInit(0.U(1.W))
  private val lvl    = Reg(Vec(2, UInt(4.W)))
  private val lvlW   = Reg(Vec(2, UInt(9.W)))             // level weights, 256 = 1
  private val lamR   = Reg(SInt(25.W))                    // clamped lambda, Q.8
  private val biasedR = Reg(SInt(25.W))                   // before the clamp, for textureQueryLod
  private val lh = RegInit(0.U(16.W)); private val ld = RegInit(0.U(16.W))
  private val ax  = RegInit(0.U(2.W))
  private val wrapEnd = RegInit(0.U(1.W))
  private val kTaps :: kZero :: kSize :: kQuery :: Nil = Enum(4)
  private val kind = RegInit(kTaps)                       // what the lane answers
  private val sj = Reg(new SamplerJob(N)); private val tj = Reg(new SamplerJob(N))
  // Only active lanes answer; with none, lane N-1 answers zeros so that `done` comes.
  private val actBits = act.asUInt
  private val firstLane = Mux(actBits.orR, PriorityEncoder(actBits), (N - 1).U)
  private val lastLane = Mux(actBits.orR, (N - 1).U - PriorityEncoder(Reverse(actBits)), (N - 1).U)
  private def nextLane(l: UInt): UInt = PriorityEncoder(actBits & ~((2.U << l) - 1.U)(N - 1, 0))
  // Per tap.
  private val tap = RegInit(0.U(3.W))
  private val tx = Reg(SInt(20.W)); private val ty = Reg(SInt(20.W)); private val tz = Reg(SInt(20.W))
  private val tBorder = RegInit(false.B)
  private val tLayer = RegInit(0.U(12.W)); private val layerOffTap = RegInit(0.U(GpuMemIO.AddrBits.W))
  // Cube corners: three sub-taps (own face, and across each edge), a third each.
  private val subTap = RegInit(0.U(2.W)); private val tCorner = RegInit(false.B)
  private val tWeight = RegInit(0.U(32.W))
  private val tWeightInt = RegInit(0.U(34.W))             // tWeight before its FP32 conversion, 2^32 = 1
  private val rowTiles = RegInit(0.U(GpuMemIO.AddrBits.W))
  private val addr = RegInit(0.U(GpuMemIO.AddrBits.W))
  private val raw = Reg(Vec(4, UInt(32.W)))
  private val vals = Reg(Vec(4, UInt(32.W)))
  private val acc = Reg(Vec(4, UInt(32.W)))
  private val accIssue = WireDefault(false.B); private val accIdx = WireDefault(0.U(2.W))
  when(ShiftRegister(accIssue, fmaLatency)) { acc(ShiftRegister(accIdx, fmaLatency)) := fma.io.out }
  // Taps overlap the sums still in the pipeline; only the answer waits for them.
  private val accBusy = (1 to fmaLatency).map(ShiftRegister(accIssue, _)).reduce(_ || _)

  io.busy := state =/= sIdle || tst =/= tIdle
  io.done := false.B
  io.resp.valid := tst === sResp && !accBusy
  io.resp.bits.lane := tj.lane
  // The image view's component swizzle (VkComponentMapping), on the result
  // (gather applied it to its component instead).
  private def oneOf: UInt = Mux(isInt, 1.U(32.W), "h3F800000".U(32.W))
  io.resp.bits.data := Mux(isGather || isSize || isQuery, acc, VecInit((0 until 4).map { i =>
    MuxLookup(swz(i), acc(i))(Seq(1.U -> 0.U, 2.U -> oneOf, 3.U -> acc(0), 4.U -> acc(1), 5.U -> acc(2), 6.U -> acc(3)))
  }))
  // The taps' fetches first, then the setup's descriptor and level reads.
  private val memT = Wire(new GpuMemIO); private val memS = Wire(new GpuMemIO)
  Seq(memT, memS).foreach { m => m.req := false.B; m.addr := 0.U; m.wr := false.B; m.wdata := 0.U; m.wlen := 1.U }
  GpuMemIO.arbitrate(Seq(memT, memS), io.gpuMem)
  private def read(m: GpuMemIO, a: UInt)(onData: UInt => Unit): Unit = {
    m.req := true.B; m.addr := a
    when(m.ready) { onData(m.data) }
  }

  /** Multiply `a` by `b`; `product` (`tProduct`) holds the result in state `next`. */
  private def mul(a: SInt, b: UInt, next: UInt): Unit = {
    mulA := a; mulB := b; mulRet := next
    state := sMul
  }
  private def mulT(a: SInt, b: UInt, next: UInt): Unit = {
    tMulA := a; tMulB := b; tMulRet := next
    tst := tMul
  }
  /** Axis `a` next, or the job to the taps after the last. */
  private def toAxis(a: UInt): Unit = {
    ax := a
    when(a >= dims) { state := sHand }.otherwise { state := sAxisMul }
  }
  /** Tile row: (y / 4) * tiles per row. Linear layout: y * row pitch. */
  private def rowMul(): Unit =
    mulT(Mux(layout === Linear.U, ty, ty >> 2), Mux(layout === Linear.U, stride, tj.tpr), sTapSlice)

  switch(state) {
    is(sIdle) {
      when(io.req.valid) {
        ctl := io.req.bits.ctl; act := io.req.bits.active; args := io.req.bits.lane
        tAddr := io.req.bits.texAddr; sAddr := io.req.bits.sampAddr
        ks := 0.U
        state := sTexDesc
      }
    }

    is(sTexDesc) {
      val tag = tAddr >> 6
      val slot = if (nTd > 1) tag(cfg.texDescCacheLog2 - 1, 0) else 0.U
      when(tdOk(slot) && tdTag(slot) === tag) {
        tdMem.foreach(m => td := m(slot)); ks := 0.U; state := sSampDesc
      }.otherwise {
        read(memS, tAddr + (ks << 2)) { d =>
          td(ks(1, 0)) := d
          when(ks === 3.U) {
            tdMem.foreach(_.write(slot, VecInit(td(0), td(1), td(2), d)))
            tdOk(slot) := true.B; tdTag(slot) := tag; ks := 0.U; state := sSampDesc
          }.otherwise { ks := ks + 1.U }
        }
      }
    }
    is(sSampDesc) {
      when(isFetch || isSize || (sdOk && sdTag === (sAddr >> 4))) { ks := 0.U; state := sLod }
        .otherwise {
          read(memS, sAddr + (ks << 2)) { d =>
            sd(ks(1, 0)) := Mux(ks === 0.U, d, fpToFixed(d, 8, 24).asUInt)   // LOD bias, min, max as Q.8
            when(ks === 3.U) { sdOk := true.B; sdTag := sAddr >> 4; ks := 0.U; state := sLod }
              .otherwise { ks := ks + 1.U }
          }
        }
    }

    // Implicit LOD: lane 1 - lane 0 is d/dx, lane 2 - lane 0 d/dy. rho = max over x and y of
    // |du|*W + |dv|*H + |dw|*D, the sum form Vulkan allows; LOD = log2(rho).
    is(sLod) {
      val needQuad = !isFetch && !isGather && !isSize && lodMode(ctl) =/= LodExplicit.U && (N >= 3).B && !unnorm
      // One level and one filter: the LOD changes nothing.
      val matters = levels =/= 1.U || magLin =/= minLin
      val is3D = ttype === T3D.U
      when(!needQuad || (!matters && !isQuery)) {
        quadLod := 0.S; lane := firstLane; state := sLane
      }.otherwise {
        val a = args
        def neg(x: UInt) = Cat(~x(31), x(30, 0))
        def abs(x: UInt) = Cat(0.U(1.W), x(30, 0))
        val one = "h3F800000".U
        if (N >= 3) {
          // Steps 0..5 issue the differences (x and y interleaved) into lodTmp, the x and y sums
          // follow in pairs once their inputs are back: one FMA pipeline, no waits between.
          val coords = (l: Int) => Seq(a(l).u, a(l).v, a(l).w)
          val diffs = for (c <- 0 until 3; l <- Seq(1, 2)) yield (coords(l)(c), neg(coords(0)(c)))
          val sum = WireDefault(false.B); val j = WireDefault(0.U(2.W)); val y = WireDefault(false.B)
          Seq(6, 7, 11, 12, 16, 17).zipWithIndex.foreach { case (s, i) =>
            when(ks === s.U) { sum := true.B; j := (i / 2).U; y := (i % 2 == 1).B } }
          when(ks < 6.U) {
            fmaA := VecInit(diffs.map(_._1))(ks(2, 0)); fmaB := one; fmaC := VecInit(diffs.map(_._2))(ks(2, 0))
            lodIssue := true.B; lodDst := Mux(ks(0), 3.U, 0.U) +& ks(2, 1)
          }.elsewhen(sum) {
            val src = lodTmp(Mux(y, 3.U, 0.U) +& j)
            // 1D arrays carry the layer in v: only u counts (dt = 0 for 1D).
            fmaA := Mux((j === 2.U && !is3D) || (j === 1.U && ttype === T1D.U), 0.U, abs(src))
            fmaB := uintToFp32(MuxLookup(j, dimW)(Seq(1.U -> dimH, 2.U -> Mux(is3D, dimD, 0.U))), 0.S)
            fmaC := Mux(j === 0.U, 0.U, lodSum(y))
            lodIssue := true.B; lodDst := Mux(y, 7.U, 6.U)
          }
        }
        ks := ks + 1.U
        when(ks === Mux(is3D, 22.U, 17.U)) {
          val rx = lodSum(0); val ry = lodSum(1)
          quadLod := fpLog2(Mux(ordered(rx) >= ordered(ry), rx, ry))
          ks := 0.U; lane := firstLane; state := sLane
        }
      }
    }

    is(sLane) {
      lvIdx := 0.U; kind := kTaps
      when(act(lane) && isSize) { kind := kSize; state := sHand }
        .elsewhen(act(lane)) { state := sLodLam }
        .otherwise { kind := kZero; state := sHand }      // inactive: answer zeros
    }
    is(sLodLam) {
      val a = args(lane)
      // All Q.8. lambda = base + clamp(sampler bias + shader bias, +-maxSamplerLodBias).
      val lod = fpToFixed(a.lod, 8, 24)
      val base0 = Mux(lodMode(ctl) === LodExplicit.U, lod, quadLod)
      val biasSum = sd(1)(23, 0).asSInt +& Mux(lodMode(ctl) === LodBias.U, lod, 0.S)
      val maxB = (MaxLodBias << 8).S
      val biased = base0 + Mux(biasSum > maxB, maxB, Mux(biasSum < -maxB, -maxB, biasSum))
      val minL = sd(2)(23, 0).asSInt; val maxL = sd(3)(23, 0).asSInt
      lamR := Mux(biased < minL, minL, Mux(biased > maxL, maxL, biased))
      biasedR := biased
      state := sLaneSetup
    }
    is(sLaneSetup) {
      val a = args(lane)
      val q = (levels - 1.U).zext << 8
      val lam = lamR
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
      sj.linear := filt
      // Array layer: 1D arrays take it from v, 2D arrays and cubes from w.
      val lsrc = Mux(ttype === T1D.U, a.v, a.w)
      val lr = Mux(isFetch, lsrc(15, 0).asSInt, fpToFixed(lsrc, 1, 18) + 1.S >> 1)  // round
      val lc = Mux(lr < 0.S, 0.U, Mux(lr.asUInt >= dimD, dimD - 1.U, lr.asUInt))
      sj.layerN := Mux(ttype === T3D.U, 0.U, lc)
      state := Mux(isQuery, sHand, sLevel)
      when(isQuery) { kind := kQuery }
    }

    is(sLevel) {
      val l = lvl(lvIdx)
      when(l === 0.U) { sj.levelOff := 0.U; state := sLevelDims }
        .otherwise {
          read(memS, tAddr + ((l +& 3.U) << 2)) { d =>
            sj.levelOff := d; state := sLevelDims
          }
        }
    }
    is(sLevelDims) {
      val l = lvl(lvIdx)
      def shr(x: UInt) = { val s = x >> l; Mux(s === 0.U, 1.U, s) }
      val w = shr(dimW); val h = Mux(ttype === T1D.U, 1.U, shr(dimH))
      val d = Mux(ttype === T3D.U, shr(dimD), 1.U)
      sj.lw := w; lh := h; ld := d
      sj.tpr := (w +& 3.U) >> 2
      // Only 3D has slices; a tap multiplies its own layer.
      when(ttype =/= T3D.U) {
        sj.sliceTexels := 0.U; wrapEnd := 0.U
        toAxis(0.U)
      }.otherwise {
        // texels per 3D slice: tiles per row * tile rows * 16
        mul(((w +& 3.U) >> 2).zext, ((h +& 3.U) >> 2) << 4, sLayer)
      }
    }
    is(sLayer) {
      sj.sliceTexels := product.asUInt(GpuMemIO.AddrBits - 1, 0)
      wrapEnd := 0.U
      toAxis(0.U)
    }
    is(sMul) {
      product := mulAR * mulBR.zext
      state := mulRet
    }

    is(sAxisMul) {
      val a = args(lane)
      val c = MuxLookup(ax, a.u)(Seq(1.U -> a.v, 2.U -> a.w))
      val size = MuxLookup(ax, sj.lw)(Seq(1.U -> lh, 2.U -> ld))
      val mode = addrMode(ax)
      // x = coordinate * size, Q.8: repeat modes wrap the float first, clamp modes clamp to [-4, 4).
      val repeatF = fpToFixedMod(c, 0)                    // Q0.24
      val mirrorF = fpToFixedMod(c, 1)                    // Q1.24
      val clampF  = fpToFixed(c, 24, 28)                  // Q3.24
      mul(Mux(mode === Repeat.U, repeatF.zext, Mux(mode === Mirror.U, mirrorF.zext, clampF)), size, sAxisFix)
    }
    is(sAxisFix) {
      val a = args(lane)
      val c = MuxLookup(ax, a.u)(Seq(1.U -> a.v, 2.U -> a.w))
      // Rounded, so a coordinate an FP32 error under a texel edge stays on it (Vulkan allows it):
      // to the 8 bits of the linear weights, and for nearest to 12, so one truly short of it is not.
      val scaled = ((product + (1 << 15).S) >> 16).asSInt // Q.8
      val scaled12 = ((product + (1 << 11).S) >> 12).asSInt // Q.12
      val fixed = fpToFixed(c, 16, 32) // Q.16
      val x8 = Mux(isFetch, (c(19, 0).asSInt << 8), Mux(unnorm, (fixed +& 128.S) >> 8, scaled))
      val off = MuxLookup(ax, 0.S(4.W))(Seq(0.U -> offU(ctl), 1.U -> offV(ctl),
                                            2.U -> Mux(ttype === T3D.U, offW(ctl), 0.S)))
      val xs = Mux(sj.linear, x8 - 128.S, x8)                // texel centres at +0.5
      val near = Mux(isFetch, x8 >> 8, Mux(unnorm, (fixed +& 8.S) >> 16, scaled12 >> 12))
      val first = Mux(sj.linear, xs >> 8, near) + off
      sj.i0(ax) := first; sj.i1(ax) := first + 1.S
      sj.fr(ax) := Mux(sj.linear, xs(7, 0).asUInt, 0.U)
      wrapEnd := 0.U
      state := sWrap
    }
    is(sWrap) {
      // Wrap one end per cycle; REPEAT and MIRRORED_REPEAT may need several
      // steps when an offset pushes a small texture's coordinate far out.
      val size = MuxLookup(ax, sj.lw)(Seq(1.U -> lh, 2.U -> ld)).zext
      val mode = addrMode(ax)
      val i = Mux(wrapEnd === 0.U, sj.i0(ax), sj.i1(ax))
      def set(v: SInt, bord: Bool): Unit = when(wrapEnd === 0.U) { sj.i0(ax) := v; sj.b0(ax) := bord }
                                                .otherwise        { sj.i1(ax) := v; sj.b1(ax) := bord }
      val period = Mux(mode === Mirror.U, size << 1, size)
      val done = WireDefault(true.B)
      when(isFetch || (ttype === TCube.U && !sj.linear)) {
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
        when(wrapEnd === 0.U && sj.linear) { wrapEnd := 1.U }
          .otherwise { toAxis(ax + 1.U) }
      }
    }

    is(sHand) {
      // The taps take the job once free; a lane without taps answers at once.
      when(tst === tIdle) {
        tj := sj
        tj.lane := lane; tj.last := lvIdx === nLev - 1.U; tj.lvlW := lvlW(lvIdx)
        tj.single := (!sj.linear || isFetch) && nLev === 1.U
        tap := 0.U; subTap := 0.U
        // textureSize: width, height, depth or layers, levels, at the level in TEXA's lod.
        val lv = args(lane).lod(3, 0)
        def at(n: UInt) = Mux((n >> lv) === 0.U, 1.U, n >> lv)
        // textureQueryLod: the level the min filter would use and lambda, as floats.
        val q = (levels - 1.U).zext << 8
        val lamC = Mux(lamR < 0.S, 0.S, Mux(lamR > q, q, lamR))
        val lvlQ = Mux(mipLin, lamC, (((lamC + 127.S) >> 8) << 8).asSInt)
        when(kind =/= kTaps || lvIdx === 0.U) { acc.foreach(_ := 0.U) }
        when(kind === kSize) {
          acc(0) := at(dimW); acc(1) := at(dimH); acc(2) := Mux(ttype === T3D.U, at(dimD), dimD); acc(3) := levels
        }
        when(kind === kQuery) { acc(0) := q8ToFp32(lvlQ); acc(1) := q8ToFp32(biasedR) }
        tst := Mux(kind === kTaps, sTap, sResp)
        when(kind === kTaps && lvIdx =/= nLev - 1.U) { lvIdx := lvIdx + 1.U; state := sLevel }
          .elsewhen(lane === lastLane) { state := sEnd }
          .otherwise { lane := nextLane(lane); state := sLane }
      }
    }
    is(sEnd) { when(io.done) { state := sIdle } }
  }

  switch(tst) {
    is(tIdle) {}
    is(tMul) {
      tProduct := tMulAR * tMulBR.zext
      tst := tMulRet
    }
    is(sTap) {
      // Tap bit a picks axis a's second texel. Gather's order is Vulkan's:
      // (tj.i0,j1), (tj.i1,j1), (tj.i1,j0), (tj.i0,j0).
      val t = Mux(isGather, VecInit(2.U, 3.U, 1.U, 0.U)(tap(1, 0)), tap)
      def pick(a: Int) = Mux(t(a) && tj.linear, tj.i1(a), tj.i0(a))
      def bord(a: Int) = Mux(t(a) && tj.linear, tj.b1(a), tj.b0(a))
      val used = (0 until 3).map(a => a.U < dims)
      tx := pick(0); ty := Mux(used(1), pick(1), 0.S); tz := Mux(used(2), pick(2), 0.S)
      tBorder := (0 until 3).map(a => used(a) && bord(a)).reduce(_ || _)
      tLayer := tj.layerN
      // Weight: the tProduct of each used axis's (1 - f) or f, and the level's.
      def aw(a: Int) = Mux(!tj.linear || !used(a), 256.U(9.W), Mux(t(a), tj.fr(a).pad(9), 256.U - tj.fr(a)))
      val wInt = aw(0) * aw(1) * aw(2) * tj.lvlW                 // 36 bits, 2^32 = 1
      tWeightInt := wInt(33, 0)                                       // to FP32 in sTapLayer
      tCorner := false.B
      when(ttype === TCube.U) {
        // Seamless: a tap off the face reads the neighbouring face; past a
        // corner, the average of the three texels meeting there.
        val n = tj.lw.zext; val x = pick(0); val y = pick(1)
        val iOut = x < 0.S || x >= n; val jOut = y < 0.S || y >= n
        def clampC(v: SInt) = Mux(v < 0.S, 0.S, Mux(v >= n, n - 1.S, v))
        def across(e: UInt, k: SInt): Unit = {
          val tbl = VecInit(CubeEdges.table.map(row => VecInit(row.map(r => (r.face * 16 + r.iSel * 4 + r.jSel).U(7.W)))))
          val r = tbl(tj.layerN(2, 0))(e)
          def pos(sel: UInt) = MuxLookup(sel, 0.S)(Seq(1.U -> (n - 1.S), 2.U -> k, 3.U -> (n - 1.S - k)))
          tLayer := r(6, 4); tx := pos(r(3, 2)); ty := pos(r(1, 0))
        }
        val eI = Mux(x < 0.S, 0.U, 1.U); val eJ = Mux(y < 0.S, 2.U, 3.U)
        when(iOut && jOut) {
          tCorner := true.B
          tWeightInt := ((wInt * 21845.U) >> 16)(33, 0)                   // a third
          switch(subTap) {
            is(0.U) { tx := clampC(x); ty := clampC(y) }
            is(1.U) { across(eI, clampC(y)) }
            is(2.U) { across(eJ, clampC(x)) }
          }
        }.elsewhen(iOut) { across(eI, y) }
          .elsewhen(jOut) { across(eJ, x) }
      }
      tst := sTapLayer
    }
    is(sTapLayer) {
      tWeight := uintToFp32(tWeightInt, (-32).S)
      when(tLayer === 0.U) { layerOffTap := 0.U; rowMul() }.otherwise { mulT(tLayer.zext, stride, sTapMul) }
    }
    is(sTapMul) {
      layerOffTap := tProduct.asUInt(GpuMemIO.AddrBits - 1, 0)
      rowMul()
    }
    is(sTapSlice) {
      rowTiles := tProduct.asUInt(GpuMemIO.AddrBits - 1, 0)
      when(tz === 0.S) { tProduct := 0.S; tst := sTapAddr }.otherwise { mulT(tz, tj.sliceTexels, sTapAddr) }
    }
    is(sTapAddr) {
      val x = tx.asUInt; val y = ty.asUInt
      val shift = Log2(fmt.bytes)
      val texel = ((rowTiles + (x >> 2)) << 4) + (y(1, 0) << 2) + x(1, 0) + tProduct.asUInt(GpuMemIO.AddrBits - 1, 0)
      val tiled = base + tj.levelOff + layerOffTap + (texel << shift)
      // A 16-byte tiled texel is two 8-byte halves 128 bytes apart in its 256-byte tile, as a
      // RAW128 attachment renders its two slices: word k is at +128*(k >> 1) + 4*(k & 1).
      val tiledSplit = base + tj.levelOff + layerOffTap + ((texel >> 4) << 8) + (texel(3, 0) << 3)
      val lin   = base + rowTiles + (x << shift)
      addr := Mux(layout === Linear.U, lin, Mux(split16, tiledSplit, tiled))(GpuMemIO.AddrBits - 1, 0)
      k := 0.U
      tst := Mux(tBorder, sDecode, sFetch)
    }
    is(sFetch) {
      val words = Mux(fmt.bytes >= 4.U, fmt.bytes >> 2, 1.U)
      val wordOff = Mux(split16, Cat(k(1), 0.U(4.W), k(0), 0.U(2.W)), k << 2)   // 128*(k>>1) + 4*(k&1)
      val a = Cat(addr(GpuMemIO.AddrBits - 1, 2), 0.U(2.W)) + wordOff
      val i = tcIdx(a)
      def got(d: UInt): Unit = {
        raw(k(1, 0)) := d
        when(k === words - 1.U) { k := 0.U; tst := sDecode }.otherwise { k := k + 1.U }
      }
      when(tcOk(i) && tcTag(i) === a(GpuMemIO.AddrBits - 1, 2)) { got(tcData(i)) }.otherwise {
        read(memT, a) { d => got(d); tcData(i) := d; tcTag(i) := a(GpuMemIO.AddrBits - 1, 2); tcOk(i) := true.B }
      }
    }
    is(sDecode) {
      // Two stages, one channel per cycle: extract channel k, convert the one extracted before.
      // Sub-word texels shift down by their byte offset first.
      val all = Cat(raw(3), raw(2), raw(1), raw(0)) >> Mux(fmt.bytes < 4.U, addr(1, 0) << 3, 0.U)
      val w1 = fmt.bits(k(1, 0))
      val field1 = ((all >> fmt.off(k(1, 0))) & ((1.U(33.W) << w1) - 1.U))(31, 0)
      val srgbLin = RegNext(srgbRom(field1(7, 0)))          // block RAM, read beside the extraction
      when(!k(2)) { decField := field1; decK := k(1, 0); decoding := true.B; k := k + 1.U }

      val field = decField
      val w = fmt.bits(decK)
      val sext = ((field << (32.U - w))(31, 0).asSInt >> (32.U - w)).asUInt
      val oneF = "h3F800000".U(32.W)
      val isAlpha = decK === 3.U
      val v = MuxLookup(fmt.kind, field)(Seq(
        TexFormat.UNORM.U  -> TexFormat.unormToFp32(field, w),
        TexFormat.SNORM.U  -> TexFormat.snormToFp32(field, w),
        TexFormat.UINT.U   -> field,
        TexFormat.SINT.U   -> sext,
        TexFormat.SFLOAT.U -> Mux(w === 16.U, Fp16Fp32.widen(field(15, 0)), field),
        TexFormat.SRGB.U   -> Mux(isAlpha, TexFormat.unormToFp32(field, w), srgbLin),
        TexFormat.UFLOAT.U -> Mux(w === 11.U, TexFormat.ufloatToFp32(field(10, 0), 6),
                                  TexFormat.ufloatToFp32(field(9, 0), 5)),
        TexFormat.SHAREDEXP.U -> TexFormat.uintScaledToFp32(field, all(31, 27).zext - 24.S)))
      // Absent channels read 0, alpha 1. VkBorderColor 0..5: float/int transparent black,
      // opaque black, opaque white.
      val dflt = Mux(isAlpha, Mux(isInt, 1.U(32.W), oneF), 0.U(32.W))
      // The format, not the float/int variant, decides the type.
      val bOne = Mux(isInt, 1.U(32.W), oneF)
      val bv = Mux(isAlpha, Mux(border >= 2.U, bOne, 0.U), Mux(border >= 4.U, bOne, 0.U))
      // The border replaces only the channels the format has.
      // A filtered tap's channel goes from the decoder straight into the FMA.
      val filt = !doCmp && !isGather && !isInt && !tj.single
      when(decoding) {
        val c = Mux(w === 0.U, dflt, Mux(tBorder, bv, v))
        vals(decK) := c
        when(filt) { fmaA := tWeight; fmaB := c; fmaC := acc(decK); accIssue := true.B; accIdx := decK }
        when(decK === 3.U) { decoding := false.B; k := 0.U; tst := Mux(filt, sTapNext, Mux(doCmp, sCompare, sAcc)) }
      }
    }
    is(sCompare) {
      // Depth compare per tap before filtering; UNORM formats clamp dref to [0, 1] first.
      val d0 = args(tj.lane).dref
      val dClamped = Mux(d0(31), 0.U, Mux(d0 > "h3F800000".U, "h3F800000".U, d0))
      val dref = ordered(Mux(fmt.kind === TexFormat.UNORM.U, dClamped, d0)); val dtex = ordered(vals(0))
      val pass = MuxLookup(cmpOp, false.B)(Seq(
        1.U -> (dref < dtex), 2.U -> (dref === dtex), 3.U -> (dref <= dtex),
        4.U -> (dref > dtex), 5.U -> (dref =/= dtex), 6.U -> (dref >= dtex), 7.U -> true.B))
      vals(0) := Mux(pass, "h3F800000".U, 0.U)
      vals(1) := 0.U; vals(2) := 0.U; vals(3) := "h3F800000".U
      tst := sAcc
    }
    is(sAcc) {
      val single = tj.single
      // Gather reads the component the view's swizzle puts at `comp`.
      val gsw = VecInit((0 until 4).map(swz))(comp(ctl))
      val gathered = MuxLookup(gsw, vals(comp(ctl)))(Seq(1.U -> 0.U, 2.U -> oneOf,
        3.U -> vals(0), 4.U -> vals(1), 5.U -> vals(2), 6.U -> vals(3)))
      when(isGather && tCorner && !isInt) {
        // A gathered corner texel is the average of the three, as filtered.
        fmaA := "h3EAAAAAB".U; fmaB := gathered                         // 1/3
        fmaC := Mux(subTap === 0.U, 0.U, acc(tap(1, 0)))
        accIssue := true.B; accIdx := tap(1, 0)
        tst := sTapNext
      }.elsewhen(isGather) {
        // An integer corner keeps its own face's texel, one of the three:
        // the spec's "may", which only requires equal texels to stay equal.
        when(!tCorner || subTap === 0.U) { acc(tap(1, 0)) := gathered }
        tst := sTapNext
      }.elsewhen(single || isInt) {
        acc := vals
        tst := sTapNext
      }.otherwise {
        // A compared tap: one channel per cycle into the FMA pipeline.
        fmaA := tWeight; fmaB := vals(k(1, 0)); fmaC := acc(k(1, 0))
        accIssue := true.B; accIdx := k(1, 0)
        when(k === 3.U) { k := 0.U; tst := sTapNext }.otherwise { k := k + 1.U }
      }
    }
    is(sTapNext) {
      val nTaps = Mux(isGather, 4.U, Mux(tj.linear && !isFetch, 1.U << dims, 1.U))
      when(tCorner && subTap =/= 2.U) {
        subTap := subTap + 1.U; tst := sTap                // the corner's next texel
      }.elsewhen(tap === nTaps - 1.U) {
        subTap := 0.U
        tst := Mux(tj.last, sResp, tIdle)
      }.otherwise {
        subTap := 0.U
        tap := tap + 1.U; tst := sTap
      }
    }

    is(sResp) {
      if (BorgDebug.trace) when(io.respReady && io.resp.valid) {
        printf("[TEX] lane %d u=0x%x v=0x%x lod=0x%x levels=%d taps i0=(%d,%d) addr=0x%x -> %x %x %x %x\n", tj.lane,
          args(tj.lane).u, args(tj.lane).v, args(tj.lane).lod, levels, tj.i0(0), tj.i0(1), addr, acc(0), acc(1), acc(2), acc(3))
      }
      when(io.respReady && io.resp.valid) {
        io.done := tj.lane === lastLane
        tst := tIdle
      }
    }
  }
}
