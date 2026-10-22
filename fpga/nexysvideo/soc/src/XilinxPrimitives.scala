package nexysvideo

import chisel3._
import chisel3.{ExtModule, IntParam, StringParam}

// Xilinx output block: sends 2 bits per clock tick (D1 on the rising edge, D2 on the falling)
class ODDR extends ExtModule(Map(
    "DDR_CLK_EDGE" -> StringParam("SAME_EDGE"), //SAME_EDGE: both bits are handed over together on the rising edge
    "INIT" -> IntParam(0), // INIT: output starts at 0
    "SRTYPE" -> StringParam("SYNC") // SYNC: reset acts on a clock edge
)) {
    val Q = IO(Output(Bool())) // pin output
    val C = IO(Input(Clock())) // 125MHz clock
    val CE = IO(Input(Bool())) // clock enable needs to be 1
    val D1 = IO(Input(Bool())) // first bit sent
    val D2 = IO(Input(Bool())) // second bit sent
    val R = IO(Input(Bool())) // reset
    val S = IO(Input(Bool())) // set
}

// Xilinx differential output buffer: drives one signal onto a wire pair for HDMI
class OBUFDS extends ExtModule {
    val I = IO(Input(Bool())) // signal in
    val O = IO(Output(Bool())) // + wire
    val OB = IO(Output(Bool())) // - wire
}