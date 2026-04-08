// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class BorgCommand extends Bundle {
  // Selects which 32-entry page (bank) of the uniform buffer the 
  // GPU should read from during rasterization (0 or 1).
  val uniformPage = UInt(1.W)
  
  // The entry point in instruction memory (IMEM) for the fragment shader.
  val fragPC      = UInt(6.W)
  
  // The screen-space bounding box of the triangle, limiting the rasterizer's search area.
  val bbox        = new Bbox()
}
