// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

class RegFileCopyIO(width: Int) extends Bundle {
  val rd = Flipped(new MemReadPort(log2Ceil(32), width))
  val wr = Flipped(new MemWritePort(log2Ceil(32), width))
}

/** Single-copy register file with exactly 1 read + 1 write port.
  * Each instance gets a unique Verilog module name to prevent CIRCT
  * deduplication, ensuring yosys can infer iCE40 Block RAMs.
  */
class RegFileCopy(width: Int, instName: String) extends Module {
  override def desiredName = instName

  val io = IO(new RegFileCopyIO(width))

  val mem = SyncReadMem(32, UInt(width.W))
  io.rd.data := mem.read(io.rd.addr, io.rd.en)

  when(io.wr.en) {
    mem.write(io.wr.addr, io.wr.data)
  }
}
