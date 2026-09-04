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
  *                       real pins -- either actual silicon, or (rung C) a
  *                       second FPGA running `BorgOnlyTop`. Needs the
  *                       pin/LPF work in Phase 5.
  *   - [[BorgPadLoop]]   Rung B: [[BorgLoopback]]'s complete system (master +
  *                       slave + a real `Borg`), but the master<->slave path
  *                       leaves the FPGA through real pads and comes back on
  *                       a ribbon cable, adding IO buffers, flight time and
  *                       SSO to rung A.
  *
  * Rung B deliberately does NOT reuse [[BorgExternal]], even though "pins out
  * plus a loopback cable" sounds like it should. [[BorgExternal]] elaborates
  * the master *alone*: there is no slave and no `Borg` in that bitstream, so
  * jumpering its `dn_*` outputs into its own `up_*` inputs merely feeds the
  * master its own transmitted beats and can never render. It is the rung C
  * arrangement (the far side is somewhere else) and only becomes a whole
  * system when a second board or real silicon supplies the slave.
  *
  * A same-board rung B therefore needs *two* pads per logical wire -- one
  * driving, one receiving -- since both endpoints live on this FPGA. At the
  * default w=16 that is 78 holes, more than J1+J2 can spare; at w=8 it is 46,
  * which fits. Hence [[BorgPadLoop]] targets override `linkParams` to
  * `LinkParams(w = 8)`.
  */
sealed trait BorgMode
case object BorgDirect extends BorgMode
case object BorgLoopback extends BorgMode
case object BorgExternal extends BorgMode
case object BorgPadLoop extends BorgMode
