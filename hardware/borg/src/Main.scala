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

  MmioGenerator.emitHeader("fpga/firmware/borg_mmio.h")
  MmioGenerator.emitPython("fpga/host/borg_mmio.py")
  MmioGenerator.emitPython("test/soc/borg_mmio.py")

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/asic_files.txt"))
  allAsicFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
