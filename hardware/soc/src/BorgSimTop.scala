// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import memory.{SdramBackend, SdramBackendSim, SdramChipModel}
import borg.BorgConfig

/** Verilator top: the [[QspiSocTop]] SoC on a Chisel SDRAM backend ([[SdramBackendSim]], or with
  * `realSdram` the board's SdramBackend into SdramChipModel). The `dbg_*` backdoor lets the C++
  * harness preload memory and read back the framebuffer.
  */
class BorgSimTop(val CLOCK_MHZ: Int, val realSdram: Boolean = false, val sdramMhz: Int = 25)
    extends RawModule with SoCLogic {
  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  val dbg_we    = IO(Input(Bool()))
  val dbg_waddr = IO(Input(UInt(24.W)))
  val dbg_wdata = IO(Input(UInt(16.W)))
  val dbg_raddr = IO(Input(UInt(24.W)))
  val dbg_rdata = IO(Output(UInt(16.W)))

  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  // Matches ULX3S.scala: RV32I, bare-metal firmware in M-mode.
  override def hasSupervisorMode: Boolean = false

  override def BORG_CFG: BorgConfig = BorgConfig.Default

  // No scanout in the sim: the firmware's PERI_FB_SELECT sync loop exits on the first read.
  override def scanoutCurBuf: Bool = fbSelectReg

  val uo_out_val = wireSoC()

  // SDRAM, full 24-bit word space (32 MB) like the board's chip.
  if (realSdram) {
    val backend = withClockAndReset(clk, !soc_rst_reg_n) { Module(new SdramBackend(sdramMhz)) }
    backend.io.backend <> mem.io.backend
    val chip = withClockAndReset(clk, !soc_rst_reg_n) {
      Module(new SdramChipModel(addrBits = 24, readLatency = 2))
    }
    val pins = backend.io.sdramPins
    chip.io.cs_n := pins.cs_n; chip.io.ras_n := pins.ras_n; chip.io.cas_n := pins.cas_n
    chip.io.we_n := pins.we_n; chip.io.cke := pins.cke;     chip.io.ba := pins.ba
    chip.io.addr := pins.addr; chip.io.dqm := pins.dqm
    chip.io.dq_out := pins.dq_out; chip.io.dq_oe := pins.dq_oe
    pins.dq_in := chip.io.dq_in
    chip.dbg.we := dbg_we; chip.dbg.waddr := dbg_waddr; chip.dbg.wdata := dbg_wdata
    chip.dbg.raddr := dbg_raddr
    dbg_rdata := chip.dbg.rdata
  } else {
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
  }

  // SDRAM read/write beats per address region, read by the C++ harness at the end of the run.
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
    // Word-offset boundaries of the 128x128 layout in borg_layout.h.
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
