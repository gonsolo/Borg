// Copyright Michael Bell 2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVCounter(val outputWidth: Int = 4) extends RawModule {
  override val desiredName = s"tinyqv_counter_$outputWidth"
  
  val clk = IO(Input(Clock()))
  val rstn = IO(Input(Bool()))
  val add = IO(Input(Bool()))
  val counter = IO(Input(UInt(3.W)))
  val set = IO(Input(Bool()))
  val data_in = IO(Input(UInt(4.W)))
  val data = IO(Output(UInt(outputWidth.W)))
  val cy_out = IO(Output(Bool()))

  withClockAndReset(clk, !rstn) {
    // 32-bit shift register broken into 8x 4-bit chunks
    val register = RegInit(VecInit(Seq.fill(8)(0.U(4.W))))
    val cy = RegInit(false.B)

    val increment_result = WireDefault(0.U(5.W))
    when (set) {
      increment_result := Cat(0.U(1.W), data_in)
    } .otherwise {
      val carryIn = Mux(counter === 0.U, add, cy)
      increment_result := Cat(0.U(1.W), register(1)) + carryIn
    }

    // Shift logic matching Verilog
    register(0) := increment_result(3, 0)
    register(7) := register(0)
    register(6) := register(7)
    register(5) := register(6)
    register(4) := register(5)
    register(3) := register(4)
    register(2) := register(3)
    register(1) := register(2)

    cy := increment_result(4)

    // data output logic: assign data = register[3 + OUTPUT_WIDTH:4]
    val flatReg = Cat(register.reverse) // register(7), register(6), ... register(0)
    data := flatReg(3 + outputWidth, 4)
    cy_out := increment_result(4)
  }
}
