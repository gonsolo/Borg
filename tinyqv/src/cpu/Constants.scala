// Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._

// RISC-V CSR addresses (12-bit)
object CSR {
  val MSTATUS = "h300".U(12.W)
  val MISA    = "h301".U(12.W)
  val MIE     = "h304".U(12.W)
  val MEPC    = "h341".U(12.W)
  val MCAUSE  = "h342".U(12.W)
  val MIP     = "h344".U(12.W)
  val CYCLE   = "hC00".U(12.W)
  val TIME    = "hC01".U(12.W)
  val MIMPID  = "hF13".U(12.W)
}

// Memory-mapped I/O address constants
object MMIO {
  // Timer registers: 0xFFFFF00x (mtime at offset 0, mtimecmp at offset 4)
  val TIMER_BASE_HI = 0xFFFFF0.U(24.W)
}
