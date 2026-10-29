// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

/** Which arrangement drives Borg's `mmio`/`gpuMem` ports.
  *
  *   - [[BorgDirect]]    Borg instantiated locally; the link package is not elaborated.
  *   - [[BorgLoopback]]  Link master, slave and Borg in one bitstream over internal wires.
  *   - [[BorgExternal]]  The link master alone; the slave is silicon or a second FPGA.
  *   - [[BorgPadLoop]]   BorgLoopback with the link through real pads and a ribbon cable. It needs
  *                       two pads per wire, which only fits J1+J2 at `LinkParams(w = 8)`.
  */
sealed trait BorgMode
case object BorgDirect extends BorgMode
case object BorgLoopback extends BorgMode
case object BorgExternal extends BorgMode
case object BorgPadLoop extends BorgMode
