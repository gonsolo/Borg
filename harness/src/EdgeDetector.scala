// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package harness

import chisel3._
import chisel3.util._

class EdgeDetectorIO extends Bundle {
  val ena = Input(Bool())
  val data = Input(Bool())
  val pos_edge = Output(Bool())
}

class RisingEdgeDetector extends Module {
  val io = IO(new EdgeDetectorIO)

  val data_q = RegInit(false.B)

  when(io.ena) {
    data_q := io.data
  }

  io.pos_edge := io.data && !data_q
}

class FallingEdgeDetectorIO extends Bundle {
  val ena = Input(Bool())
  val data = Input(Bool())
  val neg_edge = Output(Bool())
}

class FallingEdgeDetector extends Module {
  val io = IO(new FallingEdgeDetectorIO)


  val data_q = RegInit(false.B)

  when(io.ena) {
    data_q := io.data
  }

  // A falling edge occurs when the previous state was High and current is Low
  io.neg_edge := !io.data && data_q
}
