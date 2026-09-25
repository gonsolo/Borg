// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgRasterizer — integration wrapper for iterator + shader dispatcher.
  *
  * After Step 25.3d, this module is a thin composition layer that wires:
  *   - `BorgIterator`        (Step 25.3c) — bounding-box tile traversal
  *   - `BorgShaderDispatcher` (Step 25.3d) — per-pixel shader chaining FSM
  *
  * The external IO (`BorgRasterizerIO`) is **unchanged** from before the
  * extraction, so `Borg.scala` and all tests continue to work without
  * modification.
  *
  * Tile-origin mode: each command specifies a 4×4 tile origin.
  * The iterator walks all 16 pixels (x0..x0+3, y0..y0+3).
  * frag_pc and uniform_page come from dedicated registers, not the command.
  */

class BorgRasterizerIO(val cfg: BorgConfig) extends Bundle {
  // Command pop interface (Step 13.3) — forwarded to BorgIterator
  val cmdPop      = Flipped(Decoupled(new BorgCommand(cfg.coordWidth)))

  // Iterator advance (from MMIO write to BORG_ITER)
  val advance     = Input(Bool())

  // Pipeline write-back snoop (from BorgCore), per lane
  val pipeWrite = Flipped(Vec(cfg.fragLanes, new PipeWriteIO(cfg.totalBits)))

  // Core state feedback (needed for stall clearing)
  val coreStatus = Flipped(new CoreStatusIO)

  // Register-driven frag_pc and uniform_page (from dedicated MMIO registers)
  val fragPcReg       = Input(UInt(6.W))
  // Step 50 item 11: depth-test state, forwarded to BorgShaderDispatcher.
  val depthCompareOp  = Input(UInt(3.W))
  val depthWriteEn    = Input(Bool())
  val depthUnorm      = Input(Bool())
  // Step 50 item 9: blend state, forwarded to BorgShaderDispatcher. Present
  // only in a cfg.hasBlend build, matching the dispatcher's own port.
  val blendCfg        = if (cfg.hasBlend) Some(Input(new BlendConfig)) else None
  // Step 50 item 10: stencil state and the tile buffer's stencil plane,
  // forwarded to BorgShaderDispatcher.
  val frontFacing     = if (cfg.hasStencil) Some(Input(Bool())) else None
  val stencilCfg      = if (cfg.hasStencil) Some(Input(new StencilConfig)) else None
  val stencilRead     = if (cfg.hasStencil) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val stencilWrite    = if (cfg.hasStencil) Some(Output(UInt(8.W))) else None
  val stencilWriteMask = if (cfg.hasStencil) Some(Output(UInt(cfg.samples.W))) else None
  // Step 50 item 9: the tile buffer's destination-alpha plane.
  val alphaRead       = if (cfg.hasBlend) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val alphaWrite      = if (cfg.hasBlend) Some(Output(UInt(8.W))) else None
  val alphaWriteMask  = if (cfg.hasBlend) Some(Output(Bool())) else None
  // Step 50: scissor rectangle (SCISSOR_X/SCISSOR_Y). Tested here rather
  // than in the dispatcher because this is where the per-lane screen
  // coordinates are.
  val scissor         = Input(new ScissorConfig)
  val pixelOrigin     = Input(new Coord(14))   // the render window's origin
  val uniformPageReg  = Input(UInt(1.W))

  // Outputs
  val iter          = Output(new Coord(cfg.coordWidth))
  val shaderIter    = Output(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))  // per-lane pre-advance positions
  val insideFlag    = Output(Bool())
  val iterValid     = Output(Bool())
  val uniformPage   = Output(UInt(1.W)) // Expose to BorgCore
  val autoRunStall  = Output(Bool())
  val coreTrigger   = new CoreTriggerIO  // pulse: tells BorgCore to auto-run

  // One-cycle pulse: all 16 pixels of the tile have been advanced (Step 25.3h)
  val tileComplete  = Output(Bool())

  // Tile origin (top-left corner of the current 4×4 tile), valid when tileComplete fires
  val tileOrigin    = Output(new Coord(cfg.coordWidth))

  // MSAA coverage deltas from BorgSequencer (Step 50.2); see the matching
  // comment in BorgShaderDispatcherIO.  Absent at cfg.samples == 1.
  val covDelta = if (cfg.samples > 1)
    Some(Input(Vec(cfg.coveragePlanesStored, Vec(2, UInt(cfg.totalBits.W))))) else None
  // Draw front end: see BorgShaderDispatcherIO.drawMode.
  val drawMode = if (cfg.drawEnabled) Some(Input(Bool())) else None
  val topLeft  = if (cfg.drawEnabled) Some(Input(Vec(3, Bool()))) else None
  val sampleCfg    = Input(new SampleMaskConfig(cfg.samples))
  val laneCoverage = Output(Vec(cfg.fragLanes, UInt(cfg.samples.W)))
  val rawColor     = Input(Bool())                          // see the dispatcher's
  val laneDst      = Output(Vec(cfg.fragLanes, Vec(2, UInt(32.W))))
  val extRead      = if (cfg.drawEnabled && cfg.hasBlend) Some(Input(Vec(cfg.samples, UInt(32.W)))) else None
  val extWrite     = if (cfg.drawEnabled && cfg.hasBlend) Some(Output(UInt(32.W))) else None

  // Tile Buffer auto-write interface (Step 11.3)
  val tileWrite = new TileWriteIO(cfg.samples, cfg.tileDepthBits)
  // Step 25.5C: Tile Buffer read port for depth test
  val tileRead  = new TileReadIO(16, cfg.samples, cfg.tileDepthBits)

  // ZTEST (see BorgShaderDispatcher) and per-lane side-effect suppression.
  val zTestReq   = Input(Bool())
  val zTestDone  = Output(Bool())
  val laneHelper = Output(Vec(cfg.fragLanes, Bool()))
  val passSample = if (cfg.msaaMultiPass) Some(Input(UInt(log2Up(cfg.samples).W))) else None
  val occSamples = Output(UInt(log2Ceil(cfg.samples + 1).W))

  // Dispatcher FSM phase (exposed for sequencer pipeline drain)
  val dispatcherIdle  = Output(Bool())
}

class BorgRasterizer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  /** Auxiliary constructor: allows `new BorgRasterizer(FloatConfig.FP16)` in tests. */
  def this(fp: FloatConfig) = this(BorgConfig.Default.copy(fp = fp))

  val io = IO(new BorgRasterizerIO(cfg))

  // --- Sub-modules ---
  val iterator   = Module(new BorgIterator(cfg))
  val dispatcher = Module(new BorgShaderDispatcher(cfg))

  // --- Wire BorgIterator ---
  iterator.io.cmdPop    <> io.cmdPop
  // Gate advance on dispatcher idle: if the dispatcher is still processing
  // the previous pixel (autoRunStall), ignore the firmware's iter write.
  // The firmware retries because iterValid remains true.
  iterator.io.advance   := io.advance && !dispatcher.io.autoRunStall
  iterator.io.phaseIdle := dispatcher.io.idle

  // --- Wire BorgShaderDispatcher inputs ---
  dispatcher.io.pixelReady     := iterator.io.pixelReady
  dispatcher.io.shaderTileIndex := iterator.io.shaderTileIndex   // per-lane Vec
  dispatcher.io.pipeWrite      <> io.pipeWrite
  dispatcher.io.coreStatus     <> io.coreStatus
  dispatcher.io.fragPcReg      := io.fragPcReg
  dispatcher.io.depthCompareOp := io.depthCompareOp
  dispatcher.io.depthUnorm     := io.depthUnorm
  dispatcher.io.depthWriteEn   := io.depthWriteEn
  dispatcher.io.blendCfg.foreach(_ := io.blendCfg.get)
  dispatcher.io.frontFacing.foreach(_ := io.frontFacing.get)
  dispatcher.io.stencilCfg.foreach(_ := io.stencilCfg.get)
  dispatcher.io.stencilRead.foreach(_ := io.stencilRead.get)
  io.stencilWrite.foreach(_ := dispatcher.io.stencilWrite.get)
  io.stencilWriteMask.foreach(_ := dispatcher.io.stencilWriteMask.get)
  dispatcher.io.alphaRead.foreach(_ := io.alphaRead.get)
  io.alphaWrite.foreach(_ := dispatcher.io.alphaWrite.get)
  io.alphaWriteMask.foreach(_ := dispatcher.io.alphaWriteMask.get)
  // Scissor: one rectangle test per lane against its own pre-advance screen
  // position -- the same coordinates that produce shaderTileIndex, so the
  // result lines up with the tile slot the fragment will write.
  for (i <- 0 until cfg.fragLanes) {
    dispatcher.io.scissorPass(i) :=
      ScissorConfig.passes(io.scissor, iterator.io.shaderIter(i).x +& io.pixelOrigin.x,
                           iterator.io.shaderIter(i).y +& io.pixelOrigin.y)
  }
  dispatcher.io.covDelta.foreach(_ := io.covDelta.get)
  dispatcher.io.drawMode.foreach(_ := io.drawMode.get)
  dispatcher.io.topLeft.foreach(_ := io.topLeft.get)
  dispatcher.io.sampleCfg := io.sampleCfg
  io.laneCoverage := dispatcher.io.laneCoverage
  dispatcher.io.rawColor := io.rawColor
  io.laneDst := dispatcher.io.laneDst
  dispatcher.io.extRead.foreach(_ := io.extRead.get)
  io.extWrite.foreach(_ := dispatcher.io.extWrite.get)

  // --- Forward dispatcher outputs to rasterizer IO ---
  io.coreTrigger  <> dispatcher.io.coreTrigger
  io.tileWrite    <> dispatcher.io.tileWrite
  io.tileRead     <> dispatcher.io.tileRead
  io.autoRunStall := dispatcher.io.autoRunStall
  io.insideFlag   := dispatcher.io.insideFlag

  dispatcher.io.zTestReq := io.zTestReq
  io.zTestDone         := dispatcher.io.zTestDone
  io.laneHelper        := dispatcher.io.laneHelper
  dispatcher.io.passSample.foreach(_ := io.passSample.get)
  io.occSamples        := dispatcher.io.occSamples

  // --- Forward iterator outputs ---
  io.iter         := iterator.io.iter
  io.shaderIter   := iterator.io.shaderIter      // per-lane Vec → BorgCore per-lane coords
  io.iterValid    := iterator.io.iterValid
  io.tileComplete := iterator.io.tileComplete
  io.tileOrigin   := iterator.io.tileOrigin
  io.dispatcherIdle := dispatcher.io.idle

  // --- Passthrough ---
  io.uniformPage  := io.uniformPageReg  // pass through from register
}
