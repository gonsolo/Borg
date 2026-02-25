// Copyright Andreas Wendleder 2025
// CERN-OHL-S-2.0

package borg

import chisel3.RawModule
import circt.stage.ChiselStage

object Main extends App {

  def emitModule(module: => RawModule) = {
    ChiselStage.emitSystemVerilogFile(
      gen = module,
      args = Array("--target-dir", "src"),
      firtoolOpts = Array("--lowering-options=disallowLocalVariables")
    )
  }

  emitModule(new Borg())
}
