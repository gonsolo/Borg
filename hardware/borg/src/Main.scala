// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package borg

object Main extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", MmioMap.CLOCK_MHZ.toString).toInt
  println(s"Generating Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog"
  new java.io.File(targetDir).mkdirs()
  val allAsicFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new tt_um_gonsolo_borg(clockMhz), targetDir, allAsicFiles)
  Emit.emitAndCollect(new tinyQV_peripherals(clockMhz), targetDir, allAsicFiles)

  val firrtlTargetDir = "out/hardware/borg/firrtl"
  Emit.emitFIRRTL(new tt_um_gonsolo_borg(clockMhz), firrtlTargetDir)

  // C headers and Chisel register blocks are now generated from SystemRDL
  // files in hardware/rdl/ via `make rdl`.  Python constants (fpga/host/borg_mmio.py,
  // test/soc/borg_mmio.py) are hand-maintained.

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/asic_files.txt"))
  allAsicFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
