// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import borg.BorgConfig

/** Emit FIRRTL for [[BorgDirectSimTop]] (arcilator input). */
object BorgDirectSimMain extends App {
  Emit.emitFIRRTL(new BorgDirectSimTop(BorgConfig.simCfg), "out/hardware/borg/firrtl_direct")
}
