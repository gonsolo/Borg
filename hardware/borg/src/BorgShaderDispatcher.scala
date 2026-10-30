// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class BorgShaderDispatcherIO(val cfg: BorgConfig) extends Bundle {
  val pixelReady      = Input(Bool())                                   // a new quad, one cycle
  // The iterator's quad from the cycle after pixelReady: each lane's tile slot and position.
  val shaderTileIndex = Input(Vec(cfg.fragLanes, UInt(4.W)))
  val shaderIter      = Input(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))
  val quadIter        = Output(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))   // the shaded quad's

  val csrWrite   = Flipped(Vec(cfg.fragLanes, new CsrWriteIO(cfg.totalBits)))
  val csrSlot    = Input(UInt(cfg.slotBits.W))   // the quad slot csrWrite comes from
  val coreStatus = Flipped(Vec(cfg.quadSlots, new CoreStatusIO))
  // The slots the core asks quadIter and laneHelper for; laneCoverage and laneDst are csrSlot's.
  val iterSlot    = Input(UInt(cfg.slotBits.W))
  val helperSlot  = Input(UInt(cfg.slotBits.W))
  val triggerSlot = Output(UInt(cfg.slotBits.W))   // the slot coreTrigger starts
  val zTestSlot   = Input(UInt(cfg.slotBits.W))    // the slot zTestReq comes from
  val fragPcReg  = Input(UInt(6.W))

  val depthCompareOp = Input(UInt(3.W))
  val depthWriteEn   = Input(Bool())
  val depthUnorm     = Input(Bool())    // D16: a draw-mode depth is clamped to [0, 1] and rounded
  val blendCfg = if (cfg.hasBlend) Some(Input(new BlendConfig)) else None

  // Stencil: the triangle's facing, the state, and the tile's stencil plane, read with the colour
  // and depth. Written per sample: a fragment can pass for some and fail for others.
  val frontFacing      = if (cfg.hasStencil) Some(Input(Bool())) else None
  val stencilCfg       = if (cfg.hasStencil) Some(Input(new StencilConfig)) else None
  val stencilRead      = if (cfg.hasStencil) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val stencilWrite     = if (cfg.hasStencil) Some(Output(UInt(8.W))) else None
  val stencilWriteMask = if (cfg.hasStencil) Some(Output(UInt(cfg.samples.W))) else None

  val scissorPass = Input(Vec(cfg.fragLanes, Bool()))   // from the rasterizer's one rectangle test

  // The destination-alpha plane, for the DST_ALPHA factors and alpha's own blend equation.
  val alphaRead      = if (cfg.hasBlend) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val alphaWrite     = if (cfg.hasBlend) Some(Output(UInt(8.W))) else None
  val alphaWriteMask = if (cfg.hasBlend) Some(Output(Bool())) else None

  val coreTrigger = new CoreTriggerIO
  val tileWrite   = new TileWriteIO(cfg.samples, cfg.tileDepthBits)
  val tileRead    = new TileReadIO(16, cfg.samples, cfg.tileDepthBits)

  val autoRunStall = Output(Bool())     // from a quad's start until every quad is written
  val slotStall    = Output(Bool())     // no free slot: a new quad waits
  val insideFlag   = Output(Bool())
  val phase        = Output(UInt(4.W))  // debug only
  // Consumers test this, never a phase number.
  val idle         = Output(Bool())

  val zTestReq   = Input(Bool())
  val zTestDone  = Output(Bool())
  // Per lane, while the fragment shader runs: no side effects (a helper, discarded, failed ZTEST).
  val laneHelper = Output(Vec(cfg.fragLanes, Bool()))
  val occSamples = Output(UInt(log2Ceil(cfg.samples + 1).W))   // samples that passed this cycle

  // MSAA: the triangle's per-sample deltas, [plane][k] (see QuadCoverage).
  val covDelta = if (cfg.samples > 1)
    Some(Input(Vec(cfg.coveragePlanesStored, Vec(2, UInt(cfg.totalBits.W))))) else None

  // Draw front end (docs/B1_geometry_front_end.md): the raster ROM writes Zn, Zf and FragCoord.z
  // too, and an edge sample tie belongs to top and left edges only.
  val drawMode     = Input(Bool())
  val topLeft      = Input(Vec(3, Bool()))
  val sampleCfg    = Input(new SampleMaskConfig(cfg.samples))
  // The triangle's uniform page and whether it lies outside the occlusion query; with more
  // than one slot, a quad keeps these and the triangle state above from its start.
  val uniformPage  = Input(UInt(1.W))
  val occOutside   = Input(Bool())
  val pageBusy     = Output(Vec(2, Bool()))   // a quad in flight reads the page
  val laneCoverage = Output(Vec(cfg.fragLanes, UInt(cfg.samples.W)))   // gl_SampleMaskIn
  // A RAW attachment stores out_c0's bytes unblended; RAW and sRGB read each lane's destination
  // word before the shader starts, for TLD (sRGB: linear FP16, R | G << 16, B | A << 16).
  val rawColor  = Input(Bool())
  val srgbColor = Input(Bool())
  val laneDst   = Output(Vec(cfg.fragLanes, Vec(2, UInt(32.W))))
  // RAW64's second word: the tile's extension plane, read with the colour and written from out_c1.
  val extRead  = if (cfg.hasBlend) Some(Input(Vec(cfg.samples, UInt(32.W)))) else None
  val extWrite = if (cfg.hasBlend) Some(Output(UInt(32.W))) else None
}

class SampleMaskConfig(val samples: Int) extends Bundle {
  val mask        = UInt(samples.W)   // VkPipelineMultisampleStateCreateInfo::pSampleMask
  val alphaToCov  = Bool()            // alphaToCoverageEnable
  val shaderMask  = Bool()            // the fragment shader writes gl_SampleMask to sample_mask
  // rasterizationSamples = 1: every mask comes from sample 0, which the walker put at the centre.
  val single      = Bool()
}

/** A quad's way from the iterator to the tile buffer, in its slot:
  *
  *   sIdle -> sRast (the raster ROM's edge test on the core) -> [sDstRead..sDstCap: each lane's
  *   destination word, for TLD] -> sFrag (the fragment shader) -> sZRead..sTileWrite (per lane:
  *   read the tile, test and write) -> sIdle.
  *
  * A quad no lane of which is inside goes back to sIdle after sRast. Outside lanes run as helpers
  * and are masked at the write. ZTEST runs the tests mid-shader with the same states, then
  * returns to sFrag ([[FragmentOps]]). Each quad slot has a phase; the tile states (sDst*, sZ*,
  * sTileWrite) are one engine that one slot holds at a time, and a slot waiting for it is in sDstQ
  * or sZQ; one waiting for the core's trigger port is in sFragGo. */
class BorgShaderDispatcher(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgShaderDispatcherIO(cfg))
  private val N = cfg.fragLanes
  private val S = cfg.quadSlots

  val (sIdle :: sRast :: sFrag :: sZRead :: sZWait1 :: sZWait2 :: sTileWrite ::
       sDstRead :: sDstWait1 :: sDstWait2 :: sDstCap :: sDstQ :: sZQ :: sFragGo :: Nil) = Enum(14)
  private def perSlot[T <: Data](x: => T): Vec[T] = VecInit(Seq.fill(S)(x))
  val phase = RegInit(perSlot(sIdle))
  private val tileStates = Seq(sZRead, sZWait1, sZWait2, sTileWrite, sDstRead, sDstWait1, sDstWait2, sDstCap)
  private val engineBusy = phase.map(p => tileStates.map(_ === p).reduce(_ || _)).reduce(_ || _)
  val eq = RegInit(0.U(cfg.slotBits.W))      // the slot holding the tile engine
  val newSlot = PriorityEncoder(phase.map(_ === sIdle))   // a new quad's

  // The quad's inputs, held per slot: the iterator moves on once the quad has a slot.
  private val capture = RegNext(io.pixelReady, false.B)
  private val capSlot = RegNext(newSlot)
  private def held[T <: Data](live: T): UInt => T = {
    val r = Reg(Vec(S, chiselTypeOf(live)))
    when(capture) { r(capSlot) := live }
    q => Mux(capture && q === capSlot, live, r(q))
  }
  private val (tileIndexOf, scissorOf, iterOf) = (held(io.shaderTileIndex), held(io.scissorPass), held(io.shaderIter))
  val tileIndex = tileIndexOf(eq)
  io.quadIter := iterOf(io.iterSlot)
  // The triangle's state, taken as the quad starts: the sequencer moves on once the tile command is out.
  private def tri[T <: Data](live: T): UInt => T = if (S == 1) (_ => live) else {
    val r = Reg(Vec(S, chiselTypeOf(live)))
    when(io.pixelReady) { r(newSlot) := live }
    q => r(q)
  }
  private val (topLeftOf, pageOf, occOf) = (tri(io.topLeft), tri(io.uniformPage), tri(io.occOutside))
  private val covDeltaOf = io.covDelta.map(tri(_))
  private val frontFacingOf = io.frontFacing.map(tri(_))
  val cov  = new QuadCoverage(cfg, io.drawMode, topLeftOf, covDeltaOf, io.sampleCfg)
  val outs = new FragOutputs(cfg, io.drawMode, io.depthUnorm, io.sampleCfg)
  val ops  = new FragmentOps(cfg, io, outs.at(eq), cov.at(eq), scissorOf(eq), tileIndex, frontFacingOf.map(_(eq)))
  io.laneCoverage := cov.at(io.csrSlot).laneMask

  val dstWord = RegInit(perSlot(VecInit(Seq.fill(N)(VecInit(Seq.fill(2)(0.U(32.W)))))))
  io.laneDst := dstWord(io.csrSlot)
  io.extWrite.foreach(_ := 0.U)
  private val dstActive = (io.rawColor || io.srgbColor) && outs.rawSupported.B

  // ZTEST is supported where the late path writes one known sample at a time; otherwise it
  // completes at once and the tests stay late.
  val earlySupported = cfg.samples == 1 || ops.perSample
  val earlyActive = RegInit(false.B)   // the engine runs the ZTEST sub-phase
  val earlyDone   = RegInit(perSlot(false.B))   // this quad's tests already ran
  val earlyPass   = Reg(Vec(S, Vec(N, Vec(cfg.samples, Bool()))))

  // The engine's lane and sample. log2Up: a wider counter is a wider index into the lanes' Vecs,
  // which ABC9 handles badly.
  val laneCtr = RegInit(0.U(log2Up(N).W))
  val sampleCtr = Option.when(ops.perSample)(RegInit(0.U(log2Up(cfg.samples).W)))
  private val laneIdx: UInt = laneCtr

  io.coreTrigger.valid   := false.B
  io.coreTrigger.pc      := 0.U
  io.coreTrigger.isRast  := false.B
  io.coreTrigger.isSetup := false.B
  io.coreTrigger.fragment := false.B
  io.triggerSlot         := newSlot

  io.tileWrite.en       := false.B
  io.tileWrite.idx      := tileIndex(0)
  io.tileWrite.data     := 0.U.asTypeOf(new ColorZ(16, cfg.tileDepthBits))
  io.tileWrite.coverage := 0.U
  io.occSamples         := 0.U
  io.stencilWrite.foreach(_ := 0.U)
  io.stencilWriteMask.foreach(_ := 0.U)
  io.alphaWrite.foreach(_ := 0.U)
  io.alphaWriteMask.foreach(_ := false.B)
  io.tileRead.en  := false.B
  io.tileRead.idx := tileIndex(0)

  // This cycle's requests for the trigger port (a fragment shader) and the engine.
  val fragReq = WireDefault(VecInit(Seq.fill(S)(false.B)))
  val dstReq  = WireDefault(VecInit(Seq.fill(S)(false.B)))
  val lateReq = WireDefault(VecInit(Seq.fill(S)(false.B)))

  when(io.pixelReady) {
    cov.clear(newSlot)
    outs.clear(newSlot)
    earlyDone(newSlot) := false.B
    phase(newSlot) := sRast
    io.coreTrigger.valid  := true.B
    io.coreTrigger.pc     := 0.U
    io.coreTrigger.isRast := true.B
    if (BorgDebug.trace) printf("[DISP] pixelReady slot=%d\n", newSlot)
  }

  for (q <- 0 until S) {
    val st = io.coreStatus(q)
    val wasActive = RegNext(st.running || st.autoRunPending, false.B)
    val finished  = wasActive && !st.running && !st.autoRunPending
    when(phase(q) === sRast && finished) {
      when(cov.at(q.U).anyInside && io.fragPcReg =/= 0.U) {
        when(dstActive) { dstReq(q) := true.B }.otherwise { fragReq(q) := true.B }
      }.otherwise {
        phase(q) := sIdle
      }
    }
    when(phase(q) === sFrag && finished) { lateReq(q) := true.B }
    when(phase(q) === sDstQ)   { dstReq(q) := true.B }
    when(phase(q) === sZQ)     { lateReq(q) := true.B }
    when(phase(q) === sFragGo) { fragReq(q) := true.B }
  }

  // A quad waits for the engine while an older one at the same position is in flight: the tile
  // buffer sees a pixel's fragments in triangle order.
  val older = RegInit(perSlot(0.U(S.W)))
  private val busyNow = VecInit(phase.map(_ =/= sIdle)).asUInt
  for (q <- 0 until S) older(q) := Mux(io.pixelReady && newSlot === q.U, busyNow, older(q) & busyNow)
  private val behind = VecInit((0 until S).map(q => (0 until S).map(p =>
    older(q)(p) && tileIndexOf(p.U)(0) === tileIndexOf(q.U)(0)).foldLeft(false.B)(_ || _)))

  // ZTEST: run the early tests if this is a fragment shader that has not run them; otherwise (an
  // MMIO or compute run, a second ZTEST, no support) answer at once.
  val zq = io.zTestSlot
  val earlyReq = WireDefault(false.B)
  io.zTestDone := false.B
  when(io.zTestReq && !earlyActive) {
    if (earlySupported) {
      when(phase(zq) === sFrag && !earlyDone(zq)) { earlyReq := !behind(zq) }.otherwise { io.zTestDone := true.B }
    } else {
      io.zTestDone := true.B
    }
  }

  private val dstGo  = VecInit(dstReq.zip(behind).map { case (r, b) => r && !b })
  private val lateGo = VecInit(lateReq.zip(behind).map { case (r, b) => r && !b })

  // The engine, when free, to one request: ZTEST's, then a destination read, then the late tests.
  private def grantEngine(q: UInt, to: UInt): Unit = {
    eq := q; laneCtr := 0.U; sampleCtr.foreach(_ := 0.U); phase(q) := to
  }
  private val dstAny = dstGo.asUInt.orR; private val lateAny = lateGo.asUInt.orR
  private val dstWin = PriorityEncoder(dstGo); private val lateWin = PriorityEncoder(lateGo)
  when(!engineBusy && earlyReq) {
    earlyActive := true.B
    grantEngine(zq, sZRead)
    if (BorgDebug.trace) printf("[DISP] ZTEST: early tests slot=%d\n", zq)
  }.elsewhen(!engineBusy && dstAny) { grantEngine(dstWin, sDstRead) }
   .elsewhen(!engineBusy && lateAny) { grantEngine(lateWin, sZRead) }
  for (q <- 0 until S) {
    val granted = !engineBusy && !earlyReq && Mux(dstAny, dstWin === q.U && dstGo(q), lateWin === q.U && lateGo(q))
    when(dstReq(q) && !granted) { phase(q) := sDstQ }
    when(lateReq(q) && !granted) { phase(q) := sZQ }
  }

  // The tile buffer's read: address in the first cycle, data held from the third. The destination
  // word cannot go stale under the shader: an older quad on the same pixels has finished.
  when(phase(eq) === sDstRead) {
    io.tileRead.en  := true.B
    io.tileRead.idx := tileIndex(laneIdx)
    phase(eq) := sDstWait1
  }
  when(phase(eq) === sDstWait1) { phase(eq) := sDstWait2 }
  when(phase(eq) === sDstWait2) { phase(eq) := sDstCap }
  when(phase(eq) === sDstCap) {
    if (outs.rawSupported) {
      val d = io.tileRead.data(0)
      dstWord(eq)(laneIdx)(0) := Mux(io.srgbColor, Cat(d.g, d.r), Cat(io.alphaRead.get(0), ColorQuantize.quantize8(d.b),
                                 ColorQuantize.quantize8(d.g), ColorQuantize.quantize8(d.r)))
      dstWord(eq)(laneIdx)(1) := Mux(io.srgbColor, Cat(ColorQuantize.dequantize8(io.alphaRead.get(0)), d.b), io.extRead.get(0))
    }
    when(laneCtr === (N - 1).U) {
      laneCtr := 0.U
      fragReq(eq) := true.B
    }.otherwise {
      laneCtr := laneCtr + 1.U
      phase(eq) := sDstRead
    }
  }

  when(phase(eq) === sZRead) {
    io.tileRead.en  := true.B
    io.tileRead.idx := tileIndex(laneIdx)
    phase(eq) := sZWait1
  }
  when(phase(eq) === sZWait1) { phase(eq) := sZWait2 }
  when(phase(eq) === sZWait2) { phase(eq) := sTileWrite }

  when(phase(eq) === sTileWrite) {
    io.tileWrite.idx := tileIndex(laneIdx)
    ops.write(laneIdx, sampleCtr.map(_.asUInt).getOrElse(0.U), earlyActive, earlyDone(eq), earlyPass(eq))

    // The next lane once this one's samples are written; the tile read holds across them.
    def advanceLane(): Unit = {
      when(laneCtr === (N - 1).U) {
        laneCtr := 0.U
        when(earlyActive) {
          // Early tests done: back to the shader, stalled on ZTEST.
          earlyActive   := false.B
          earlyDone(eq) := true.B
          phase(eq)     := sFrag
          io.zTestDone  := true.B
        }.otherwise {
          phase(eq) := sIdle
        }
      }.otherwise {
        laneCtr := laneCtr + 1.U
        phase(eq) := sZRead
      }
    }
    sampleCtr match {
      case Some(ctr) =>
        when(ctr === (cfg.samples - 1).U) { ctr := 0.U; advanceLane() }.otherwise { ctr := ctr + 1.U }
      case None => advanceLane()
    }
  }

  // The trigger port: a new quad's edge test first, else one fragment shader; the others wait.
  private val fragWin = PriorityEncoder(fragReq)
  when(!io.pixelReady && fragReq.asUInt.orR) {
    io.coreTrigger.valid := true.B
    io.coreTrigger.pc    := io.fragPcReg
    io.coreTrigger.fragment := true.B
    io.triggerSlot       := fragWin
    if (BorgDebug.trace) printf("[DISP] -> sFrag slot=%d pc=%d\n", fragWin, io.fragPcReg)
  }
  for (q <- 0 until S) when(fragReq(q)) {
    phase(q) := Mux(!io.pixelReady && fragWin === q.U, sFrag, sFragGo)
  }

  cov.capture(io.csrWrite, phase(io.csrSlot) === sRast, io.csrSlot)
  outs.captureRast(io.csrWrite, phase(io.csrSlot) === sRast, io.csrSlot)
  outs.captureFrag(io.csrWrite, phase(io.csrSlot) === sFrag, io.csrSlot)

  // A new quad waits for a free slot; a new tile command, MMIO and the flusher for all of them.
  io.slotStall    := !phase.map(_ === sIdle).reduce(_ || _)
  io.autoRunStall := !phase.map(_ === sIdle).reduce(_ && _)
  io.insideFlag   := cov.at(eq).anyInside
  io.phase        := phase(0)
  io.idle         := phase.map(_ === sIdle).reduce(_ && _)
  for (p <- 0 until 2) io.pageBusy(p) := (0 until S).map(q => phase(q) =/= sIdle && pageOf(q.U) === p.U).reduce(_ || _)
  when(occOf(eq)) { io.occSamples := 0.U }
  require(phase(0).getWidth <= io.phase.getWidth, "the phase port must hold every state")

  // A helper lane: no covered sample, outside the scissor, discarded, or after ZTEST none of its
  // samples passed.
  private val (hq, covH, outsH) = (io.helperSlot, cov.at(io.helperSlot), outs.at(io.helperSlot))
  for (i <- 0 until N) {
    val failedEarly = earlyDone(hq) && !earlyPass(hq)(i).reduce(_ || _)
    io.laneHelper(i) := phase(hq) === sFrag &&
      (!covH.inside(i) || !scissorOf(hq)(i) || outsH.killed(i) || failedEarly)
  }
}
