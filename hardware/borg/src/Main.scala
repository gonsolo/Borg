// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package borg

import chisel3.RawModule
import circt.stage.ChiselStage

object Main extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", MmioMap.CLOCK_MHZ.toString).toInt
  println(s"Generating Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/hardware/borg/verilog"
  new java.io.File(targetDir).mkdirs()
  var allAsicFiles = Set[String]()

  def emitAndCollect(gen: => chisel3.RawModule, firtoolOpts: Array[String]) = {
    ChiselStage.emitSystemVerilogFile(
      gen = gen,
      args = Array("--target-dir", targetDir),
      firtoolOpts = firtoolOpts
    )
    val filelistPath = s"$targetDir/filelist.f"
    if (new java.io.File(filelistPath).exists()) {
      val lines = scala.io.Source.fromFile(filelistPath).getLines().toList
      allAsicFiles ++= lines.map(f => s"../$targetDir/$f")
    }
  }

  emitAndCollect(
    new tt_um_gonsolo_borg(clockMhz),
    Array("--split-verilog", "--lowering-options=disallowLocalVariables,disallowPackedArrays,noAlwaysComb", "--disable-all-randomization", "--strip-debug-info")
  )

  emitAndCollect(
    new tinyQV_peripherals(clockMhz),
    Array("--split-verilog", "--lowering-options=disallowLocalVariables,disallowPackedArrays", "--disable-all-randomization", "--strip-debug-info")
  )

  MmioMap.emitHeader("fpga/firmware/borg_mmio.h")
  MmioMap.emitPython("fpga/host/borg_mmio.py")
  MmioMap.emitPython("test/soc/borg_mmio.py")

  val asicFilesPath = s"$targetDir/asic_files.txt"
  val fw = new java.io.PrintWriter(new java.io.File(asicFilesPath))
  allAsicFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
