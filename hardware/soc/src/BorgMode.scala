// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

/** Which physical arrangement drives Borg's `mmio`/`gpuMem` ports.
  *
  * Three values, not a boolean, because the on-hardware ladder for the
  * Borg-only wafer.space bridge has three distinct rungs and they all need
  * different pin bindings even though [[BorgLoopback]] and [[BorgExternal]]
  * both go through the link RTL:
  *
  *   - [[BorgDirect]]    Borg instantiated locally (today's behaviour, every
  *                       target). The link package is not even elaborated.
  *   - [[BorgLoopback]]  `BorgLinkMaster` + `BorgLinkSlave` + a real `Borg`,
  *                       all in one bitstream, "pins" are internal wires.
  *                       Rung A of the on-hardware ladder: proves the whole
  *                       bridge -- framing, credits, arbitration, training,
  *                       the four interface hazards -- on real hardware with
  *                       zero ASIC-side work. This is Phase 3's milestone.
  *   - [[BorgExternal]]  `BorgLinkMaster` only; the far side is reached over
  *                       real pins -- either actual silicon, or (rungs B/C)
  *                       a loopback cable / a second FPGA running
  *                       `BorgOnlyTop`. Needs the pin/LPF work in Phase 5.
  */
sealed trait BorgMode
case object BorgDirect extends BorgMode
case object BorgLoopback extends BorgMode
case object BorgExternal extends BorgMode
