// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

// @doc:alu
class TinyQVAluIO extends Bundle {
  val op = Input(UInt(4.W))
  val a = Input(UInt(4.W))
  val b = Input(UInt(4.W))
  val cy_in = Input(Bool())
  val cmp_in = Input(Bool())
  val d = Output(UInt(4.W))
  val cy_out = Output(Bool())
  val cmp_res = Output(Bool())
}

class TinyQVAlu extends RawModule {
  val io = IO(new TinyQVAluIO)

  val a_for_add = Cat(0.B, io.a)
  val b_for_add = Cat(0.B, Mux(io.op(1) || io.op(3), ~io.b, io.b))
  val sum = a_for_add + b_for_add + io.cy_in.asUInt
  val a_xor_b = io.a ^ io.b

  io.d := MuxLookup(io.op(2, 0), 0.U(4.W))(Seq(
    0.U -> sum(3, 0),
    7.U -> (io.a & io.b),
    6.U -> (io.a | io.b),
    4.U -> a_xor_b
  ))

  io.cmp_res := Mux(io.op(0), !sum(4),
              Mux(io.op(1), io.a(3) ^ b_for_add(3) ^ sum(4),
                io.cmp_in && a_xor_b === 0.U))

  io.cy_out := sum(4)
}
// @doc:end

class TinyQVShifterIO extends Bundle {
  val op = Input(UInt(2.W))
  val counter = Input(UInt(3.W))
  val a = Input(UInt(32.W))
  val b = Input(UInt(5.W))
  val d = Output(UInt(4.W))
}

class TinyQVShifter extends RawModule {
  val io = IO(new TinyQVShifterIO)

  val top_bit = Mux(io.op(1), io.a(31), 0.B)
  val shift_right = io.op(0)

  val a_for_shift_right = Mux(shift_right, io.a, Reverse(io.a))

  val c = Mux(shift_right, io.counter, ~io.counter)
  val shift_amt = Cat(0.B, io.b) + Cat(0.B, c, 0.U(2.W))
  
  val adjusted_shift_amt = shift_amt(4,0)

  val a_for_shift = Cat(Fill(3, top_bit), a_for_shift_right)

  val dr = Mux(shift_amt(5), Fill(4, top_bit), (a_for_shift >> adjusted_shift_amt)(3, 0))

  io.d := Mux(shift_right, dr, Reverse(dr))
}
