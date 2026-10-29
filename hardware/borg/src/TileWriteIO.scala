// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Tile buffer write port, master side. One `data` is broadcast to every sample `coverage` sets:
  * shading is per pixel (no sampleRateShading). */
class TileWriteIO(val samples: Int = 1, val zBits: Int = 16) extends Bundle {
  val idx      = Output(UInt(4.W))    // x[1:0] | y[1:0] << 2
  val data     = Output(new ColorZ(16, zBits))
  val en       = Output(Bool())
  val coverage = Output(UInt(samples.W))
}

/** Tile buffer read port, master side: per-sample data 2 cycles after `en`. */
class TileReadIO(val dataBits: Int = 16, val samples: Int = 1, val zBits: Int = 16) extends Bundle {
  val idx  = Output(UInt(4.W))
  val en   = Output(Bool())
  val data = Input(Vec(samples, new ColorZ(dataBits, zBits)))
}

class TileClearIO(val zBits: Int = 16) extends Bundle {
  val en    = Output(Bool())    // pulse: clear every entry to `color`
  val busy  = Input(Bool())
  val color = Output(new ColorZ(16, zBits))
}
