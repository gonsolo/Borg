// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class Coord(val w: Int = 10) extends Bundle {
  val x = UInt(w.W)
  val y = UInt(w.W)
}
