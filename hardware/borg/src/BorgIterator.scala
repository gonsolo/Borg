// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgIterator: walks one 4x4 tile in 2x2 quads.
  *
  * Each `advance` latches the current quad for the shader, steps, and pulses `pixelReady`.
  * A new command is popped only while the rasterizer is idle and the tile is exhausted.
  */
class BorgIteratorIO(val cfg: BorgConfig) extends Bundle {
  val cmdPop      = Flipped(Decoupled(new BorgCommand(cfg.coordWidth)))

  val advance     = Input(Bool())

  // Rasterizer is in sIdle, so a command may be popped.
  val phaseIdle   = Input(Bool())

  val iter        = Output(new Coord(cfg.coordWidth))

  // Pre-advance quad for the shader: lanes 0..3 = TL, TR, BL, BR.
  val shaderIter  = Output(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))

  val iterValid   = Output(Bool())

  // Tile-local index: x[1:0] | y[1:0]<<2.
  val tileIndex        = Output(UInt(4.W))

  val shaderTileIndex  = Output(Vec(cfg.fragLanes, UInt(4.W)))

  val pixelReady  = Output(Bool())

  val tileComplete = Output(Bool())

  // When tileComplete fires, iter.y is already past the tile; use tileOrigin.
  val tileOrigin = Output(new Coord(cfg.coordWidth))
}

class BorgIterator(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgIteratorIO(cfg))

  private val qdim = 2                                  // quad edge (pixels)
  private val step = qdim                               // advance stride per axis

  val iter_reg        = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))
  val shader_iter_reg = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))
  val tile_origin_reg = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))
  val tile_max_reg    = RegInit(0.U.asTypeOf(new Coord(cfg.coordWidth)))

  val iter_valid = iter_reg.y < tile_max_reg.y

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

  // Ignore advance until a command is loaded, or it fires a spurious tileComplete.
  val pixel_ready    = WireDefault(false.B)
  val tile_complete  = WireDefault(false.B)
  when(io.advance && iter_valid) {
    shader_iter_reg := iter_reg
    if (BorgDebug.trace) printf("[ITER] advance iter=(%d,%d) shaderIdx=%d -> shaderIdx=%d\n",
      iter_reg.x, iter_reg.y, tileIndex(shader_iter_reg), tileIndex(iter_reg))
    when(iter_reg.x + step.U >= tile_max_reg.x) {
      iter_reg.x := tile_origin_reg.x
      val next_y = iter_reg.y + step.U
      iter_reg.y := next_y
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

  // Static `<< 2`: `<< 2.U` is a dynamic shift and widens to 5 bits.
  def tileIndex(c: Coord): UInt = c.x(1, 0) | (c.y(1, 0) << 2)

  io.iter            := iter_reg
  io.iterValid       := iter_valid
  io.tileIndex       := tileIndex(iter_reg)
  io.pixelReady      := pixel_ready
  io.tileComplete    := tile_complete
  io.tileOrigin      := tile_origin_reg

  for (i <- 0 until cfg.fragLanes) {
    val lx = shader_iter_reg.x + (i % qdim).U
    val ly = shader_iter_reg.y + (i / qdim).U
    io.shaderIter(i).x   := lx
    io.shaderIter(i).y   := ly
    io.shaderTileIndex(i) := lx(1, 0) | (ly(1, 0) << 2)
  }
}
