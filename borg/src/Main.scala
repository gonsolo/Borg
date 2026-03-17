// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package borg

import chisel3.RawModule
import circt.stage.ChiselStage

object Main extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "12").toInt
  println(s"Generating Verilog with CLOCK_MHZ = $clockMhz")

  val targetDir = "out/borg/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen = new tt_um_gonsolo_borg(clockMhz),
    args = Array("--target-dir", targetDir),
    firtoolOpts = Array("--split-verilog", "--lowering-options=disallowLocalVariables,noAlwaysComb", "--disable-all-randomization", "--strip-debug-info")
  )

  ChiselStage.emitSystemVerilogFile(
    gen = new tinyQV_peripherals(clockMhz),
    args = Array("--target-dir", targetDir),
    firtoolOpts = Array("--split-verilog", "--lowering-options=disallowLocalVariables", "--disable-all-randomization", "--strip-debug-info")
  )

  ChiselStage.emitSystemVerilogFile(
    gen = new tinyQV_top(clockMhz),
    args = Array("--target-dir", targetDir),
    firtoolOpts = Array("--split-verilog", "--lowering-options=disallowLocalVariables,noAlwaysComb", "--disable-all-randomization", "--strip-debug-info")
  )

  PicoIcePins.emitPCF(s"$targetDir/pico_ice.pcf", clockMhz)
  MmioMap.emitHeader("fpga/firmware/mmio.h")
}
