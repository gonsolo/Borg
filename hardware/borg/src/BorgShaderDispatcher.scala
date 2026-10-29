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
  val coreStatus = Flipped(new CoreStatusIO)
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

  val autoRunStall = Output(Bool())     // from a quad's start until it is written
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

/** A quad's way from the iterator to the tile buffer:
  *
  *   sIdle -> sRast (the raster ROM's edge test on the core) -> [sDstRead..sDstCap: each lane's
  *   destination word, for TLD] -> sFrag (the fragment shader) -> sZRead..sTileWrite (per lane:
  *   read the tile, test and write) -> sIdle.
  *
  * A quad no lane of which is inside goes back to sIdle after sRast. Outside lanes run as helpers
  * and are masked at the write. ZTEST runs the tests mid-shader with the same states, then
  * returns to sFrag ([[FragmentOps]]). */
class BorgShaderDispatcher(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgShaderDispatcherIO(cfg))
  private val N = cfg.fragLanes

  val (sIdle :: sRast :: sFrag :: sZRead :: sZWait1 :: sZWait2 :: sTileWrite ::
       sDstRead :: sDstWait1 :: sDstWait2 :: sDstCap :: Nil) = Enum(11)
  val phase = RegInit(sIdle)

  // The quad's inputs, held: the iterator moves on once the quad has a slot.
  private val capture = RegNext(io.pixelReady, false.B)
  private def held[T <: Data](live: T): T = Mux(capture, live, RegEnable(live, capture))
  val tileIndex   = held(io.shaderTileIndex)
  val scissorPass = held(io.scissorPass)
  io.quadIter := held(io.shaderIter)

  // The quad slot; one until the dispatcher keeps several.
  val slot = 0.U(cfg.slotBits.W)
  private def perSlot[T <: Data](x: => T): Vec[T] = VecInit(Seq.fill(cfg.quadSlots)(x))
  val cov  = new QuadCoverage(cfg, io.drawMode, io.topLeft, io.covDelta, io.sampleCfg)
  val outs = new FragOutputs(cfg, io.drawMode, io.depthUnorm, io.sampleCfg)
  private val (covQ, outsQ) = (cov.at(slot), outs.at(slot))
  val ops  = new FragmentOps(cfg, io, outsQ, covQ, scissorPass, tileIndex)
  io.laneCoverage := covQ.laneMask

  val auto_run_stall = RegInit(false.B)

  val dstWord = RegInit(perSlot(VecInit(Seq.fill(N)(VecInit(Seq.fill(2)(0.U(32.W)))))))
  io.laneDst := dstWord(slot)
  io.extWrite.foreach(_ := 0.U)
  private val dstActive = (io.rawColor || io.srgbColor) && outs.rawSupported.B

  // ZTEST is supported where the late path writes one known sample at a time; otherwise it
  // completes at once and the tests stay late.
  val earlySupported = cfg.samples == 1 || ops.perSample
  val earlyActive = RegInit(false.B)   // in the ZTEST sub-phase
  val earlyDone   = RegInit(perSlot(false.B))   // this quad's tests already ran
  val earlyPass   = Reg(Vec(cfg.quadSlots, Vec(N, Vec(cfg.samples, Bool()))))

  // log2Up: a wider counter is a wider index into the lanes' Vecs, which ABC9 handles badly.
  val laneCtr = RegInit(0.U(log2Up(N).W))
  val sampleCtr = Option.when(ops.perSample)(RegInit(0.U(log2Up(cfg.samples).W)))
  private val laneIdx: UInt = laneCtr

  io.coreTrigger.valid   := false.B
  io.coreTrigger.pc      := 0.U
  io.coreTrigger.isRast  := false.B
  io.coreTrigger.isSetup := false.B

  io.tileWrite.en       := false.B
  io.tileWrite.idx      := tileIndex(0)
  io.tileWrite.data     := 0.U.asTypeOf(new ColorZ(16, cfg.tileDepthBits))
  io.tileWrite.coverage := 0.U
  io.occSamples         := 0.U
  io.stencilWrite.foreach(_ := 0.U)
  io.stencilWriteMask.foreach(_ := 0.U)
  io.alphaWrite.foreach(_ := 0.U)
  io.alphaWriteMask.foreach(_ := false.B)

  // ZTEST: run the early tests if this is a fragment shader that has not run them; otherwise (an
  // MMIO or compute run, a second ZTEST, no support) answer at once.
  io.zTestDone := false.B
  when(io.zTestReq && !earlyActive) {
    if (earlySupported) {
      when(phase === sFrag && !earlyDone(slot)) {
        earlyActive := true.B
        laneCtr     := 0.U
        sampleCtr.foreach(_ := 0.U)
        phase       := sZRead
        if (BorgDebug.trace) printf("[DISP] ZTEST: early tests\n")
      }.otherwise {
        io.zTestDone := true.B
      }
    } else {
      io.zTestDone := true.B
    }
  }

  io.tileRead.en  := false.B
  io.tileRead.idx := tileIndex(0)

  when(io.pixelReady) {
    cov.clear(slot)
    outs.clear(slot)
    laneCtr := 0.U
    sampleCtr.foreach(_ := 0.U)
    earlyActive := false.B
    earlyDone(slot) := false.B
    auto_run_stall := true.B
    phase := sRast
    io.coreTrigger.valid  := true.B
    io.coreTrigger.pc     := 0.U
    io.coreTrigger.isRast := true.B
    if (BorgDebug.trace) printf("[DISP] pixelReady tileIdx0=%d\n", tileIndex(0))
  }

  val core_was_active = RegNext(io.coreStatus.running || io.coreStatus.autoRunPending, false.B)
  val core_just_finished = core_was_active && !io.coreStatus.running && !io.coreStatus.autoRunPending

  when(phase === sRast && core_just_finished) {
    when(covQ.anyInside && io.fragPcReg =/= 0.U) {
      when(dstActive) {
        laneCtr := 0.U
        phase   := sDstRead
      }.otherwise {
        phase := sFrag
        io.coreTrigger.valid := true.B
        io.coreTrigger.pc    := io.fragPcReg
      }
      if (BorgDebug.trace) printf("[DISP] -> sFrag pc=%d any_inside=%d\n", io.fragPcReg, covQ.anyInside)
    }.otherwise {
      phase := sIdle
      auto_run_stall := false.B
      if (BorgDebug.trace) printf("[DISP] -> sIdle (no lane inside or no frag)\n")
    }
  }

  when(phase === sFrag && core_just_finished) {
    laneCtr := 0.U
    phase   := sZRead
  }

  // The tile buffer's read: address in the first cycle, data held from the third. The destination
  // word cannot go stale under the shader: quads of a triangle cover distinct pixels and the next
  // triangle starts once this one is written.
  when(phase === sDstRead) {
    io.tileRead.en  := true.B
    io.tileRead.idx := tileIndex(laneIdx)
    phase := sDstWait1
  }
  when(phase === sDstWait1) { phase := sDstWait2 }
  when(phase === sDstWait2) { phase := sDstCap }
  when(phase === sDstCap) {
    if (outs.rawSupported) {
      val d = io.tileRead.data(0)
      dstWord(slot)(laneIdx)(0) := Mux(io.srgbColor, Cat(d.g, d.r), Cat(io.alphaRead.get(0), ColorQuantize.quantize8(d.b),
                                 ColorQuantize.quantize8(d.g), ColorQuantize.quantize8(d.r)))
      dstWord(slot)(laneIdx)(1) := Mux(io.srgbColor, Cat(ColorQuantize.dequantize8(io.alphaRead.get(0)), d.b), io.extRead.get(0))
    }
    when(laneCtr === (N - 1).U) {
      laneCtr := 0.U
      phase   := sFrag
      io.coreTrigger.valid := true.B
      io.coreTrigger.pc    := io.fragPcReg
    }.otherwise {
      laneCtr := laneCtr + 1.U
      phase   := sDstRead
    }
  }

  when(phase === sZRead) {
    io.tileRead.en  := true.B
    io.tileRead.idx := tileIndex(laneIdx)
    phase := sZWait1
  }
  when(phase === sZWait1) { phase := sZWait2 }
  when(phase === sZWait2) { phase := sTileWrite }

  when(phase === sTileWrite) {
    io.tileWrite.idx := tileIndex(laneIdx)
    ops.write(laneIdx, sampleCtr.map(_.asUInt).getOrElse(0.U), earlyActive, earlyDone(slot), earlyPass(slot))

    // The next lane once this one's samples are written; the tile read holds across them.
    def advanceLane(): Unit = {
      when(laneCtr === (N - 1).U) {
        laneCtr := 0.U
        when(earlyActive) {
          // Early tests done: back to the shader, stalled on ZTEST.
          earlyActive  := false.B
          earlyDone(slot) := true.B
          phase        := sFrag
          io.zTestDone := true.B
        }.otherwise {
          phase := sIdle
          auto_run_stall := false.B
        }
      }.otherwise {
        laneCtr := laneCtr + 1.U
        phase := sZRead
      }
    }
    sampleCtr match {
      case Some(ctr) =>
        when(ctr === (cfg.samples - 1).U) { ctr := 0.U; advanceLane() }.otherwise { ctr := ctr + 1.U }
      case None => advanceLane()
    }
  }

  cov.capture(io.csrWrite, phase === sRast, io.csrSlot)
  outs.captureRast(io.csrWrite, phase === sRast, io.csrSlot)
  outs.captureFrag(io.csrWrite, phase === sFrag, io.csrSlot)

  io.autoRunStall := auto_run_stall
  io.insideFlag   := covQ.anyInside
  io.phase        := phase
  io.idle         := phase === sIdle
  require(phase.getWidth <= io.phase.getWidth, "the phase port must hold every state")

  // A helper lane: no covered sample, outside the scissor, discarded, or after ZTEST none of its
  // samples passed.
  for (i <- 0 until N) {
    val failedEarly = earlyDone(slot) && !earlyPass(slot)(i).reduce(_ || _)
    io.laneHelper(i) := phase === sFrag &&
      (!covQ.inside(i) || !scissorPass(i) || outsQ.killed(i) || failedEarly)
  }
}
