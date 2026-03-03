// Copyright (c) 2021 Ben Marshall
// Changes Copyright (c) 2023 Michael Bell
// Changes Copyright (c) 2024 Michael Bell
// MIT License

package tinyqv.peri.uart

import chisel3._
import chisel3.util._

class UartRxIO(payloadBits: Int, countRegLen: Int) extends Bundle {
  val uart_rxd = Input(Bool())
  val uart_rts = Output(Bool())
  val uart_rx_read = Input(Bool())
  val uart_rx_valid = Output(Bool())
  val uart_rx_data = Output(UInt(payloadBits.W))
  val baud_divider = Input(UInt(countRegLen.W))
}

class UartRx(
  val countRegLen: Int = 13,
  val payloadBits: Int = 8,
  val stopBits: Int = 1
) extends Module {
  override val desiredName = "uart_rx"

  val io = IO(new UartRxIO(payloadBits, countRegLen))

  val FSM_IDLE = 0.U(4.W)
  val FSM_START = 1.U(4.W)
  val FSM_RECV = 2.U(4.W)
  val FSM_STOP = (2 + payloadBits).U(4.W)
  val FSM_READY = (2 + payloadBits + stopBits).U(4.W)

  val fsm_state = RegInit(FSM_IDLE)
  val cycle_counter = RegInit(0.U(countRegLen.W))
  val bit_sample = RegInit(false.B)
  val recieved_data = Reg(UInt(payloadBits.W))
  val uart_rts_reg = RegInit(true.B)

  val next_bit = cycle_counter >= io.baud_divider
  val mid_bit = cycle_counter === (io.baud_divider >> 1.U)

  val next_fsm_state = WireDefault(fsm_state)

  switch(fsm_state) {
    is(FSM_IDLE) {
      next_fsm_state := Mux(io.uart_rxd, FSM_IDLE, FSM_START)
    }
    is(FSM_STOP) {
      next_fsm_state := Mux(mid_bit, Mux(io.uart_rxd, FSM_READY, FSM_IDLE), FSM_STOP)
    }
    is(FSM_READY) {
      next_fsm_state := Mux(io.uart_rx_read, FSM_IDLE, FSM_READY)
    }
  }

  when(fsm_state =/= FSM_IDLE && fsm_state =/= FSM_STOP && fsm_state =/= FSM_READY) {
    next_fsm_state := Mux(next_bit, fsm_state + 1.U, fsm_state)
  }

  when(fsm_state >= FSM_RECV && fsm_state < FSM_STOP && next_bit) {
    recieved_data := Cat(bit_sample, recieved_data(payloadBits - 1, 1))
  }

  when(mid_bit) {
    bit_sample := io.uart_rxd
  }

  when(next_bit || fsm_state === FSM_IDLE || fsm_state === FSM_READY) {
    cycle_counter := 0.U
  }.otherwise {
    cycle_counter := cycle_counter + 1.U
  }

  fsm_state := next_fsm_state

  uart_rts_reg := fsm_state > FSM_START && !io.uart_rx_read

  io.uart_rx_valid := fsm_state === FSM_READY
  io.uart_rx_data := recieved_data
  io.uart_rts := uart_rts_reg
}
