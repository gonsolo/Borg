// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Texture fetch configuration (inputs to BorgRasterizer).
  * mortonIndex: 16-bit Morton-encoded texel address (from Fp16ToUint8 + MortonEncode)
  * baseAddr:    16-bit PSRAM base address of the texture (×8 for byte offset)
  * en:          texture fetch enable gate
  */
class TexConfigIO extends Bundle {
  val mortonIndex = Input(UInt(16.W))
  val baseAddr    = Input(UInt(16.W))
  val en          = Input(Bool())
}
