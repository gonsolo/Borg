// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Colour plus depth of width `zw` (default `w`). Depth is FP32 at the tile ports: the depth test
  * compares in the attachment's format, and FP16 holds neither D16 near 1.0 nor D32_SFLOAT. */
class ColorZ(val w: Int = 16, val zw: Int = -1) extends Bundle {
  private val zWidth = if (zw < 0) w else zw
  val r = UInt(w.W)
  val g = UInt(w.W)
  val b = UInt(w.W)
  val z = UInt(zWidth.W)
}
