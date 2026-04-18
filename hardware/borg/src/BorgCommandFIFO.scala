// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class BorgCommandFIFOIO(coordWidth: Int = 9) extends Bundle {
  val enq = Flipped(Decoupled(new BorgCommand(coordWidth)))
  val deq = Decoupled(new BorgCommand(coordWidth))
}

class BorgCommandFIFO(entries: Int = 2, coordWidth: Int = 9) extends Module {
  val io = IO(new BorgCommandFIFOIO(coordWidth))

  // Use Chisel's built-in Queue to implement the FIFO.
  val queue = Queue(io.enq, entries)
  io.deq <> queue
}
