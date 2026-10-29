// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Emits the Verilator sim top and sim_files.txt into its own directory, apart
  * from QspiSocMain's. */
object BorgSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating sim Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog_sim"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgSimTop(clockMhz), targetDir, allFiles)
  // Must match BorgSimTop's BORG_CFG: firtool splits per module, so another config here would
  // overwrite its BorgCore.sv.
  Emit.emitAndCollect(new Peripherals(clockMhz, BorgConfig.Default), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()

  // The arcilator top: flat MemBackendIO, no SdramBackendSim.
  val firrtlSimTargetDir = "out/hardware/borg/firrtl_sim"
  Emit.emitFIRRTL(new BorgArcSimTop(clockMhz), firrtlSimTargetDir)
}
