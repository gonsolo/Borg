// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Verilator sim of the RV64 SoC with a tiny Borg (the linux_borg FPGA top, without
  * the board pins). BORG_MINIMAL_CFG = tiny (default) | cube.
  *
  * CLOCK_MHZ=20 mill hardware.soc.runMain soc.MinimalSocBorgSimMain
  */
object MinimalSocBorgSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "20").toInt
  val cfg = sys.env.getOrElse("BORG_MINIMAL_CFG", "tiny") match {
    case "cube" => BorgConfig.Simt.copy(samples = 1, hasBlend = false, hasStencil = false,
                                        hasCompute = false, hasDepthFlush = false)
    case _      => BorgConfig.Tiny
  }
  val targetDir = "out/hardware/soc/verilog_borg_sim"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()
  Emit.emitAndCollect(new MinimalSocSimTop(clockMhz, Some(cfg)), targetDir, allFiles)
  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/sim_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
