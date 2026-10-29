// Copyright Andreas Wendleder 2026
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.RawModule
import circt.stage.ChiselStage

/** Verilog emission shared by every Main object, so all use the same firtool options. */
object Emit {

  /** Empty targetDir: --split-verilog never prunes files an earlier run left, such as stale
    * verification/ printfs that verilator would glob in. */
  def cleanTargetDir(dir: String): Unit = {
    def deleteRecursively(f: java.io.File): Unit = {
      if (f.isDirectory) f.listFiles().foreach(deleteRecursively)
      f.delete()
    }
    deleteRecursively(new java.io.File(dir))
    new java.io.File(dir).mkdirs()
  }

  val firtoolOpts = Array(
    "-O=release",
    "--split-verilog",
    "--lowering-options=" +
      "disallowLocalVariables," +
      "disallowPackedArrays," +
      "noAlwaysComb",
    "--disable-all-randomization"
  )

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
