// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Verilator sim of the RV64 SoC with the Cube Borg (the linux_borg FPGA top, without
  * the board pins).
  *
  * CLOCK_MHZ=20 mill hardware.soc.runMain soc.MinimalSocBorgSimMain
  */
object MinimalSocBorgSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "20").toInt
  val targetDir = "out/hardware/soc/verilog_borg_sim"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()
  Emit.emitAndCollect(new MinimalSocSimTop(clockMhz, Some(BorgConfig.Cube)), targetDir, allFiles)
  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
