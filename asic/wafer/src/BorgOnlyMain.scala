// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.wafer

import borg.BorgConfig
import borg.link.LinkParams
import soc.Emit

/** Emits the wafer.space Borg-only top into its own directory, apart from
  * QspiSocMain's, which Emit.cleanTargetDir wipes. */
object BorgOnlyMain extends App {
  val cfg = BorgConfig.Wafer
  // A runtime w=8 fallback behind the link_narrow strap: post-silicon recovery.
  val p   = LinkParams(narrowCapable = true)

  val targetDir = "out/hardware/borg/verilog_wafer"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgOnlyTop(cfg, p), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/wafer_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
