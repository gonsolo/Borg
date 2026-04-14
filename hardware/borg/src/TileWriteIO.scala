// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Rasterizer → TileBuffer write interface (from rasterizer Output perspective).
  * idx:  4-bit tile buffer slot (x[1:0] | y[1:0] << 2)
  * data: packed ColorZ(16) — r/g/b/z each 16-bit FP
  * en:   write-enable (combinationally high in sTileWrite state)
  */
class TileWriteIO extends Bundle {
  val idx  = Output(UInt(4.W))
  val data = Output(new ColorZ(16))
  val en   = Output(Bool())
}
