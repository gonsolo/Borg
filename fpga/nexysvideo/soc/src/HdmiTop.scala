// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._
import chisel3.util._

import soc.HdmiColourBars

import _root_.circt.stage.ChiselStage
import soc.Emit

class HdmiTop extends RawModule{
    val clk = IO(Input(Clock()))
    val cpu_resetn = IO(Input(Bool()))
    val hdmi_tx_clk_p = IO(Output(Bool()))
    val hdmi_tx_clk_n = IO(Output(Bool()))
    val hdmi_tx_p = IO(Output(UInt(3.W)))
    val hdmi_tx_n = IO(Output(UInt(3.W)))
    val led = IO(Output(Bool()))

    val clkGen = Module(new Clocks)
    clkGen.io.clk100 := clk
    clkGen.io.rst := !cpu_resetn
    led := clkGen.io.locked
    withClockAndReset(clkGen.io.clk125, !clkGen.io.locked) {

        val bars = Module(new HdmiColourBars)

        val serB = Module(new TmdsSerializer)
        serB.io.en   := bars.io.tick
        serB.io.tmds := bars.io.tmds(0)

        val serG = Module(new TmdsSerializer)
        serG.io.en   := bars.io.tick
        serG.io.tmds := bars.io.tmds(1)

        val serR = Module(new TmdsSerializer)
        serR.io.en   := bars.io.tick
        serR.io.tmds := bars.io.tmds(2)

        val serClk = Module(new TmdsSerializer)
        serClk.io.en   := bars.io.tick
        serClk.io.tmds := bars.io.tmds(3)

        val bufB = Module(new OBUFDS)
        bufB.I := serB.io.out

        val bufG = Module(new OBUFDS)
        bufG.I := serG.io.out

        val bufR = Module(new OBUFDS)
        bufR.I := serR.io.out

        val bufClk = Module(new OBUFDS)
        bufClk.I := serClk.io.out

        hdmi_tx_clk_p := bufClk.O
        hdmi_tx_clk_n := bufClk.OB
        hdmi_tx_p := Cat(bufR.O, bufG.O, bufB.O)
        hdmi_tx_n := Cat(bufR.OB, bufG.OB, bufB.OB)
    }
}

object HdmiTopMain extends App{
    val targetDir = "out/nexysvideo_hdmi/verilog"
    Emit.cleanTargetDir(targetDir)

    ChiselStage.emitSystemVerilogFile(
        gen = new HdmiTop,
        args = Array("--target-dir", targetDir),
        firtoolOpts = Emit.firtoolOpts
    )
}