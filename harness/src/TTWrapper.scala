// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package harness

import chisel3._
import chisel3.util._
import borg.Borg

// To map it exactly like Verilog, we use RawModule
class TTWrapper extends RawModule {
  val ui_in = IO(Input(UInt(8.W)))
  val uo_out = IO(Output(UInt(8.W)))
  val uio_in = IO(Input(UInt(8.W)))
  val uio_out = IO(Output(UInt(8.W)))
  val uio_oe = IO(Output(UInt(8.W)))
  val ena = IO(Input(Bool()))
  val clk = IO(Input(Clock()))
  val rst_n = IO(Input(AsyncReset()))

  // Note: we'll name the module explicitly to match the macro name
  override def desiredName = "tt_um_tqv_peripheral_harness"

  // Chisel's implicit clock and reset boundary
  withClockAndReset(clk, !rst_n.asBool) {
    // 1. Synchronize ui_in
    val ui_in_sync = Module(new Synchronizer(stages = 2, width = 8))
    ui_in_sync.io.data_in := ui_in
    
    // SPI pins
    val spi_cs_n = uio_in(4)
    val spi_clk = uio_in(5)
    val spi_mosi = uio_in(6)

    val spi_cs_n_sync = Module(new Synchronizer(stages = 2, width = 1))
    spi_cs_n_sync.io.data_in := spi_cs_n
    
    val spi_clk_sync = Module(new Synchronizer(stages = 2, width = 1))
    spi_clk_sync.io.data_in := spi_clk

    val spi_mosi_sync = Module(new Synchronizer(stages = 2, width = 1))
    spi_mosi_sync.io.data_in := spi_mosi

    // Instantiate Borg
    val borgPeri = Module(new Borg())
    
    // Instantiate SPI Reg
    val spiReg = Module(new SpiReg(addrW = 6, regW = 32))
    spiReg.io.ena := 1.B // ena
    spiReg.io.spi_mosi := spi_mosi_sync.io.data_out
    spiReg.io.spi_clk := spi_clk_sync.io.data_out
    spiReg.io.spi_cs_n := spi_cs_n_sync.io.data_out
    
    // Connect SPI Reg to Borg
    borgPeri.io.ui_in := ui_in_sync.io.data_out
    uo_out := borgPeri.io.uo_out
    
    borgPeri.io.address := spiReg.io.reg_addr
    
    val addrValid = spiReg.io.reg_addr_v
    val dataValid = spiReg.io.reg_data_o_dv
    val dataRw = spiReg.io.reg_rw
    val txnN = spiReg.io.txn_width
    
    val dataWriteN = WireDefault(3.U(2.W))
    val dataReadN = WireDefault(3.U(2.W))
    
    when(dataValid && dataRw) {
      dataWriteN := txnN
    }
    when(addrValid && !dataRw) {
      dataReadN := txnN
    }
    
    borgPeri.io.data_write_n := dataWriteN
    borgPeri.io.data_read_n := dataReadN
    
    // Masked data_out from Borg back to SPI
    val rawDataOut = borgPeri.io.data_out
    val dataOut1 = Mux(txnN(1) === 0.B, Cat(0.U(16.W), rawDataOut(15, 0)), rawDataOut)
    val dataOutMasked = Mux(txnN === 0.U, Cat(dataOut1(31, 16), 0.U(8.W), dataOut1(7, 0)), dataOut1)

    spiReg.io.reg_data_i := dataOutMasked
    
    // Connect SPI data output to Borg input
    borgPeri.io.data_in := spiReg.io.reg_data_o
    
    spiReg.io.reg_data_i_dv := borgPeri.io.data_ready
    
    // Outputs
    val spiMiso = spiReg.io.spi_miso
    val userInt = borgPeri.io.user_interrupt
    val dataReady = borgPeri.io.data_ready
    
    uio_out := Cat(0.U(4.W), spiMiso.asUInt, 0.U(1.W), dataReady.asUInt, userInt.asUInt)
    uio_oe := Cat(0.U(4.W), 1.U(1.W), 0.U(1.W), 1.U(1.W), 1.U(1.W))
  }
}
