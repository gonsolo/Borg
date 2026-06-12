// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import memory.SdramBackendSim
import borg.BorgConfig

/** Verilator-only full-SoC top WITH the HDMI scanout in the loop.
  *
  * Identical SoC to [[BorgSimTop]] (CPU + Borg + MemoryController + behavioral
  * SDRAM + dbg backdoor), but adds the real [[HdmiScanoutFp16]] display engine
  * and the 640×480 VGA timing generator, arbitrated onto the shared gpuMem port
  * exactly like the ULX3S board top.  The scanout's RGB8 output (the actual
  * displayed pixel) plus the raster coordinates are exposed so the C++ harness
  * can capture the frame the MONITOR would show — not the raw SDRAM contents.
  *
  * This closes the gap that hid the green corner pixel: the existing sim reads
  * the framebuffer back through the SDRAM backdoor (always the correct address),
  * so a scanout-side addressing bug is invisible.  Here the image goes through
  * the scanout's own fbBase (firmware-programmed via PERI_SCANOUT_FB0/FB1), the
  * fill FSM, the FP16→RGB8 conversion and the display read — the same path as HW.
  *
  * Single clock: on hardware the whole display path is 25 MHz (only the final
  * TMDS serialization is 125 MHz and does not change pixel values), so one clock
  * faithfully reproduces the displayed image.
  */
class BorgHdmiSimTop(val CLOCK_MHZ: Int, fbW: Int = 128, fbH: Int = 128)
    extends RawModule with SoCLogic {
  val ui_in   = IO(Input(UInt(8.W)))
  val uo_out  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  // Host backdoor into the behavioral SDRAM.
  val dbg_we    = IO(Input(Bool()))
  val dbg_waddr = IO(Input(UInt(24.W)))
  val dbg_wdata = IO(Input(UInt(16.W)))
  val dbg_raddr = IO(Input(UInt(24.W)))
  val dbg_rdata = IO(Output(UInt(16.W)))

  // Displayed-pixel outputs (what the monitor shows) + raster position.
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

  override def BORG_CFG: BorgConfig = BorgConfig.Simt

  // ── HDMI scanout (declared before wireSoC so wireGpuMem can reference it) ──
  val scanout = withClockAndReset(clk, !soc_rst_reg_n) {
    Module(new HdmiScanoutFp16(fbWidth = fbW, fbHeight = fbH))
  }
  override def scanoutCurBuf: Bool = scanout.io.curBuf

  // ── gpuMem arbiter: Borg GPU has priority; scanout reads in the gaps ──
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

  // ── 640×480 @ 25 MHz VGA timing (every SoC cycle is a pixel) ──
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

    // Expose the displayed pixel.  scanout RGB is registered (showD), valid one
    // cycle after the raster coordinate is presented, so delay the position to
    // match for a coherent (coord, colour) stream.
    disp_de   := RegNext(de, false.B)
    disp_hcnt := RegNext(hCount, 0.U)
    disp_vcnt := RegNext(vCount, 0.U)
    disp_r    := scanout.io.red
    disp_g    := scanout.io.green
    disp_b    := scanout.io.blue
  }

  // ── Behavioral SDRAM backend + host backdoor (as BorgSimTop) ──
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
