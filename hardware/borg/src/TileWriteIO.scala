// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Tile buffer write port — master's perspective (rasterizer / MMIO).
  * idx:  4-bit tile buffer slot (x[1:0] | y[1:0] << 2)
  * data: packed ColorZ(16) — r/g/b/z each 16-bit FP
  * en:   write-enable
  */
class TileWriteIO extends Bundle {
  val idx  = Output(UInt(4.W))
  val data = Output(new ColorZ(16))
  val en   = Output(Bool())
}

/** Tile buffer read port — master's perspective (flusher / MMIO).
  * idx:  4-bit tile buffer slot to read
  * en:   pulse to trigger BRAM read
  * data: RGBZ result, available 2 cycles after en
  *
  * Symmetric with [[TileWriteIO]].
  */
class TileReadIO(val dataBits: Int = 16) extends Bundle {
  val idx  = Output(UInt(4.W))
  val en   = Output(Bool())
  val data = Input(new ColorZ(dataBits))
}

/** Tile buffer clear port — master's perspective.
  * en:   pulse to start sequential clear (Z=FP16_MAX, RGB=0)
  * busy: high while clear is in progress
  */
class TileClearIO extends Bundle {
  val en   = Output(Bool())
  val busy = Input(Bool())
}
