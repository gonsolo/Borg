// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Verilog emission for [[QspiSocTop]], the QSPI CPU SoC.
  *
  * Run via: CLOCK_MHZ=4 mill hardware.soc.runMain soc.QspiSocMain
  *
  * Emits split Verilog into out/hardware/borg/verilog/ and writes
  * soc_files.txt (paths relative to out/.., i.e. prefixed "../") for `make lint`
  * and the cocotb SoC tests.
  */
object QspiSocMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new QspiSocTop(clockMhz), targetDir, allFiles)
  // Emit Peripherals with the same config so BorgBinner uses the Wafer sizing,
  // not the Default (which would clobber BorgBinner.sv with the wrong size).
  Emit.emitAndCollect(new Peripherals(clockMhz, BorgConfig.Wafer), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/soc_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
