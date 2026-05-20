// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import soc.{Emit, Peripherals}

/** Verilog emission entry point for the verilator simulation target.
  *
  * Run via: CLOCK_MHZ=4 mill asic.tt.runMain asic.tt.BorgSimMain
  *
  * Emits split Verilog into out/hardware/borg/verilog_sim/ and writes
  * sim_files.txt for the verilator build.  Separate from the ASIC flow
  * (TTMain → out/hardware/borg/verilog/) so the two never clobber each other.
  */
object BorgSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating sim Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog_sim"
  new java.io.File(targetDir).mkdirs()
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgSimTop(clockMhz), targetDir, allFiles)
  Emit.emitAndCollect(new Peripherals(clockMhz), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
