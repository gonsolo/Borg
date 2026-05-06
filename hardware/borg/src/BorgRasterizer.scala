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

  // Pipeline write-back snoop (from BorgCore)
  val pipeWrite = Flipped(new PipeWriteIO(cfg.totalBits))

  // Core state feedback (needed for stall clearing)
  val coreStatus = Flipped(new CoreStatusIO)

  // Register-driven frag_pc and uniform_page (from dedicated MMIO registers)
  val fragPcReg       = Input(UInt(6.W))
  val uniformPageReg  = Input(UInt(1.W))

  // Outputs
  val iter          = Output(new Coord(cfg.coordWidth))
  val shaderIter    = Output(new Coord(cfg.coordWidth))  // latched pre-advance position for coordLut
  val insideFlag    = Output(Bool())
  val iterValid     = Output(Bool())
  val uniformPage   = Output(UInt(1.W)) // Expose to BorgCore
  val autoRunStall  = Output(Bool())
  val coreTrigger   = new CoreTriggerIO  // pulse: tells BorgCore to auto-run

  // One-cycle pulse: all 16 pixels of the tile have been advanced (Step 25.3h)
  val tileComplete  = Output(Bool())

  // Tile origin (top-left corner of the current 4×4 tile), valid when tileComplete fires
  val tileOrigin    = Output(new Coord(cfg.coordWidth))

  // Tile Buffer auto-write interface (Step 11.3)
  val tileWrite = new TileWriteIO
  // Step 25.5C: Tile Buffer read port for depth test
  val tileRead  = new TileReadIO(16)

  // GPU memory read/write port (Step 19.2/24.3)
  val gpuMem    = new GpuMemIO
  val texConfig  = new TexConfigIO

  // Snooped fragment U/V outputs for autonomous Morton encoding (Step 21.2)
  // When texturing, frag.s maps U→outR (r26) and V→outG (r27) via shader recompilation.
  val fragU      = Output(UInt(16.W))
  val fragV      = Output(UInt(16.W))

  // Step 34.5: FTEX core ↔ dispatcher texture request/response
  val texReq  = Input(Bool())
  val texU    = Input(UInt(16.W))
  val texV    = Input(UInt(16.W))
  val texDone = Output(Bool())
  val texR    = Output(UInt(16.W))
  val texG    = Output(UInt(16.W))
  val texB    = Output(UInt(16.W))

  // Dispatcher FSM phase (exposed for sequencer pipeline drain)
  val dispatcherPhase = Output(UInt(3.W))
}

class BorgRasterizer(val cfg: BorgConfig = BorgConfig.Sim) extends Module {
  /** Auxiliary constructor: allows `new BorgRasterizer(FloatConfig.FP16)` in tests. */
  def this(fp: FloatConfig) = this(BorgConfig.Sim.copy(fp = fp))

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
  dispatcher.io.shaderTileIndex := iterator.io.shaderTileIndex
  dispatcher.io.pipeWrite      <> io.pipeWrite
  dispatcher.io.coreStatus     <> io.coreStatus
  dispatcher.io.fragPcReg      := io.fragPcReg
  dispatcher.io.texConfig      <> io.texConfig

  // --- Forward dispatcher outputs to rasterizer IO ---
  io.coreTrigger  <> dispatcher.io.coreTrigger
  io.tileWrite    <> dispatcher.io.tileWrite
  io.tileRead     <> dispatcher.io.tileRead
  io.gpuMem       <> dispatcher.io.gpuMem
  io.autoRunStall := dispatcher.io.autoRunStall
  io.insideFlag   := dispatcher.io.insideFlag
  io.fragU        := dispatcher.io.fragU
  io.fragV        := dispatcher.io.fragV

  // Step 34.5: FTEX core ↔ dispatcher forwarding
  dispatcher.io.texReq := io.texReq
  dispatcher.io.texU   := io.texU
  dispatcher.io.texV   := io.texV
  io.texDone           := dispatcher.io.texDone
  io.texR              := dispatcher.io.texR
  io.texG              := dispatcher.io.texG
  io.texB              := dispatcher.io.texB

  // --- Forward iterator outputs ---
  io.iter         := iterator.io.iter
  io.shaderIter   := iterator.io.shaderIter
  io.iterValid    := iterator.io.iterValid
  io.tileComplete := iterator.io.tileComplete
  io.tileOrigin   := iterator.io.tileOrigin
  io.dispatcherPhase := dispatcher.io.phase

  // --- Passthrough ---
  io.uniformPage  := io.uniformPageReg  // pass through from register
}
