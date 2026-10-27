// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.wafer

import borg.BorgConfig
import borg.link.LinkParams
import soc.Emit

/** Verilog emission for the Borg-only bridge on the wafer.space **1x1** slot.
  *
  * Run via: mill asic.wafer.runMain asic.wafer.BorgOnly1x1Main
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
  val cfg = BorgConfig.Wafer
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
