// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Verilog emission for the full-SoC-with-HDMI-scanout verilator sim.
  *
  * Run via: CLOCK_MHZ=25 mill asic.tt.runMain asic.tt.BorgHdmiSimMain
  *
  * Emits into out/hardware/borg/verilog_hdmi_sim/ (separate from BorgSimMain so
  * the two never clobber each other).
  */
object BorgHdmiSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "25").toInt
  val fbW = sys.env.getOrElse("FB_W", "128").toInt
  val fbH = sys.env.getOrElse("FB_H", "128").toInt
  println(s"Generating HDMI-sim Verilog: CLOCK_MHZ=$clockMhz fb=${fbW}x${fbH}")

  val targetDir = "out/hardware/borg/verilog_hdmi_sim"
  new java.io.File(targetDir).mkdirs()
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgHdmiSimTop(clockMhz, fbW, fbH), targetDir, allFiles)
  // Keep the standalone Peripherals emit in the SAME 4-lane Simt config as the
  // top (firtool splits per module; a Default-config emit would clobber the
  // 4-lane BorgCore.sv).  See BorgSimMain for the full rationale.
  Emit.emitAndCollect(new Peripherals(clockMhz, BorgConfig.Simt), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
