// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Verilog for the full SoC with HDMI scanout under Verilator, apart from BorgSimMain's. */
object BorgHdmiSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val fbW = sys.env.getOrElse("FB_W", "128").toInt
  val fbH = sys.env.getOrElse("FB_H", "128").toInt
  println(s"Generating HDMI-sim Verilog: CLOCK_MHZ=$clockMhz fb=${fbW}x${fbH}")

  val targetDir = "out/hardware/borg/verilog_hdmi_sim"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgHdmiSimTop(clockMhz, fbW, fbH), targetDir, allFiles)
  // The standalone Peripherals emit uses the top's config: firtool splits per module.
  Emit.emitAndCollect(new Peripherals(clockMhz, BorgConfig.Default), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
