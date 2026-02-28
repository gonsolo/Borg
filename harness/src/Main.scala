// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package harness

import circt.stage.ChiselStage

object Main extends App {

  new java.io.File("out/harness/verilog").mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen = new TTWrapper(),
    args = Array("--target-dir", "out/harness/verilog"),
    firtoolOpts = Array("--split-verilog", "--lowering-options=disallowLocalVariables", "--disable-all-randomization", "--strip-debug-info")
  )
}
