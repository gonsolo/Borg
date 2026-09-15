// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.simulator.{HasSimulator, SimulatorAPI}
import svsim.CommonCompilationSettings
import svsim.CommonCompilationSettings.OptimizationStyle

/** `simulate()` for full-`Borg`/`BorgTestWrapper`-scale DUTs, trading
  * Verilator's own default -O3 scheduling optimization (and the generated
  * C++'s -O3) for -O1, per svsim.verilator.Backend's
  * OptimizeForCompilationSpeed style (see memory
  * project_mill_concurrency_bug_root_caused for the -O3-at-this-scale cost:
  * ~900s/test, most of it Verilator's own translation pass, before any C++
  * compiler even starts). Correctness tests run a few hundred cycles once
  * and don't need -O3's faster steady-state simulated-cycle throughput --
  * that trade only pays off for long-running simulations, which these
  * aren't.
  */
trait FastBuildSimulator extends SimulatorAPI {
  implicit val hasSimulator: HasSimulator = HasSimulator.simulators.verilator(
    CommonCompilationSettings.default.copy(
      optimizationStyle = OptimizationStyle.OptimizeForCompilationSpeed
    )
  )
}
