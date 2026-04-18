// Copyright Andreas Wendleder 2025-2026
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc


object Main extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog"
  new java.io.File(targetDir).mkdirs()
  val allAsicFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new tt_um_gonsolo_borg(clockMhz), targetDir, allAsicFiles)
  Emit.emitAndCollect(new Peripherals(clockMhz), targetDir, allAsicFiles)

  val firrtlTargetDir = "out/hardware/borg/firrtl"
  Emit.emitFIRRTL(new tt_um_gonsolo_borg(clockMhz), firrtlTargetDir)

  val firrtlSimDir = "out/hardware/borg/firrtl_sim"
  Emit.emitFIRRTL(new tt_um_gonsolo_borg_sim(clockMhz), firrtlSimDir)

  // C headers and Chisel register blocks are now generated from SystemRDL
  // files in hardware/rdl/ via `make rdl`.  Python constants (fpga/host/borg_mmio.py,
  // test/soc/borg_mmio.py) are hand-maintained.

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/asic_files.txt"))
  allAsicFiles.toList.sorted.foreach(fw.println)
  fw.close()

  // Generate Simulation-only fast memory variant
  val simTargetDir = "out/hardware/borg/verilog_sim"
  new java.io.File(simTargetDir).mkdirs()
  val simAsicFiles = collection.mutable.Set[String]()
  Emit.emitAndCollect(new tt_um_gonsolo_borg_sim(clockMhz), simTargetDir, simAsicFiles)
  Emit.emitAndCollect(new Peripherals(clockMhz), simTargetDir, simAsicFiles)
  val fwSim = new java.io.PrintWriter(new java.io.File(s"$simTargetDir/asic_files.txt"))
  simAsicFiles.toList.sorted.foreach(fwSim.println)
  fwSim.close()
}
