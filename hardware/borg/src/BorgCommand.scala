// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class BorgCommand(coordWidth: Int = 9) extends Bundle {
  // The 4×4 tile origin in pixel coordinates (4-aligned).
  // frag_pc and uniform_page are now read from their dedicated registers.
  val tileOrigin = new Coord(coordWidth)
}
