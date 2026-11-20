// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._

class LiteDramCmd extends Bundle {
  val we   = Bool()
  val addr = UInt(25.W)
}

class LiteDramWdata extends Bundle {
  val data = UInt(128.W)
  val we   = UInt(16.W)
}

/** LiteDRAM's native user port as litedram_gen emits it: 16-byte lines, `addr` in lines, `we` per byte. */
class LiteDramNative extends Bundle {
  val cmd   = Decoupled(new LiteDramCmd)
  val wdata = Decoupled(new LiteDramWdata)
  val rdata = Flipped(Decoupled(UInt(128.W)))
}

class LiteDramMigUiIO extends Bundle {
  val ui       = Flipped(new MigUi)
  val dram     = new LiteDramNative
  val initDone = Input(Bool())
}

/** The MIG's user interface on LiteDRAM's native port, so [[GpuMemMig]], [[MigUiArbiter]] and
  * [[MigScanout]] run on either. A line's data may come before its write command, as the MIG takes
  * it; LiteDRAM asks for it only once the write is scheduled, so a FIFO holds it until then.
  */
class LiteDramMigUi extends Module {
  val io = IO(new LiteDramMigUiIO)
  val u = io.ui

  val wq = Module(new Queue(new LiteDramWdata, 4))
  val in = RegInit(false.B)   // a register in front of the FIFO's LUT RAM, for timing
  val inBits = Reg(new LiteDramWdata)
  u.app_wdf_rdy   := !in || wq.io.enq.ready
  wq.io.enq.valid := in
  wq.io.enq.bits  := inBits
  when(wq.io.enq.fire) { in := false.B }
  when(u.app_wdf_wren && u.app_wdf_rdy) {
    in          := true.B
    inBits.data := u.app_wdf_data
    inBits.we   := ~u.app_wdf_mask
  }
  io.dram.wdata <> wq.io.deq

  io.dram.cmd.valid     := u.app_en
  io.dram.cmd.bits.we   := u.app_cmd === 0.U
  io.dram.cmd.bits.addr := u.app_addr(27, 3)
  u.app_rdy             := io.dram.cmd.ready

  io.dram.rdata.ready   := true.B
  u.app_rd_data_valid   := io.dram.rdata.valid
  u.app_rd_data         := io.dram.rdata.bits
  u.init_calib_complete := io.initDone
}
