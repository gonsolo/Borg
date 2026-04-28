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

  // Pre-advance position latched before stepping (used by shader r30/r31)
  val shaderIter  = Output(new Coord(cfg.coordWidth))

  // True while y has not reached tile_max.y
  val iterValid   = Output(Bool())

  // 4-bit tile-local index: x[1:0] | y[1:0]<<2  (current position)
  val tileIndex        = Output(UInt(4.W))

  // 4-bit tile-local index based on pre-advance (shader_iter_reg) — used by sTileWrite
  val shaderTileIndex  = Output(UInt(4.W))

  // One-cycle pulse: advance was processed, rasterizer should start sRast
  val pixelReady  = Output(Bool())

  // One-cycle pulse: tile just exhausted (last advance stepped y past tile_max.y)
  val tileComplete = Output(Bool())
}

class BorgIterator(val cfg: BorgConfig = BorgConfig.Sim) extends Module {
  val io = IO(new BorgIteratorIO(cfg))

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
  }

  // --- Pixel advance ---
  val pixel_ready    = WireDefault(false.B)
  val tile_complete  = WireDefault(false.B)
  when(io.advance) {
    shader_iter_reg := iter_reg   // latch pre-advance position
    when(iter_reg.x + 1.U >= tile_max_reg.x) {
      iter_reg.x := tile_origin_reg.x
      val next_y = iter_reg.y + 1.U
      iter_reg.y := next_y
      // Tile complete: y just stepped to or past tile_max.y
      when(next_y >= tile_max_reg.y) {
        tile_complete := true.B
      }
    }.otherwise {
      iter_reg.x := iter_reg.x + 1.U
    }
    pixel_ready := true.B
  }

  // --- Tile index helper ---
  def tileIndex(c: Coord): UInt = c.x(1, 0) | (c.y(1, 0) << 2.U)

  // --- Outputs ---
  io.iter            := iter_reg
  io.shaderIter      := shader_iter_reg
  io.iterValid       := iter_valid
  io.tileIndex       := tileIndex(iter_reg)
  io.shaderTileIndex := tileIndex(shader_iter_reg)
  io.pixelReady      := pixel_ready
  io.tileComplete    := tile_complete
}
