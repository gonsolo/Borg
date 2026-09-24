// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Colour plus depth. `zw` is the depth width: colour stays FP16 at the
  * tile ports, but depth follows the datapath (BorgConfig.tileDepthBits) --
  * FP32 on an FP32 build, because Vulkan's depth test compares against the
  * value in the attachment's own format and FP16 cannot hold D16's precision
  * near 1.0, nor D32_SFLOAT at all. Defaults to `w`: every FP16 user is
  * unchanged. */
class ColorZ(val w: Int = 16, val zw: Int = -1) extends Bundle {
  private val zWidth = if (zw < 0) w else zw
  val r = UInt(w.W)
  val g = UInt(w.W)
  val b = UInt(w.W)
  val z = UInt(zWidth.W)
}
