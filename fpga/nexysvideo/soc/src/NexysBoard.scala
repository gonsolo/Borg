// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}
import soc.{AsyncFifo, LiteDramMigUi, MigScanout, MigUiArbiter, MigUi, UsbFifo}

class IOBUF extends ExtModule {
  val IO = chisel3.IO(Analog(1.W))
  val I  = chisel3.IO(Input(Bool()))
  val O  = chisel3.IO(Output(Bool()))
  val T  = chisel3.IO(Input(Bool()))
}

/** The Nexys Video board as the tops share it: clocks, the DDR3 behind LiteDRAM, the FT2232H's
  * synchronous FIFO (`rx`, `tx` to the 25 MHz side) and the HDMI scan-out, which reads first.
  * A top drives `gpuMig` on `dram.user_clk`, the 25 MHz sides of `rx` and `tx`, and gives the host
  * LiteDRAM's calibration registers through [[dramWindow]].
  */
trait NexysBoard { self: RawModule =>
  /** Where the scan-out reads its descriptor in the DDR3: Borg's BORG_SCANOUT_SPI (hardware/rdl/layout.txt). */
  def scanoutDesc: Long = 0x8000000L

  val clk        = IO(Input(Clock()))
  val cpu_resetn = IO(Input(Bool()))
  val led        = IO(Output(UInt(4.W)))

  val prog_clko  = IO(Input(Clock()))
  val prog_d     = IO(Vec(8, Analog(1.W)))
  val prog_rxen  = IO(Input(Bool()))
  val prog_txen  = IO(Input(Bool()))
  val prog_rdn   = IO(Output(Bool()))
  val prog_wrn   = IO(Output(Bool()))
  val prog_oen   = IO(Output(Bool()))
  val prog_siwun = IO(Output(Bool()))

  val hdmi_tx_clk_p = IO(Output(Bool()))
  val hdmi_tx_clk_n = IO(Output(Bool()))
  val hdmi_tx_p     = IO(Output(UInt(3.W)))
  val hdmi_tx_n     = IO(Output(UInt(3.W)))

  val ddram_dq      = IO(Analog(16.W))
  val ddram_dqs_p   = IO(Analog(2.W))
  val ddram_dqs_n   = IO(Analog(2.W))
  val ddram_a       = IO(Output(UInt(15.W)))
  val ddram_ba      = IO(Output(UInt(3.W)))
  val ddram_ras_n   = IO(Output(Bool()))
  val ddram_cas_n   = IO(Output(Bool()))
  val ddram_we_n    = IO(Output(Bool()))
  val ddram_reset_n = IO(Output(Bool()))
  val ddram_clk_p   = IO(Output(Bool()))
  val ddram_clk_n   = IO(Output(Bool()))
  val ddram_cke     = IO(Output(Bool()))
  val ddram_dm      = IO(Output(UInt(2.W)))
  val ddram_odt     = IO(Output(Bool()))

  val clkGen = Module(new Clocks)
  clkGen.io.clk100 := clk
  clkGen.io.rst    := !cpu_resetn
  val locked = clkGen.io.locked

  val dram = Module(new litedram_core)
  dram.clk := clkGen.io.clk200
  dram.rst := !locked
  ddram_a       := dram.ddram_a
  ddram_ba      := dram.ddram_ba
  ddram_ras_n   := dram.ddram_ras_n
  ddram_cas_n   := dram.ddram_cas_n
  ddram_we_n    := dram.ddram_we_n
  ddram_reset_n := dram.ddram_reset_n
  ddram_clk_p   := dram.ddram_clk_p
  ddram_clk_n   := dram.ddram_clk_n
  ddram_cke     := dram.ddram_cke
  ddram_dm      := dram.ddram_dm
  ddram_odt     := dram.ddram_odt
  attach(ddram_dq, dram.ddram_dq)
  attach(ddram_dqs_p, dram.ddram_dqs_p)
  attach(ddram_dqs_n, dram.ddram_dqs_n)

  /** LiteDRAM's control bus for the host's register offsets 0x400-0x5FF, on the caller's clock. */
  def dramWindow(): LiteDramWindow = {
    val w = Module(new LiteDramWindow)
    w.io.wbClock := dram.user_clk
    w.io.wbReset := dram.user_rst
    dram.wb_ctrl_adr   := w.io.wb.adr
    dram.wb_ctrl_dat_w := w.io.wb.datW
    dram.wb_ctrl_sel   := w.io.wb.sel
    dram.wb_ctrl_cyc   := w.io.wb.cyc
    dram.wb_ctrl_stb   := w.io.wb.stb
    dram.wb_ctrl_we    := w.io.wb.we
    dram.wb_ctrl_cti   := 0.U
    dram.wb_ctrl_bte   := 0.U
    w.io.wb.datR := dram.wb_ctrl_dat_r
    w.io.wb.ack  := dram.wb_ctrl_ack
    w
  }

  // The FIFO's outputs need 8 of the clock's 16.7 ns: an MMCM takes the clock tree's delay out.
  val usbMmcm = Module(new MMCME2_BASE(inPeriod = 16.667, mult = 16.0, div0 = 16.0, div1 = 16, div2 = 16))
  val usbBuf  = Module(new BUFG)
  val usbFb   = Module(new BUFG)
  usbMmcm.CLKIN1 := prog_clko
  // An MMCM relocks only through a reset once its input is back: pulse one every 3.3 ms while unlocked.
  val usbRetry = withClockAndReset(clkGen.io.clk25, !locked) {
    val n = RegInit(0.U(16.W))
    n := Mux(RegNext(RegNext(usbMmcm.LOCKED)), 0.U, n + 1.U)
    n.andR
  }
  usbMmcm.RST    := !locked || usbRetry
  usbMmcm.PWRDWN := false.B
  usbFb.I        := usbMmcm.CLKFBOUT
  usbMmcm.CLKFBIN := usbFb.O
  usbBuf.I       := usbMmcm.CLKOUT0
  val usbClk = usbBuf.O
  // The chip's clock only runs once the host opens the FIFO: assert at once, release on its edge.
  // Each host session restarts the chip's clock: the whole link and Borg start over with it.
  val usbLost = !usbMmcm.LOCKED
  def resetIn(c: Clock, r: Bool): Bool = withClockAndReset(c, r.asAsyncReset) { ShiftRegister(false.B, 2, true.B, true.B) }
  val usbReset  = resetIn(usbClk, usbLost)

  val rx = Module(new AsyncFifo(8, 4))
  val tx = Module(new AsyncFifo(9, 4))
  rx.enqClock := usbClk; rx.enqReset := usbReset
  tx.deqClock := usbClk; tx.deqReset := usbReset
  withClockAndReset(usbClk, usbReset) {
    val usb = Module(new UsbFifo)
    val p   = usb.io.pins
    rx.enq <> usb.io.rx
    usb.io.tx.valid := tx.deq.valid
    usb.io.tx.bits  := tx.deq.bits(7, 0)
    usb.io.txEnd    := tx.deq.bits(8)
    tx.deq.ready    := usb.io.tx.ready
    val bufs = Seq.fill(8)(Module(new IOBUF))
    for ((b, i) <- bufs.zipWithIndex) {
      attach(b.IO, prog_d(i))
      b.I := p.dOut(i)
      b.T := !p.dOe
    }
    p.dIn      := VecInit(bufs.map(_.O)).asUInt
    p.rxfN     := prog_rxen
    p.txeN     := prog_txen
    prog_rdn   := p.rdN
    prog_wrn   := p.wrN
    prog_oen   := p.oeN
    prog_siwun := p.siwuN
  }

  // The DDR3 serves Borg and the scan-out, which goes first.
  val gpuMig = Wire(new MigUi)
  withClockAndReset(dram.user_clk, dram.user_rst) {
    val arb  = Module(new MigUiArbiter)
    val scan = Module(new MigScanout(scanoutDesc))
    arb.io.a <> gpuMig
    arb.io.b <> scan.io.mig
    scan.io.pixClock := clkGen.io.clk125
    scan.io.pixReset := resetIn(clkGen.io.clk125, !locked)
    val ui = Module(new LiteDramMigUi)
    ui.io.ui <> arb.io.mig
    ui.io.initDone := dram.init_done
    dram.connect(ui.io.dram)

    withClockAndReset(clkGen.io.clk125, scan.io.pixReset) {
      val outs = (0 until 4).map { i =>
        val ser = Module(new TmdsSerializer)
        ser.io.en   := scan.io.tick
        ser.io.tmds := scan.io.tmds(i)
        val buf = Module(new OBUFDS)
        buf.I := ser.io.out
        buf
      }
      hdmi_tx_p     := Cat(outs(2).O, outs(1).O, outs(0).O)
      hdmi_tx_n     := Cat(outs(2).OB, outs(1).OB, outs(0).OB)
      hdmi_tx_clk_p := outs(3).O
      hdmi_tx_clk_n := outs(3).OB
    }
  }

}
