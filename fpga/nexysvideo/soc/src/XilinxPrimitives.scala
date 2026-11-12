// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._
import chisel3.{ExtModule, IntParam, StringParam, DoubleParam}

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

// Xilinx clock block (MMCM): by default 25, 125 and 200 MHz from the 100 MHz board clock
// VCO = 100 MHz x 10 = 1000 MHz, then each output divides it down
class MMCME2_BASE(inPeriod: Double = 10.0, mult: Double = 10.0, div0: Double = 40.0, div1: Int = 8, div2: Int = 5) extends ExtModule(Map(
    "CLKIN1_PERIOD" -> DoubleParam(inPeriod),
    "CLKFBOUT_MULT_F" -> DoubleParam(mult),
    "DIVCLK_DIVIDE" -> IntParam(1),
    "CLKOUT0_DIVIDE_F" -> DoubleParam(div0),
    "CLKOUT1_DIVIDE" -> IntParam(div1),
    "CLKOUT2_DIVIDE" -> IntParam(div2)
)) {
    val CLKIN1 = IO(Input(Clock()))
    val CLKFBIN = IO(Input(Clock()))
    val RST = IO(Input(Bool()))
    val PWRDWN = IO(Input(Bool()))
    val CLKOUT0 = IO(Output(Clock()))
    val CLKOUT1 = IO(Output(Clock()))
    val CLKOUT2 = IO(Output(Clock()))
    val CLKFBOUT = IO(Output(Clock()))
    val LOCKED = IO(Output(Bool()))
}

// Xilinx global clock buffer: puts a clock onto the chip's clock network
class BUFG extends ExtModule{
    val I = IO(Input(Clock()))
    val O = IO(Output(Clock()))
}

// STARTUPE2: Xilinx block that passes the bootloader's clock to the flash clock pin
class STARTUPE2 extends ExtModule(Map(
    "PROG_USR" -> StringParam("FALSE"),     // security feature off
    "SIM_CCLK_FREQ" -> DoubleParam(0.0)     // simulation only
)) {
    val USRCCLKO = IO(Input(Bool()))    // bootloader's spi_clk -> flash clock pin
    val USRCCLKTS = IO(Input(Bool()))   // 0 = drive the flash clock pin
    val CLK = IO(Input(Bool()))         // unused, tie 0
    val GSR = IO(Input(Bool()))         // 1 = reset every flip-flop, tie 0
    val GTS = IO(Input(Bool()))         // 1 = float every output pin, tie 0
    val KEYCLEARB = IO(Input(Bool()))   // 0 = clear encryption key, tie 1
    val PACK = IO(Input(Bool()))        // unused, tie 0
    val USRDONEO = IO(Input(Bool()))    // DONE pin value, tie 1
    val USRDONETS = IO(Input(Bool()))   // 1 = leave the DONE pin alone
}