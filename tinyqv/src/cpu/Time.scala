// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVTime extends Module {

  val time_pulse = IO(Input(Bool()))
  val set_mtime = IO(Input(Bool()))
  val set_mtimecmp = IO(Input(Bool()))
  val data_in = IO(Input(UInt(4.W)))
  val counter = IO(Input(UInt(3.W)))

  val read_mtimecmp = IO(Input(Bool()))
  val data_out = IO(Output(UInt(4.W)))

  val timer_interrupt = IO(Output(Bool()))

    val mtime_out = Wire(UInt(4.W))
    val time_pulse_r = RegInit(false.B)

    val i_mtime = Module(new TinyQVCounter(4))
    i_mtime.add := time_pulse | time_pulse_r
    i_mtime.counter := counter
    i_mtime.set := set_mtime
    i_mtime.data_in := data_in
    mtime_out := i_mtime.data

    // mtimecmp implementation
    val mtimecmp = RegInit(0.U(32.W))
    
    val next_mtimecmp_31_4 = Cat(mtimecmp(3, 0), mtimecmp(31, 8))
    val next_mtimecmp_3_0 = Mux(set_mtimecmp, data_in, mtimecmp(7, 4))
    
    mtimecmp := Cat(next_mtimecmp_31_4, next_mtimecmp_3_0)

    // Comparison logic
    val cy = RegInit(false.B)
    val comparison = mtime_out +& (~mtimecmp(7, 4)).asUInt + cy.asUInt
    
    cy := Mux(counter === 7.U, true.B, comparison(4))
    
    // timer_interrupt logic
    val timer_interrupt_reg = RegInit(false.B)
    when (counter === 7.U) {
      timer_interrupt_reg := (comparison(3, 2) === 0.U)
    }
    timer_interrupt := timer_interrupt_reg
    
    // time_pulse_r: latch time_pulse until counter wraps to 0
    when (counter === 0.U) {
      time_pulse_r := false.B
    } .otherwise {
      time_pulse_r := time_pulse | time_pulse_r
    }

    data_out := Mux(read_mtimecmp, mtimecmp(7, 4), mtime_out)
}

