package nexysvideo

import chisel3._
import chisel3.ExtModule

class clk_wiz_0 extends ExtModule {
    val clk_in1 = IO(Input(Clock()))
    val resetn = IO(Input(Bool()))
    val clk_out1 = IO(Output(Clock()))
    val clk_out2 = IO(Output(Clock()))
    val locked = IO(Output(Bool()))
}