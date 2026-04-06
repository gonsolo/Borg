// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class ColorZ(val w: Int = 16) extends Bundle {
  val r = UInt(w.W)
  val g = UInt(w.W)
  val b = UInt(w.W)
  val z = UInt(w.W)
}
