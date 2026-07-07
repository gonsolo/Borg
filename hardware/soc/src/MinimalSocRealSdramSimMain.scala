// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

/** Verilog emission entry point for the MinimalSoC + real SdramBackend/
  * SdramController/SdramChipModel verilator simulation target.
  *
  * Run via: CLOCK_MHZ=25 mill hardware.soc.runMain soc.MinimalSocRealSdramSimMain
  *
  * Emits split Verilog into out/hardware/soc/verilog_realsdram_sim/ and
  * writes sim_files.txt for the verilator build.
  */
object MinimalSocRealSdramSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  println(s"Generating MinimalSoC+real-SDRAM sim Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/soc/verilog_realsdram_sim"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new MinimalSocRealSdramSimTop(clockMhz), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
