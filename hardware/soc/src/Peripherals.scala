// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.{Borg, BorgConfig, GpuMemIO}
import hutt.HuttBus

/** User peripheral address decode constants.
  *
  * Within the 12-bit user-peripheral address window, bits 11:10 select the
  * peripheral and bits 9:0 are the sub-address within it.
  */
private[soc] object PeriphDecode {
  val USER_PERI_NONE = 0
  val USER_PERI_GPIO = 1
  val USER_PERI_UART = 2
  val USER_PERI_BORG = 3

  val USER_PERI_SEL_HI   = 11
  val USER_PERI_SEL_LO   = 10
  val USER_SUB_ADDR_HI   = 9
  val USER_SUB_ADDR_LO   = 0

  // GPIO non-standard address bit decoding (unchanged from original SoC design).
  val GPIO_FUNC_SEL_BIT    = 5
  val GPIO_FUNC_SEL_IDX_HI = 4
  val GPIO_FUNC_SEL_IDX_LO = 2
  val GPIO_OUT_OFFSET       = 0
  val GPIO_IN_OFFSET        = 4

  def userPeriU(idx: Int): UInt = idx.U(2.W)
}

class PeripheralsIO(val CLOCK_MHZ: Int) extends Bundle {
  val ui_in  = Input(UInt(8.W))
  val uo_out = Output(UInt(8.W))

  /** CPU MMIO port — 12-bit byte address, Decoupled req/resp. */
  val mmio = Flipped(new HuttBus(12))

  // GPU read port — Borg.scanout etc. (Step 19.2: Borg → MemoryController)
  val gpuMem = new GpuMemIO
}

/** Routes the CPU MMIO bus to GPIO / UART / Borg based on addr[11:10].
  *
  * The protocol is single-outstanding: at most one request is in flight to
  * one peripheral at a time.  `active` tracks which sub-bus owns the
  * pending response so the resp mux can route it back to the CPU.
  */
class Peripherals(val CLOCK_MHZ: Int, val borgCfg: BorgConfig = BorgConfig.Sim) extends Module {
  val io = IO(new PeripheralsIO(CLOCK_MHZ))

  import PeriphDecode._
  val PERI_GPIO = userPeriU(USER_PERI_GPIO)
  val PERI_UART = userPeriU(USER_PERI_UART)
  val PERI_BORG = userPeriU(USER_PERI_BORG)
  val PERI_NONE = userPeriU(USER_PERI_NONE)

  // -- Submodules ------------------------------------------------------------
  val i_uart = Module(new peri.uart.PeriUart(CLOCK_MHZ))
  val borg   = Module(new Borg(borgCfg))

  // -- Address decode --------------------------------------------------------
  val reqBits  = io.mmio.req.bits
  val peri_sel = reqBits.addr(USER_PERI_SEL_HI, USER_PERI_SEL_LO)
  val sub_addr = reqBits.addr(USER_SUB_ADDR_HI, USER_SUB_ADDR_LO)
  val is_gpio  = peri_sel === PERI_GPIO
  val is_uart  = peri_sel === PERI_UART
  val is_borg  = peri_sel === PERI_BORG

  // -- Active-target latch (which peripheral owns the in-flight resp) -------
  val active        = RegInit(PERI_NONE)
  val activePending = RegInit(false.B)

  // -- GPIO inline registers -------------------------------------------------
  val gpio_out_reg  = RegInit(0.U(8.W))
  val func_sel_reg  = RegInit(VecInit(
    PERI_UART.pad(6), PERI_UART.pad(6),
    PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6),
    PERI_GPIO.pad(6), PERI_GPIO.pad(6), PERI_GPIO.pad(6)
  ))
  val gpio_resp_data = RegInit(0.U(32.W))
  // GPIO responds the cycle after req.fire (matches UART/Borg single-cycle lat).
  val gpioRespPending = RegInit(false.B)

  // -- Sub-bus wiring -------------------------------------------------------
  i_uart.io.ui_in              := io.ui_in
  i_uart.io.mmio.req.valid     := io.mmio.req.valid && is_uart
  i_uart.io.mmio.req.bits.addr := sub_addr(5, 0)
  i_uart.io.mmio.req.bits.data := reqBits.data
  i_uart.io.mmio.req.bits.write := reqBits.write
  i_uart.io.mmio.req.bits.size := reqBits.size
  i_uart.io.mmio.resp.ready    := io.mmio.resp.ready && (active === PERI_UART)

  borg.io.mmio.req.valid       := io.mmio.req.valid && is_borg
  borg.io.mmio.req.bits.addr   := sub_addr(9, 0)
  borg.io.mmio.req.bits.data   := reqBits.data
  borg.io.mmio.req.bits.write  := reqBits.write
  borg.io.mmio.req.bits.size   := reqBits.size
  borg.io.mmio.resp.ready      := io.mmio.resp.ready && (active === PERI_BORG)
  borg.io.gpuMem               <> io.gpuMem

  // -- req.ready muxing ------------------------------------------------------
  // Don't accept a new request while one is in flight (single-outstanding).
  val canAccept = !activePending
  io.mmio.req.ready := canAccept && MuxCase(true.B, Seq(
    is_uart -> i_uart.io.mmio.req.ready,
    is_borg -> borg.io.mmio.req.ready,
    is_gpio -> true.B   // GPIO always ready when not pending
  ))

  // -- GPIO read/write -------------------------------------------------------
  val isOutAddr = sub_addr === GPIO_OUT_OFFSET.U
  val isInAddr  = sub_addr === GPIO_IN_OFFSET.U
  val isFuncSel = sub_addr(GPIO_FUNC_SEL_BIT) === 1.U && sub_addr(1, 0) === 0.U
  val funcSelIdx = sub_addr(GPIO_FUNC_SEL_IDX_HI, GPIO_FUNC_SEL_IDX_LO)

  when(io.mmio.req.fire && is_gpio) {
    when(reqBits.write) {
      when(isOutAddr)  { gpio_out_reg := reqBits.data(7, 0) }
      when(isFuncSel)  { func_sel_reg(funcSelIdx) := reqBits.data(5, 0) }
    }
    // Capture read data combinationally for the response.
    gpio_resp_data := MuxCase(0.U, Seq(
      isOutAddr -> Cat(0.U(24.W), gpio_out_reg),
      isInAddr  -> Cat(0.U(24.W), io.ui_in)
    ))
  }

  // -- Activate / deactivate the in-flight slot -----------------------------
  when(io.mmio.req.fire) {
    active        := peri_sel
    activePending := true.B
    when(is_gpio) { gpioRespPending := true.B }
  }
  when(io.mmio.resp.fire) {
    activePending  := false.B
    gpioRespPending := false.B
  }

  // -- Response mux ---------------------------------------------------------
  io.mmio.resp.valid := MuxCase(false.B, Seq(
    (active === PERI_UART) -> i_uart.io.mmio.resp.valid,
    (active === PERI_BORG) -> borg.io.mmio.resp.valid,
    (active === PERI_GPIO) -> gpioRespPending
  ))
  io.mmio.resp.bits := MuxCase(0.U, Seq(
    (active === PERI_UART) -> i_uart.io.mmio.resp.bits,
    (active === PERI_BORG) -> borg.io.mmio.resp.bits,
    (active === PERI_GPIO) -> gpio_resp_data
  ))

  // -- uo_out muxing (per-pin function select) ------------------------------
  val uo_out_uart = i_uart.io.uo_out
  val uo_out_borg = borg.io.uo_out
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
