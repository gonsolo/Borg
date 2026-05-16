// SPDX-FileCopyrightText: © 2024 Michael Bell
// SPDX-License-Identifier: CERN-OHL-S-2.0
package soc

import chisel3._
import chisel3.util._
import tinyqv.cpu.{TinyQVIO, TinyQV}
import memory.{MemoryController, MemoryControllerIO, QspiPinsIO}
import borg.BorgConfig


// ---------------------------------------------------------------------------
// TinyQV bus decoder constants — inlined from the former MmioMap.scala.
// These are Michael Bell's foundational address-decode choices and cannot
// be expressed in SystemRDL.
// ---------------------------------------------------------------------------
private[soc] object SoCDecode {
  // Magic comparison values for SoC vs User region detection
  private val SOC_REGION_ID  = 0x800000  // Cat(addr[27:6], addr[1:0])
  private val USER_REGION_ID = 0x8000    // addr[27:12]

  case class AddrRegion(matchFn: UInt => Bool, indexHi: Int, indexLo: Int) {
    def matches(addr: UInt): Bool = matchFn(addr)
    def index(addr: UInt): UInt = addr(indexHi, indexLo)
  }

  val socRegion = AddrRegion(
    matchFn = addr => Cat(addr(27, 6), addr(1, 0)) === SOC_REGION_ID.U,
    indexHi = 5, indexLo = 2
  )
  val userRegion = AddrRegion(
    matchFn = addr => addr(27, 12) === USER_REGION_ID.U,
    indexHi = 11, indexLo = 0
  )

  // SoC peripheral indices (addr[5:2])
  val PERI_NONE              = 0x0
  val PERI_ID                = 0x2
  val PERI_GPIO_OUT_SEL      = 0x3
  val PERI_DEBUG_UART        = 0x6
  val PERI_DEBUG_UART_STATUS = 0x7
  val PERI_DEBUG_UART_BAUD   = 0x8
  val PERI_TIME_LIMIT        = 0xB
  val PERI_DEBUG             = 0xC
  val PERI_USER              = 0xF

  def socPeriU(idx: Int): UInt = idx.U(4.W)
}

// CpuExtModule wraps the Chisel-generated TinyQV Verilog for SoC integration
class CpuExtModule extends ExtModule(Map()) {
  override val desiredName = "TinyQV"
  val clock = IO(Input(Clock()))
  val reset = IO(Input(Reset()))
  val io = FlatIO(new TinyQVIO)
}

/** Platform-independent SoC backbone shared by all target top-level modules.
  *
  * Wires together the three peer components:
  *   - [[TinyQV]]           — RISC-V CPU; no QSPI knowledge
  *   - [[MemoryController]] — owns SPI/QSPI pins; arbitrates CPU instr-fetch,
  *                            CPU data, and GPU read
  *   - [[Peripherals]]      — Borg GPU + UART + GPIO; no QSPI knowledge
  *
  * Target-specific top-level modules that mix in this trait live in:
  *   - [[asic.tt.tt_um_gonsolo_borg]] — Tiny Tapeout ASIC (asic/tt/)
  *   - [[soc.tinyQV_top]]             — pico-ice iCE40 FPGA (fpga/picoice/)
  *   - [[soc.ULX3S]]                  — ULX3S ECP5 FPGA   (fpga/ulx3s/)
  *
  * Each subclass must implement the abstract members: soc_clk, soc_rst_n,
  * soc_rst_reg_n, soc_ui_in. Each subclass also instantiates a [[memory.QspiBackend]]
  * (or future [[memory.SdramBackend]]) and wires mem.io.backend to it.
  */
trait SoCLogic { self: RawModule =>
  def CLOCK_MHZ: Int
  def BORG_CFG: BorgConfig = BorgConfig.Sim

  // --- Abstract members provided by each top-level ---
  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt

  // --- Core and peripheral instantiation ---
  lazy val cpu = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new TinyQV())
  }
  lazy val mem = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new MemoryController())
  }
  lazy val peripherals = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new Peripherals(CLOCK_MHZ, BORG_CFG))
  }
  lazy val uartTx = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new tinyqv.peri.uart.UartTx(13))
  }



  /** Wire the GPU memory port.  Default: direct Borg↔MemoryController. */
  def wireGpuMem(): Unit = {
    mem.io.gpuMem <> peripherals.io.gpuMem
  }

  /** Wire up the entire SoC. Call this from the top-level module body. */
  def wireSoC(): UInt = {

    // -------------------------------------------------------------------------
    // MemoryController wiring
    // -------------------------------------------------------------------------
    // io.backend must be connected by the subclass after calling wireSoC()

      // simple case: only real controller
      mem.io.instrFetch.instr_addr         := cpu.io.instr_addr
      mem.io.instrFetch.instr_fetch_restart := cpu.io.instr_fetch_restart
      mem.io.instrFetch.instr_fetch_stall   := cpu.io.instr_fetch_stall

      mem.io.cpuData <> cpu.io.memBus

      cpu.io.instr_fetch_started := mem.io.instrFetch.instr_fetch_started
      cpu.io.instr_fetch_stopped := mem.io.instrFetch.instr_fetch_stopped
      cpu.io.instr_data          := mem.io.instrFetch.instr_data
      cpu.io.instr_ready         := mem.io.instrFetch.instr_ready

      // gpu read port — overridable so ULX3S can mux with HDMI scanout
      wireGpuMem()

    // -------------------------------------------------------------------------
    // mmio peripheral bus (unchanged from before)
    // -------------------------------------------------------------------------
    val addr            = cpu.io.data_addr
    val write_n         = cpu.io.data_write_n
    val read_n          = cpu.io.data_read_n
    val read_complete   = cpu.io.data_read_complete
    val data_to_write   = cpu.io.data_out

    val data_ready      = Wire(Bool())
    val data_from_read  = WireDefault(0.U(32.W))

    cpu.io.data_ready := data_ready
    cpu.io.data_in    := data_from_read

    val peri_out         = peripherals.io.uo_out
    val peri_data_out    = peripherals.io.data_out
    val peri_data_ready  = peripherals.io.data_ready
    val peri_interrupts  = peripherals.io.user_interrupts

    // peripherals get synchronized ui_in
    val ui_in_sync0 = withClockAndReset(soc_clk, false.B) { RegNext(soc_ui_in) }
    val ui_in_sync  = withClockAndReset(soc_clk, false.B) { RegNext(ui_in_sync0) }

    val interrupt_req = Cat(peri_interrupts, ui_in_sync(1, 0))
    cpu.io.interrupt_req := interrupt_req

    val time_pulse = Wire(Bool())
    cpu.io.time_pulse := time_pulse

    peripherals.io.ui_in             := ui_in_sync
    peripherals.io.addr_in           := addr(11, 0)
    peripherals.io.data_in           := data_to_write
    peripherals.io.data_write_n      := write_n
    peripherals.io.data_read_n       := read_n
    peripherals.io.data_read_complete := read_complete

    import SoCDecode._
    val connect_peripheral = WireDefault(socPeriU(PERI_NONE))

    when(SoCDecode.socRegion.matches(addr)) {
      connect_peripheral := SoCDecode.socRegion.index(addr)
    } .elsewhen(SoCDecode.userRegion.matches(addr)) {
      connect_peripheral := socPeriU(PERI_USER)
    }

    val gpio_out_sel = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(Cat(!soc_ui_in(0), 0.U(1.W)))
    }
    val time_limit = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(math.max((CLOCK_MHZ / 4) - 1, 1).U(5.W))
    }

    val default_baud_divider = ((CLOCK_MHZ * 1000000) / 115200).U(13.W)
    val debug_baud_divider = withClockAndReset(soc_clk, !soc_rst_reg_n) {
      RegInit(default_baud_divider)
    }

    withClockAndReset(soc_clk, false.B) {
      when(write_n =/= 3.U(2.W)) {
        when(connect_peripheral === socPeriU(PERI_GPIO_OUT_SEL)) {
          gpio_out_sel := data_to_write(7, 6)
        }
        when(connect_peripheral === socPeriU(PERI_TIME_LIMIT)) {
          time_limit := data_to_write(6, 2)
        }
        when(connect_peripheral === socPeriU(PERI_DEBUG_UART_BAUD)) {
          debug_baud_divider := data_to_write(12, 0)
        }
      }
    }

    val debug_uart_tx_busy = uartTx.io.uart_tx_busy

    data_from_read := "hffffffff".U(32.W)
    switch(connect_peripheral) {
      is(socPeriU(PERI_ID))             { data_from_read := 0x41.U(32.W) }
      is(socPeriU(PERI_GPIO_OUT_SEL))   { data_from_read := Cat(0.U(24.W), gpio_out_sel, 0.U(6.W)) }
      is(socPeriU(PERI_DEBUG_UART_STATUS)) { data_from_read := Cat(0.U(31.W), debug_uart_tx_busy) }
      is(socPeriU(PERI_DEBUG_UART_BAUD))   { data_from_read := Cat(0.U(19.W), debug_baud_divider) }
      is(socPeriU(PERI_TIME_LIMIT))     { data_from_read := Cat(0.U(25.W), time_limit, 3.U(2.W)) }
      is(socPeriU(PERI_USER))           { data_from_read := peri_data_out }
    }

    data_ready := Mux(connect_peripheral === socPeriU(PERI_USER), peri_data_ready, 1.U(1.W))

    val debug_uart_tx_start = (write_n =/= 3.U(2.W)) &&
                              (connect_peripheral === socPeriU(PERI_DEBUG_UART))

    val debug_uart_txd = uartTx.io.uart_txd
    uartTx.io.uart_tx_en   := debug_uart_tx_start
    uartTx.io.uart_tx_data := data_to_write(7, 0)
    uartTx.io.baud_divider := debug_baud_divider

    val time_count = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(0.U(7.W)) }
    withClockAndReset(soc_clk, false.B) {
      when(time_pulse) {
        time_count := 0.U
      } .otherwise {
        time_count := time_count + 1.U
      }
    }
    time_pulse := (time_count === Cat(time_limit, 3.U(2.W)))

    val debug_register_data = withClockAndReset(soc_clk, !soc_rst_reg_n) { RegInit(soc_ui_in(1)) }
    withClockAndReset(soc_clk, false.B) {
      when(write_n =/= 3.U(2.W) && connect_peripheral === socPeriU(PERI_DEBUG)) {
        debug_register_data := data_to_write(0)
      }
    }

    val debug_rd_r = withClockAndReset(soc_clk, false.B) { RegNext(cpu.io.debug_rd) }

    val debug_signals = Cat(
      cpu.io.debug_instr_complete,
      cpu.io.debug_instr_ready,
      cpu.io.debug_instr_valid,
      cpu.io.debug_fetch_restart,
      read_n =/= 3.U(2.W),
      write_n =/= 3.U(2.W),
      cpu.io.debug_data_ready,
      cpu.io.debug_interrupt_pending,
      cpu.io.debug_branch,
      cpu.io.debug_early_branch,
      cpu.io.debug_ret,
      cpu.io.debug_reg_wen,
      cpu.io.debug_counter_0,
      cpu.io.debug_data_continue,
      mem.io.debug_stall_txn,  // now from memorycontroller, not tinyqv
      mem.io.debug_stop_txn
    )

    val debug_signal = debug_signals(soc_ui_in(6, 3))

    // build uo_out value
    val uo_out_val = Cat(
      Mux(gpio_out_sel(1), peri_out(7), debug_signal),
      Mux(gpio_out_sel(0), peri_out(6), debug_uart_txd),
      Mux(debug_register_data, debug_rd_r(3), peri_out(5)),
      Mux(debug_register_data, debug_rd_r(2), peri_out(4)),
      Mux(debug_register_data, debug_rd_r(1), peri_out(3)),
      Mux(debug_register_data, debug_rd_r(0), peri_out(2)),
      peri_out(1),
      peri_out(0)
    )

    // return read_complete for the unused signal xor in tt top-level
    // (pico-ice doesn't need it)
    uo_out_val
  }
}

