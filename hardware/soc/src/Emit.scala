// Copyright Andreas Wendleder 2026
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.RawModule
import circt.stage.ChiselStage

/** Shared Verilog emission utilities.
  *
  * Central definition of firtool options, used by all Main objects
  * (hardware/borg, hardware/hutt, fpga/ulx3s, asic/tt) to ensure consistent
  * Verilog output.
  */
object Emit {
  val firtoolOpts = Array(
    "-O=release",
    "--split-verilog",
    "--lowering-options=" +
      "disallowLocalVariables," +
      "disallowPackedArrays," +
      "noAlwaysComb",
    "--disable-all-randomization"
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
      allFiles ++= lines
        .filterNot(_.startsWith("verification/"))  // probe/layer files: yosys cannot parse _layerCapture
        .map(f => s"../$targetDir/$f")
    }
  }
  def emitFIRRTL(
      gen: => RawModule,
      targetDir: String
  ): Unit = {
    new java.io.File(targetDir).mkdirs()
    ChiselStage.emitCHIRRTLFile(
      gen = gen,
      args = Array("--target-dir", targetDir)
    )
  }
}
