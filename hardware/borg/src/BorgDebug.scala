// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Compile-time gate for Chisel debug `printf`s.
  *
  * The Borg pipeline has many `printf` traces that are invaluable when
  * debugging but emit one `$display`/`$fwrite` per cycle in the verilator
  * simulator — throttling it to a crawl (a full triangle render went from
  * ~2M cycles in seconds to minutes).  They are guarded as
  * `if (BorgDebug.trace) printf(...)`, so when `trace` is false the printf
  * node is never elaborated (no `$display` in the emitted Verilog).
  *
  * Off by default; set the env var `BORG_TRACE` at Verilog-generation time to
  * re-enable (e.g. `BORG_TRACE=1 make generate_verilog_sim`).
  */
object BorgDebug {
  val trace: Boolean = sys.env.contains("BORG_TRACE")
}
