// Copyright © 2021 Ben Marshall
// Changes Copyright © 2023 Michael Bell
// Changes Copyright  © 2024 Michael Bell
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: MIT

package peri.uart

import chisel3._
import chisel3.util._

class UartTxIO(payloadBits: Int, countRegLen: Int) extends Bundle {
  val uart_txd = Output(Bool())
  val uart_tx_busy = Output(Bool())
  val uart_tx_en = Input(Bool())
  val uart_tx_data = Input(UInt(payloadBits.W))
  val baud_divider = Input(UInt(countRegLen.W))
}

class UartTx(
  val countRegLen: Int = 13,
  val payloadBits: Int = 8,
  val stopBits: Int = 1
) extends Module {
  override val desiredName = "uart_tx"

  val io = IO(new UartTxIO(payloadBits, countRegLen))

  val FSM_IDLE = 0.U(4.W)
  val FSM_START = 1.U(4.W)
  val FSM_SEND = 2.U(4.W)
  val FSM_STOP = (2 + payloadBits).U(4.W)
  val FSM_END = (2 + payloadBits + stopBits - 1).U(4.W)

  val txd_reg = RegInit(true.B)
  val data_to_send = RegInit(0.U(payloadBits.W))
  val cycle_counter = RegInit(0.U(countRegLen.W))
  val fsm_state = RegInit(FSM_IDLE)

  io.uart_tx_busy := fsm_state =/= FSM_IDLE
  io.uart_txd := txd_reg

  val next_bit = cycle_counter >= io.baud_divider

  val next_fsm_state = WireDefault(fsm_state)

  when(fsm_state === FSM_IDLE) {
    next_fsm_state := Mux(io.uart_tx_en, FSM_START, FSM_IDLE)
  }.otherwise {
    when(next_bit) {
      next_fsm_state := Mux(fsm_state === FSM_END, FSM_IDLE, fsm_state + 1.U)
    }
  }

  when(fsm_state === FSM_IDLE && io.uart_tx_en) {
    data_to_send := io.uart_tx_data
  }.elsewhen(fsm_state >= FSM_SEND && fsm_state < FSM_STOP && next_bit) {
    data_to_send := Cat(false.B, data_to_send(payloadBits - 1, 1))
  }

  when(next_bit) {
    cycle_counter := 0.U
  }.elsewhen(fsm_state =/= FSM_IDLE) {
    cycle_counter := cycle_counter + 1.U
  }

  fsm_state := next_fsm_state

  when(fsm_state === FSM_START) {
    txd_reg := false.B
  }.elsewhen(fsm_state >= FSM_SEND && fsm_state < FSM_STOP) {
    txd_reg := data_to_send(0)
  }.otherwise {
    txd_reg := true.B
  }
}
