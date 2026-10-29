// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

class RegFileCopyIO(width: Int, entries: Int) extends Bundle {
  val rd = Flipped(new MemReadPort(log2Ceil(entries), width))
  val wr = Flipped(new MemWritePort(log2Ceil(entries), width))
}

/** Single-copy register file with exactly 1 read + 1 write port: 32 registers per quad slot.
  * Each instance gets a unique Verilog module name to prevent CIRCT
  * deduplication, ensuring yosys can infer iCE40 Block RAMs.
  */
class RegFileCopy(width: Int, instName: String, slots: Int = 1) extends Module {
  override def desiredName = instName

  val io = IO(new RegFileCopyIO(width, 32 * slots))

  val mem = SyncReadMem(32 * slots, UInt(width.W))
  io.rd.data := mem.read(io.rd.addr, io.rd.en)

  when(io.wr.en) {
    mem.write(io.wr.addr, io.wr.data)
  }
}

object RegFileCopy {
  /** Register `reg` of quad slot `slot`. */
  def addr(cfg: BorgConfig, slot: UInt, reg: UInt): UInt = if (cfg.quadSlots == 1) reg else Cat(slot, reg)
}
