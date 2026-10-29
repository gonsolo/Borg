// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Scissor rectangle (SCISSOR_X / SCISSOR_Y), half-open like `VkRect2D`.
  * 13 bits so that x1 = 4096 covers a 4096-wide framebuffer. */
class ScissorConfig(val w: Int = 13) extends Bundle {
  val enable = Bool()
  val x0 = UInt(w.W)
  val x1 = UInt(w.W)
  val y0 = UInt(w.W)
  val y1 = UInt(w.W)
}

object ScissorConfig {
  /** Disabled passes everything; an empty rectangle passes nothing. */
  def passes(cfg: ScissorConfig, x: UInt, y: UInt): Bool =
    !cfg.enable || (x >= cfg.x0 && x < cfg.x1 && y >= cfg.y0 && y < cfg.y1)
}
