// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package soc

import circt.stage.ChiselStage

// FPGA-specific Verilog generation: HuttTop wrapper and PCF pinout.
// Run via: mill fpga.picoice.soc.runMain soc.FpgaMain
object FpgaMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  val targetDir = "out/fpga/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen = new HuttTop(clockMhz),
    args = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  PicoIcePins.emitPCF(s"$targetDir/pico_ice.pcf")
}
