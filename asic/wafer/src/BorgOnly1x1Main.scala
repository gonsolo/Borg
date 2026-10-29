// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.wafer

import borg.BorgConfig
import borg.link.LinkParams
import soc.Emit

/** Verilog for the Borg-only bridge on the wafer.space 1x1 slot: [[BorgOnlyMain]]'s design with
  * the 1x1 pad map, in its own directory since both slots emit a `BorgOnlyTop`. */
object BorgOnly1x1Main extends App {
  val cfg = BorgConfig.Wafer
  // The 1x1 map fits w=16, but the narrow strap is the only post-silicon recovery mode.
  val p = LinkParams(narrowCapable = true)

  val targetDir = "out/hardware/borg/verilog_wafer_1x1"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgOnlyTop(cfg, p, Slot1x1), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/wafer_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
