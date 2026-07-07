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

  /** Sparse timer-interrupt tracing: CLINT mtimecmp writes + M/S timer trap
    * edges only (a handful of lines over a multi-hundred-million-cycle Linux
    * boot), unlike `trace` which dumps every instruction. Set `HUTT_TIMER_TRACE`
    * at Verilog-generation time.
    */
  val timerTrace: Boolean = sys.env.contains("HUTT_TIMER_TRACE")
}
