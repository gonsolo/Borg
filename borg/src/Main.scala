// Copyright Andreas Wendleder 2025
// CERN-OHL-S-2.0

package borg

import chisel3.RawModule
import circt.stage.ChiselStage

object Main extends App {

  val targetDir = "src"
  val borgDir = "src/user_peripherals/borg"

  ChiselStage.emitSystemVerilogFile(
    gen = new tt_um_tt_tinyQV(),
    args = Array("--target-dir", "src"),
    firtoolOpts = Array("--split-verilog", "--lowering-options=disallowLocalVariables", "--disable-all-randomization", "--strip-debug-info")
  )

  import java.nio.file.{Files, Paths, StandardCopyOption}
  import java.io.File

  new File(borgDir).mkdirs()

  for (f <- new File(targetDir).listFiles() if f.getName.endsWith(".sv") || f.getName.endsWith(".v") || f.getName.endsWith(".h")) {
    val name = f.getName
    if (name == "tt_um_tt_tinyQV.sv" || name == "tt_um_tt_tinyQV.v") {
      Files.move(f.toPath, Paths.get(targetDir, "project.v"), StandardCopyOption.REPLACE_EXISTING)
    } else if (name == "tinyQV_peripherals.sv" || name == "tinyQV_peripherals.v") {
      Files.move(f.toPath, Paths.get(targetDir, "peripherals.v"), StandardCopyOption.REPLACE_EXISTING)
    } else if (name == "project.v" || name == "peripherals.v") {
      // already moved
    } else if (name == "tinyQV.sv" || name == "uart_tx.sv" || name == "tqvp_uart_wrapper.sv" || name == "peri_uart.sv" || name == "uart_rx.sv" || name == "tt_um_tt_tinyQV_firrtl_macros.vh") {
      f.delete()
    } else {
      Files.move(f.toPath, Paths.get(borgDir, name), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
