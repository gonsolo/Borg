// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.experimental.Analog

/** The DDR3 controller `make litedram` generates from LiteDram.yml: the board's DDR3, its own PLL
  * from the 100 MHz `clk`, one native user port on `user_clk` and the Wishbone control bus that
  * calibration runs over. The Nexys has no chip select: `ddram_cs_n` stays unconnected.
  */
class litedram_core extends ExtModule {
  val clk = IO(Input(Clock()))
  val rst = IO(Input(Bool()))

  val ddram_dq      = IO(Analog(16.W))
  val ddram_dqs_p   = IO(Analog(2.W))
  val ddram_dqs_n   = IO(Analog(2.W))
  val ddram_a       = IO(Output(UInt(15.W)))
  val ddram_ba      = IO(Output(UInt(3.W)))
  val ddram_ras_n   = IO(Output(Bool()))
  val ddram_cas_n   = IO(Output(Bool()))
  val ddram_we_n    = IO(Output(Bool()))
  val ddram_cs_n    = IO(Output(Bool()))
  val ddram_reset_n = IO(Output(Bool()))
  val ddram_clk_p   = IO(Output(Bool()))
  val ddram_clk_n   = IO(Output(Bool()))
  val ddram_cke     = IO(Output(Bool()))
  val ddram_dm      = IO(Output(UInt(2.W)))
  val ddram_odt     = IO(Output(Bool()))

  val pll_locked = IO(Output(Bool()))
  val init_done  = IO(Output(Bool()))
  val init_error = IO(Output(Bool()))
  val user_clk   = IO(Output(Clock()))
  val user_rst   = IO(Output(Bool()))

  val user_port_native_0_cmd_valid   = IO(Input(Bool()))
  val user_port_native_0_cmd_ready   = IO(Output(Bool()))
  val user_port_native_0_cmd_we      = IO(Input(Bool()))
  val user_port_native_0_cmd_addr    = IO(Input(UInt(25.W)))
  val user_port_native_0_wdata_valid = IO(Input(Bool()))
  val user_port_native_0_wdata_ready = IO(Output(Bool()))
  val user_port_native_0_wdata_data  = IO(Input(UInt(128.W)))
  val user_port_native_0_wdata_we    = IO(Input(UInt(16.W)))
  val user_port_native_0_rdata_valid = IO(Output(Bool()))
  val user_port_native_0_rdata_ready = IO(Input(Bool()))
  val user_port_native_0_rdata_data  = IO(Output(UInt(128.W)))

  val wb_ctrl_adr   = IO(Input(UInt(30.W)))
  val wb_ctrl_dat_w = IO(Input(UInt(32.W)))
  val wb_ctrl_dat_r = IO(Output(UInt(32.W)))
  val wb_ctrl_sel   = IO(Input(UInt(4.W)))
  val wb_ctrl_cyc   = IO(Input(Bool()))
  val wb_ctrl_stb   = IO(Input(Bool()))
  val wb_ctrl_we    = IO(Input(Bool()))
  val wb_ctrl_cti   = IO(Input(UInt(3.W)))
  val wb_ctrl_bte   = IO(Input(UInt(2.W)))
  val wb_ctrl_ack   = IO(Output(Bool()))
  val wb_ctrl_err   = IO(Output(Bool()))

  /** The native port as a [[soc.LiteDramNative]], on `user_clk`. */
  def connect(p: soc.LiteDramNative): Unit = {
    user_port_native_0_cmd_valid   := p.cmd.valid
    p.cmd.ready                    := user_port_native_0_cmd_ready
    user_port_native_0_cmd_we      := p.cmd.bits.we
    user_port_native_0_cmd_addr    := p.cmd.bits.addr
    user_port_native_0_wdata_valid := p.wdata.valid
    p.wdata.ready                  := user_port_native_0_wdata_ready
    user_port_native_0_wdata_data  := p.wdata.bits.data
    user_port_native_0_wdata_we    := p.wdata.bits.we
    p.rdata.valid                  := user_port_native_0_rdata_valid
    user_port_native_0_rdata_ready := p.rdata.ready
    p.rdata.bits                   := user_port_native_0_rdata_data
  }
}

object LiteDramWindow {
  /** Window word 31 reads this: the board's memory is LiteDRAM ("LDR1"). */
  val Id = 0x4c445231L

  /** Window word to control-bus word: 0-31 the controller, 32-63 the PHY, 64-127 the DFII. */
  def ctrlWord(w: UInt): UInt = Mux(w(6), 0x400.U | w(5, 0), Mux(w(5), 0x200.U | w(4, 0), w(4, 0)))
}

/** Host register offsets 0x200-0x3FF, the 128 words above Borg's registers, onto [[litedram_core]]'s
  * control bus on its own clock, for calibration from the host.
  */
class LiteDramWindow extends Module {
  val io = IO(new soc.HuttWishboneIO(10))
  val b  = Module(new soc.HuttWishbone(10))
  b.io.wbClock := io.wbClock
  b.io.wbReset := io.wbReset
  io.wb <> b.io.wb
  io.wb.adr := LiteDramWindow.ctrlWord(b.io.wb.adr(6, 0))

  val isId = io.bus.req.bits.addr(8, 2) === 31.U
  val id   = RegInit(false.B)
  b.io.bus.req.valid  := io.bus.req.valid && !isId
  b.io.bus.req.bits   := io.bus.req.bits
  io.bus.req.ready    := b.io.bus.req.ready && !id
  io.bus.resp.valid   := id || b.io.bus.resp.valid
  io.bus.resp.bits    := Mux(id, LiteDramWindow.Id.U, b.io.bus.resp.bits)
  b.io.bus.resp.ready := io.bus.resp.ready && !id
  when(io.bus.req.fire && isId) { id := true.B }
  when(io.bus.resp.fire && id) { id := false.B }
}
