// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Scissor rectangle state (SCISSOR_X / SCISSOR_Y).
  *
  * `x0`/`y0` are inclusive and `x1`/`y1` exclusive, so a `VkRect2D` maps
  * straight across: `x1 = offset.x + extent.width`.
  *
  * The scissor test is core Vulkan -- every graphics pipeline carries a
  * scissor rectangle, there is no feature bit and no way to opt out of the
  * state -- and Borg had none: a fragment inside the triangle was always
  * written.
  *
  * 13 bits per bound rather than 12: the bounds are half-open, so covering
  * the full 4096-wide framebuffer Vulkan requires needs `x1 = 4096`, one past
  * what 12 bits can hold.
  */
class ScissorConfig(val w: Int = 13) extends Bundle {
  val enable = Bool()
  val x0 = UInt(w.W)
  val x1 = UInt(w.W)
  val y0 = UInt(w.W)
  val y1 = UInt(w.W)
}

object ScissorConfig {
  /** Does pixel (x, y) survive the scissor test?
    *
    * Disabled means "everything passes", not "nothing passes" -- an empty
    * rectangle and a disabled test are different states, and conflating them
    * would blank the frame for firmware that never writes the register.
    */
  def passes(cfg: ScissorConfig, x: UInt, y: UInt): Bool =
    !cfg.enable || (x >= cfg.x0 && x < cfg.x1 && y >= cfg.y0 && y < cfg.y1)
}
