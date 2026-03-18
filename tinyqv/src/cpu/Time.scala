// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVTimeIO extends Bundle {
  val time_pulse = Input(Bool())
  val set_mtime = Input(Bool())
  val set_mtimecmp = Input(Bool())
  val data_in = Input(UInt(4.W))
  val counter = Input(UInt(3.W))
  val read_mtimecmp = Input(Bool())
  val data_out = Output(UInt(4.W))
  val timer_interrupt = Output(Bool())
}

class TinyQVTime extends Module {
  val io = IO(new TinyQVTimeIO)

    val mtime_out = Wire(UInt(4.W))
    val time_pulse_r = RegInit(false.B)

    val i_mtime = Module(new TinyQVCounter(4))
    i_mtime.io.add := io.time_pulse | time_pulse_r
    i_mtime.io.counter := io.counter
    i_mtime.io.set := io.set_mtime
    i_mtime.io.data_in := io.data_in
    mtime_out := i_mtime.io.data

    // mtimecmp implementation
    val mtimecmp = RegInit(0.U(32.W))
    
    val next_mtimecmp_31_4 = Cat(mtimecmp(3, 0), mtimecmp(31, 8))
    val next_mtimecmp_3_0 = Mux(io.set_mtimecmp, io.data_in, mtimecmp(7, 4))
    
    mtimecmp := Cat(next_mtimecmp_31_4, next_mtimecmp_3_0)

    // Comparison logic
    val cy = RegInit(false.B)
    val comparison = mtime_out +& (~mtimecmp(7, 4)).asUInt + cy.asUInt
    
    cy := Mux(io.counter === 7.U, true.B, comparison(4))
    
    // timer_interrupt logic
    val timer_interrupt_reg = RegInit(false.B)
    when (io.counter === 7.U) {
      timer_interrupt_reg := (comparison(3, 2) === 0.U)
    }
    io.timer_interrupt := timer_interrupt_reg
    
    // time_pulse_r: latch time_pulse until counter wraps to 0
    when (io.counter === 0.U) {
      time_pulse_r := false.B
    } .otherwise {
      time_pulse_r := io.time_pulse | time_pulse_r
    }

    io.data_out := Mux(io.read_mtimecmp, mtimecmp(7, 4), mtime_out)
}

