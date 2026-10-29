// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Composes BorgIterator (walks a command's 4x4 tile) and BorgShaderDispatcher
  * (per-pixel shader runs), and tests the scissor per lane. */
class BorgRasterizerIO(val cfg: BorgConfig) extends Bundle {
  val cmdPop      = Flipped(Decoupled(new BorgCommand(cfg.coordWidth)))

  // MMIO write to BORG_ITER.
  val advance     = Input(Bool())

  val csrWrite  = Flipped(Vec(cfg.fragLanes, new CsrWriteIO(cfg.totalBits)))
  val csrSlot   = Input(UInt(cfg.slotBits.W))

  val coreStatus = Flipped(Vec(cfg.quadSlots, new CoreStatusIO))
  val iterSlot   = Input(UInt(cfg.slotBits.W))
  val helperSlot = Input(UInt(cfg.slotBits.W))
  val triggerSlot = Output(UInt(cfg.slotBits.W))

  val fragPcReg       = Input(UInt(6.W))
  val depthCompareOp  = Input(UInt(3.W))
  val depthWriteEn    = Input(Bool())
  val depthUnorm      = Input(Bool())
  val blendCfg        = if (cfg.hasBlend) Some(Input(new BlendConfig)) else None
  val frontFacing     = if (cfg.hasStencil) Some(Input(Bool())) else None
  val stencilCfg      = if (cfg.hasStencil) Some(Input(new StencilConfig)) else None
  val stencilRead     = if (cfg.hasStencil) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val stencilWrite    = if (cfg.hasStencil) Some(Output(UInt(8.W))) else None
  val stencilWriteMask = if (cfg.hasStencil) Some(Output(UInt(cfg.samples.W))) else None
  val alphaRead       = if (cfg.hasBlend) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val alphaWrite      = if (cfg.hasBlend) Some(Output(UInt(8.W))) else None
  val alphaWriteMask  = if (cfg.hasBlend) Some(Output(Bool())) else None
  // Tested here, where the per-lane screen coordinates are.
  val scissor         = Input(new ScissorConfig)
  val pixelOrigin     = Input(new Coord(14))   // the render window's origin
  val uniformPageReg  = Input(UInt(1.W))

  val iter          = Output(new Coord(cfg.coordWidth))
  val shaderIter    = Output(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))  // per-lane pre-advance positions
  val insideFlag    = Output(Bool())
  val iterValid     = Output(Bool())
  val uniformPage   = Output(UInt(1.W))
  val autoRunStall  = Output(Bool())
  val coreTrigger   = new CoreTriggerIO

  // Pulse: all 16 pixels of the tile have been advanced.
  val tileComplete  = Output(Bool())

  val tileOrigin    = Output(new Coord(cfg.coordWidth))

  val covDelta = if (cfg.samples > 1)
    Some(Input(Vec(cfg.coveragePlanesStored, Vec(2, UInt(cfg.totalBits.W))))) else None
  val drawMode = Input(Bool())
  val topLeft  = Input(Vec(3, Bool()))
  val sampleCfg    = Input(new SampleMaskConfig(cfg.samples))
  val laneCoverage = Output(Vec(cfg.fragLanes, UInt(cfg.samples.W)))
  val srgbColor    = Input(Bool())                          // sRGB attachment: TLD reads linear FP16
  val rawColor     = Input(Bool())
  val laneDst      = Output(Vec(cfg.fragLanes, Vec(2, UInt(32.W))))
  val extRead      = if (cfg.hasBlend) Some(Input(Vec(cfg.samples, UInt(32.W)))) else None
  val extWrite     = if (cfg.hasBlend) Some(Output(UInt(32.W))) else None

  val tileWrite = new TileWriteIO(cfg.samples, cfg.tileDepthBits)
  val tileRead  = new TileReadIO(16, cfg.samples, cfg.tileDepthBits)

  val zTestReq   = Input(Bool())
  val zTestDone  = Output(Bool())
  val laneHelper = Output(Vec(cfg.fragLanes, Bool()))
  val occSamples = Output(UInt(log2Ceil(cfg.samples + 1).W))

  // For the sequencer's pipeline drain.
  val dispatcherIdle  = Output(Bool())
}

class BorgRasterizer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgRasterizerIO(cfg))

  val iterator   = Module(new BorgIterator(cfg))
  val dispatcher = Module(new BorgShaderDispatcher(cfg))

  iterator.io.cmdPop    <> io.cmdPop
  // An advance during autoRunStall is dropped; firmware retries while iterValid.
  iterator.io.advance   := io.advance && !dispatcher.io.autoRunStall
  iterator.io.phaseIdle := dispatcher.io.idle

  dispatcher.io.pixelReady     := iterator.io.pixelReady
  dispatcher.io.shaderTileIndex := iterator.io.shaderTileIndex
  dispatcher.io.shaderIter     := iterator.io.shaderIter
  dispatcher.io.csrWrite       <> io.csrWrite
  dispatcher.io.csrSlot        := io.csrSlot
  dispatcher.io.iterSlot       := io.iterSlot
  dispatcher.io.helperSlot     := io.helperSlot
  io.triggerSlot               := dispatcher.io.triggerSlot
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
  // Each lane's pre-advance position, the one that produces shaderTileIndex.
  for (i <- 0 until cfg.fragLanes) {
    dispatcher.io.scissorPass(i) :=
      ScissorConfig.passes(io.scissor, iterator.io.shaderIter(i).x +& io.pixelOrigin.x,
                           iterator.io.shaderIter(i).y +& io.pixelOrigin.y)
  }
  dispatcher.io.covDelta.foreach(_ := io.covDelta.get)
  dispatcher.io.drawMode := io.drawMode
  dispatcher.io.topLeft := io.topLeft
  dispatcher.io.sampleCfg := io.sampleCfg
  io.laneCoverage := dispatcher.io.laneCoverage
  dispatcher.io.rawColor := io.rawColor
  dispatcher.io.srgbColor := io.srgbColor
  io.laneDst := dispatcher.io.laneDst
  dispatcher.io.extRead.foreach(_ := io.extRead.get)
  io.extWrite.foreach(_ := dispatcher.io.extWrite.get)

  io.coreTrigger  <> dispatcher.io.coreTrigger
  io.tileWrite    <> dispatcher.io.tileWrite
  io.tileRead     <> dispatcher.io.tileRead
  io.autoRunStall := dispatcher.io.autoRunStall
  io.insideFlag   := dispatcher.io.insideFlag

  dispatcher.io.zTestReq := io.zTestReq
  io.zTestDone         := dispatcher.io.zTestDone
  io.laneHelper        := dispatcher.io.laneHelper
  io.occSamples        := dispatcher.io.occSamples

  io.iter         := iterator.io.iter
  io.shaderIter   := dispatcher.io.quadIter
  io.iterValid    := iterator.io.iterValid
  io.tileComplete := iterator.io.tileComplete
  io.tileOrigin   := iterator.io.tileOrigin
  io.dispatcherIdle := dispatcher.io.idle

  io.uniformPage  := io.uniformPageReg
}
