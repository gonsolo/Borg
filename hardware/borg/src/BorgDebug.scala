// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Elaboration-time gate for debug printfs, which slow Verilator to a crawl.
  * Set `BORG_TRACE` when generating Verilog to enable them. */
object BorgDebug {
  val trace: Boolean = sys.env.contains("BORG_TRACE")
}
