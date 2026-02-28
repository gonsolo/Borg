// Copyright Michael Bell 2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVAlu extends RawModule {
  override val desiredName = "tinyqv_alu"

  val op = IO(Input(UInt(4.W)))
  val a = IO(Input(UInt(4.W)))
  val b = IO(Input(UInt(4.W)))
  val cy_in = IO(Input(Bool()))
  val cmp_in = IO(Input(Bool()))
  val d = IO(Output(UInt(4.W)))
  val cy_out = IO(Output(Bool()))
  val cmp_res = IO(Output(Bool()))

  val a_for_add = Cat(0.B, a)
  val b_for_add = Cat(0.B, Mux(op(1) || op(3), ~b, b))
  val sum = a_for_add + b_for_add + cy_in.asUInt
  val a_xor_b = a ^ b

  d := MuxLookup(op(2, 0), 0.U(4.W))(Seq(
    0.U -> sum(3, 0),
    7.U -> (a & b),
    6.U -> (a | b),
    4.U -> a_xor_b
  ))

  cmp_res := Mux(op(0), !sum(4),
              Mux(op(1), a(3) ^ b_for_add(3) ^ sum(4),
                cmp_in && a_xor_b === 0.U))

  cy_out := sum(4)
}

class TinyQVShifter extends RawModule {
  override val desiredName = "tinyqv_shifter"

  val op = IO(Input(UInt(2.W))) // op[3:2]
  val counter = IO(Input(UInt(3.W)))
  val a = IO(Input(UInt(32.W)))
  val b = IO(Input(UInt(5.W)))
  val d = IO(Output(UInt(4.W)))

  val top_bit = Mux(op(1), a(31), 0.B) // op(1) is op[3]
  val shift_right = op(0) // op(0) is op[2]

  val a_for_shift_right = Mux(shift_right, a, Reverse(a))

  val c = Mux(shift_right, counter, ~counter)
  val shift_amt = Cat(0.B, b) + Cat(0.B, c, 0.U(2.W))
  
  val adjusted_shift_amt = shift_amt(4,0)

  val a_for_shift = Cat(Fill(3, top_bit), a_for_shift_right)

  val dr = Mux(shift_amt(5), Fill(4, top_bit), (a_for_shift >> adjusted_shift_amt)(3, 0))

  d := Mux(shift_right, dr, Reverse(dr))
}
