// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.{Borg, BorgConfig, BorgMmioIf, GpuMemIO}
import borg.link.{BorgLinkMaster, LinkParams, LinkPins}
import hutt.HuttBus

/** User window decode: addr[11:10] selects the peripheral, addr[9:0] is the
  * sub-address. */
private[soc] object PeriphDecode {
  val USER_PERI_NONE = 0
  val USER_PERI_GPIO = 1
  val USER_PERI_UART = 2
  val USER_PERI_BORG = 3

  val USER_PERI_SEL_HI   = 11
  val USER_PERI_SEL_LO   = 10
  val USER_SUB_ADDR_HI   = 9
  val USER_SUB_ADDR_LO   = 0

  val GPIO_FUNC_SEL_BIT    = 5
  val GPIO_FUNC_SEL_IDX_HI = 4
  val GPIO_FUNC_SEL_IDX_LO = 2
  val GPIO_OUT_OFFSET       = 0
  val GPIO_IN_OFFSET        = 4

  def userPeriU(idx: Int): UInt = idx.U(2.W)
}

/** The BorgLinkMaster's pins, routed by the top-level to pads or a loopback. */
class BorgLinkPortsIO(val p: LinkParams) extends Bundle {
  val dnPins = Output(new LinkPins(p.w))
  val upPins = Input(new LinkPins(p.w))
  val dnCred = Input(Bool())
  val upCred = Output(Bool())
  val linkFast  = Input(Bool())
  // Ignored unless the build is narrowCapable.
  val linkNarrow = Input(Bool())
  val farLinkUp = Input(Bool())
  val linkUp  = Output(Bool())
  val linkErr = Output(Bool())
}

/** Rung B ([[soc.BorgPadLoop]]): both link ends are on this FPGA, so each wire
  * has a driving and a receiving pad, bridged by a ribbon cable. */
class BorgPadLoopIO(val p: LinkParams) extends Bundle {
  // master -> pads -> cable -> pads -> slave
  val dnOut     = Output(new LinkPins(p.w))
  val dnIn      = Input(new LinkPins(p.w))
  val upCredOut = Output(Bool())
  val upCredIn  = Input(Bool())
  // slave -> pads -> cable -> pads -> master
  val upOut     = Output(new LinkPins(p.w))
  val upIn      = Input(new LinkPins(p.w))
  val dnCredOut = Output(Bool())
  val dnCredIn  = Input(Bool())
  // The slave's linkUp, never the master's (see wireBorgPadLoop).
  val linkUpOut = Output(Bool())
  val linkUpIn  = Input(Bool())
}

class PeripheralsIO(
    val CLOCK_MHZ: Int,
    val borgMode: BorgMode = BorgDirect,
    val linkParams: LinkParams = LinkParams()
) extends Bundle {
  val ui_in  = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))

  val mmio = Flipped(new HuttBus(12))

  val gpuMem = new GpuMemIO

  val link: Option[BorgLinkPortsIO] =
    if (borgMode != BorgDirect) Some(new BorgLinkPortsIO(linkParams)) else None
}

/** Routes the CPU MMIO bus to GPIO, UART or Borg, one request in flight. */
class Peripherals(
    val CLOCK_MHZ: Int,
    val borgCfg: BorgConfig = BorgConfig.Default,
    val borgMode: BorgMode = BorgDirect,
    val linkParams: LinkParams = LinkParams()
) extends Module {
  val io = IO(new PeripheralsIO(CLOCK_MHZ, borgMode, linkParams))

  import PeriphDecode._
  val PERI_GPIO = userPeriU(USER_PERI_GPIO)
  val PERI_UART = userPeriU(USER_PERI_UART)
  val PERI_BORG = userPeriU(USER_PERI_BORG)
  val PERI_NONE = userPeriU(USER_PERI_NONE)

  val i_uart = Module(new peri.uart.PeriUart(CLOCK_MHZ))

  // BorgLinkMaster presents Borg's own interface, so borgIf wires either.
  val borgOpt: Option[Borg] =
    if (borgMode == BorgDirect) Some(Module(new Borg(borgCfg))) else None
  val linkOpt: Option[BorgLinkMaster] =
    if (borgMode != BorgDirect) Some(Module(new BorgLinkMaster(linkParams))) else None
  val borgIf: BorgMmioIf = borgOpt.map(_.io: BorgMmioIf).getOrElse(linkOpt.get.io: BorgMmioIf)

  io.link.foreach { linkIo =>
    val m = linkOpt.get.io
    linkIo.dnPins  := m.dnPins
    m.upPins       := linkIo.upPins
    linkIo.upCred  := m.upCred
    m.dnCred       := linkIo.dnCred
    m.linkFast     := linkIo.linkFast
    m.narrow       := linkIo.linkNarrow
    m.farLinkUp    := linkIo.farLinkUp
    linkIo.linkUp  := m.linkUp
    linkIo.linkErr := m.linkErr
  }

  val reqBits  = io.mmio.req.bits
  val peri_sel = reqBits.addr(USER_PERI_SEL_HI, USER_PERI_SEL_LO)
  val sub_addr = reqBits.addr(USER_SUB_ADDR_HI, USER_SUB_ADDR_LO)
  val is_gpio  = peri_sel === PERI_GPIO
  val is_uart  = peri_sel === PERI_UART
  val is_borg  = peri_sel === PERI_BORG

  // Which peripheral owns the in-flight response.
  val active        = RegInit(PERI_NONE)
  val activePending = RegInit(false.B)

  val gpio_out_reg  = RegInit(0.U(8.W))
  val func_sel_reg  = RegInit(VecInit(
    PERI_UART.pad(6), PERI_UART.pad(6),
    PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6),
    PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6)
  ))
  val gpio_resp_data = RegInit(0.U(32.W))
  val gpioRespPending = RegInit(false.B)

  i_uart.io.ui_in              := io.ui_in
  i_uart.io.mmio.req.valid     := io.mmio.req.valid && is_uart
  i_uart.io.mmio.req.bits.addr := sub_addr(5, 0)
  i_uart.io.mmio.req.bits.data := reqBits.data
  i_uart.io.mmio.req.bits.write := reqBits.write
  i_uart.io.mmio.req.bits.size := reqBits.size
  i_uart.io.mmio.resp.ready    := io.mmio.resp.ready && (active === PERI_UART)

  borgIf.mmio.req.valid       := io.mmio.req.valid && is_borg
  borgIf.mmio.req.bits.addr   := sub_addr(9, 0)
  borgIf.mmio.req.bits.data   := reqBits.data
  borgIf.mmio.req.bits.write  := reqBits.write
  borgIf.mmio.req.bits.size   := reqBits.size
  borgIf.mmio.resp.ready      := io.mmio.resp.ready && (active === PERI_BORG)
  borgIf.gpuMem               <> io.gpuMem

  val canAccept = !activePending
  io.mmio.req.ready := canAccept && MuxCase(true.B, Seq(
    is_uart -> i_uart.io.mmio.req.ready,
    is_borg -> borgIf.mmio.req.ready,
    is_gpio -> true.B
  ))

  val isOutAddr = sub_addr === GPIO_OUT_OFFSET.U
  val isInAddr  = sub_addr === GPIO_IN_OFFSET.U
  val isFuncSel = sub_addr(GPIO_FUNC_SEL_BIT) === 1.U && sub_addr(1, 0) === 0.U
  val funcSelIdx = sub_addr(GPIO_FUNC_SEL_IDX_HI, GPIO_FUNC_SEL_IDX_LO)

  when(io.mmio.req.fire && is_gpio) {
    when(reqBits.write) {
      when(isOutAddr)  { gpio_out_reg := reqBits.data(7, 0) }
      when(isFuncSel)  { func_sel_reg(funcSelIdx) := reqBits.data(5, 0) }
    }
    gpio_resp_data := MuxCase(0.U, Seq(
      isOutAddr -> Cat(0.U(24.W), gpio_out_reg),
      isInAddr  -> Cat(0.U(24.W), io.ui_in)
    ))
  }

  when(io.mmio.req.fire) {
    active        := peri_sel
    activePending := true.B
    when(is_gpio) { gpioRespPending := true.B }
  }
  when(io.mmio.resp.fire) {
    activePending  := false.B
    gpioRespPending := false.B
  }

  io.mmio.resp.valid := MuxCase(false.B, Seq(
    (active === PERI_UART) -> i_uart.io.mmio.resp.valid,
    (active === PERI_BORG) -> borgIf.mmio.resp.valid,
    (active === PERI_GPIO) -> gpioRespPending
  ))
  io.mmio.resp.bits := MuxCase(0.U, Seq(
    (active === PERI_UART) -> i_uart.io.mmio.resp.bits,
    (active === PERI_BORG) -> borgIf.mmio.resp.bits,
    (active === PERI_GPIO) -> gpio_resp_data
  ))

  // Per-pin function select; Borg drives no pins, so its lanes read 0.
  val uo_out_uart = i_uart.io.uo_out
  val uo_out_borg = 0.U(8.W)
  val uo_out_muxed = Wire(Vec(8, Bool()))
  for (k <- 0 until 8) {
    when(func_sel_reg(k) === PERI_UART) {
      uo_out_muxed(k) := uo_out_uart(k)
    } .elsewhen(func_sel_reg(k) === PERI_BORG) {
      uo_out_muxed(k) := uo_out_borg(k)
    } .otherwise {
      uo_out_muxed(k) := gpio_out_reg(k)
    }
  }
  io.uo_out := uo_out_muxed.asUInt

}
