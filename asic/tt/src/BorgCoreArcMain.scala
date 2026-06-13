// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import borg.{BorgConfig, BorgCore}

/** Debug: emit FIRRTL for a standalone BorgCore with the custom FMA, for the
  * arcilator custom-FMA investigation (see docs/arcilator_custom_fma_bug.md).
  * The C++ harness simulation/arcilator/debug/core_harness.cpp drives ADD/MUL/
  * FMA/FNEG via MMIO and reads regReadData — all correct in arcilator, which is
  * the paradox (BorgCore is fine standalone; only the full SoC miscompiles).
  * Not a build target.
  *
  * Emit FIRRTL: `mill asic.tt.runMain asic.tt.BorgCoreArcMain`
  * (also prints the instruction encodings used by the harness)
  */
object BorgCoreArcMain extends App {
  println("ENC ADD(0,1,2)=0x"   + borg.Instructions.ADD(0, 1, 2).toString(16))
  println("ENC MUL(0,1,2)=0x"   + borg.Instructions.MUL(0, 1, 2).toString(16))
  println("ENC FMA(0,1,3,2)=0x" + borg.Instructions.FMA(0, 1, 3, 2).toString(16))
  println("ENC FNEG(0,2)=0x"    + borg.Instructions.FNEG(0, 2).toString(16))
  soc.Emit.emitFIRRTL(new BorgCore(BorgConfig.Default),
                      "out/hardware/borg/firrtl_corearc")
}
