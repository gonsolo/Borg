// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import borg.BorgConfig
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
  Emit.cleanTargetDir(targetDir)
  val allAsicFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new tt_um_gonsolo_borg(clockMhz), targetDir, allAsicFiles)
  // Emit Peripherals with the same ASIC config so BorgBinner uses maxBinTiles=16,
  // not the Default 1024 (which would clobber BorgBinner.sv with the wrong size).
  Emit.emitAndCollect(new Peripherals(clockMhz, BorgConfig.Asic), targetDir, allAsicFiles)

  val firrtlTargetDir = "out/hardware/borg/firrtl"
  Emit.emitFIRRTL(new tt_um_gonsolo_borg(clockMhz), firrtlTargetDir)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/asic_files.txt"))
  allAsicFiles.toList.sorted.foreach(fw.println)
  fw.close()
}

