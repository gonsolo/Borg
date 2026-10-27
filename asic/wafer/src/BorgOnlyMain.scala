// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.wafer

import borg.BorgConfig
import borg.link.LinkParams
import soc.Emit

/** Verilog emission entry point for the Borg-only wafer.space ASIC target.
  *
  * Run via: mill asic.wafer.runMain asic.wafer.BorgOnlyMain
  *
  * Emits split Verilog into out/hardware/borg/verilog_wafer/ -- deliberately
  * *not* out/hardware/borg/verilog/, which QspiSocMain owns and Emit.cleanTargetDir
  * wipes on every TT emission.
  */
object BorgOnlyMain extends App {
  // BorgConfig.Wafer is Default's feature set (FP32, 4x MSAA, depth flush)
  // at the slot's sizing -- see its own comment for every sizing decision.
  val cfg = BorgConfig.Wafer
  // narrowCapable: the tapeout gets the real runtime w=16 -> w=8 mux behind the
  // link_narrow strap, not an elaboration-time width. Pins cannot be
  // re-synthesized after tapeout, so this is the only form in which the
  // post-silicon recovery mode actually exists.
  val p   = LinkParams(narrowCapable = true)

  val targetDir = "out/hardware/borg/verilog_wafer"
  Emit.cleanTargetDir(targetDir)
  val allFiles = collection.mutable.Set[String]()

  Emit.emitAndCollect(new BorgOnlyTop(cfg, p), targetDir, allFiles)

  val fw = new java.io.PrintWriter(new java.io.File(s"$targetDir/wafer_files.txt"))
  allFiles.toList.sorted.foreach(fw.println)
  fw.close()
}
