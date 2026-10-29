// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** ECP5 `USRMCLK`: drives the configuration SPI clock pin from user logic, to
  * clock the flash after configuration. Needs `SYSCONFIG MASTER_SPI_PORT=DISABLE;`
  * in the LPF. */
class Usrmclk extends ExtModule {
  override def desiredName = "USRMCLK"  // must match the Lattice/nextpnr primitive name exactly

  val USRMCLKI  = IO(Input(Clock()))

  // Active-low tristate: false drives the pin.
  val USRMCLKTS = IO(Input(Bool()))
}
