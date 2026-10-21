package nexysvideo

import chisel3._

class BlinkyTop extends RawModule {
    val clk = IO(Input(Clock()))
    val cpu_resetn = IO(Input(Bool()))
    val led = IO(Output(Bool()))
    val clkWiz = Module(new clk_wiz_0)
    clkWiz.clk_in1 := clk
    clkWiz.resetn := cpu_resetn
    val blinky = withClockAndReset(clkWiz.clk_out1, !clkWiz.locked) {
        Module(new Blinky)
    }
    led := blinky.io.led

}