// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package borg

import circt.stage.ChiselStage

// FPGA-specific Verilog generation: tinyQV_top wrapper and PCF pinout.
// Run via: mill fpga.tinyqv.runMain borg.FpgaMain
object FpgaMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", MmioMap.CLOCK_MHZ.toString).toInt
  val targetDir = "out/fpga/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen = new tinyQV_top(clockMhz),
    args = Array("--target-dir", targetDir),
    firtoolOpts = Array("--split-verilog", "--lowering-options=disallowLocalVariables,disallowPackedArrays,noAlwaysComb", "--disable-all-randomization", "--strip-debug-info")
  )

  PicoIcePins.emitPCF(s"$targetDir/pico_ice.pcf")
}
