// Copyright Andreas Wendleder 2026
// CERN-OHL-S-2.0

package borg

import chisel3.RawModule
import circt.stage.ChiselStage

/** Shared Verilog emission utilities.
  *
  * Central definition of firtool options, used by all three Main objects
  * (hardware/borg, hardware/tinyqv, fpga/tinyqv) to ensure consistent
  * Verilog output.
  */
object Emit {
  val firtoolOpts = Array(
    "--split-verilog",
    "--lowering-options=" +
      "disallowLocalVariables," +
      "disallowPackedArrays," +
      "noAlwaysComb",
    "--disable-all-randomization",
    "--strip-debug-info"
  )

  /** Emit split-verilog and collect generated filenames. */
  def emitAndCollect(
      gen: => RawModule,
      targetDir: String,
      allFiles: collection.mutable.Set[String]
  ): Unit = {
    ChiselStage.emitSystemVerilogFile(
      gen = gen,
      args = Array("--target-dir", targetDir),
      firtoolOpts = firtoolOpts
    )
    val filelistPath = s"$targetDir/filelist.f"
    if (new java.io.File(filelistPath).exists()) {
      val lines = scala.io.Source.fromFile(filelistPath).getLines().toList
      allFiles ++= lines.map(f => s"../$targetDir/$f")
    }
  }
}
