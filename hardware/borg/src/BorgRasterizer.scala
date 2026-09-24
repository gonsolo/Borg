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

  // Tile Buffer auto-write interface (Step 11.3)
  val tileWrite = new TileWriteIO(cfg.samples, cfg.tileDepthBits)
  // Step 25.5C: Tile Buffer read port for depth test
  val tileRead  = new TileReadIO(16, cfg.samples, cfg.tileDepthBits)

  // GPU memory read/write port (Step 19.2/24.3)
  val gpuMem    = new GpuMemIO
  val texConfig  = new TexConfigIO
  // log2 of the texture dimension (tex_config_log2_dim MMIO field), for
  // clamping texel coordinates to the last valid row/column -- see
  // ClampTexCoord's comment.
  val log2Dim    = Input(UInt(4.W))
  // Runtime VkFilter for the sampler, forwarded to BorgShaderDispatcher.
  val texFilterLinear = if (cfg.hasBilinear) Some(Input(Bool())) else None
  val texAddrModeU    = if (cfg.hasBilinear) Some(Input(UInt(2.W))) else None
  val texAddrModeV    = if (cfg.hasBilinear) Some(Input(UInt(2.W))) else None
  val texBorder       = if (cfg.hasBilinear) Some(Input(UInt(2.W))) else None

  // Step 34.5: FTEX core ↔ dispatcher texture request/response
  val texReq  = Input(Bool())
  val texU    = Input(UInt(16.W))
  val texV    = Input(UInt(16.W))
  val texDone = Output(Bool())
  // ZTEST (see BorgShaderDispatcher) and per-lane side-effect suppression.
  val zTestReq   = Input(Bool())
  val zTestDone  = Output(Bool())
  val laneHelper = Output(Vec(cfg.fragLanes, Bool()))
  val passSample = if (cfg.msaaMultiPass) Some(Input(UInt(log2Up(cfg.samples).W))) else None
  val occSamples = Output(UInt(log2Ceil(cfg.samples + 1).W))
  val texR    = Output(UInt(16.W))
  val texG    = Output(UInt(16.W))
  val texB    = Output(UInt(16.W))
  val texA    = Output(UInt(16.W))

  // Dispatcher FSM phase (exposed for sequencer pipeline drain)
  val dispatcherPhase = Output(UInt(3.W))
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
  iterator.io.phaseIdle := (dispatcher.io.phase === 0.U)  // sIdle = Enum(5)(0) = 0

  // --- Wire BorgShaderDispatcher inputs ---
  dispatcher.io.pixelReady     := iterator.io.pixelReady
  dispatcher.io.shaderTileIndex := iterator.io.shaderTileIndex   // per-lane Vec
  dispatcher.io.pipeWrite      <> io.pipeWrite
  dispatcher.io.coreStatus     <> io.coreStatus
  dispatcher.io.fragPcReg      := io.fragPcReg
  dispatcher.io.depthCompareOp := io.depthCompareOp
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
  dispatcher.io.texConfig      <> io.texConfig
  dispatcher.io.log2Dim        := io.log2Dim
  dispatcher.io.texFilterLinear.foreach(_ := io.texFilterLinear.get)
  dispatcher.io.texAddrModeU.foreach(_ := io.texAddrModeU.get)
  dispatcher.io.texAddrModeV.foreach(_ := io.texAddrModeV.get)
  dispatcher.io.texBorder.foreach(_ := io.texBorder.get)
  dispatcher.io.covDelta.foreach(_ := io.covDelta.get)
  dispatcher.io.drawMode.foreach(_ := io.drawMode.get)
  dispatcher.io.topLeft.foreach(_ := io.topLeft.get)
  dispatcher.io.sampleCfg := io.sampleCfg
  io.laneCoverage := dispatcher.io.laneCoverage

  // --- Forward dispatcher outputs to rasterizer IO ---
  io.coreTrigger  <> dispatcher.io.coreTrigger
  io.tileWrite    <> dispatcher.io.tileWrite
  io.tileRead     <> dispatcher.io.tileRead
  io.gpuMem       <> dispatcher.io.gpuMem
  io.autoRunStall := dispatcher.io.autoRunStall
  io.insideFlag   := dispatcher.io.insideFlag

  // Step 34.5: FTEX core ↔ dispatcher forwarding
  dispatcher.io.texReq := io.texReq
  dispatcher.io.texU   := io.texU
  dispatcher.io.texV   := io.texV
  io.texDone           := dispatcher.io.texDone
  io.texR              := dispatcher.io.texR
  io.texG              := dispatcher.io.texG
  io.texB              := dispatcher.io.texB
  io.texA              := dispatcher.io.texA
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
  io.dispatcherPhase := dispatcher.io.phase

  // --- Passthrough ---
  io.uniformPage  := io.uniformPageReg  // pass through from register
}
