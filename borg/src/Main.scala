// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package borg

import chisel3.RawModule
import circt.stage.ChiselStage

object Main extends App {

  new java.io.File("out/borg/verilog").mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen = new tt_um_tt_tinyQV(),
    args = Array("--target-dir", "out/borg/verilog"),
    firtoolOpts = Array("--split-verilog", "--lowering-options=disallowLocalVariables", "--disable-all-randomization", "--strip-debug-info")
  )
}
