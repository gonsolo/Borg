// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}
import _root_.circt.stage.ChiselStage
import borg.{Borg, BorgConfig}
import soc.{AsyncFifo, Emit, GpuMemArbiter, GpuMemMig, HostLink, MigScanout, MigUiArbiter, MigUi, UsbFifo}

class IOBUF extends ExtModule {
  val IO = chisel3.IO(Analog(1.W))
  val I  = chisel3.IO(Input(Bool()))
  val O  = chisel3.IO(Output(Bool()))
  val T  = chisel3.IO(Input(Bool()))
}

/** Borg on the Nexys Video as the host's device (`borg_ftdi`, BORG_HW_RAW): the direct simulator's
  * protocol over the FT2232H's synchronous FIFO, memory on the DDR3, the presented picture on HDMI.
  * No CPU.
  */
class NexysLinkTop extends RawModule {
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

  val ddr3_dq      = IO(Analog(16.W))
  val ddr3_dqs_p   = IO(Analog(2.W))
  val ddr3_dqs_n   = IO(Analog(2.W))
  val ddr3_addr    = IO(Output(UInt(15.W)))
  val ddr3_ba      = IO(Output(UInt(3.W)))
  val ddr3_ras_n   = IO(Output(Bool()))
  val ddr3_cas_n   = IO(Output(Bool()))
  val ddr3_we_n    = IO(Output(Bool()))
  val ddr3_reset_n = IO(Output(Bool()))
  val ddr3_ck_p    = IO(Output(UInt(1.W)))
  val ddr3_ck_n    = IO(Output(UInt(1.W)))
  val ddr3_cke     = IO(Output(UInt(1.W)))
  val ddr3_dm      = IO(Output(UInt(2.W)))
  val ddr3_odt     = IO(Output(UInt(1.W)))

  val clkGen = Module(new Clocks)
  clkGen.io.clk100 := clk
  clkGen.io.rst    := !cpu_resetn
  val locked = clkGen.io.locked

  val mig = Module(new mig_7series_0)
  mig.sys_clk_i   := clkGen.io.clk200
  mig.sys_rst     := locked
  mig.app_sr_req  := false.B
  mig.app_ref_req := false.B
  mig.app_zq_req  := false.B
  ddr3_addr    := mig.ddr3_addr
  ddr3_ba      := mig.ddr3_ba
  ddr3_ras_n   := mig.ddr3_ras_n
  ddr3_cas_n   := mig.ddr3_cas_n
  ddr3_we_n    := mig.ddr3_we_n
  ddr3_reset_n := mig.ddr3_reset_n
  ddr3_ck_p    := mig.ddr3_ck_p
  ddr3_ck_n    := mig.ddr3_ck_n
  ddr3_cke     := mig.ddr3_cke
  ddr3_dm      := mig.ddr3_dm
  ddr3_odt     := mig.ddr3_odt
  attach(ddr3_dq, mig.ddr3_dq)
  attach(ddr3_dqs_p, mig.ddr3_dqs_p)
  attach(ddr3_dqs_n, mig.ddr3_dqs_n)

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
  val borgReset = resetIn(clkGen.io.clk25, !locked || usbLost)

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

  // The MIG serves Borg and the scan-out, which goes first.
  val gpuMig = Wire(new MigUi)
  withClockAndReset(mig.ui_clk, mig.ui_clk_sync_rst) {
    val arb  = Module(new MigUiArbiter)
    val scan = Module(new MigScanout(0x8000000))   // BORG_SCANOUT_SPI in hardware/rdl/layout.txt
    arb.io.a <> gpuMig
    arb.io.b <> scan.io.mig
    scan.io.pixClock := clkGen.io.clk125
    scan.io.pixReset := resetIn(clkGen.io.clk125, !locked)
    val m = arb.io.mig
    mig.app_addr     := m.app_addr
    mig.app_cmd      := m.app_cmd
    mig.app_en       := m.app_en
    mig.app_wdf_data := m.app_wdf_data
    mig.app_wdf_mask := m.app_wdf_mask
    mig.app_wdf_wren := m.app_wdf_wren
    mig.app_wdf_end  := m.app_wdf_end
    m.app_rdy             := mig.app_rdy
    m.app_wdf_rdy         := mig.app_wdf_rdy
    m.app_rd_data         := mig.app_rd_data
    m.app_rd_data_valid   := mig.app_rd_data_valid
    m.init_calib_complete := mig.init_calib_complete

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

  rx.deqClock := clkGen.io.clk25; rx.deqReset := borgReset
  tx.enqClock := clkGen.io.clk25; tx.enqReset := borgReset
  withClockAndReset(clkGen.io.clk25, borgReset) {
    val b    = Module(new Borg(BorgConfig.Default))
    val link = Module(new HostLink)
    val arb  = Module(new GpuMemArbiter(2))
    link.io.mmio <> b.io.mmio
    arb.io.in(0) <> b.io.gpuMem
    arb.io.in(1) <> link.io.mem
    link.io.in <> rx.deq
    tx.enq.valid      := link.io.out.valid
    tx.enq.bits       := Cat(link.io.outEnd, link.io.out.bits)
    link.io.out.ready := tx.enq.ready

    val ddr = Module(new GpuMemMig)
    ddr.io.gpu <> arb.io.out
    ddr.io.uiClock := mig.ui_clk
    ddr.io.uiReset := mig.ui_clk_sync_rst || resetIn(mig.ui_clk, usbLost)
    gpuMig <> ddr.io.mig

    val down = Counter(link.io.in.fire, 1 << 12)._1
    val up   = Counter(link.io.out.fire, 1 << 12)._1
    led := Cat(up(11), down(11), locked, RegNext(RegNext(mig.init_calib_complete)))
  }
}

object NexysLinkTopMain extends App {
  val targetDir = "out/nexysvideo_link/verilog"
  Emit.cleanTargetDir(targetDir)
  ChiselStage.emitSystemVerilogFile(
    gen = new NexysLinkTop,
    args = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
