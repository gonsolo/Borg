// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

/** Verilator sim Verilog for the MinimalSoC (no Borg, RV64).
  * Run: CLOCK_MHZ=25 mill hardware.soc.runMain soc.MinimalSocSimMain
  */
object MinimalSocSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  println(s"Generating MinimalSoC sim Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/soc/verilog_sim"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new MinimalSocSimTop(clockMhz), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
