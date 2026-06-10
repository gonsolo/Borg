// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Generic memory read port. */
class MemReadPort(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val addr = Output(UInt(addrWidth.W))
  val en   = Output(Bool())
  val data = Input(UInt(dataWidth.W))
}

/** Generic memory write port. */
class MemWritePort(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val addr = Output(UInt(addrWidth.W))
  val en   = Output(Bool())
  val data = Output(UInt(dataWidth.W))
}

/** Core pipeline control signals. */
class CoreControlIO extends Bundle {
  val start       = Output(Bool())
  val reset       = Output(Bool())
  val startPC          = Output(UInt(6.W))
  val uniformWritePage = Output(UInt(1.W))
}

/** Simulation-only LUT initialization port. */
class LutInitIO(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val en     = Output(Bool())
  val isRcp  = Output(Bool())
  val isFrsq = Output(Bool()) // selects the reciprocal-sqrt LUT (34 entries)
  val addr   = Output(UInt(addrWidth.W))
  val data   = Output(UInt(dataWidth.W))
}

