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
  * *not* out/hardware/borg/verilog/, which TTMain owns and Emit.cleanTargetDir
  * wipes on every TT emission.
  */
object BorgOnlyMain extends App {
  // BorgConfig.Wafer is Default's feature set (FP32, 4x MSAA, depth flush)
  // at the slot's sizing -- see its own comment for every sizing decision.
  // Extended-ISA knobs, overridable from the environment so the area
  // probe can A/B them without dirtying the working tree -- same pattern as
  // CLOCK_MHZ. Defaults match BorgConfig, so an unset environment emits
  // exactly what it always did.
  //
  //   BORG_WAFER_MEMORY_OPS=0   drop LOAD/STORE and the core's DRAM port
  //   BORG_WAFER_CONTROL_FLOW=0 drop BRZ/BRNZ and the execution mask
  private def envFlag(name: String, default: Boolean): Boolean =
    sys.env.get(name).map(v => v != "0" && v.toLowerCase != "false").getOrElse(default)

  val cfg = BorgConfig.Wafer.copy(
    hasMemoryOps   = envFlag("BORG_WAFER_MEMORY_OPS", BorgConfig.Wafer.hasMemoryOps),
    hasControlFlow = envFlag("BORG_WAFER_CONTROL_FLOW", BorgConfig.Wafer.hasControlFlow))
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
