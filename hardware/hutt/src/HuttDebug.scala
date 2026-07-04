// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

/** Compile-time gate for Hutt CPU debug `printf`s, mirroring
  * `borg.BorgDebug` (see that file for the rationale — off by default so
  * the printf node is never elaborated into Verilog, avoiding a per-cycle
  * `$display` that would throttle simulation to a crawl).
  *
  * Off by default; set the env var `HUTT_TRACE` at Verilog-generation time
  * to re-enable (e.g. `HUTT_TRACE=1 make generate_verilog_sim`).
  */
object HuttDebug {
  val trace: Boolean = sys.env.contains("HUTT_TRACE")
}
