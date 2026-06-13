// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import chisel3._
import soc.SoCLogic
import memory.SdramBackendSim
import borg.BorgConfig

/** Verilator-only top-level module.
  *
  * Identical SoC (CPU, MemoryController, Peripherals, Borg) as
  * [[tt_um_gonsolo_borg]], but the MemoryController backend is driven by a real
  * Chisel [[SdramBackendSim]] (behavioral SDRAM with realistic latency and a
  * registered done/busy handshake) instead of QSPI or a hand-written C++ flat
  * backend.  This removes the C++ backend as a variable when debugging the
  * ULX3S SDRAM path: the GPU/MemoryController now talk to the same kind of
  * MemBackendIO backend they use on hardware.
  *
  * A `dbg_*` host backdoor (into SdramBackendSim's memory) lets the C++ harness
  * preload firmware/texture and read back the framebuffer without modeling the
  * bus.  NOT used for the ASIC GDS flow (that stays on tt_um_gonsolo_borg).
  */
class BorgSimTop(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  // Host backdoor into the behavioral SDRAM (firmware/texture load + readback).
  val dbg_we    = IO(Input(Bool()))
  val dbg_waddr = IO(Input(UInt(24.W)))
  val dbg_wdata = IO(Input(UInt(16.W)))
  val dbg_raddr = IO(Input(UInt(24.W)))
  val dbg_rdata = IO(Output(UInt(16.W)))

  // SoCLogic abstract members — identical to tt_um_gonsolo_borg.
  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // 2×2 quad SIMT fragment shading (sim).
  override def BORG_CFG: BorgConfig = BorgConfig.Simt

  // Immediately reflect fb_select writes back as scanoutCurBuf so the firmware's
  // PERI_FB_SELECT sync loop exits on the first read (no real scanout in the sim).
  override def scanoutCurBuf: Bool = fbSelectReg

  // Wire up the SoC.
  val uo_out_val = wireSoC()

  // Behavioral SDRAM backend (full 24-bit word space to match real SDRAM).
  val sdram = withClockAndReset(clk, !soc_rst_reg_n) {
    Module(new SdramBackendSim(words = 0x1000000, rdDelay = 4, wrDelay = 2, dbg = true))
  }
  sdram.io.backend <> mem.io.backend

  val d = sdram.dbgIO.get
  d.we      := dbg_we
  d.waddr   := dbg_waddr
  d.wdata   := dbg_wdata
  d.raddr   := dbg_raddr
  dbg_rdata := d.rdata

  // ── SDRAM bandwidth instrumentation (sim-only) ─────────────────────────────
  // Count backend read/write *beats* bucketed by address region, exposed as
  // outputs the C++ harness reads at the end of the run.  This is the real
  // SDRAM traffic the GPU/CPU generate (the SDRAM model is internal to this
  // top, so C++ flat_* counting can't see it).  Region = 16-bit-word offset
  // within the chip (spi_byte>>1), boundaries from borg_layout.h:
  //   code/cpu <0x2540 | desc <0x2B40 | texture <0x42B40 | fb <0x62B40 | other
  val rb_code  = IO(Output(UInt(32.W)));  val wb_code  = IO(Output(UInt(32.W)))
  val rb_desc  = IO(Output(UInt(32.W)));  val wb_desc  = IO(Output(UInt(32.W)))
  val rb_tex   = IO(Output(UInt(32.W)));  val wb_tex   = IO(Output(UInt(32.W)))
  val rb_fb    = IO(Output(UInt(32.W)));  val wb_fb    = IO(Output(UInt(32.W)))
  val rb_bin   = IO(Output(UInt(32.W)));  val wb_bin   = IO(Output(UInt(32.W)))
  val rb_setup = IO(Output(UInt(32.W)));  val wb_setup = IO(Output(UInt(32.W)))
  val rb_stack = IO(Output(UInt(32.W)));  val wb_stack = IO(Output(UInt(32.W)))
  withClockAndReset(clk, !soc_rst_reg_n) {
    val be   = mem.io.backend
    val isP  = be.addrIn(23)
    val wOff = be.addrIn(22, 0)
    // 128×128 layout (word offsets = spi_byte>>1): code <0x2540 | desc <0x2B40 |
    // tex <0x42B40 | fb <0x62B40 | bin <0x162B40 (2MB, SEQ_MAX_TRI=1024) |
    // setup <0x172B40 (128KB) | above = stack/heap.
    val region = Wire(UInt(3.W))
    when(!isP)                     { region := 0.U }   // flash → code bucket
      .elsewhen(wOff < 0x2540.U)   { region := 0.U }   // code/cpu/instr/stack-lo
      .elsewhen(wOff < 0x2B40.U)   { region := 1.U }   // descriptors
      .elsewhen(wOff < 0x42B40.U)  { region := 2.U }   // texture
      .elsewhen(wOff < 0x62B40.U)  { region := 3.U }   // framebuffer
      .elsewhen(wOff < 0x162B40.U) { region := 4.U }   // bin lists (2MB region)
      .elsewhen(wOff < 0x172B40.U) { region := 5.U }   // per-triangle setup
      .otherwise                   { region := 6.U }   // stack/heap (high)
    val rb = RegInit(VecInit(Seq.fill(7)(0.U(32.W))))
    val wb = RegInit(VecInit(Seq.fill(7)(0.U(32.W))))
    when(be.startRead)  { rb(region) := rb(region) + 1.U }
    when(be.startWrite) { wb(region) := wb(region) + be.lenIn }
    rb_code := rb(0); rb_desc := rb(1); rb_tex := rb(2); rb_fb := rb(3)
    rb_bin  := rb(4); rb_setup := rb(5); rb_stack := rb(6)
    wb_code := wb(0); wb_desc := wb(1); wb_tex := wb(2); wb_fb := wb(3)
    wb_bin  := wb(4); wb_setup := wb(5); wb_stack := wb(6)
  }

  uo_out := uo_out_val
}
