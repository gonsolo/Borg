// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Tile buffer write port — master's perspective (rasterizer / MMIO).
  * idx:      4-bit tile buffer slot (x[1:0] | y[1:0] << 2)
  * data:     packed ColorZ(16) — r/g/b/z each 16-bit FP
  * en:       write-enable
  * coverage: per-sample write mask (MSAA).  Bit s enables sample s.
  *
  * MSAA note: there is exactly ONE `data` for all samples — a fragment is shaded
  * once per pixel and that single result is broadcast to every covered sample.
  * Only the *mask* is per-sample.  That is what keeps this port (and its ~30 call
  * sites) unchanged in shape, and it is standard MSAA semantics; per-sample
  * shading would be `sampleRateShading`, which Vulkan lets us report unsupported.
  * At samples==1 `coverage` is a single bit that callers tie to `en`'s meaning,
  * so the single-sample path stays bit-identical.
  */
class TileWriteIO(val samples: Int = 1) extends Bundle {
  val idx      = Output(UInt(4.W))
  val data     = Output(new ColorZ(16))
  val en       = Output(Bool())
  val coverage = Output(UInt(samples.W))
}

/** Tile buffer read port — master's perspective (flusher / MMIO).
  * idx:  4-bit tile buffer slot to read
  * en:   pulse to trigger BRAM read
  * data: per-sample RGBZ results, available 2 cycles after en
  *
  * Unlike the write port this IS per-sample: the depth test compares against each
  * sample's own Z (BorgShaderDispatcher), and the flusher averages all samples to
  * resolve.  At samples==1 this is a Vec of one — index `.data(0)`.
  *
  * Symmetric with [[TileWriteIO]].
  */
class TileReadIO(val dataBits: Int = 16, val samples: Int = 1) extends Bundle {
  val idx  = Output(UInt(4.W))
  val en   = Output(Bool())
  val data = Input(Vec(samples, new ColorZ(dataBits)))
}

/** Tile buffer clear port — master's perspective.
  * en:   pulse to start sequential clear (Z=FP16_MAX, RGB=0)
  * busy: high while clear is in progress
  */
class TileClearIO extends Bundle {
  val en    = Output(Bool())
  val busy  = Input(Bool())
  val color = Output(new ColorZ(16))  // clear color (Step 32.5: sequencer-driven)
}
