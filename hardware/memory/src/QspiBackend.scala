// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._

/** QSPI memory backend.
  *
  * Bridges [[MemBackendIO]] (the arbiter protocol) to physical QSPI pins
  * via [[QspiController]].  Instantiate at the SoC top level alongside
  * [[MemoryController]] and wire:
  *
  * {{{
  *   mem.io.backend        <> qspiBackend.io.backend
  *   qspiBackend.io.qspiPins <> (platform-specific physical pins)
  * }}}
  */
class QspiBackendIO extends Bundle {
  val backend  = Flipped(new MemBackendIO)  // connect to mem.io.backend
  val qspiPins = new QspiPinsIO             // connect to physical pins
}

class QspiBackend extends Module {
  val io = IO(new QspiBackendIO)

  val q = Module(new QspiController())

  // Arbiter commands → QspiController
  q.io.addr_in     := io.backend.addrIn
  q.io.data_in     := io.backend.dataIn
  q.io.start_read  := io.backend.startRead
  q.io.start_write := io.backend.startWrite
  q.io.stall_txn   := io.backend.stallTxn
  q.io.stop_txn    := io.backend.stopTxn

  // QspiController responses → Arbiter
  io.backend.dataOut   := q.io.data_out
  io.backend.dataReq   := q.io.data_req
  io.backend.dataReady := q.io.data_ready
  io.backend.busy      := q.io.busy

  // QspiController → Physical pins
  io.qspiPins.dataOut     := q.io.spi_data_out
  io.qspiPins.dataOe      := q.io.spi_data_oe
  io.qspiPins.clkOut      := q.io.spi_clk_out
  io.qspiPins.flashSelect := q.io.spi_flash_select
  io.qspiPins.ramASelect  := q.io.spi_ram_a_select
  io.qspiPins.ramBSelect  := q.io.spi_ram_b_select

  // Physical pins → QspiController
  q.io.spi_data_in := io.qspiPins.dataIn
}
