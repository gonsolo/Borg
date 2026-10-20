package nexysvideo

import chisel3._
import chisel3.util._

class BlinkyIO extends Bundle{
    val led = Output(Bool())
}

class Blinky extends Module {
    val io = IO(new BlinkyIO)

    val count = RegInit(0.U(26.W))
    count := count + 1.U
    io.led := count(24)
}