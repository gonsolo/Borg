package nexysvideo

import chisel3._

class ClocksIO extends Bundle{
    val clk100 = Input(Clock())
    val rst = Input(Bool())
    val clk25 = Output(Clock())
    val clk125 = Output(Clock())
    val clk200 = Output(Clock())
    val locked = Output(Bool())
}

class Clocks extends RawModule{
    val io = IO(new ClocksIO)

    val mmcm = Module(new MMCME2_BASE)
    mmcm.CLKIN1 := io.clk100
    mmcm.RST := io.rst
    mmcm.PWRDWN := 0.B
    mmcm.CLKFBIN := mmcm.CLKFBOUT

    val buf25 = Module(new BUFG)
    buf25.I := mmcm.CLKOUT0
    io.clk25 := buf25.O

    val buf125 = Module(new BUFG)
    buf125.I := mmcm.CLKOUT1
    io.clk125 := buf125.O

    val buf200 = Module(new BUFG)
    buf200.I := mmcm.CLKOUT2
    io.clk200 := buf200.O

    io.locked := mmcm.LOCKED
} 