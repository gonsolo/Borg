// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import circt.stage.ChiselStage

/** Emits BorgTileBuffer on its own, in both MSAA storage strategies, so the
  * area of `msaaMultiPass` can be measured against the resident-samples
  * baseline without building a whole SoC.
  *
  *   mill hardware.borg.runMain borg.EmitTileBuffer <outDir>
  */
object EmitTileBuffer extends App {
  val dir = if (args.nonEmpty) args(0) else "out/tilebuffer"

  // Wafer's tile configuration: 4x MSAA, UNORM8 colour, stencil + blend.
  def baseline = new BorgTileBuffer(dataBits = 16, samples = 4, colorBits = 8,
                                    hasStencil = true, hasAlpha = true,
                                    multiPass = false)
  def multipass = new BorgTileBuffer(dataBits = 16, samples = 4, colorBits = 8,
                                     hasStencil = true, hasAlpha = true,
                                     multiPass = true)

  ChiselStage.emitSystemVerilogFile(baseline,  args = Array("--target-dir", s"$dir/baseline"))
  ChiselStage.emitSystemVerilogFile(multipass, args = Array("--target-dir", s"$dir/multipass"))
  println(s"emitted to $dir/{baseline,multipass}")
}
