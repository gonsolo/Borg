// SPDX-FileCopyrightText: © 2024 Michael Bell
// SPDX-License-Identifier: CERN-OHL-S-2.0
package soc

import chisel3._
import chisel3.util._
import tinyqv.cpu.{tinyQVIO, TinyQV}


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

// tinyQV ExtModule wraps the Chisel-generated TinyQV Verilog for SoC integration
class tinyQV_ExtModule extends ExtModule(Map()) {
  override val desiredName = "TinyQV"
  val clock = IO(Input(Clock()))
  val reset = IO(Input(Reset()))
  val io = FlatIO(new tinyQVIO)
}

/** Common SoC logic shared between TT ASIC and pico-ice FPGA top-level modules.
  *
  * Architecture: three peer components wired by this trait:
  *   - [[TinyQV]]           — pure CPU; no QSPI knowledge
  *   - [[MemoryController]] — owns SPI/QSPI pins; arbitrates CPU instr-fetch,
  *                            CPU data, and GPU read (Step 19.2)
  *   - [[tinyQV_peripherals]] (Borg GPU + UART + GPIO) — no QSPI knowledge
  *
  * Subclasses must provide: soc_clk, soc_rst_n, soc_rst_reg_n, soc_ui_in,
  * soc_qspi_data_in. The trait provides: QSPI outputs, uo_out value, and all
  * internal SoC wiring.
  *
  * When SIM_FAST_MEM = true, a [[MemoryControllerSim]] is also instantiated and
  * its instruction-fetch outputs are MUXed in based on soc_ui_in(7) at runtime.
  */
trait SoCLogic { self: RawModule =>
  def CLOCK_MHZ: Int
  def SIM_FAST_MEM: Boolean = false

  // --- Abstract members provided by each top-level ---
  def soc_clk: Clock
  def soc_rst_n: Bool
  def soc_rst_reg_n: Bool
  def soc_ui_in: UInt
  def soc_qspi_data_in: UInt

  // --- Core and peripheral instantiation ---
  lazy val i_tinyqv = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new TinyQV())
  }
  lazy val i_memReal = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new MemoryController())
  }
  lazy val i_peripherals = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new tinyQV_peripherals(CLOCK_MHZ))
  }
  lazy val i_debug_uart_tx = withClockAndReset(soc_clk, !soc_rst_reg_n) {
    Module(new tinyqv.peri.uart.UartTx(13))
  }

  // --- QSPI outputs — sourced from MemoryController, not TinyQV ---
  lazy val qspi_data_out:     UInt = i_memReal.io.spi_data_out
  lazy val qspi_data_oe:      UInt = i_memReal.io.spi_data_oe
  lazy val qspi_clk_out:      Bool = i_memReal.io.spi_clk_out
  lazy val qspi_flash_select: Bool = i_memReal.io.spi_flash_select
  lazy val qspi_ram_a_select: Bool = i_memReal.io.spi_ram_a_select
  lazy val qspi_ram_b_select: Bool = i_memReal.io.spi_ram_b_select

  /** Wire up the entire SoC. Call this from the top-level module body. */
  def wireSoC(): UInt = {

    // -------------------------------------------------------------------------
    // MemoryController wiring
    // Real controller always present; sim controller optional (SIM_FAST_MEM).
    // -------------------------------------------------------------------------
    i_memReal.io.spi_data_in := soc_qspi_data_in

    // GPU read port — wired from peripherals (Step 19.2)
    i_memReal.io.gpu_addr     := i_peripherals.io.gpu_addr
    i_memReal.io.gpu_read_req := i_peripherals.io.gpu_read_req

    if (SIM_FAST_MEM) {
      val memSim = withClockAndReset(soc_clk, !soc_rst_reg_n) {
        Module(new MemoryControllerSim())
      }
      memSim.io.spi_data_in := soc_qspi_data_in
      memSim.io.gpu_addr     := i_peripherals.io.gpu_addr
      memSim.io.gpu_read_req := i_peripherals.io.gpu_read_req

      val fast_sim_en = soc_ui_in(7)

      // Both controllers receive all CPU signals so sim_psram_ext stays in sync
      Seq(i_memReal.io, memSim.io).foreach { m =>
        m.instrFetch.instr_addr         := i_tinyqv.io.instr_addr
        m.instrFetch.instr_fetch_restart := i_tinyqv.io.instr_fetch_restart
        m.instrFetch.instr_fetch_stall   := i_tinyqv.io.instr_fetch_stall
        m.cpu_addr          := i_tinyqv.io.mem_addr
        m.cpu_write_n       := i_tinyqv.io.mem_write_n
        m.cpu_read_n        := i_tinyqv.io.mem_read_n
        m.cpu_data_out      := i_tinyqv.io.mem_data_out
        m.cpu_data_continue := i_tinyqv.io.mem_data_continue
      }

      // Instruction fetch outputs MUXed: sim (fast) vs real (QSPI)
      i_tinyqv.io.instr_fetch_started :=
        Mux(fast_sim_en, memSim.io.instrFetch.instr_fetch_started,
                         i_memReal.io.instrFetch.instr_fetch_started)
      i_tinyqv.io.instr_fetch_stopped :=
        Mux(fast_sim_en, memSim.io.instrFetch.instr_fetch_stopped,
                         i_memReal.io.instrFetch.instr_fetch_stopped)
      i_tinyqv.io.instr_data :=
        Mux(fast_sim_en, memSim.io.instrFetch.instr_data,
                         i_memReal.io.instrFetch.instr_data)
      i_tinyqv.io.instr_ready :=
        Mux(fast_sim_en, memSim.io.instrFetch.instr_ready,
                         i_memReal.io.instrFetch.instr_ready)

      // Data always from real controller (QSPI PSRAM model)
      i_tinyqv.io.mem_ready   := i_memReal.io.cpu_ready
      i_tinyqv.io.mem_data_in := i_memReal.io.cpu_data_in

      // GPU read responses MUXed by fast_sim_en (Step 19.2)
      i_peripherals.io.gpu_data       := Mux(fast_sim_en, memSim.io.gpu_data,       i_memReal.io.gpu_data)
      i_peripherals.io.gpu_read_ready := Mux(fast_sim_en, memSim.io.gpu_read_ready, i_memReal.io.gpu_read_ready)

    } else {
      // Simple case: only real controller
      i_memReal.io.instrFetch.instr_addr         := i_tinyqv.io.instr_addr
      i_memReal.io.instrFetch.instr_fetch_restart := i_tinyqv.io.instr_fetch_restart
      i_memReal.io.instrFetch.instr_fetch_stall   := i_tinyqv.io.instr_fetch_stall
      i_memReal.io.cpu_addr          := i_tinyqv.io.mem_addr
      i_memReal.io.cpu_write_n       := i_tinyqv.io.mem_write_n
      i_memReal.io.cpu_read_n        := i_tinyqv.io.mem_read_n
      i_memReal.io.cpu_data_out      := i_tinyqv.io.mem_data_out
      i_memReal.io.cpu_data_continue := i_tinyqv.io.mem_data_continue

      i_tinyqv.io.instr_fetch_started := i_memReal.io.instrFetch.instr_fetch_started
      i_tinyqv.io.instr_fetch_stopped := i_memReal.io.instrFetch.instr_fetch_stopped
      i_tinyqv.io.instr_data          := i_memReal.io.instrFetch.instr_data
      i_tinyqv.io.instr_ready         := i_memReal.io.instrFetch.instr_ready

      i_tinyqv.io.mem_ready   := i_memReal.io.cpu_ready
      i_tinyqv.io.mem_data_in := i_memReal.io.cpu_data_in

      // GPU read responses from real controller (Step 19.2)
      i_peripherals.io.gpu_data       := i_memReal.io.gpu_data
      i_peripherals.io.gpu_read_ready := i_memReal.io.gpu_read_ready
    }

    // -------------------------------------------------------------------------
    // MMIO peripheral bus (unchanged from before)
    // -------------------------------------------------------------------------
    val addr            = i_tinyqv.io.data_addr
    val write_n         = i_tinyqv.io.data_write_n
    val read_n          = i_tinyqv.io.data_read_n
    val read_complete   = i_tinyqv.io.data_read_complete
    val data_to_write   = i_tinyqv.io.data_out

    val data_ready      = Wire(Bool())
    val data_from_read  = WireDefault(0.U(32.W))

    i_tinyqv.io.data_ready := data_ready
    i_tinyqv.io.data_in    := data_from_read

    val peri_out         = i_peripherals.io.uo_out
    val peri_data_out    = i_peripherals.io.data_out
    val peri_data_ready  = i_peripherals.io.data_ready
    val peri_interrupts  = i_peripherals.io.user_interrupts

    // Peripherals get synchronized ui_in
    val ui_in_sync0 = withClockAndReset(soc_clk, false.B) { RegNext(soc_ui_in) }
    val ui_in_sync  = withClockAndReset(soc_clk, false.B) { RegNext(ui_in_sync0) }

    val interrupt_req = Cat(peri_interrupts, ui_in_sync(1, 0))
    i_tinyqv.io.interrupt_req := interrupt_req

    val time_pulse = Wire(Bool())
    i_tinyqv.io.time_pulse := time_pulse

    i_peripherals.io.ui_in             := ui_in_sync
    i_peripherals.io.addr_in           := addr(11, 0)
    i_peripherals.io.data_in           := data_to_write
    i_peripherals.io.data_write_n      := write_n
    i_peripherals.io.data_read_n       := read_n
    i_peripherals.io.data_read_complete := read_complete

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
      RegInit(Math.max((CLOCK_MHZ / 4) - 1, 1).U(5.W))
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

    val debug_uart_tx_busy = i_debug_uart_tx.io.uart_tx_busy

    data_from_read := "hFFFFFFFF".U(32.W)
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

    val debug_uart_txd = i_debug_uart_tx.io.uart_txd
    i_debug_uart_tx.io.uart_tx_en   := debug_uart_tx_start
    i_debug_uart_tx.io.uart_tx_data := data_to_write(7, 0)
    i_debug_uart_tx.io.baud_divider := debug_baud_divider

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

    val debug_rd_r = withClockAndReset(soc_clk, false.B) { RegNext(i_tinyqv.io.debug_rd) }

    val debug_signals = Cat(
      i_tinyqv.io.debug_instr_complete,
      i_tinyqv.io.debug_instr_ready,
      i_tinyqv.io.debug_instr_valid,
      i_tinyqv.io.debug_fetch_restart,
      read_n =/= 3.U(2.W),
      write_n =/= 3.U(2.W),
      i_tinyqv.io.debug_data_ready,
      i_tinyqv.io.debug_interrupt_pending,
      i_tinyqv.io.debug_branch,
      i_tinyqv.io.debug_early_branch,
      i_tinyqv.io.debug_ret,
      i_tinyqv.io.debug_reg_wen,
      i_tinyqv.io.debug_counter_0,
      i_tinyqv.io.debug_data_continue,
      i_memReal.io.debug_stall_txn,  // now from MemoryController, not TinyQV
      i_memReal.io.debug_stop_txn
    )

    val debug_signal = debug_signals(soc_ui_in(6, 3))

    // Build uo_out value
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

    // Return read_complete for the unused signal XOR in TT top-level
    // (pico-ice doesn't need it)
    uo_out_val
  }
}


class tt_um_gonsolo_borg(val CLOCK_MHZ: Int) extends RawModule with SoCLogic {
  val ui_in  = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val uio_in  = IO(Input(UInt(8.W)))
  val uio_out = IO(Output(UInt(8.W)))
  val uio_oe  = IO(Output(UInt(8.W)))
  val ena     = IO(Input(Bool()))
  val clk     = IO(Input(Clock()))
  val rst_n   = IO(Input(Bool()))

  // Implement SoCLogic abstract members
  def soc_clk = clk
  def soc_rst_n = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset((!clk.asBool).asClock, false.B) {
    RegNext(rst_n)
  }
  def soc_ui_in = ui_in
  def soc_qspi_data_in = Cat(uio_in(5, 4), uio_in(2, 1))

  // Wire up the SoC
  val uo_out_val = wireSoC()

  // TT-specific QSPI I/O mapping — signals now sourced from MemoryController
  uio_out := Cat(
    qspi_ram_b_select,
    qspi_ram_a_select,
    qspi_data_out(3, 2),
    qspi_clk_out,
    qspi_data_out(1, 0),
    qspi_flash_select
  )
  uio_oe := Mux(
    rst_n,
    Cat(3.U(2.W), qspi_data_oe(3, 2), 1.U(1.W), qspi_data_oe(1, 0), 1.U(1.W)),
    0.U(8.W)
  )

  // Avoid warnings on unused inputs
  val read_complete = i_tinyqv.io.data_read_complete
  val unused = ena ^ uio_in(7) ^ uio_in(6) ^ uio_in(3) ^ uio_in(0) ^ read_complete

  uo_out := Cat(uo_out_val(7, 1), uo_out_val(0) ^ unused ^ unused)
}

/** Simulation-only variant with fast memory array instance built-in. */
class tt_um_gonsolo_borg_sim(override val CLOCK_MHZ: Int)
    extends tt_um_gonsolo_borg(CLOCK_MHZ) {
  override def SIM_FAST_MEM: Boolean = true
  override val desiredName = "tt_um_gonsolo_borg"
}
