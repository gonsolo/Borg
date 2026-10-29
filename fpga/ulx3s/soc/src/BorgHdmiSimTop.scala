// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import memory.SdramBackendSim
import borg.BorgConfig

/** Verilator top: [[BorgSimTop]] plus the real HDMI scanout and VGA timing, so
  * the harness captures the displayed pixels, scanout addressing included. One
  * clock suffices: only TMDS serialization runs faster on hardware. */
class BorgHdmiSimTop(val CLOCK_MHZ: Int, fbW: Int = 128, fbH: Int = 128)
    extends RawModule with SoCLogic {
  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  // Host backdoor into the SDRAM model.
  val dbg_we    = IO(Input(Bool()))
  val dbg_waddr = IO(Input(UInt(24.W)))
  val dbg_wdata = IO(Input(UInt(16.W)))
  val dbg_raddr = IO(Input(UInt(24.W)))
  val dbg_rdata = IO(Output(UInt(16.W)))

  val disp_de    = IO(Output(Bool()))
  val disp_hcnt  = IO(Output(UInt(10.W)))
  val disp_vcnt  = IO(Output(UInt(10.W)))
  val disp_r     = IO(Output(UInt(8.W)))
  val disp_g     = IO(Output(UInt(8.W)))
  val disp_b     = IO(Output(UInt(8.W)))

  def soc_clk   = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in

  override def BORG_CFG: BorgConfig = BorgConfig.Default

  // Before wireSoC, which calls wireGpuMem.
  val scanout = withClockAndReset(clk, !soc_rst_reg_n) {
    Module(new HdmiScanoutFp16(fbWidth = fbW, fbHeight = fbH))
  }
  override def scanoutCurBuf: Bool = scanout.io.curBuf

  // Borg has priority; scanout reads in the gaps.
  override def wireGpuMem(): Unit = {
    val gpuActive   = peripherals.io.gpuMem.req || peripherals.io.gpuMem.wr
    val scanoutOwns = withClockAndReset(clk, !soc_rst_reg_n) { RegInit(false.B) }
    when(scanoutOwns) {
      when(mem.io.gpuMem.ready) { scanoutOwns := false.B }
    }.otherwise {
      when(!gpuActive && scanout.io.gpuReq) { scanoutOwns := true.B }
    }
    val serveGpu = !scanoutOwns
    mem.io.gpuMem.req   := Mux(serveGpu, peripherals.io.gpuMem.req,  scanout.io.gpuReq)
    mem.io.gpuMem.addr  := Mux(serveGpu, peripherals.io.gpuMem.addr, scanout.io.gpuAddr)
    mem.io.gpuMem.wr    := Mux(serveGpu, peripherals.io.gpuMem.wr,   false.B)
    mem.io.gpuMem.wdata := peripherals.io.gpuMem.wdata
    mem.io.gpuMem.wlen  := Mux(serveGpu, peripherals.io.gpuMem.wlen, 1.U)
    peripherals.io.gpuMem.data    := mem.io.gpuMem.data
    peripherals.io.gpuMem.ready   := mem.io.gpuMem.ready && !scanoutOwns
    peripherals.io.gpuMem.waccept := mem.io.gpuMem.waccept && serveGpu
    scanout.io.gpuData  := mem.io.gpuMem.data
    scanout.io.gpuReady := mem.io.gpuMem.ready && scanoutOwns
  }

  val uo_out_val = wireSoC()
  uo_out := uo_out_val

  // 640x480 VGA timing, one pixel per cycle.
  withClockAndReset(clk, !soc_rst_reg_n) {
    val hCount = RegInit(0.U(10.W))
    val vCount = RegInit(0.U(10.W))
    val hTotal = 800.U; val vTotal = 525.U
    val hActive = 640.U; val vActive = 480.U
    when(hCount === hTotal - 1.U) {
      hCount := 0.U
      when(vCount === vTotal - 1.U) { vCount := 0.U }.otherwise { vCount := vCount + 1.U }
    }.otherwise { hCount := hCount + 1.U }
    val de = (hCount < hActive) && (vCount < vActive)
    scanout.io.hCount := hCount
    scanout.io.vCount := vCount
    scanout.io.de     := de
    scanout.io.tick25 := true.B
    scanout.io.enable := true.B
    scanout.io.frontBuf := fbSelectReg
    scanout.io.fbBase   := scanoutFbBase0
    scanout.io.fbBase1  := scanoutFbBase1

    // Scanout RGB is registered: delay the position to match.
    disp_de   := RegNext(de, false.B)
    disp_hcnt := RegNext(hCount, 0.U)
    disp_vcnt := RegNext(vCount, 0.U)
    disp_r    := scanout.io.red
    disp_g    := scanout.io.green
    disp_b    := scanout.io.blue
  }

  val sdram = withClockAndReset(clk, !soc_rst_reg_n) {
    Module(new SdramBackendSim(words = 0x1000000, rdDelay = 4, wrDelay = 2, dbg = true))
  }
  sdram.io.backend <> mem.io.backend
  val d = sdram.dbgIO.get
  d.we    := dbg_we
  d.waddr := dbg_waddr
  d.wdata := dbg_wdata
  d.raddr := dbg_raddr
  dbg_rdata := d.rdata
}
