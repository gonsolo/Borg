// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** Wrapper for the ECP5 `USRMCLK` primitive.
  *
  * Routes a user-logic clock signal to the MCLK pin that is shared
  * with the ECP5 configuration SPI interface, allowing user logic to
  * clock the onboard Winbond flash after configuration is complete.
  *
  * Requires `SYSCONFIG MASTER_SPI_PORT=DISABLE;` in the LPF file to
  * prevent the ECP5 from reclaiming the pin post-configuration.
  *
  * Usage:
  * {{{
  *   val usrmclk = Module(new Usrmclk)
  *   usrmclk.USRMCLKI  := mySpiClk
  *   usrmclk.USRMCLKTS := false.B   // 0 = enabled (active-low tristate)
  * }}}
  */
class Usrmclk extends ExtModule {
  override def desiredName = "USRMCLK"  // must match the Lattice/nextpnr primitive name exactly

  /** Clock input routed to MCLK. Toggle this as your SPI clock. */
  val USRMCLKI  = IO(Input(Clock()))

  /** Tristate control — hold `false` (0) to enable user clock output. */
  val USRMCLKTS = IO(Input(Bool()))
}
