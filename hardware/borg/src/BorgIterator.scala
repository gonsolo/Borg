// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgIterator — bounding-box tile traversal engine.
  *
  * Owns the command-pop interface, the four iteration registers
  * (iter_reg, shader_iter_reg, tile_origin_reg, tile_max_reg),
  * iter_valid computation, and the pixel-advance stepping logic.
  *
  * On each `advance` pulse:
  *   1. Latches current `iter_reg` into `shader_iter_reg`
  *      (pre-advance position used by coordLut / shader r30/r31).
  *   2. Steps `iter_reg.x/y` within the 4×4 tile.
  *   3. Asserts `pixelReady` for one cycle so the rasterizer FSM
  *      can trigger the edge shader and set its stall flag.
  *
  * Command popping: when `phase_idle` is asserted by the parent
  * rasterizer AND `iter_valid` is false, a new command is accepted
  * from `cmdPop`.
  *
  * Step 25.3c.
  */
class BorgIteratorIO(val cfg: BorgConfig) extends Bundle {
  // Command pop interface
  val cmdPop      = Flipped(Decoupled(new BorgCommand(cfg.coordWidth)))

  // Advance pulse from MMIO (parent rasterizer forwards BORG_ITER write)
  val advance     = Input(Bool())

  // Rasterizer tells us it is in sIdle so we may pop a command
  val phaseIdle   = Input(Bool())

  // Current iterator position (goes to MMIO iter_x/iter_y and coordLut)
  val iter        = Output(new Coord(cfg.coordWidth))

  // Pre-advance position latched before stepping (used by shader r30/r31).
  // Per-lane: at fragLanes==1 a single pixel; at fragLanes==4 the 2×2 quad
  // (TL/TR/BL/BR = lane 0/1/2/3) rooted at the latched quad origin.
  val shaderIter  = Output(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))

  // True while y has not reached tile_max.y
  val iterValid   = Output(Bool())

  // 4-bit tile-local index: x[1:0] | y[1:0]<<2  (current position)
  val tileIndex        = Output(UInt(4.W))

  // 4-bit tile-local index per lane (pre-advance) — used by sTileWrite
  val shaderTileIndex  = Output(Vec(cfg.fragLanes, UInt(4.W)))

  // One-cycle pulse: advance was processed, rasterizer should start sRast
  val pixelReady  = Output(Bool())

  // One-cycle pulse: tile just exhausted (last advance stepped y past tile_max.y)
  val tileComplete = Output(Bool())

  // Tile origin (top-left corner of the current 4×4 tile)
  // Valid whenever iter_valid has been true (i.e. a command is active).
  // At the moment tileComplete fires, iter.y has already advanced past the tile;
  // use tileOrigin (not iter) to get the correct tile base coordinates.
  val tileOrigin = Output(new Coord(cfg.coordWidth))
}

class BorgIterator(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgIteratorIO(cfg))

  // SIMT quad geometry: fragLanes==4 emits a 2×2 quad per advance (step 2 in each
  // axis → four quads tile the 4×4); fragLanes==1 is the scalar single-pixel walk.
  private val qdim = if (cfg.fragLanes == 4) 2 else 1   // quad edge (pixels)
  private val step = qdim                               // advance stride per axis

  // --- Registers ---
  val iter_reg        = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))
  val shader_iter_reg = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))
  val tile_origin_reg = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))
  val tile_max_reg    = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))

  // --- iter_valid ---
  val iter_valid = iter_reg.y < tile_max_reg.y

  // --- Command popping ---
  // Pop when: rasterizer is idle AND tile is exhausted AND FIFO has data
  io.cmdPop.ready := false.B
  when(io.phaseIdle && io.cmdPop.valid && !iter_valid) {
    io.cmdPop.ready     := true.B
    tile_origin_reg     := io.cmdPop.bits.tileOrigin
    tile_max_reg.x      := io.cmdPop.bits.tileOrigin.x + 4.U
    tile_max_reg.y      := io.cmdPop.bits.tileOrigin.y + 4.U
    iter_reg            := io.cmdPop.bits.tileOrigin
    if (BorgDebug.trace) printf("[ITER] cmdPop origin=(%d,%d) phaseIdle=%d iterValid=%d shaderIdx=%d\n",
      io.cmdPop.bits.tileOrigin.x, io.cmdPop.bits.tileOrigin.y,
      io.phaseIdle, iter_valid, tileIndex(shader_iter_reg))
  }

  // --- Pixel advance ---
  // Gate on iter_valid: if no tile is loaded (exhausted or pre-pop), advance is a no-op.
  // This prevents spurious tileComplete when the sequencer fires advance before
  // the FIFO command has been popped into the iterator registers.
  val pixel_ready    = WireDefault(false.B)
  val tile_complete  = WireDefault(false.B)
  when(io.advance && iter_valid) {
    shader_iter_reg := iter_reg   // latch pre-advance position
    if (BorgDebug.trace) printf("[ITER] advance iter=(%d,%d) shaderIdx=%d -> shaderIdx=%d\n",
      iter_reg.x, iter_reg.y, tileIndex(shader_iter_reg), tileIndex(iter_reg))
    when(iter_reg.x + step.U >= tile_max_reg.x) {
      iter_reg.x := tile_origin_reg.x
      val next_y = iter_reg.y + step.U
      iter_reg.y := next_y
      // Tile complete: y just stepped to or past tile_max.y
      when(next_y >= tile_max_reg.y) {
        tile_complete := true.B
        if (BorgDebug.trace) printf("[ITER] tileComplete iter=(%d,%d)\n", iter_reg.x, iter_reg.y)
      }
    }.otherwise {
      iter_reg.x := iter_reg.x + step.U
    }
    pixel_ready := true.B
  }
  when(io.advance && !iter_valid) {
    if (BorgDebug.trace) printf("[ITER] advance IGNORED (iter_valid=0) iter=(%d,%d) max=(%d,%d)\n",
      iter_reg.x, iter_reg.y, tile_max_reg.x, tile_max_reg.y)
  }

  // --- Tile index helper ---
  def tileIndex(c: Coord): UInt = c.x(1, 0) | (c.y(1, 0) << 2.U)

  // --- Outputs ---
  io.iter            := iter_reg
  io.iterValid       := iter_valid
  io.tileIndex       := tileIndex(iter_reg)
  io.pixelReady      := pixel_ready
  io.tileComplete    := tile_complete
  io.tileOrigin      := tile_origin_reg

  // Per-lane pre-advance positions: lane i sits at (qx + i%qdim, qy + i/qdim)
  // of the latched quad origin.  At fragLanes==1 this is just shader_iter_reg.
  for (i <- 0 until cfg.fragLanes) {
    val lx = shader_iter_reg.x + (i % qdim).U
    val ly = shader_iter_reg.y + (i / qdim).U
    io.shaderIter(i).x   := lx
    io.shaderIter(i).y   := ly
    io.shaderTileIndex(i) := lx(1, 0) | (ly(1, 0) << 2.U)
  }
}
