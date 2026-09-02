// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import borg.BorgConfig
import borg.link.LinkParams
import soc.Emit

/** Verilog emission entry point for the Borg-only wafer.space ASIC target.
  *
  * Run via: mill asic.tt.runMain asic.tt.BorgOnlyMain
  *
  * Emits split Verilog into out/hardware/borg/verilog_wafer/ -- deliberately
  * *not* out/hardware/borg/verilog/, which TTMain owns and Emit.cleanTargetDir
  * wipes on every TT emission.
  */
object BorgOnlyMain extends App {
  // Phase 0's probes measured BorgConfig.Asic's sizing (fragLanes=4,
  // samples=4) at 71.55% utilization / clean 25MHz timing on the 1x0.5 slot
  // -- see the plan doc's "Conclusion: ship BorgConfig.Asic's current sizing
  // as BorgConfig.Wafer unchanged". BorgConfig.Wafer trims only the
  // interface (debugPorts=false), not the sizing.
  val cfg = BorgConfig.Wafer
  val p   = LinkParams()

  val targetDir = "out/hardware/borg/verilog_wafer"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgOnlyTop(cfg, p), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/wafer_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
