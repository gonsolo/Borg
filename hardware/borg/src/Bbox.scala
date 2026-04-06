// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class Bbox(val w: Int = 6) extends Bundle {
  val min = new Coord(w)
  val max = new Coord(w)
}
