// Copyright Michael Bell 2023-2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVQspiFlash(val dataWidthBytes: Int = 2, val addrBits: Int = 24) extends RawModule {
  override val desiredName = "qspi_flash_controller"

  val clk = IO(Input(Clock()))
  val rstn = IO(Input(Bool()))

  // External SPI interface
  val spi_data_in = IO(Input(UInt(4.W)))
  val spi_data_out = IO(Output(UInt(4.W)))
  val spi_data_oe = IO(Output(UInt(4.W)))
  val spi_select = IO(Output(Bool()))
  val spi_clk_out = IO(Output(Bool()))

  // Internal interface
  val addr_in = IO(Input(UInt(addrBits.W)))
  val start_read = IO(Input(Bool()))
  val stall_read = IO(Input(Bool()))
  val stop_read = IO(Input(Bool()))

  val data_out = IO(Output(UInt((dataWidthBytes * 8).W)))
  val data_ready = IO(Output(Bool()))
  val busy = IO(Output(Bool()))

  val DATA_WIDTH_BITS = dataWidthBytes * 8

  object State extends ChiselEnum {
    val Idle, Cmd, Addr, Dummy1, Dummy2, Data, Stalled = Value
  }

  withClockAndReset(clk, !rstn || stop_read) {
    val fsm_state = RegInit(State.Idle)
    val addr = Reg(UInt(addrBits.W))
    val data = Reg(UInt(DATA_WIDTH_BITS.W))
    
    // nibbles_remaining needs to be wide enough for max(DATA_WIDTH_BITS, ADDR_BITS, 31) / 4
    // 32 bits = 8 nibbles. 24 bits = 6 nibbles.
    val maxNibbles = (dataWidthBytes * 8).max(addrBits).max(32) / 4
    val nibbles_remaining = RegInit(0.U(log2Ceil(maxNibbles + 1).W))

    val spi_clk_out_reg = RegInit(true.B)
    val spi_data_oe_reg = RegInit(0.U(4.W))
    val data_ready_reg = RegInit(false.B)

    data_ready := data_ready_reg
    busy := fsm_state =/= State.Idle
    spi_select := fsm_state === State.Idle
    spi_clk_out := spi_clk_out_reg
    spi_data_oe := spi_data_oe_reg
    data_out := data

    data_ready_reg := false.B

    // FSM Logic
    switch(fsm_state) {
      is(State.Idle) {
        when(start_read) {
          fsm_state := State.Cmd
          nibbles_remaining := (8 - 1).U
          spi_data_oe_reg := 1.U
          spi_clk_out_reg := false.B
          addr := addr_in
        }
      }
      is(State.Stalled) {
        data_ready_reg := true.B
        when(!stall_read) {
          fsm_state := State.Data
        }
      }
    }
    
    // Main FSM transitions and clock toggling
    when(fsm_state =/= State.Idle && fsm_state =/= State.Stalled) {
      spi_clk_out_reg := !spi_clk_out_reg
      when(spi_clk_out_reg) {
        when(nibbles_remaining === 0.U) {
          switch(fsm_state) {
            is(State.Cmd) {
              fsm_state := State.Addr
              nibbles_remaining := ((addrBits >> 2) - 1).U
              spi_data_oe_reg := 15.U // 4'b1111
            }
            is(State.Addr) {
              fsm_state := State.Dummy1
              nibbles_remaining := (2 - 1).U
            }
            is(State.Dummy1) {
              fsm_state := State.Dummy2
              spi_data_oe_reg := 0.U
              nibbles_remaining := (4 - 1).U
            }
            is(State.Dummy2) {
              fsm_state := State.Data
              nibbles_remaining := ((DATA_WIDTH_BITS >> 2) - 1).U
            }
            is(State.Data) {
              data_ready_reg := true.B
              nibbles_remaining := ((DATA_WIDTH_BITS >> 2) - 1).U
              when(stall_read) {
                fsm_state := State.Stalled
              }
            }
          }
        } .otherwise {
          nibbles_remaining := nibbles_remaining - 1.U
        }
      }
    }

    // Address shift logic
    when(fsm_state === State.Addr && spi_clk_out_reg) {
      addr := Cat(addr(addrBits - 5, 0), 0.U(4.W))
    }

    // Data capture logic
    when(fsm_state === State.Data && spi_clk_out_reg) {
      data := Cat(data(DATA_WIDTH_BITS - 5, 0), spi_data_in)
    }

    // SPI data out logic
    // fsm_state == FSM_CMD  ? {3'b000, !(nibbles_remaining == 4 || nibbles_remaining == 2)} :
    // fsm_state == FSM_ADDR ? addr[ADDR_BITS-1:ADDR_BITS-4] :
    //                         4'b0001;
    val cmd_bit = !(nibbles_remaining === 4.U || nibbles_remaining === 2.U)
    spi_data_out := MuxCase(1.U(4.W), Seq(
      (fsm_state === State.Cmd) -> Cat(0.U(3.W), cmd_bit),
      (fsm_state === State.Addr) -> addr(addrBits - 1, addrBits - 4)
    ))
  }
}
