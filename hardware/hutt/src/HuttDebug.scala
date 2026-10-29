// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

/** Elaboration-time gates for Hutt's debug printfs, like `borg.BorgDebug`: set `HUTT_TRACE` when
  * generating Verilog. */
object HuttDebug {
  val trace: Boolean = sys.env.contains("HUTT_TRACE")

  // Only mtimecmp writes and timer trap edges, sparse enough for a Linux boot.
  val timerTrace: Boolean = sys.env.contains("HUTT_TIMER_TRACE")
}
