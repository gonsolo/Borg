// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.{BorgConfig, BorgCore}

/** Debug: FIRRTL of a standalone BorgCore for simulation/arcilator/debug/core_harness.cpp,
  * plus the instruction encodings it uses (docs/arcilator_custom_fma_bug.md).
  */
object BorgCoreArcMain extends App {
  println("ENC ADD(0,1,2)=0x"   + borg.Instructions.ADD(0, 1, 2).toString(16))
  println("ENC MUL(0,1,2)=0x"   + borg.Instructions.MUL(0, 1, 2).toString(16))
  println("ENC FMA(0,1,3,2)=0x" + borg.Instructions.FMA(0, 1, 3, 2).toString(16))
  println("ENC FNEG(0,2)=0x"    + borg.Instructions.FNEG(0, 2).toString(16))
  soc.Emit.emitFIRRTL(new BorgCore(BorgConfig.Default),
                      "out/hardware/borg/firrtl_corearc")
}
