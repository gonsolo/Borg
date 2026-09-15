// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import borg.BorgConfig
import borg.link.LinkParams
import soc.Emit

/** Verilog emission for the Borg-only bridge on the wafer.space **1x1** slot.
  *
  * Run via: mill asic.tt.runMain asic.tt.BorgOnly1x1Main
  *
  * Same design and config as [[BorgOnlyMain]]; only the pad map differs
  * (Slot1x1: 40 bidir + 12 input-only, vs 1x0.5's 46 + 4). See BorgOnlyTop's
  * class doc for why that is a re-map rather than a truncation.
  *
  * Emits to a separate directory from BorgOnlyMain so the two slots' Verilog
  * can coexist -- both produce a module named `BorgOnlyTop` with different port
  * widths, so mixing them in one directory would be silently wrong. Build with
  * `SLOT=1x1` against this output.
  */
object BorgOnly1x1Main extends App {
  // Extended-ISA knobs, overridable from the environment so the nightly area
  // probe can A/B them without dirtying the working tree -- same pattern as
  // CLOCK_MHZ. Defaults match BorgConfig, so an unset environment emits
  // exactly what it always did.
  //
  //   BORG_WAFER_MEMORY_OPS=0   drop LOAD/STORE and the core's DRAM port
  //   BORG_WAFER_CONTROL_FLOW=0 drop BRZ/BRNZ and the execution mask
  private def envFlag(name: String, default: Boolean): Boolean =
    sys.env.get(name).map(v => v != "0" && v.toLowerCase != "false").getOrElse(default)

  val cfg = BorgConfig.Wafer.copy(
    hasMemoryOps   = envFlag("BORG_WAFER_MEMORY_OPS", BorgConfig.Wafer.hasMemoryOps),
    hasControlFlow = envFlag("BORG_WAFER_CONTROL_FLOW", BorgConfig.Wafer.hasControlFlow))
  // narrowCapable: as on 1x0.5, the runtime w=16 -> w=8 mux is the only
  // post-silicon recovery mode, and pins cannot be re-synthesized after
  // tapeout. Note the 1x1 map does NOT need narrow mode to fit -- it carries
  // the full w=16 across 38 of 40 bidir pads -- but keeping the strap costs
  // nothing and preserves the recovery path.
  val p = LinkParams(narrowCapable = true)

  val targetDir = "out/hardware/borg/verilog_wafer_1x1"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgOnlyTop(cfg, p, Slot1x1), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/wafer_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
