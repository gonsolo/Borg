// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import chisel3._
import soc.SoCLogic
import memory.MemBackendIO

/** Verilator-only top-level module.
  *
  * Identical SoC (CPU, MemoryController, Peripherals, Borg) as
  * [[tt_um_gonsolo_borg]], but instead of bridging the MemoryController to a
  * QSPI nibble-serial backend it exposes the [[MemBackendIO]] bus directly as
  * top-level ports.  The C++ verilator harness models a trivial flat memory
  * behind this bus — far simpler and more robust than emulating the QSPI
  * protocol.  This top is NOT used for the ASIC GDS flow (that stays on
  * [[tt_um_gonsolo_borg]] + QspiBackend).
  */
class BorgSimTop(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))
  // MemBackendIO is declared from the arbiter's perspective, matching
  // mem.io.backend exactly, so a straight `<>` exposes the bus to the top.
  val backend = IO(new MemBackendIO)

  // SoCLogic abstract members — identical to tt_um_gonsolo_borg.
  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // Wire up the SoC.
  val uo_out_val = wireSoC()

  // Expose the MemoryController backend bus directly (no QspiBackend).
  backend <> mem.io.backend

  uo_out := uo_out_val
}
