package nexysvideo

import chisel3._
import chisel3.util._

import soc.TmdsEncoder

class HdmiTop extends RawModule{
    val clk = IO(Input(Clock()))
    val cpu_resetn = IO(Input(Bool()))
    val hdmi_tx_clk_p = IO(Output(Bool()))
    val hdmi_tx_clk_n = IO(Output(Bool()))
    val hdmi_tx_p = IO(Output(UInt(3.W)))
    val hdmi_tx_n = IO(Output(UInt(3.W)))
    val led = IO(Output(Bool()))

    val clkWiz = Module(new clk_wiz_0)
    clkWiz.clk_in1 := clk
    clkWiz.resetn := cpu_resetn
    led := clkWiz.locked
    withClockAndReset(clkWiz.clk_out2, !clkWiz.locked) {
        val count = RegInit(0.U(3.W))
        val tick25 = (count === 4.U)
        when(tick25) { count := 0.U } .otherwise { count := count + 1.U }

        val hCount = RegInit(0.U(10.W))
        val vCount = RegInit(0.U(10.W))

        // 640x480 @ 60 Hz (25.175 MHz pixel clock approx 25.0 MHz)
        val hTotal = 800.U
        val vTotal = 525.U
        val hActive = 640.U
        val vActive = 480.U
        val hFront = 16.U
        val hSync = 96.U
        val vFront = 10.U
        val vSync = 2.U

        when(tick25) {
            when(hCount === hTotal - 1.U) {
                hCount := 0.U
                when(vCount === vTotal - 1.U) {
                    vCount := 0.U
                } .otherwise {
                    vCount := vCount + 1.U
                }
            } .otherwise {
                hCount := hCount + 1.U
            }
        }

        val de = (hCount < hActive) && (vCount < vActive) // inside visible picture
        val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync)) // pulse telling monitor to start new line
        val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync)) // pulse telling monitor to start new frame

        // Color bars: 8 vertical bars
        // hActive is 640. 640 / 8 = 80 pixels per bar.
        // Bar index = hCount / 80
        val barIdx = hCount / 80.U

        // Colors: White, Yellow, Cyan, Green, Magenta, Red, Blue, Black
        val red   = Mux(de && (barIdx === 0.U || barIdx === 1.U || barIdx === 4.U || barIdx === 5.U), 255.U(8.W), 0.U(8.W))
        val green = Mux(de && (barIdx === 0.U || barIdx === 1.U || barIdx === 2.U || barIdx === 3.U), 255.U(8.W), 0.U(8.W))
        val blue  = Mux(de && (barIdx === 0.U || barIdx === 2.U || barIdx === 4.U || barIdx === 6.U), 255.U(8.W), 0.U(8.W))

        val encB = Module(new TmdsEncoder)
        encB.io.en   := tick25
        encB.io.data := blue
        encB.io.c    := Cat(vsync, hsync)
        encB.io.de   := de

        val encG = Module(new TmdsEncoder)
        encG.io.en   := tick25
        encG.io.data := green
        encG.io.c    := 0.U
        encG.io.de   := de

        val encR = Module(new TmdsEncoder)
        encR.io.en   := tick25
        encR.io.data := red
        encR.io.c    := 0.U
        encR.io.de   := de

        val serB = Module(new TmdsSerializer)
        serB.io.en   := tick25
        serB.io.tmds := encB.io.tmds

        val serG = Module(new TmdsSerializer)
        serG.io.en   := tick25
        serG.io.tmds := encG.io.tmds

        val serR = Module(new TmdsSerializer)
        serR.io.en   := tick25
        serR.io.tmds := encR.io.tmds

        val serClk = Module(new TmdsSerializer)
        serClk.io.en   := tick25
        serClk.io.tmds := "b0000011111".U // Clock pattern
    }
}