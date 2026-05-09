// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import soc.{Emit, Peripherals}

/** Verilog emission entry point for the Tiny Tapeout ASIC target.
  *
  * Run via: CLOCK_MHZ=4 mill asic.tt.runMain asic.tt.TTMain
  *
  * Emits split Verilog into out/hardware/borg/verilog/ and writes
  * asic_files.txt for the lint and GDS flows.
  */
object TTMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog"
  new java.io.File(targetDir).mkdirs()
  val allAsicFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new tt_um_gonsolo_borg(clockMhz), targetDir, allAsicFiles)
  Emit.emitAndCollect(new Peripherals(clockMhz), targetDir, allAsicFiles)

  val firrtlTargetDir = "out/hardware/borg/firrtl"
  Emit.emitFIRRTL(new tt_um_gonsolo_borg(clockMhz), firrtlTargetDir)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/asic_files.txt"))
  allAsicFiles.toList.sorted.foreach(fw.println)
  fw.close()
}

