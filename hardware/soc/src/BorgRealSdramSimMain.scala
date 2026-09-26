// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Verilog emission entry point for the verilator simulation on the ULX3S's
  * real SDRAM path: BorgSimTop(realSdram = true), whose memory is
  * SdramBackend -> SdramController -> SdramChipModel instead of the
  * fixed-latency SdramBackendSim. Same harness, firmware and captures as the
  * regular sim (simulation/verilator `make cts-uart-realsdram`).
  *
  * Run via: CLOCK_MHZ=4 mill hardware.soc.runMain soc.BorgRealSdramSimMain
  *
  * Emits split Verilog into out/hardware/borg/verilog_sim_realsdram/.
  */
object BorgRealSdramSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating real-SDRAM sim Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog_sim_realsdram"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgSimTop(clockMhz, realSdram = true), targetDir, allFiles)
  // Must match BorgSimTop's BORG_CFG, as in BorgSimMain.
  Emit.emitAndCollect(new Peripherals(clockMhz, BorgConfig.simCfg), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
