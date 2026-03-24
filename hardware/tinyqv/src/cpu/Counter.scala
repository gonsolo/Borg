// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVCounterIO(val outputWidth: Int) extends Bundle {
  val add = Input(Bool())
  val counter = Input(UInt(3.W))
  val set = Input(Bool())
  val data_in = Input(UInt(4.W))
  val data = Output(UInt(outputWidth.W))
  val cy_out = Output(Bool())
}

class TinyQVCounter(val outputWidth: Int = 4) extends Module {
  val io = IO(new TinyQVCounterIO(outputWidth))

    // @doc:nibble-counter
    // 32-bit shift register broken into 8x 4-bit chunks
    val registers = RegInit(VecInit(Seq.fill(8)(0.U(4.W))))
    val cy = RegInit(false.B)

    val increment_result = WireDefault(0.U(5.W))
    val carryIn = Mux(io.counter === 0.U, io.add, cy)
    
    when (io.set) {
      increment_result := Cat(0.U(1.W), io.data_in)
    } .otherwise {
      increment_result := Cat(0.U(1.W), registers(0)) + carryIn
    }

    // Shift logic matching Verilog
    for (i <- 0 until 7) {
      registers(i) := registers(i+1)
    }
    registers(7) := increment_result(3, 0)

    cy := increment_result(4)
    // @doc:end

    // data output logic: assign data = register[3 + OUTPUT_WIDTH:4]
    val flatReg = Cat(registers.reverse) 
    io.data := flatReg(outputWidth - 1, 0)
    io.cy_out := increment_result(4)
}
