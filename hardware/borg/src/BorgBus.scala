// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

/** Unified bus bundle representing the 32-bit MMIO path from host CPU, allowing standard connectivity without manual line unwrapping. */
class BorgBusIO(val addrBits: Int = 9, val dataBits: Int = 32) extends Bundle {
  val address   = Output(UInt(addrBits.W))
  val data_in   = Output(UInt(dataBits.W))
  val is_writing = Output(Bool())
  val is_reading = Output(Bool())
}
