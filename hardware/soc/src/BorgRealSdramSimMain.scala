// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Verilator sim Verilog for the ULX3S real-SDRAM path: BorgSimTop(realSdram = true), with
  * SdramBackend -> SdramController -> SdramChipModel (`make cts-uart-realsdram`).
  * Run: CLOCK_MHZ=4 mill hardware.soc.runMain soc.BorgRealSdramSimMain
  */
object BorgRealSdramSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating real-SDRAM sim Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog_sim_realsdram"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgSimTop(clockMhz, realSdram = true), targetDir, allFiles)
  // Must match BorgSimTop's BORG_CFG, as in BorgSimMain.
  Emit.emitAndCollect(new Peripherals(clockMhz, BorgConfig.Default), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
