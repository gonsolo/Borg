// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package harness

import chisel3._
import chisel3.util._

class SynchronizerIO(width: Int) extends Bundle {
  val data_in = Input(UInt(width.W))
  val data_out = Output(UInt(width.W))
}

class Synchronizer(stages: Int = 2, width: Int = 1) extends Module {
  val io = IO(new SynchronizerIO(width))

  // Create a shift register of the requested depth
  val syncRegs = RegInit(VecInit(Seq.fill(stages)(0.U(width.W))))

  syncRegs(0) := io.data_in
  for (i <- 1 until stages) {
    syncRegs(i) := syncRegs(i - 1)
  }

  io.data_out := syncRegs(stages - 1)
}
