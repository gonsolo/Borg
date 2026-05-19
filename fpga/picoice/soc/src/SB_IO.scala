// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.{ExtModule, IntParam}
import chisel3.experimental.Analog

/** Lattice iCE40 SB_IO primitive for tri-state I/O.
  *
  * PIN_TYPE encoding (6 bits): {output_type[5:2], input_type[1:0]}
  * 0b1010_01 = registered output + simple input
  */
class SB_IO(pinType: Int, pullup: Int = 0) extends ExtModule(
  Map("PIN_TYPE" -> IntParam(pinType), "PULLUP" -> IntParam(pullup))
) {
  val PACKAGE_PIN   = IO(Analog(1.W))
  val OUTPUT_CLK    = IO(Input(Clock()))
  val INPUT_CLK     = IO(Input(Clock()))
  val OUTPUT_ENABLE = IO(Input(Bool()))
  val D_OUT_0       = IO(Input(Bool()))
  val D_IN_0        = IO(Output(Bool()))
}
