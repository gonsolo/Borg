// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class RegIndices extends Bundle {
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rs3 = UInt(5.W)
  val rd  = UInt(5.W)
}
